# Operations Runbook

Operational procedures for the async event sourcing pipeline.

```
Command -> Decider -> Event Store + Outbox (same txn)
                           |
                      Outbox Poller (100ms)
                           |
                      RabbitMQ fanout exchange "events"
                           |
              +------------+------------+
              |            |            |
        Queue:        Queue:       Queue:
     projector.    projector.   notifications.
     account-     transfer-    account-events
     balances     status            |
              |            |   +----+----+
       Account       Transfer  |         |
       Projector     Projector Reactor  Notification
              |            |  (event -> Projector
              +------+-----+  command)     |
                     |            |    Notification DB
                Read Store   Event Store
                (Postgres)   (Postgres)
```

RabbitMQ messages are wake-up signals, not event carriers. Each projector
reads directly from the event store using checkpoints. Lost or duplicate
messages are harmless -- the catch-up timer recovers within 30 seconds.

## Databases

| Database | Purpose | Tables |
|----------|---------|--------|
| **Event Store** | Append-only event log | `events`, `idempotency_keys`, `event_outbox`, `projection_checkpoints` |
| **Read Store** | Bank read models | `account_balances`, `transfer_status`, `projection_checkpoints` |
| **Notification DB** | Notification read models + consumer state | `notifications`, `consumer_inbox`, `consumer_checkpoints`, `projection_checkpoints` |

`consumer_inbox` and `consumer_checkpoints` live in the **notification DB**,
not the event store. The event store has no consumer tables.

## Migrations

Each database has its own migration directory:

```bash
# Event store (resources/migrations/)
JDBC_URL=jdbc:postgresql://localhost:5432/eventstore bb migrate

# Read store (resources/read-migrations/)
JDBC_URL=jdbc:postgresql://localhost:5432/readstore bb migrate-read

# Notification DB (resources/notification-migrations/)
JDBC_URL=jdbc:postgresql://localhost:5432/notificationdb bb migrate-notification
```

Or directly via the CLI with a custom migration directory:

```bash
clj -M -m es.migrations-cli migrate read-migrations
clj -M -m es.migrations-cli status notification-migrations
```

## Queues and DLQs

| Component | Queue | DLQ |
|-----------|-------|-----|
| Account Projector | `projector.account-balances` | `projector.account-balances.dlq` |
| Transfer Projector | `projector.transfer-status` | `projector.transfer-status.dlq` |
| Notification Consumer | `notifications.account-events` | `notifications.account-events.dlq` |
| Notification Projector | `projector.notification-status` | `projector.notification-status.dlq` |

Nothing reaches the DLQ in normal operation. Messages are always ACKed.
DLQ messages are just stale notifications and can be safely purged:

```bash
rabbitmqctl purge_queue projector.account-balances.dlq
```

## Poison Events

A poison event causes a projection handler to throw on every attempt.

**Automatic handling:** after 5 consecutive failures on the same event
(configurable via `:max-consecutive-failures`), the projector skips the
event, advances the checkpoint past it, and logs `POISON EVENT SKIPPED`.
Only handler errors count -- infrastructure failures (DB outages) do not.

**Recovery:**

1. Find the event: `SELECT * FROM events WHERE global_sequence = <N>;`
2. Fix the handler code and deploy
3. Rebuild the projection (see below)

## Rebuilding Projections

### When to rebuild

- After fixing a poison event handler
- After adding handlers for previously skipped event types
- After schema changes to read model tables

### Bank projections (read store)

```clojure
;; Stop the projector first
(component/stop (:account-projector system))

;; Rebuild
(es.async-projection/rebuild! event-store-ds read-db-ds
  {:projection-name   "account-balances"
   :read-model-tables ["account_balances"]
   :handler           handler-fn})

;; Restart
(component/start (:account-projector system))
```

### Notification projection (notification DB)

```clojure
(component/stop (:notification-projector system))

(es.async-projection/rebuild! event-store-ds notification-db-ds
  {:projection-name   "notification-status"
   :read-model-tables ["notifications"]
   :handler           notification-handler-fn})

(component/start (:notification-projector system))
```

### Offline rebuild with BM25 search indexes

TRUNCATE invalidates ParadeDB BM25 indexes. If rebuilding offline:

1. Drop search indexes first:
   ```clojure
   (es.search/drop-search! read-db-ds "idx_account_balances_search")
   ```
2. Truncate read model tables and `projection_checkpoints`
3. Start the application -- projectors catch up from sequence 0
4. Recreate search indexes after rebuild completes:
   ```clojure
   (es.search/ensure-search! read-db-ds bank.account-projection/search-index-config)
   ```

### Monitoring rebuild progress

```sql
SELECT * FROM projection_checkpoints;           -- on read DB
SELECT MAX(global_sequence) FROM events;         -- on event store
```

## Deploying New Event Types

1. Deploy the projector handler first (or simultaneously)
2. If events were written before the handler existed, they were skipped
   by `skip-unknown? true`
3. Rebuild the projection to pick up skipped events

### Consumer checkpoint behaviour with unknown events

The consumer framework (`es.consumer`) halts checkpoint advancement at
the first unknown event type. Known events before and after it are still
processed via inbox claims, but the catch-up checkpoint stays behind the
unknown event. After deploying the handler, catch-up replays from the
halted position -- no data is lost.

## Deploying New Integration Events

1. Add the schema to `events/<module>.clj` (shared contract)
2. Add the mapper in the producer's `integration-events.clj`
3. Add the handler in the consumer's reactor
4. Deploy all changes together (or consumer first, then producer)

