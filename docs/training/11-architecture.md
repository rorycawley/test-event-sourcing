# Architecture -- Patterns, Trade-offs, and Solutions

This document explains the architectural decisions behind this event sourcing system. It covers the core patterns, the problems they solve, the trade-offs made, and the alternatives considered. Read this to understand not just *what* the code does, but *why* it's built this way.

## Table of Contents

- [The Big Picture](#the-big-picture)
- [The Decider Pattern](#the-decider-pattern)
- [Pull-Transform-Push](#pull-transform-push)
- [Event Store Design](#event-store-design)
- [Concurrency: Advisory Locks + Optimistic Versioning](#concurrency-advisory-locks--optimistic-versioning)
- [Command Idempotency](#command-idempotency)
- [Event Versioning and Upcasting](#event-versioning-and-upcasting)
- [Projections: Disposable Read Models](#projections-disposable-read-models)
- [Synchronous vs Async Projections](#synchronous-vs-async-projections)
- [The Transactional Outbox](#the-transactional-outbox)
- [Notifications, Not Event Carriers](#notifications-not-event-carriers)
- [Poison Event Handling](#poison-event-handling)
- [The Saga Pattern: Cross-Aggregate Coordination](#the-saga-pattern-cross-aggregate-coordination)
- [Data-Driven Composition](#data-driven-composition)
- [Framework/Domain Separation](#frameworkdomain-separation)
- [Component Lifecycle Management](#component-lifecycle-management)
- [BM25 Full-Text Search](#bm25-full-text-search)
- [Testing Strategy](#testing-strategy)
- [When to Add More Layers](#when-to-add-more-layers)
- [References](#references)

---

## The Big Picture

The system has two modes of operation that share the same pure domain logic:

```
SYNCHRONOUS MODE (single database)
───────────────────────────────────
Command → Decider → Event Store ─→ Projection → Read Model
                    (Postgres)      (same DB)    (same DB)


ASYNC MODE (CQRS, separate databases)
──────────────────────────────────────
Command → Decider → Event Store + Outbox ─→ Poller → RabbitMQ
                    (Postgres)                           │
                                                    ┌────┴────┐
                                                    ▼         ▼
                                               Projector  Projector
                                               (catch-up)  (catch-up)
                                                    │         │
                                                    ▼         ▼
                                                 Read DB   Read DB
                                                (Postgres) (same DB)
```

The domain code (`bank.account`, `bank.transfer`) is identical in both modes. Only the infrastructure wiring changes.

---

## The Decider Pattern

**Source:** Jérôme Chassaing, "Functional Event Sourcing: Decider" (2021)

**The problem:** Event sourcing systems often scatter business logic across command handlers, event handlers, sagas, and projections. Where do the rules live? Where does state come from?

**The solution:** A Decider is three things bundled together:

```clojure
{:initial-state {:status :not-found, :balance 0}   ;; state before any events
 :decide        decide                               ;; Command × State → [Event]
 :evolve        evolve}                              ;; State × Event → State
```

- **`decide`** takes a command and the current state, then either produces new events or rejects the command. This is where business rules live.
- **`evolve`** takes the current state and an event, then produces the next state. This is a pure fold step.
- **`initial-state`** is the starting point before any events have occurred.

### Why this matters

The Decider is **entirely pure** — no database, no I/O, no timestamps, no sequence numbers. This means:

1. **Testing is trivial.** You can test every business rule by calling `(decide command state)` with hand-crafted data. No database needed.

2. **Portability.** The same Decider works in-memory (tests), against PostgreSQL (production), or in a REPL with event vectors. Change the infrastructure, the domain doesn't change.

3. **Reasoning.** You can read `bank.account` and understand every business rule without knowing anything about Postgres, RabbitMQ, or projections.

### Example: bank account

```clojure
;; bank/account.clj — pure, no I/O

(defn evolve [state event]
  (let [{:keys [event-type payload]} (validate-event! event)]
    (case event-type
      "account-opened"  (assoc state :status :open :owner (:owner payload))
      "money-deposited" (update state :balance + (:amount payload))
      "money-withdrawn" (update state :balance - (:amount payload))
      state)))

(defn- decide-withdraw [state {:keys [amount]}]
  (when-not (= :open (:status state))
    (throw (ex-info "Account not open" {:error/type :domain/account-not-open})))
  (when (> amount (:balance state))
    (throw (ex-info "Insufficient funds"
                    {:error/type :domain/insufficient-funds
                     :balance (:balance state) :amount amount})))
  [(mk-event "money-withdrawn" {:amount amount})])
```

Notice: no JDBC, no transactions, no locks. Just data in, data out.

### Trade-off: full stream reload

The Decider pattern requires loading the entire event stream for an aggregate on every command (to reconstruct state via `reduce evolve initial-state events`). This is fine for streams with hundreds or low thousands of events. For aggregates with millions of events, you'd need snapshots — which this codebase doesn't implement because bank account streams are naturally small.

---

## Pull-Transform-Push

**Source:** Zach Tellman, *Elements of Clojure*

**The problem:** How do you connect a pure Decider to an impure database?

**The solution:** Three distinct phases:

```
1. PULL      — Load events from the store (I/O, Postgres)
2. TRANSFORM — Fold events into state, decide new events (pure, no I/O)
3. PUSH      — Append new events to the store (I/O, Postgres)
```

This is implemented in `es.decider/handle!`:

```clojure
(defn handle! [ds decider {:keys [stream-id] :as command} & opts]
  ;; — Pull —
  (let [events     (store/load-stream ds stream-id)
        ;; — Transform —
        state      (evolve-state decider events)
        version    (stream-version events)
        new-events ((:decide decider) command state)]
    ;; — Push —
    (store/append-events! ds stream-id version
                          (:idempotency-key command) command new-events
                          :on-events-appended (:on-events-appended opts))))
```

### Why separate phases matter

- **Pull** is the only place that reads from the database. If you change how events are stored, only Pull changes.
- **Transform** is pure and testable without a database. The Decider lives here.
- **Push** is the only place that writes. Concurrency control, idempotency, and the outbox hook all live here.

The domain never knows it's inside a transaction. It never knows about advisory locks or version numbers. It just receives state and returns events.

---

## Event Store Design

**File:** `es/store.clj`

The event store is an append-only log in PostgreSQL with two sequence numbers:

| Field | Scope | Purpose |
|---|---|---|
| `global_sequence` | All streams | Monotonic counter across the entire store. Projections use this as a cursor ("give me everything after position N"). |
| `stream_sequence` | Per stream | Version counter within one aggregate's stream. Used for optimistic concurrency ("I read version 5, so I expect to write version 6"). |

### Why two sequences?

**`global_sequence`** is essential for projections. When a projector asks "what happened since I last checked?", it needs a total order across all streams. This is a Postgres `BIGSERIAL` — monotonically increasing, assigned by the database.

**`stream_sequence`** is essential for concurrency. When two processes try to modify the same account simultaneously, the version check detects the conflict. This is application-assigned, incrementing per-stream.

### Why UUIDv7 for event identity?

Events are identified by UUIDv7, not by their sequence numbers. UUIDv7 is:

- **Time-ordered** — the first 48 bits are a Unix timestamp, so events sort chronologically
- **Globally unique** — no coordination between processes needed
- **Application-generated** — no round-trip to the database to get an ID

The implementation uses `java.security.SecureRandom` for the random bits:

```clojure
(defn uuid-v7 []
  (let [ts     (System/currentTimeMillis)
        rand-a (bit-and (.nextInt secure-random) 0xFFF)
        rand-b (.nextLong secure-random)
        msb    (-> (bit-shift-left ts 16)
                   (bit-or (bit-shift-left 7 12))
                   (bit-or rand-a))
        lsb    (-> (bit-and rand-b 0x3FFFFFFFFFFFFFFF)
                   (bit-or (unchecked-long 0x8000000000000000)))]
    (UUID. msb lsb)))
```

### Why JSONB for payloads?

Event payloads are stored as PostgreSQL JSONB. This means:

- **Schema-free storage** — the store doesn't validate payload structure; that's the domain's job
- **Queryable** — you can write SQL queries against payload fields if needed
- **Indexed** — Postgres can index JSONB paths for fast lookups
- **Compact** — JSONB is stored in a binary format, more compact than text JSON

The store is deliberately version-agnostic. It stores whatever `event_version` it receives and passes it through on read. Versioning is a domain concern.

---

## Concurrency: Advisory Locks + Optimistic Versioning

**The problem:** Two processes handling commands for the same account simultaneously could both read version 5, both decide based on the same state, and both try to write version 6. One of them has stale data.

**The solution:** A three-layer defence:

### Layer 1: Advisory lock (serialises writers)

```clojure
(advisory-lock! tx (str "stream:" stream-id))
```

`pg_advisory_xact_lock` is a Postgres-specific lock that:

- Lives for the duration of the current transaction
- Is keyed by a 64-bit integer (derived from stream-id via MD5)
- Does NOT lock any rows — it's a pure coordination mechanism
- Serialises all writers for the same stream

This means: only one transaction at a time can be writing events for a given stream. Other writers queue up and wait.

### Layer 2: Version check (detects stale reads)

```clojure
(when (not= actual-version expected-version)
  (throw (ex-info "Optimistic concurrency conflict"
                  {:error/type :concurrency/optimistic-conflict
                   :error/retryable? true ...})))
```

Even with advisory locks, the Pull phase happened *before* the transaction started. The state might be stale. The version check catches this: "I read version 5, but someone else already wrote version 6."

### Layer 3: Unique constraint (safety net)

```sql
UNIQUE(stream_id, stream_sequence)
```

If somehow both layers above fail (a bug in our code), the database constraint prevents duplicate versions. This is the belt-and-suspenders approach.

### The retry loop

When a concurrency conflict is detected, the entire Pull → Transform → Push cycle re-executes:

```clojure
(defn handle-with-retry! [ds decider command & opts]
  (loop [attempt 1]
    (let [result (try
                   (handle! ds decider command ...)
                   (catch ExceptionInfo e
                     (if (and (retryable? e) (< attempt max-retries))
                       ::retry
                       (throw e))))]
      (if (= ::retry result)
        (do (sleep (backoff-ms attempt))   ;; exponential backoff + jitter
            (recur (inc attempt)))
        result))))
```

**Why exponential backoff with jitter?** Without it, multiple processes competing for the same stream would retry at the same time (thundering herd), causing repeated conflicts. Jitter spreads retries over time.

### Trade-off: advisory locks are Postgres-specific

This concurrency strategy is deeply tied to PostgreSQL. If you migrated to DynamoDB, you'd use conditional writes instead. The benefit is simplicity: advisory locks are fast, don't touch data rows, and integrate naturally with Postgres transactions.

---

## Command Idempotency

**The problem:** Network failures, retries, and at-least-once delivery mean the same command might arrive twice. If "deposit $100" is processed twice, the account gains $200 — a real bug.

**The solution:** A dedicated `idempotency_keys` table:

```sql
idempotency_keys (
  idempotency_key TEXT PRIMARY KEY,
  stream_id TEXT,
  command_type TEXT,
  command_payload JSONB,
  created_at TIMESTAMPTZ
)
```

### How it works

1. Before processing, try to INSERT the idempotency key:
   ```sql
   INSERT INTO idempotency_keys (idempotency_key, stream_id, command_type, command_payload)
   VALUES (?, ?, ?, ?)
   ON CONFLICT (idempotency_key) DO NOTHING
   RETURNING idempotency_key
   ```

2. If the INSERT succeeds: this is a new command. Proceed normally.

3. If the INSERT conflicts: the key already exists. Compare the stored command with the incoming one:
   - **Same command** (same stream, type, payload) → return `:idempotent` (safe replay)
   - **Different command** → throw "idempotency key collision" (bug in the caller)

### Why a separate table?

**Alternative considered:** Adding an `idempotency_key` column to the events table.

**Problem with that approach:** Checking "was this key used?" would require scanning across all streams. With a dedicated table, the check is a single primary key lookup — fast and independent of event volume.

The idempotency table also avoids cross-stream races. Two commands with the same key targeting different streams are correctly detected as a collision.

### Fast path

`check-idempotency!` is called *before* loading the event stream:

```clojure
(if (= :idempotent (store/check-idempotency! ds stream-id idempotency-key command))
  :idempotent   ;; skip everything: no load, no decide, no append
  ...)
```

This means replayed commands don't even hit the event stream — they short-circuit immediately.

---

## Event Versioning and Upcasting

**The problem:** Event schemas evolve over time. The first version of "money-deposited" had `{:amount 100}`. Later you add `:origin` and `:currency`. Old events in the store still have the old shape.

**The solution:** Events carry a version number. Upcasters transform old versions to current, one step at a time:

```
money-deposited v1: {:amount 100}
       ↓ upcast: add :origin "legacy"
money-deposited v2: {:amount 100, :origin "legacy"}
       ↓ upcast: add :currency "USD"
money-deposited v3: {:amount 100, :origin "legacy", :currency "USD"}
```

### Implementation

Upcasters are declared as data in the domain:

```clojure
;; bank/account.clj
(def ^:private event-upcasters
  {["money-deposited" 1]
   (fn [{:keys [payload] :as event}]
     (assoc event
            :event-version 2
            :payload (assoc payload :origin "legacy")))

   ["money-deposited" 2]
   (fn [{:keys [payload] :as event}]
     (assoc event
            :event-version 3
            :payload (assoc payload :currency "USD")))})
```

The upcaster chain is built by `es.decider-kit/make-event-upcaster`:

```clojure
(loop [{:keys [event-version] :as current} event]
  (if (= event-version latest-ver)
    current
    (let [upcaster (get event-upcasters [event-type event-version])]
      (recur (upcaster current)))))
```

### Key decisions

- **Upcasting happens on read, not on write.** Old events stay as-is in the database. This means you never need to migrate event data.
- **One version step at a time.** Going from v1 to v3 goes through v2. This keeps each upcaster simple and composable.
- **New events are always written at the latest version.** The `make-event-factory` function stamps the current version.
- **Upcasters live in the domain.** The store doesn't know about versions — it just stores and retrieves `event_version` as an integer.

### Trade-off: read-time cost

Every event is upcasted on every read. For streams with thousands of events and many version steps, this adds CPU time. In practice, most events are already at the latest version (only truly old events need upcasting), so the cost is negligible.

---

## Projections: Disposable Read Models

**The problem:** An append-only event store is great for writes but terrible for queries. "What's Alice's balance?" requires loading her entire event stream and folding through every event.

**The solution:** Projections. A projection is a pre-computed, query-optimised view derived from the event stream:

```
Events → Handler → Read Model (e.g. account_balances table)
```

### Key properties

1. **Disposable.** The read model can be destroyed and rebuilt from the event stream at any time. This is the `rebuild!` function.

2. **Checkpoint-based.** Each projection tracks the last `global_sequence` it processed. Catch-up means "read all events after my checkpoint, apply them, advance checkpoint."

3. **Fail-fast.** If a handler throws (unknown event type, invariant violation), the transaction rolls back and the checkpoint doesn't advance. The projection is stuck until the problem is fixed. This is intentional — silent data corruption is worse than a stuck projection.

4. **Idempotent handlers.** Each handler checks `last_global_sequence < ?` in its SQL:

   ```sql
   UPDATE account_balances
   SET balance = balance + ?, last_global_sequence = ?
   WHERE account_id = ? AND last_global_sequence < ?
   ```

   If the same event is applied twice (e.g. after a crash), the `WHERE` clause ensures it's a no-op.

### Why `last_global_sequence` on every read model row?

Without it, replaying events after a crash could apply the same balance change twice. The per-row sequence acts as a fine-grained idempotency guard — even if the projection checkpoint is slightly behind, individual rows are protected.

---

## Synchronous vs Async Projections

This system supports two projection modes. Understanding the trade-offs is critical.

### Synchronous mode (`es.projection`)

```
Event Store + Read Model → same database → same transaction
```

**How it works:**
1. Acquire advisory lock (serialise projection workers)
2. Read checkpoint
3. Read new events since checkpoint
4. Apply events to read model
5. Advance checkpoint
6. Commit transaction

**Guarantees:**
- Events and read model updates are in the same transaction
- If anything fails, everything rolls back
- Checkpoint and read model are always consistent

**Limitations:**
- Read model lives in the event store database (tightly coupled)
- Projection work happens synchronously (on demand)
- Can't scale read and write databases independently

### Async mode (`es.async-projection`)

```
Event Store (DB 1) → RabbitMQ → Projector → Read Model (DB 2)
```

**How it works:**
1. RabbitMQ message (or periodic timer) triggers catch-up
2. Acquire advisory lock on read DB
3. Read checkpoint from read DB
4. Read new events from event store DB
5. Apply events to read model on read DB
6. Advance checkpoint on read DB
7. ACK the RabbitMQ message

**Guarantees:**
- At-least-once delivery (outbox + checkpoint-based idempotency)
- Independent databases for reads and writes
- Projectors can be scaled independently

**Limitations:**
- **Eventual consistency** — the read model may lag behind the event store
- **No cross-database transaction** — if the projector crashes between reading events and writing to the read DB, events are re-processed on restart (safe because handlers are idempotent)
- **More infrastructure** — requires RabbitMQ and a second Postgres instance

### Why advisory locks in both modes?

Without serialisation, two catch-up processes could:
1. Both read the same checkpoint (e.g. position 100)
2. Both read events 101-110
3. Both try to apply the same events
4. One might advance the checkpoint to 110, while the other regresses it to 105

The advisory lock on the read database prevents this by ensuring only one catch-up runs at a time for each projection.

In async mode, there's an additional `compare-and-set!` gate (`catching-up?` atom) to avoid unnecessary lock contention when both a RabbitMQ message and the catch-up timer fire simultaneously.

---

## The Transactional Outbox

**The problem:** You need to write events to Postgres AND publish notifications to RabbitMQ. But these are two different systems. If you write to Postgres and then publish to RabbitMQ, a crash between the two leaves events unpublished. If you publish first and then write, a crash leaves phantom messages in RabbitMQ.

**The solution:** The transactional outbox pattern.

```
┌─────────────── Same Postgres Transaction ──────────────┐
│                                                         │
│  INSERT INTO events (...)                               │
│  INSERT INTO event_outbox (global_sequence)             │
│                                                         │
└─────────────────────────────────────────────────────────┘
         ▼
    Outbox Poller (separate process)
         │
         ├── Read unpublished rows (FOR UPDATE SKIP LOCKED)
         ├── Publish to RabbitMQ
         └── Mark as published (UPDATE ... SET published_at = NOW())
```

### How the hook works

`append-events!` accepts an optional `:on-events-appended` callback:

```clojure
;; Called within the same transaction as the event INSERT
(when on-events-appended
  (on-events-appended tx global-sequences))
```

The outbox hook writes one row per event:

```clojure
(defn make-outbox-hook []
  (fn [tx global-sequences]
    (doseq [gs global-sequences]
      (record! tx gs))))
```

Because the outbox INSERT is in the same transaction as the event INSERT, they succeed or fail together. No dual-write problem.

### Poller design: per-row processing

**Early version (bug):** The poller read a batch of rows, published them all, and marked them all as published in one transaction. Problem: if publish-fn threw on row 5 out of 10, the transaction rolled back, but rows 1-4 were already in RabbitMQ. On retry, they'd be re-published.

**Current version:** Each row is processed individually. Read the batch, then for each row: publish, mark published. If row 5 fails, rows 1-4 are already committed. Row 5 and beyond will be retried.

```clojure
(reduce
 (fn [acc row]
   (publish-fn message)
   (jdbc/with-transaction [tx ds]
     (jdbc/execute-one! tx
       ["UPDATE event_outbox SET published_at = NOW()
         WHERE id = ? AND published_at IS NULL" (:id row)]))
   (inc acc))
 0 rows)
```

### Trade-off: ordering with multiple pollers

`FOR UPDATE SKIP LOCKED` allows concurrent pollers, but inter-poller ordering is NOT guaranteed. Poller A might publish event 5 while Poller B publishes event 3. Since messages are just "wake up" signals (not event carriers), this doesn't matter — each projector reads events in order from the store.

---

## Notifications, Not Event Carriers

**The problem:** How should RabbitMQ messages relate to events? Two approaches:

### Approach A: Messages carry event data

```
Publish: {event-type: "money-deposited", payload: {amount: 100}, ...}
Consumer: parse message, apply directly to read model
```

**Problems:**
- Dual-write: event data exists in both Postgres AND RabbitMQ. Which is authoritative?
- If message is lost, the projector misses events permanently
- If messages arrive out of order, the projector must handle reordering
- Messages drift from the event store if bugs occur

### Approach B: Messages are notifications (what this project does)

```
Publish: {global_sequence: 42, stream_id: "account-1", ...}  ← "wake up" signal
Consumer: ignore message content, call process-new-events!
          which reads directly from the event store
```

**Benefits:**
- **Event store is the single source of truth.** RabbitMQ is just a notification channel.
- **Lost messages are harmless.** The periodic catch-up timer (every 30s) reads any events the projector missed.
- **Duplicate messages are harmless.** Checkpoint-based idempotency skips already-processed events.
- **No reordering problem.** Events are always read from the store in `global_sequence` order.

### Trade-off: latency

In approach A, the projector can apply the event immediately from the message. In approach B, the projector must query the event store on every wake-up. The extra database query adds a few milliseconds of latency. In practice, this is negligible compared to the projection write itself.

### The catch-up timer

RabbitMQ messages provide low-latency wake-ups, but they can be lost (broker restart, network partition). The catch-up timer runs every 30 seconds (configurable) and calls `process-new-events!` regardless of whether any messages arrived. This guarantees eventual consistency even if RabbitMQ goes down.

```clojure
;; Background timer thread in make-consumer
(while @running
  (Thread/sleep catch-up-interval-ms)
  (when @running
    (do-catch-up!)))
```

### Why projectors don't need an inbox

The **transactional inbox** pattern solves idempotent, exactly-once processing for consumers that apply event data directly from the message. The consumer writes the inbound message to a local inbox table inside the same transaction as its side effects, using message ID deduplication.

Our projectors don't need this because the checkpoint *is* the inbox:

- `process-new-events!` reads from the event store where `global_sequence > checkpoint`
- The checkpoint advances in the same transaction as the projection write
- Duplicate RabbitMQ deliveries just trigger a catch-up that finds zero new events
- The projector never reads from the message payload at all

An inbox would become relevant for consumers that **act on the message content directly** — for example, a notification service that sends an email on `account-opened`. That consumer can't go back to the event store and replay; it needs to know "have I already processed this specific message?" to avoid sending duplicate emails. That's the inbox use case.

---

## Poison Event Handling

**The problem:** A projection handler throws on event #42 (maybe a bug, maybe malformed data). Every catch-up reads from the checkpoint, hits event #42, and throws. The projector is permanently stuck.

**The solution:** After N consecutive failures (default 5), skip the poison event:

```
Attempt 1: process events → fail on #42 → consecutive-fails = 1
Attempt 2: process events → fail on #42 → consecutive-fails = 2
...
Attempt 5: process events → fail on #42 → consecutive-fails = 5
           → SKIP: advance checkpoint past #42
           → call on-poison callback for alerting
           → reset consecutive-fails = 0
           → resume normal processing from #43
```

### Implementation details

**Capturing the failed checkpoint:** When a catch-up fails, the code captures the current checkpoint in an atom:

```clojure
(let [checkpoint (read-checkpoint read-db-ds projection-name)
      fails (swap! consecutive-fails inc)]
  (reset! last-failed-checkpoint checkpoint)
  (when (>= fails max-consecutive-failures)
    (skip-poison-event! ...)))
```

**Why capture instead of re-reading?** If another process advances the checkpoint between the failure and the skip, re-reading would get a stale value. By capturing at failure time, we know exactly which event to skip.

**Skip API:** `skip-poison-event!` processes any valid events before the poison, then advances the checkpoint past it:

```clojure
(skip-poison-event! event-store-ds read-db-ds config
                    poison-global-sequence on-poison)
```

It loops one event at a time: valid events are projected normally, and the poison event is skipped (checkpoint advances past it).

### Recovery via rebuild

A full `rebuild!` replays ALL events from the beginning, including previously skipped ones. This is by design: if you fix the bug that caused the handler to throw, rebuild will now process the event correctly.

### Trade-off: data gap

Between skipping and rebuilding, the read model is missing the data from the poison event. For an account balance projection, this could mean a balance is wrong. The `on-poison` callback should trigger alerts so operators investigate promptly.

---

## The Saga Pattern: Cross-Aggregate Coordination

**The problem:** Transferring money between two accounts requires modifying two separate aggregates (source and destination). But each aggregate has its own stream and its own concurrency control. You can't wrap both in a single transaction.

**The solution:** A saga — a sequence of steps, each of which is independently idempotent, with compensation for rollback.

### The transfer as a Decider

The transfer itself is modeled as its own Decider with a state machine:

```
not-found → initiated → debited → credited → completed
                 ↓           ↓
              failed      failed (with compensation)
```

This Decider is pure — it just tracks progress. The actual money movement happens on the account Deciders.

### Step execution

The saga coordinator (`bank.transfer-saga`) orchestrates three Deciders:

```
Step 1: INITIATE  — record transfer intent on transfer stream
Step 2: DEBIT     — withdraw from source account
                    record debit on transfer stream
Step 3: CREDIT    — deposit to destination account
                    record credit on transfer stream
Step 4: COMPLETE  — mark transfer complete on transfer stream
```

Each step uses idempotency keys derived from the transfer ID:

```clojure
(str "transfer-" transfer-id "-" (name step))
;; "transfer-tx-1-initiate", "transfer-tx-1-debit", etc.
```

### Failure and compensation

- **Debit fails** (e.g. insufficient funds): mark transfer failed. No compensation needed — nothing was debited yet.
- **Credit fails** (e.g. destination account closed): **compensate** by depositing back to source, then mark transfer failed.

```clojure
;; Credit failed — refund the source
(let [refund-result (saga/try-command! ds account/decider
                      {:command-type :deposit
                       :stream-id from-account
                       :idempotency-key (step-key transfer-id :compensate)
                       :data {:amount amount}})]
  (when (:error refund-result)
    ;; Compensation MUST succeed — propagate rather than lose money
    (throw (ex-info "Compensation failed" ...))))
```

### Crash recovery

If the process dies mid-transfer, `resume!` reads the transfer stream, evolves to the current state, and re-enters the state machine:

```clojure
(defn resume! [ds transfer-id]
  (let [events (store/load-stream ds (transfer-stream-id transfer-id))
        state  (decider/evolve-state transfer/decider events)]
    (case (:status state)
      :completed {:status :already-completed}
      :failed    {:status :already-failed}
      (run-from ds transfer-id stream state))))
```

Because every step is idempotent, re-execution is always safe. A step that was already completed returns `:idempotent` and the loop advances to the next step.

### Trade-off: saga events in RabbitMQ

The saga supports an optional `:on-events-appended` hook. When the outbox hook is passed (e.g. `(saga/execute! ds id from to amount :on-events-appended outbox-hook)`), every saga step — account debits/credits and transfer progress events — flows through the outbox → RabbitMQ pipeline, giving real-time projection updates. Without the hook, transfer events reach async projectors only via the catch-up timer (default 30s).

---

## Data-Driven Composition

**The problem:** Adding a new aggregate (e.g. a loan product) shouldn't require copying boilerplate from existing aggregates.

**The solution:** Two factory toolkits that turn data declarations into wired-up functions.

### Decider Kit (`es.decider-kit`)

Five factories, each takes data and returns a function:

| Factory | Input | Output |
|---|---|---|
| `make-command-validator` | `{:deposit money-schema}` | `(fn [command] nil \| throw)` |
| `make-event-upcaster` | versions + upcaster fns | `(fn [event] upcasted-event)` |
| `make-event-validator` | schemas + upcaster | `(fn [event] validated-event \| throw)` |
| `make-event-factory` | versions + validator | `(fn [type payload] event)` |
| `make-decide` | validator + decisions | `(fn [command state] [events])` |

A new aggregate declares schemas and decision functions as data:

```clojure
(def command-data-specs {:open-account open-schema, :deposit money-schema})
(def decisions {:open-account decide-open, :deposit decide-deposit})
(def decide (kit/make-decide (kit/make-command-validator command-data-specs)
                             validate-event!
                             decisions))
```

No macros. No multimethods. No inheritance. Just functions that return functions.

### Projection Kit (`es.projection-kit`)

Two factories:

```clojure
;; Handler dispatch
(def handler (kit/make-handler
               {"account-opened"  handle-opened
                "money-deposited" handle-deposit}))

;; Reusable query
(def get-balance (kit/make-query "account_balances" "account_id"))
```

**`:skip-unknown? true`** is important for async projectors that only handle a subset of event types. Without it, a transfer projector receiving an "account-opened" event would throw. With it, unknown events are silently skipped.

---

## Framework/Domain Separation

The codebase is split into two layers:

```
bank.*  (domain)  ──depends-on──►  es.*  (framework)
                                    │
                                    └── never imports bank.*
```

### What each layer knows

| Layer | Namespaces | Knows about |
|---|---|---|
| **Framework** | `es.store`, `es.decider`, `es.projection`, etc. | Events, streams, transactions, locks. Nothing about accounts, transfers, or banking. |
| **Domain** | `bank.account`, `bank.transfer`, `bank.*-projection` | Business rules, event types, read model schemas. Uses framework via function calls. |

### The composition root

`bank.system` and `bank.components` are the wiring points where framework meets domain. They merge projection handler maps, create Component system-maps, and configure queue names.

### Why not hexagonal/onion/ports-and-adapters?

**Considered:** Projection handlers define a protocol (port), and JDBC handlers implement it (adapter):

```
bank.account-projection → ProjectionPort (protocol) ← JdbcProjectionAdapter
```

**Rejected because:**

1. **Projection handlers are inherently SQL.** Their entire purpose is `INSERT INTO account_balances ...`. Abstracting this behind a protocol wraps SQL in an extra function call without adding value.

2. **The Decider is the real boundary.** The critical separation — pure domain vs. effectful infrastructure — is already provided by the Decider pattern. Adding another layer of abstraction on top doesn't solve a new problem.

3. **The infrastructure isn't swappable.** This codebase is deeply Postgres-specific: advisory locks, `FOR UPDATE SKIP LOCKED`, JSONB, BM25 search via ParadeDB. A protocol pretending these are interchangeable would be a leaky abstraction.

4. **~15 namespaces don't need onion layers.** The full dependency graph fits on a whiteboard. Protocol/adapter overhead would add indirection without making the system easier to reason about.

---

## Component Lifecycle Management

**File:** `es/component.clj`

**The problem:** The system has many stateful resources (Postgres connections, RabbitMQ connections, background threads) that must start in dependency order and stop in reverse order. Getting this wrong causes resource leaks or startup failures.

**The solution:** Stuart Sierra's Component library. Each resource is a record implementing the `Lifecycle` protocol:

```clojure
(defrecord Datasource [mode container datasource]
  component/Lifecycle
  (start [this]
    (let [pg (infra/start-postgres!)
          ds (infra/->datasource pg)]
      (assoc this :container pg :datasource ds)))
  (stop [this]
    (when container (infra/stop-postgres! container))
    (assoc this :container nil :datasource nil)))
```

### Dependency graph

```
dev-full-system:
  Migrator (event) ─────► Datasource (event store)
  Migrator (read)  ─────► Datasource (read DB)
  OutboxPoller ──────────► Datasource (event store) + RabbitMQ
  AccountProjector ──────► Datasource (event store) + Datasource (read DB) + RabbitMQ
  TransferProjector ─────► Datasource (event store) + Datasource (read DB) + RabbitMQ
```

Component starts resources in dependency order (datasources first, then migrators, then pollers/projectors) and stops in reverse (projectors first, then pollers, then connections).

### Error handling in stop

Stop methods use try-finally to ensure cleanup even if something throws:

```clojure
(stop [this]
  (try
    (when poller (outbox/stop-poller! poller))
    (finally
      (when channel (rabbitmq/close-channel channel))))
  (assoc this :channel nil :poller nil))
```

This ensures the channel is closed even if stopping the poller throws.

---

## BM25 Full-Text Search

**File:** `es/search.clj`

**The problem:** You need to search projected data (e.g. "find all accounts owned by Alice") with relevance ranking, not just exact matches.

**The solution:** ParadeDB's `pg_search` extension, which adds Tantivy-backed BM25 search to PostgreSQL. No external search service (Elasticsearch, etc.) required.

### Why not SQL migrations for the index?

BM25 indexes have a different lifecycle than schema DDL:

- They must be recreated after `TRUNCATE` (Tantivy index becomes stale)
- They use `DELETE` not `TRUNCATE` between tests (to preserve the index)
- They're idempotent (`CREATE INDEX IF NOT EXISTS`)

So the index is created via `ensure-search!`, typically called on startup, not in migration scripts.

### SQL injection prevention

DDL statements (`CREATE INDEX`, `DROP INDEX`) cannot use parameterised queries. All identifiers are validated against an allowlist:

```clojure
(def ^:private identifier-pattern #"^[a-zA-Z_][a-zA-Z0-9_]*$")

(defn- validate-identifier! [s label]
  (when-not (re-matches identifier-pattern s)
    (throw (ex-info (str "Invalid SQL identifier for " label) {:label label :value s}))))
```

---

## Testing Strategy

The test suite is structured in layers, each building on the previous:

### Layer 1: Unit tests (no database)

**What they test:** Pure domain logic — decide, evolve, command validation, event upcasting.

```clojure
;; bank/account_test.clj
(deftest withdraw-rejects-insufficient-funds
  (is (thrown-with-msg? ExceptionInfo #"Insufficient funds"
        (decide {:command-type :withdraw :data {:amount 200}}
                {:status :open :balance 100}))))
```

**Why they're fast:** No database, no I/O. Hundreds of tests in milliseconds.

### Layer 2: Integration tests (1 database)

**What they test:** The full command → event → projection pipeline against a real Postgres.

```clojure
;; Full lifecycle: open, deposit, withdraw, project, query
(deftest end-to-end-lifecycle
  (handle! ds account/decider open-command)
  (handle! ds account/decider deposit-command)
  (system/process-new-events! ds)
  (is (= 50 (:balance (get-balance ds "account-1")))))
```

**Why real Postgres:** Advisory locks, JSONB, `BIGSERIAL`, and `FOR UPDATE SKIP LOCKED` can't be mocked meaningfully. Testing against a real database catches bugs that mocks hide.

### Layer 3: Async tests (2+ databases)

**What they test:** The outbox, RabbitMQ publishing, async projection across separate databases.

These use Testcontainers to spin up two Postgres instances and a RabbitMQ instance.

### Layer 4: Property-based tests

**What they test:** Invariants that must hold for ANY sequence of commands.

```clojure
(deftest random-deposits-never-go-negative
  (let [result
        (tc/quick-check 200
          (prop/for-all [amounts (gen/vector (gen/such-that pos? gen/nat) 1 20)]
            (let [state (reduce evolve initial-state (map deposit-event amounts))]
              (>= (:balance state) 0))))]
    (is (:pass? result))))
```

### Layer 5: Performance tests

**What they test:** Latency and throughput don't regress between changes.

Results are compared against a checked-in baseline (`perf/baseline.edn`).

### Why Testcontainers everywhere?

Every database test uses Testcontainers (ParadeDB image). This means:

- No external database setup required
- Each test namespace gets a fresh, disposable database
- Tests are isolated — no shared state between namespaces
- CI runs exactly the same as local development

---

## When to Add More Layers

The current two-layer architecture (framework + domain) is sufficient for this codebase. More layers would be justified if:

| Trigger | Layer to add |
|---|---|
| Multiple bounded contexts (orders, payments, shipping alongside bank) | Inter-context boundaries with explicit APIs |
| HTTP API surface | Controller/handler layer that calls domain, never infrastructure directly |
| Non-Postgres targets (Elasticsearch projections, DynamoDB store) | Port/adapter per target with protocol interfaces |
| Team boundaries (separate teams own domain vs. infrastructure) | Formal interface contracts between layers |
| Mobile/web clients with different query patterns | Dedicated read model per client, possibly with GraphQL |

For now, the discipline of "aggregates are pure, projections are SQL, framework doesn't know about domain" provides the important benefits without the ceremony of additional abstraction layers.

---

## References

- **Jérôme Chassaing** — [Functional Event Sourcing: Decider](https://thinkbeforecoding.com/post/2021/12/17/functional-event-sourcing-decider) — the Decider pattern
- **Zach Tellman** — *Elements of Clojure* — Pull, Transform, Push
- **Greg Young** — Event Sourcing and CQRS
- **Stuart Sierra** — [Component](https://github.com/stuartsierra/component) — lifecycle management
- **Chris Richardson** — [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
- **ParadeDB** — [pg_search](https://www.paradedb.com/) — BM25 full-text search for PostgreSQL
