# Operations Runbook

Operational procedures for the async event sourcing pipeline:
Event Store (Postgres) -> Outbox -> RabbitMQ -> Async Projectors / Consumers -> Read Store / Notification DB (Postgres)

## Architecture Quick Reference

```
Command -> Decider -> Event Store + Outbox (same txn)
                           |
                      Outbox Poller (100ms poll)
                           |
                      RabbitMQ fanout exchange "events"
                           |
              +------------+------------+
              |            |            |
        Queue:        Queue:       Queue:
        balances      transfers    notifications
              |            |            |
       Account       Transfer     Notification
       Projector     Projector    Consumer Group
              |            |            |
              +------+-----+      +----+----+
                     |             |         |
                Read Store    Reactor    Notification
                (Postgres)   (event ->   Projector
                              command)       |
                                       Notification DB
                                        (Postgres)
```

**Key principle:** RabbitMQ messages are notifications ("wake up"), not event
carriers. Each projector reads directly from the event store using checkpoints.
Lost or duplicate messages are harmless.

## Databases

The full async system uses three databases:

| Database | Purpose | Key Tables |
|----------|---------|------------|
| **Event Store** | Source of truth for all events | `events`, `idempotency_keys`, `event_outbox`, `projection_checkpoints` |
| **Read Store** | Bank read models (async CQRS) | `account_balances`, `transfer_status`, `projection_checkpoints` |
| **Notification DB** | Notification read models + consumer state | `notifications`, `consumer_inbox`, `consumer_checkpoints`, `projection_checkpoints` |

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

1. **Check logs** for `POISON EVENT SKIPPED` -- note the `global_sequence` and `event_type`
2. **Inspect the event** in the event store:
   ```sql
   SELECT * FROM events WHERE global_sequence = <N>;
   ```
3. **Diagnose the cause** -- usually a handler bug or schema mismatch
4. **Fix the handler code** and deploy
5. **Rebuild the projection** to re-process the skipped event:
   ```clojure
   (require '[es.async-projection :as async-proj])
   (async-proj/rebuild! event-store-ds read-db-ds projection-config)
   ```
   Or stop the projector, rebuild, restart.

### Preventing poison events

- Validate event schemas in the domain layer before appending (`validate-event!`)
- Use `skip-unknown? true` on async projectors -- but be aware that unknown event
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
   (es.search/drop-search! read-db-ds "idx_account_balances_search")
   ```
   Or via SQL: `DROP INDEX IF EXISTS idx_account_balances_search;`
4. Truncate read model tables + projection_checkpoints
5. Start the application -- projectors will catch up from global_sequence 0
6. Recreate BM25 search indexes after the rebuild completes:
   ```clojure
   (es.search/ensure-search! read-db-ds bank.account-projection/search-index-config)
   ```

### Important notes

- Rebuilds process in batches of 1000 events to avoid OOM
- If rebuild crashes mid-way, re-run it -- it clears tables first (idempotent)
- Large event stores: rebuilds can take a long time. Monitor progress via:
  ```sql
  SELECT * FROM projection_checkpoints;  -- on read DB
  SELECT MAX(global_sequence) FROM events;  -- on event store
  ```

## Rebuilding Notification Read Models

The notification projection uses the same rebuild pattern but targets the
notification database:

```clojure
;; Stop the notification projector
(component/stop (:notification-projector system))

;; Rebuild from notification events in the event store
(es.async-projection/rebuild! event-store-ds notification-db-ds
  {:projection-name   "notification-status"
   :read-model-tables ["notifications"]
   :handler           notification-handler-fn})