The outbox mapper returns `nil` for events that should not be published.

## Outbox Maintenance

### Checking lag

```sql
SELECT COUNT(*) FROM event_outbox WHERE published_at IS NULL;

SELECT MIN(id), MIN(global_sequence)
FROM event_outbox WHERE published_at IS NULL;
```

If the count is growing: check the outbox-poller daemon thread is alive,
RabbitMQ is reachable, and the poller logs for errors.

### Cleanup

```sql
DELETE FROM event_outbox
WHERE published_at < NOW() - INTERVAL '7 days';
```

## Consumer Inbox Maintenance

### Cleanup

```sql
DELETE FROM consumer_inbox
WHERE processed_at < NOW() - INTERVAL '7 days';
```

### Checking consumer lag

```sql
SELECT
  cc.consumer_group,
  cc.last_global_sequence AS consumer_checkpoint,
  (SELECT MAX(global_sequence) FROM event_outbox) AS latest_outbox,
  (SELECT MAX(global_sequence) FROM event_outbox) - cc.last_global_sequence AS lag
FROM consumer_checkpoints cc;
```

## Transfer Saga

The transfer saga (`bank.transfer-saga`) coordinates debits and credits
across two account aggregates with a transfer progress tracker. Every
step uses idempotency keys, so re-execution is always safe.

### Crash recovery

```clojure
(bank.transfer-saga/resume! ds "transfer-id")
```

### Finding incomplete transfers

```sql
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

### Outbox hook

Pass `(system/make-outbox-hook)` as `:on-events-appended` for real-time
projection updates via the outbox pipeline. Without it, projections
update on the next catch-up timer tick (30s default, 1s in tests).

## Notification Module

### Checking delivery status

```sql
SELECT * FROM notifications WHERE status = 'pending';

SELECT notification_type, COUNT(*)
FROM notifications WHERE status = 'failed'
GROUP BY notification_type;
```

### Debugging missing notifications

1. **Outbox** -- are integration events published?
   ```sql
   SELECT * FROM event_outbox WHERE published_at IS NOT NULL ORDER BY id DESC LIMIT 10;
   ```
2. **Consumer checkpoint** -- is the notification consumer running?
   ```sql
   -- on notification DB
   SELECT * FROM consumer_checkpoints WHERE consumer_group = 'account-notifications';
   ```
3. **Inbox claims** -- were events claimed?
   ```sql
   -- on notification DB
   SELECT * FROM consumer_inbox WHERE consumer_group = 'account-notifications'
   ORDER BY global_sequence DESC LIMIT 10;
   ```
4. **Notification aggregates** -- were they created?
   ```sql
   -- on event store
   SELECT * FROM events WHERE stream_id LIKE 'notification-%'
   ORDER BY global_sequence DESC LIMIT 10;
   ```
5. **Notification read model** -- did the projector run?
   ```sql
   -- on notification DB
   SELECT * FROM projection_checkpoints;
   SELECT * FROM notifications ORDER BY created_at DESC LIMIT 10;
   ```

### Notification projector freshness

The outbox hook only publishes bank integration events. Notification
aggregate events written by the reactor do NOT trigger a RabbitMQ wake-up.
The notification projector relies on the catch-up timer (30s default).
If real-time freshness is needed, add a second outbox hook for notification
aggregate writes in the reactor.

## Monitoring Checklist

| Metric | Where | Alert |
|--------|-------|-------|
| Projection lag | `projection_checkpoints` vs `MAX(events.global_sequence)` | > 1000 behind |
| Consumer lag | `consumer_checkpoints` vs `MAX(event_outbox.global_sequence)` | > 1000 behind |
| Outbox backlog | `COUNT(*) FROM event_outbox WHERE published_at IS NULL` | > 100 |
| Poison events | Logs: `POISON EVENT SKIPPED` | Any |
| Projector health | RabbitMQ UI: consumer count per queue | 0 consumers |
| Consumer health | RabbitMQ UI: consumers on `notifications.account-events` | 0 consumers |
| DLQ depth | RabbitMQ UI: `.dlq` queue depth | > 0 |
| Inbox size | `COUNT(*) FROM consumer_inbox` per DB | > 1M |

## Connection Pooling

Each datasource uses HikariCP with these defaults:

| Setting | Default |
|---------|---------|
| `maximum-pool-size` | 10 |
| `minimum-idle` | 2 |
| `connection-timeout` | 30000ms |
| `idle-timeout` | 600000ms |
| `max-lifetime` | 1800000ms |

Override via datasource config map (`:jdbc-url` mode).

## Startup Sequence

Component starts in dependency order:

1. Datasources (Event Store, Read Store, Notification DB)
2. Migrators (schema migrations on each database)
3. Search indexes (BM25 via ParadeDB)
4. RabbitMQ connection
5. Outbox poller (100ms poll daemon)
6. Async projectors (subscribe + initial catch-up)
7. Consumer groups (subscribe + initial catch-up)

Each projector and consumer group runs an immediate catch-up on start
to process events written while the system was down.

## Graceful Shutdown

`(component/stop system)` performs:

1. Signal stop to all daemon threads
2. Cancel RabbitMQ consumers
3. Wait for in-flight work (5s timeout)
4. Close RabbitMQ channels and connection
5. Stop outbox poller
6. Close HikariCP connection pools

No data is lost on `SIGKILL`:
- Uncommitted transactions roll back
- Unprocessed outbox rows retry on next startup
- Projectors catch up from their last checkpoint
- Consumer inbox provides exactly-once on restart
