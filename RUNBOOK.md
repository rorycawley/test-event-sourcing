# Async Projection Operations Runbook

Operational procedures for the async projection pipeline:
Event Store (Postgres) → Outbox → RabbitMQ → Async Projectors → Read Store (Postgres)

## Architecture Quick Reference

```
Command → Decider → Event Store + Outbox (same txn)
                         │
                    Outbox Poller (100ms poll)
                         │
                    RabbitMQ fanout exchange "events"
                         │
                ┌────────┴────────┐
                │                 │
          Queue: balances    Queue: transfers
                │                 │
         Account Projector  Transfer Projector
                │                 │
                └────────┬────────┘
                         │
                    Read Store (Postgres)
```

**Key principle:** RabbitMQ messages are notifications ("wake up"), not event
carriers. Each projector reads directly from the event store using checkpoints.
Lost or duplicate messages are harmless.

## Poison Events

### What is a poison event?

An event that causes a projection handler to throw every time it's processed.
Examples: schema mismatch after a code change, handler bug, corrupt payload.

### How it's handled automatically

1. Each projector tracks consecutive failures
2. After 5 consecutive failures (configurable via `:max-consecutive-failures`):
   - The poison event is **skipped** (checkpoint advances past it)
   - The `on-poison` callback fires (default: prints to stderr)
   - The projector resumes processing subsequent events
3. The read model will be **missing** the data from the skipped event

### What to do when a poison event is skipped

1. **Check logs** for `POISON EVENT SKIPPED` — note the `global_sequence` and `event_type`
2. **Inspect the event** in the event store:
   ```sql
   SELECT * FROM events WHERE global_sequence = <N>;
   ```
3. **Diagnose the cause** — usually a handler bug or schema mismatch
4. **Fix the handler code** and deploy
5. **Rebuild the projection** to re-process the skipped event:
   ```clojure
   (require '[es.async-projection :as async-proj])
   (async-proj/rebuild! event-store-ds read-db-ds projection-config)
   ```
   Or stop the projector, rebuild, restart.

### Preventing poison events

- Validate event schemas in the domain layer before appending (`validate-event!`)
- Use `skip-unknown? true` on async projectors — but be aware that unknown event
  types will be silently skipped (see "Deploying New Event Types" below)
- Test projection handlers against all known event types and versions

## Rebuilding Projections

### When to rebuild

- After fixing a handler bug that caused poison events
- After adding handlers for previously unknown event types
- After schema changes to read model tables
- When read model data appears inconsistent

### How to rebuild

**Option A: REPL (with running system)**

Stop the projector consumer first to avoid interleaving:

```clojure
;; Stop the projector
(component/stop (:account-projector system))

;; Rebuild
(es.async-projection/rebuild! event-store-ds read-db-ds
  {:projection-name   "account-balances"
   :read-model-tables ["account_balances"]
   :handler           handler-fn})

;; Restart the projector
(component/start (:account-projector system))
```

**Option B: Offline rebuild**

1. Stop the application
2. Connect to both databases
3. Drop BM25 search indexes before truncating (TRUNCATE invalidates pg_search indexes):
   ```clojure
   (es.search/drop-search! read-db-ds {:index-name "idx_account_balances_search"})
   ```
   Or via SQL: `DROP INDEX IF EXISTS idx_account_balances_search;`
4. Truncate read model tables + projection_checkpoints
5. Start the application — projectors will catch up from global_sequence 0
6. Recreate BM25 search indexes after the rebuild completes:
   ```clojure
   (es.search/ensure-search! read-db-ds bank.account-projection/search-index-config)
   ```

### Important notes

- Rebuilds process in batches of 1000 events to avoid OOM
- If rebuild crashes mid-way, re-run it — it clears tables first (idempotent)
- Large event stores: rebuilds can take a long time. Monitor progress via:
  ```sql
  SELECT * FROM projection_checkpoints;  -- on read DB
  SELECT MAX(global_sequence) FROM events;  -- on event store
  ```

## Deploying New Event Types

When adding a new event type to the write side:

1. **Deploy the projector handler first** (or simultaneously)
2. If the projector was deployed **after** events of the new type were written,
   those events were skipped by `skip-unknown? true`
3. **Rebuild the projection** to pick up the skipped events

This is the trade-off for independent deployability. The alternative (fail on
unknown) would block the entire projector on unrecognized events.

## Dead Letter Queue

### What ends up on the DLQ

Currently: nothing in normal operation. Messages are nacked with `requeue=true`,
so they re-enter the main queue. The DLQ (`<queue>.dlq`) is infrastructure for
future use (e.g., message TTL, explicit rejection).

Since messages are notifications (not event carriers), losing a message is
harmless — the catch-up timer will pick up any missed events within 30 seconds.

### Inspecting the DLQ

Use the RabbitMQ management UI or CLI:

```bash
# List messages on the DLQ (non-destructive peek)
rabbitmqctl list_queues name messages

# Via management API
curl -u guest:guest http://localhost:15672/api/queues/%2F/projector.account-balances.dlq
```

### Draining the DLQ

Messages on the DLQ are just notifications. They can be safely discarded:

```bash
rabbitmqctl purge_queue projector.account-balances.dlq
```

Or requeue them to the main queue if you want to trigger catch-ups:

```bash
# Via management UI: move messages from DLQ back to main queue
```

## Outbox Maintenance

### Table growth

Published outbox rows (`published_at IS NOT NULL`) accumulate over time. In
production, add a periodic cleanup job:

```sql
-- Safe to run anytime — only deletes already-published rows
DELETE FROM event_outbox
WHERE published_at < NOW() - INTERVAL '7 days';
```

Consider running this as a cron job or including it in a maintenance window.

### Checking outbox lag

```sql
-- How many events are waiting to be published?
SELECT COUNT(*) FROM event_outbox WHERE published_at IS NULL;

-- Oldest unpublished event
SELECT MIN(id), MIN(global_sequence)
FROM event_outbox
WHERE published_at IS NULL;
```

If the count is growing, check:
1. Is the outbox poller running? (check for the `outbox-poller` daemon thread)
2. Is RabbitMQ healthy and reachable?
3. Are there errors in the poller logs?

## Monitoring Checklist

| Metric | Where to check | Alert threshold |
|---|---|---|
| Projection lag | `projection_checkpoints.last_global_sequence` vs `MAX(events.global_sequence)` | > 1000 events behind |
| Outbox backlog | `COUNT(*) FROM event_outbox WHERE published_at IS NULL` | > 100 |
| Poison events | Application logs for `POISON EVENT SKIPPED` | Any occurrence |
| Consumer health | RabbitMQ management UI — consumer count per queue | 0 consumers |
| DLQ depth | RabbitMQ management UI — message count on `.dlq` queues | > 0 |

## Saga-Specific Notes

### Transfer saga events and the outbox

The transfer saga (`bank.transfer-saga`) accepts an optional `:on-events-appended`
hook. When provided (e.g. the transactional outbox hook from `es.outbox`), every
command in the saga — account debits/credits and transfer progress events — flows
through the hook for real-time projection updates via the outbox → RabbitMQ
pipeline. Without the hook, events are picked up by the projectors' **catch-up
timer** (default 30s, 1s in tests).

If calling `execute!` or `resume!` without the outbox hook, transfer status
projections can be delayed by up to the catch-up interval. Pass the outbox hook
for real-time updates, or reduce `:catch-up-interval-ms` on the transfer projector.

### Saga crash recovery

If the application crashes mid-transfer:
```clojure
(bank.transfer-saga/resume! ds "transfer-id")
```
Every saga step uses idempotency keys, so resumption is always safe.