;; Restart
(component/start (:notification-projector system))
```

## Deploying New Event Types

When adding a new event type to the write side:

1. **Deploy the projector handler first** (or simultaneously)
2. If the projector was deployed **after** events of the new type were written,
   those events were skipped by `skip-unknown? true`
3. **Rebuild the projection** to pick up the skipped events

This is the trade-off for independent deployability. The alternative (fail on
unknown) would block the entire projector on unrecognized events.

### Consumer checkpoint behaviour with unknown events

The consumer framework (`es.consumer`) halts checkpoint advancement when it
encounters an unknown event type during catch-up. Known events before and after
the unknown one are still processed (via inbox claims), but the catch-up
checkpoint stays at the position before the unknown event. This means:

- **No data loss** -- the unknown event will be reprocessed after the handler is deployed
- **Known events are not blocked** -- inbox claims are independent of the checkpoint
- **Catch-up replays** -- until the handler is deployed, catch-ups re-read from the halted checkpoint

## Deploying New Integration Events

When adding a new integration event type:

1. **Add the schema** to `events/<module>.clj` (shared contract)
2. **Add the mapper** in the producer's `integration-events.clj`
3. **Add the handler** in the consumer's reactor or handler-specs
4. **Deploy all changes together** (or consumer first, then producer)

The outbox mapper returns `nil` for events that should not be published, so
new domain events are safe to add without publishing them as integration events.

## Dead Letter Queue

### What ends up on the DLQ

Currently: nothing in normal operation. Messages are always ACKed regardless
of whether the catch-up succeeds or fails. The projector relies on its own
checkpoint-based catch-up (timer + RabbitMQ notifications) for retry, not
RabbitMQ redelivery. The DLQ (`<queue>.dlq`) is infrastructure for future use
(e.g., message TTL, explicit rejection).

Since messages are notifications (not event carriers), losing or ACKing a
message is harmless -- the catch-up timer will pick up any missed events
within 30 seconds.

### Queue and DLQ names

| Component | Queue | DLQ |
|-----------|-------|-----|
| Account Projector | `projector.account-balances` | `projector.account-balances.dlq` |
| Transfer Projector | `projector.transfer-status` | `projector.transfer-status.dlq` |
| Notification Consumer | `notifications.account-events` | `notifications.account-events.dlq` |
| Notification Projector | `projector.notification-status` | `projector.notification-status.dlq` |

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
-- Safe to run anytime -- only deletes already-published rows
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

## Consumer Inbox Maintenance

### Table growth

The `consumer_inbox` table accumulates one row per processed event per consumer
group. In production, add periodic cleanup:

```sql
-- Safe to delete old inbox claims (older than 7 days)
DELETE FROM consumer_inbox
WHERE processed_at < NOW() - INTERVAL '7 days';
```

### Checking consumer lag

```sql
-- Compare consumer checkpoint vs latest outbox event
SELECT
  cc.consumer_group,
  cc.last_global_sequence AS consumer_checkpoint,
  (SELECT MAX(global_sequence) FROM event_outbox) AS latest_outbox,
  (SELECT MAX(global_sequence) FROM event_outbox) - cc.last_global_sequence AS lag
FROM consumer_checkpoints cc;
```

### Checking inbox claims

```sql
-- How many events has each consumer group processed?
SELECT consumer_group, COUNT(*), MAX(global_sequence) AS latest
FROM consumer_inbox
GROUP BY consumer_group;
```

## Monitoring Checklist

| Metric | Where to check | Alert threshold |
|---|---|---|
| Projection lag | `projection_checkpoints.last_global_sequence` vs `MAX(events.global_sequence)` | > 1000 events behind |
| Consumer lag | `consumer_checkpoints.last_global_sequence` vs `MAX(event_outbox.global_sequence)` | > 1000 events behind |
| Outbox backlog | `COUNT(*) FROM event_outbox WHERE published_at IS NULL` | > 100 |
| Poison events | Application logs for `POISON EVENT SKIPPED` | Any occurrence |
| Projector health | RabbitMQ management UI -- consumer count per queue | 0 consumers |
| Consumer group health | RabbitMQ management UI -- consumer count on notification queue | 0 consumers |
| DLQ depth | RabbitMQ management UI -- message count on `.dlq` queues | > 0 |
| Inbox table size | `SELECT COUNT(*) FROM consumer_inbox` (per database) | > 1M (schedule cleanup) |

## Saga-Specific Notes

### Transfer saga events and the outbox

The transfer saga (`bank.transfer-saga`) accepts an optional `:on-events-appended`
hook. When provided (e.g. the transactional outbox hook from `es.outbox`), every
command in the saga -- account debits/credits and transfer progress events -- flows
through the hook for real-time projection updates via the outbox -> RabbitMQ
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

### Finding incomplete transfers

```sql
-- Transfers that started but didn't complete or fail
SELECT stream_id, MAX(event_type) AS last_event
FROM events
WHERE stream_id LIKE 'transfer-%'
GROUP BY stream_id
HAVING MAX(event_type) NOT IN ('transfer-completed', 'transfer-failed');
```

Resume each one:
```clojure
(doseq [transfer-id incomplete-ids]
  (bank.transfer-saga/resume! ds transfer-id))
```

## Notification Module Operations

### Checking notification delivery status

```sql
-- All pending notifications (waiting for delivery worker)
SELECT * FROM notifications WHERE status = 'pending';

-- Failed notifications by type
SELECT notification_type, COUNT(*)
FROM notifications
WHERE status = 'failed'
GROUP BY notification_type;

-- Retry distribution
SELECT retry_count, COUNT(*)
FROM notifications
GROUP BY retry_count
ORDER BY retry_count;
```

### Notification reactor debugging

The notification reactor subscribes to bank integration events. If notifications
are not being created:

1. **Check the outbox** -- are integration events being published?
   ```sql
   SELECT * FROM event_outbox WHERE published_at IS NOT NULL ORDER BY id DESC LIMIT 10;
   ```

2. **Check the consumer group** -- is the notification consumer running?
   ```sql
   SELECT * FROM consumer_checkpoints WHERE consumer_group LIKE 'notification%';
   ```

3. **Check the inbox** -- were the events claimed?
   ```sql
   SELECT * FROM consumer_inbox WHERE consumer_group LIKE 'notification%'
   ORDER BY global_sequence DESC LIMIT 10;
   ```

4. **Check the event store** -- were notification aggregates created?
   ```sql
   SELECT * FROM events WHERE stream_id LIKE 'notification-%'
   ORDER BY global_sequence DESC LIMIT 10;
   ```

5. **Check the notification DB** -- did the notification projector run?
   ```sql
   SELECT * FROM projection_checkpoints;
   SELECT * FROM notifications ORDER BY created_at DESC LIMIT 10;
   ```

### Integration event flow tracing

To trace a single event through the entire pipeline:

```sql
-- 1. Find the domain event
SELECT id, global_sequence, stream_id, event_type, payload
FROM events WHERE global_sequence = <N>;

-- 2. Check the outbox
SELECT * FROM event_outbox WHERE global_sequence = <N>;

-- 3. Check consumer inbox (on notification DB)
SELECT * FROM consumer_inbox WHERE global_sequence = <N>;

-- 4. Check if a notification aggregate was created
SELECT * FROM events WHERE stream_id LIKE 'notification-%'
  AND payload->>'global_sequence' = '<N>';

-- 5. Check the notification read model (on notification DB)
SELECT * FROM notifications WHERE stream_id LIKE 'notification-%-<N>';
```

## Connection Pooling (HikariCP)

Each datasource uses HikariCP. Default settings:

| Setting | Default | Tune when |
|---------|---------|-----------|
| `maximum-pool-size` | 10 | Many concurrent writers or projectors |
| `minimum-idle` | 2 | High cold-start latency |
| `connection-timeout` | 30000ms | Connection starvation under load |
| `idle-timeout` | 600000ms | Too many idle connections |
| `max-lifetime` | 1800000ms | PgBouncer or proxy with shorter limits |

Override via datasource config:

```clojure
{:mode :jdbc-url
 :jdbc-url "jdbc:postgresql://..."
 :user "..." :password "..."
 :maximum-pool-size 20
 :minimum-idle 5}
```

## Startup Sequence

The Component system starts in dependency order:

1. **Datasources** -- connect to Event Store, Read Store, Notification DB
2. **Migrators** -- run schema migrations on each database
3. **Search Indexes** -- ensure BM25 indexes exist (ParadeDB)
4. **RabbitMQ Connection** -- connect to broker
5. **Outbox Poller** -- start 100ms polling daemon
6. **Async Projectors** -- subscribe to queues, run initial catch-up
7. **Consumer Groups** -- subscribe to queues, run initial catch-up

### Verifying startup

After start, verify all components are running:

```clojure
;; Check all datasources are connected
(jdbc/execute-one! (get-in system [:event-store-ds :datasource]) ["SELECT 1"])
(jdbc/execute-one! (get-in system [:read-db-ds :datasource]) ["SELECT 1"])
(jdbc/execute-one! (get-in system [:notification-db :datasource]) ["SELECT 1"])

;; Check projector checkpoints
(jdbc/execute! (get-in system [:read-db-ds :datasource])
  ["SELECT * FROM projection_checkpoints"])
```

## Graceful Shutdown

`(component/stop system)` performs:

1. Signal stop to all daemon threads (no new work accepted)
2. Cancel RabbitMQ subscriptions
3. Wait for in-flight work (5s timeout)
4. Close RabbitMQ channels and connection
5. Stop outbox poller
6. Close datasource pools (HikariCP)
7. Stop Testcontainers (dev/test mode only)

If the process is killed (`SIGKILL`), no data is lost:
- Uncommitted transactions roll back (Postgres guarantee)
- Unprocessed outbox rows are retried on next startup
- Projectors catch up from their last checkpoint
- Consumer inbox provides exactly-once on restart
