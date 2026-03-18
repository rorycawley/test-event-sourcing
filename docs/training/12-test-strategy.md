# Test Strategy -- Deep Analysis

This document explains the testing strategy used in this event sourcing system: what is tested, why it's tested that way, how each test achieves its goal, and how effective the strategy is at catching real bugs.

## Table of Contents

- [Philosophy: What Testing Means Here](#philosophy-what-testing-means-here)
- [The Test Pyramid](#the-test-pyramid)
- [Layer 1: Pure Unit Tests](#layer-1-pure-unit-tests)
- [Layer 2: Framework Unit Tests](#layer-2-framework-unit-tests)
- [Layer 3: Integration Tests (Single Database)](#layer-3-integration-tests-single-database)
- [Layer 4: Async Integration Tests (Two Databases)](#layer-4-async-integration-tests-two-databases)
- [Layer 5: End-to-End Tests (Full Pipeline)](#layer-5-end-to-end-tests-full-pipeline)
- [Layer 6: Property-Based (Fuzz) Tests](#layer-6-property-based-fuzz-tests)
- [Layer 7: Performance Tests](#layer-7-performance-tests)
- [Test Infrastructure: Fixtures and Isolation](#test-infrastructure-fixtures-and-isolation)
- [Concurrency Testing](#concurrency-testing)
- [What the Tests Prove](#what-the-tests-prove)
- [What the Tests Don't Cover](#what-the-tests-dont-cover)
- [Effectiveness Analysis](#effectiveness-analysis)

---

## Philosophy: What Testing Means Here

Event sourcing systems have a specific failure profile. The dangerous bugs aren't typos or off-by-ones — they're:

- **Data corruption through race conditions** — two processes advance the same checkpoint, double-counting events
- **Silent data loss through idempotency failures** — replayed commands create duplicate events, or collisions go undetected
- **Stuck projections from poison events** — one bad event blocks all future processing forever
- **Money errors in cross-aggregate coordination** — a saga deducts from one account but crashes before crediting another
- **Schema drift** — old events in the store don't match the current code

The test strategy is shaped around these failure modes. Pure logic gets simple unit tests. Concurrency, idempotency, and crash recovery get dedicated integration tests against real Postgres. The question isn't "does the happy path work?" — it's "does the system behave correctly when things go wrong?"

---

## The Test Pyramid

```
                    ┌───────────────────┐
                    │   End-to-End      │  3 tests
                    │ (3 Testcontainers)│  Full async pipeline
                    ├───────────────────┤
                    │  Async Integration│  8 tests
                    │ (2× Postgres)     │  Two-DB projection, outbox, poison
                    ├───────────────────┤
                    │    Integration    │  ~40 tests
                    │ (1× Postgres)     │  Concurrency, idempotency, sagas
                    ├───────────────────┤
                    │    Framework Unit │  ~30 tests
                    │  (mocks/redefs)   │  Retry logic, kit factories, CLI
                    ├───────────────────┤
                    │     Pure Unit     │  ~20 tests + property-based
                    │  (no I/O at all)  │  Domain logic, schemas, upcasting
                    └───────────────────┘
```

The pyramid is intentionally bottom-heavy in the domain layer (pure tests are cheap and fast) and intentionally thorough in the integration layer (concurrency bugs require real database behaviour).

---

## Layer 1: Pure Unit Tests

### What

Tests for `bank.account` and `bank.transfer` — the Decider logic. No database, no fixtures, no I/O.

### Why

The Decider pattern exists specifically to make the most important code — business rules — testable without infrastructure. If `decide-withdraw` correctly rejects insufficient funds as a pure function, it will correctly reject them against Postgres, against a test double, or in any future environment. Testing pure functions is the highest-value, lowest-cost investment.

### How

Each test calls `decide` or `evolve` directly with hand-crafted state:

```clojure
;; bank/account_test.clj
(deftest domain-rule-violations-are-typed
  (let [funds-e (capture-exception
                 #(account/decide {:command-type :withdraw
                                   :data {:amount 200}}
                                  {:status :open :balance 100}))]
    (is (= :domain/insufficient-funds (:error/type (ex-data funds-e))))))
```

**What makes this effective:**

- **No setup cost.** No container startup, no migrations, no cleanup. These tests run in milliseconds.
- **Precise assertions.** Each test targets one rule. The assertion checks the structured error type (`:domain/insufficient-funds`), not just "did it throw?". This means tests fail for the right reason.
- **Every error path is typed.** The tests verify `:error/type` keywords, which means callers (like the saga) can match on error types reliably. If someone changes the error keyword, the test breaks immediately.

### What is tested

| File | Tests | What it proves |
|---|---|---|
| `account_test.clj` | 9 | Invalid amounts, unknown commands, invalid shapes, domain rejections (already-open, insufficient funds), deposit produces correct events, upcasting chain (v1→v3, v2→v3), future version rejection |
| `transfer_test.clj` | ~10 | State machine transitions (initiate→debit→credit→complete), guard validations (wrong status, account mismatch, amount mismatch), terminal state rejection |

### What it catches

- Incorrect business rules (wrong threshold, wrong state check)
- Missing event fields
- Broken upcasting chains
- Schema validation failures

---

## Layer 2: Framework Unit Tests

### What

Tests for the framework machinery that operates independently of any domain: retry logic, data-driven factories, saga helpers, store utilities, migration wrappers, CLI.

### Why

The framework is reused across all aggregates. A bug in `make-event-upcaster` affects every Decider in the system. These tests ensure the machinery works correctly with minimal setup.

### How

Two techniques: **pure function tests** and **`with-redefs` mocking**.

**Pure tests** (no I/O) verify functions like `optimistic-concurrency-conflict?`:

```clojure
;; es/store_test.clj
(deftest optimistic-concurrency-conflict-requires-both-type-and-retryable
  (is (false? (store/optimistic-concurrency-conflict?
               (ex-info "x" {:error/type :concurrency/optimistic-conflict
                              :error/retryable? false}))))
  (is (true? (store/optimistic-concurrency-conflict?
              (ex-info "x" {:error/type :concurrency/optimistic-conflict
                             :error/retryable? true})))))
```

**Mocked tests** replace I/O functions to test control flow:

```clojure
;; es/decider_test.clj — tests retry logic without a database
(deftest handle-with-retry-retries-on-optimistic-conflict
  (let [call-count (atom 0)]
    (with-redefs [es.decider/handle!
                  (fn [& _]
                    (swap! call-count inc)
                    (if (< @call-count 3)
                      (throw (ex-info "conflict"
                               {:error/type :concurrency/optimistic-conflict
                                :error/retryable? true}))
                      :ok))]
      (is (= :ok (decider/handle-with-retry! :fake-ds {} {}
                    :sleep-fn (fn [_] nil)))))))
```

### Key files and what they prove

| File | What it proves |
|---|---|
| `decider_test.clj` | Retry stops at max-retries, non-retryable errors propagate, backoff increases exponentially, invalid envelope caught |
| `decider_kit_test.clj` | All five factory functions validate inputs, dispatch correctly, chain upcasters, reject unknown types/versions, auto-wrap envelope schemas |
| `store_test.clj` | UUIDv7 has correct version/variant bits, is monotonic, JSON roundtrips through PGobject, nil handling |
| `projection_kit_test.clj` | Handler dispatches on event-type, throws on unknown (with full context), passes context through, propagates return values |
| `saga_test.clj` | `domain-error?` distinguishes `:domain/*` from other error types, `try-command!` catches domain errors but propagates infra errors, `run-loop` drives state machine to terminal state |
| `migrations_test.clj` | Config construction is consistent across migrate/rollback/status |
| `migrations_cli_test.clj` | CLI dispatches commands correctly, handles missing env vars, builds datasource with optional credentials |

### What it catches

- Retry logic bugs (infinite loops, wrong backoff)
- Factory wiring errors (schema validation skipped, dispatch to wrong handler)
- API contract violations (wrong function signature, missing required fields)

---

## Layer 3: Integration Tests (Single Database)

### What

Tests that run against a real PostgreSQL instance (via Testcontainers). These verify the interactions between code and database that cannot be tested with mocks.

### Why

The most dangerous bugs in this system live in the database interactions:

1. **Advisory locks** — do they actually serialise writers? Mocks can't test this.
2. **Optimistic concurrency** — does the version check work under real concurrent load?
3. **Idempotency** — does `INSERT ... ON CONFLICT DO NOTHING` behave correctly with concurrent inserts?
4. **Projection fail-fast** — does the transaction actually roll back, preventing checkpoint advancement?
5. **TRUNCATE RESTART IDENTITY** — do deterministic sequence values make assertions reliable?

These are properties of the database, not of the code. You cannot verify them without a real database.

### How: Fixtures

Every integration test namespace uses the same two-fixture pattern:

```clojure
(use-fixtures :once support/with-system)   ;; start Postgres once per namespace
(use-fixtures :each support/with-clean-db) ;; TRUNCATE before each test
```

**`:once` fixture** — starts a single Testcontainer for the entire namespace:

```clojure
(defn with-system [f]
  (let [pg (infra/start-postgres!)
        ds (infra/->datasource pg)]
    (try
      (migrations/migrate! ds)
      (binding [*ds* ds]
        (f))
      (finally
        (infra/stop-postgres! pg)))))
```

**`:each` fixture** — truncates all tables with `RESTART IDENTITY`:

```clojure
(defn reset-db! []
  (jdbc/with-transaction [tx *ds*]
    (jdbc/execute-one! tx
      ["TRUNCATE TABLE account_balances, transfer_status,
                       projection_checkpoints, events,
                       idempotency_keys, event_outbox
        RESTART IDENTITY"])))
```

**Why `RESTART IDENTITY`?** Without it, `global_sequence` values depend on how many events previous tests inserted. With it, every test starts from `global_sequence = 1`, making assertions like `(is (= 3 (checkpoint)))` deterministic.

### What is tested

**Idempotency** (`integration_test.clj`):

```clojure
(deftest replayed-command-returns-idempotent
  ;; Same command, same key → returns :idempotent (not :ok)
  (is (= :ok (decider/handle! *ds* account/decider
               {:command-type :open-account :stream-id "acct-1"
                :idempotency-key "cmd-open-1" :data {:owner "Alice"}})))
  (is (= :idempotent (decider/handle! *ds* account/decider
               {:command-type :open-account :stream-id "acct-1"
                :idempotency-key "cmd-open-1" :data {:owner "Alice"}}))))
```

**Why this matters:** Without this test, a network retry could create duplicate events, leading to double-counted balances.

**Idempotency collision** — same key, different command:

```clojure
(deftest idempotency-key-collision-throws
  ;; Different stream, same key → collision (not replay)
  (decider/handle! *ds* account/decider
    {:stream-id "acct-1" :idempotency-key "shared-key" :data {:owner "Alice"} ...})
  (let [e (try (decider/handle! *ds* account/decider
                 {:stream-id "acct-2" :idempotency-key "shared-key" :data {:owner "Bob"} ...})
               (catch ExceptionInfo ex ex))]
    (is (= :idempotency/key-collision (:error/type (ex-data e))))))
```

**Why this matters:** Without collision detection, a caller reusing keys across streams would silently overwrite commands.

**Projection fail-fast** — invalid events don't corrupt the read model:

```clojure
(deftest projection-fail-fast-does-not-advance-checkpoint
  ;; Deposit without account-opened → invariant violation
  (store/append-events! *ds* "broken-1" 0 "key" cmd
    [{:event-type "money-deposited" :payload {:amount 15}}])
  (let [e (try (system/process-new-events! *ds*) (catch ExceptionInfo ex ex))]
    (is (= "Projection invariant violation: expected single-row update" (.getMessage e))))
  ;; Checkpoint MUST NOT advance
  (is (= 0 (support/checkpoint)))
  ;; No row created for the broken stream
  (is (nil? (account-projection/get-balance *ds* "broken-1"))))
```

**Why this matters:** If the checkpoint advanced past a failed event, the projection would permanently miss that event's data. Balances would be wrong with no way to detect it.

**Legacy event upcasting through the full stack:**

```clojure
(deftest projection-upcasts-legacy-v1-deposit-events
  ;; Simulate historical v1 events in the store
  (store/append-events! *ds* "legacy" 0 "key" cmd
    [{:event-type "account-opened" :event-version 1 :payload {:owner "Alice"}}
     {:event-type "money-deposited" :event-version 1 :payload {:amount 10}}])
  (system/process-new-events! *ds*)
  (is (= 10 (:balance (account-projection/get-balance *ds* "legacy")))))
```

**Why this matters:** Old events in the store must still produce correct projections. This test proves the full chain: store read → upcast v1→v2→v3 → projection handler → correct balance.

### Saga tests (`transfer_saga_test.clj`)

The saga tests are integration tests that verify the most complex behaviour in the system: multi-aggregate coordination with crash recovery.

**Happy path** — verifies all four transfer events and correct balances:

```clojure
(deftest successful-transfer-moves-funds
  (open-and-fund! "alice" "Alice" 200)
  (open-and-fund! "bob" "Bob" 50)
  (let [result (saga/execute! *ds* "tx-1" "alice" "bob" 75)]
    (is (= :completed (:status result))))
  ;; Verify via event streams (authoritative)
  (is (= 125 (:balance (evolve-state account/decider (load-stream *ds* "alice")))))
  (is (= 125 (:balance (evolve-state account/decider (load-stream *ds* "bob")))))
  ;; Verify transfer stream has exactly 4 events in order
  (is (= ["transfer-initiated" "debit-recorded" "credit-recorded" "transfer-completed"]
         (mapv :event-type (load-stream *ds* "transfer-tx-1")))))
```

**Compensation** — credit fails, money is refunded:

```clojure
(deftest transfer-compensates-when-destination-not-open
  (open-and-fund! "alice" "Alice" 200)
  ;; "ghost" was never opened — credit will fail
  (let [result (saga/execute! *ds* "tx-comp-1" "alice" "ghost" 50)]
    (is (= :failed (:status result))))
  ;; Alice's balance restored
  (is (= 200 (:balance (evolve-state account/decider (load-stream *ds* "alice")))))
  ;; Transfer events: initiated, debit-recorded, failed (compensation happened)
  (is (= ["transfer-initiated" "debit-recorded" "transfer-failed"]
         (mapv :event-type (load-stream *ds* "transfer-tx-comp-1")))))
```

**Why this matters:** Without compensation, a failed credit leaves money in limbo — debited from Alice but never credited to anyone. This test proves the refund happens.

**Crash recovery** — resume from every possible mid-saga state:

```clojure
(deftest resume-from-debited-completes-transfer
  ;; Simulate: debit succeeded, process crashed before credit
  ;; Manually debit Alice and write partial transfer events
  (decider/handle! *ds* account/decider withdraw-command)
  (write-transfer-events! "transfer-tx-resume-2"
    [{:event-type "transfer-initiated" ...}])
  (store/append-events! *ds* "transfer-tx-resume-2" 1 ...
    [{:event-type "debit-recorded" ...}])
  ;; Resume picks up from :debited
  (let [result (saga/resume! *ds* "tx-resume-2")]
    (is (= :completed (:status result))))
  ;; Money arrived at destination
  (is (= 130 (:balance bob-state))))
```

**Why this matters:** This simulates the most dangerous failure mode — the system crashed after taking money from one account but before giving it to another. The test proves `resume!` correctly continues from where it left off.

There are resume tests for **every non-terminal state**: `:initiated`, `:debited`, `:credited`, `:completed`, `:failed`, and `:not-found`. This covers every possible crash point in the saga.

---

## Layer 4: Async Integration Tests (Two Databases)

### What

Tests that use two separate Postgres instances — one for the event store, one for the read model — to verify the async projection engine, outbox, and poison event handling.

### Why

The async projection mode fundamentally changes the consistency model. Events and projections are in **different databases** with **no shared transaction**. Race conditions that are impossible in single-DB mode become possible. These tests verify correctness under this weaker consistency model.

### How: Two-container fixture

```clojure
(defn- with-system [f]
  (let [event-pg (infra/start-postgres!)
        event-ds (infra/->datasource event-pg)
        read-pg  (infra/start-postgres!)
        read-ds  (infra/->datasource read-pg)]
    (try
      (migrations/migrate! event-ds)                         ;; events, idempotency_keys
      (migrations/migrate! read-ds :migration-dir "read-migrations") ;; checkpoints, balances
      (binding [*event-store-ds* event-ds
                *read-db-ds*     read-ds]
        (f))
      (finally
        (infra/stop-postgres! read-pg)
        (infra/stop-postgres! event-pg)))))
```

**Why separate containers?** The read DB doesn't have the `events` table. The event store doesn't have `account_balances`. One test (`event-store-and-read-db-are-independent`) explicitly verifies this by checking `information_schema.tables`:

```clojure
(deftest event-store-and-read-db-are-independent
  (let [tables (jdbc/execute! *read-db-ds*
                 ["SELECT table_name FROM information_schema.tables WHERE table_schema = 'public'"])]
    (is (contains? table-names "projection_checkpoints"))
    (is (not (contains? table-names "events")))))
```

### Toy domain, not bank domain

The `es/projection_test.clj` and `es/async_projection_test.clj` files use a **toy counter projection** instead of the bank domain:

```clojure
(def counter-handler-specs
  {"counter-created"     (fn [tx event _ctx] ...)
   "counter-incremented" (fn [tx event ctx] ...)})
```

**Why?** These tests verify the **framework**, not the domain. Using a toy domain proves the projection engine works with *any* domain, not just banks. If the tests used `bank.account`, a projection bug could be confused with a domain bug.

### Outbox tests (`outbox_test.clj`)

The outbox tests verify the full lifecycle: record, poll, publish, mark.

**Transaction boundary — hook failure rolls back events:**

```clojure
(deftest hook-failure-rolls-back-events
  (let [failing-hook (fn [_tx _seqs] (throw (ex-info "Hook exploded" {})))]
    (is (thrown? ExceptionInfo
          (store/append-events! *ds* "s-fail" 0 nil cmd events
            :on-events-appended failing-hook)))
    (is (empty? (store/load-stream *ds* "s-fail")))
    (is (empty? (outbox-rows)))))
```

**Why this matters:** The outbox hook runs inside the event store transaction. If it throws, both the events AND the outbox rows must roll back. Otherwise you'd have events without outbox entries (events never published) or outbox entries without events (phantom notifications).

**Publish failure doesn't mark as published:**

```clojure
(deftest publish-failure-does-not-mark-as-published
  (append-test-events! "s-pub-fail" events :on-events-appended hook)
  (is (thrown? Exception
        (outbox/poll-and-publish! *ds*
          (fn [_] (throw (ex-info "RabbitMQ down" {}))) 100)))
  ;; Row still unpublished — poller will retry
  (is (nil? (:published-at (first (outbox-rows))))))
```

**Why this matters:** If a publish failure marked the row as published, that event would never be sent to RabbitMQ. The projector would miss it permanently.

### Poison event test (`async_projection_test.clj`)

This is one of the most important tests in the suite:

```clojure
(deftest poison-event-skipped-after-max-failures
  ;; 1. Append a poison event (unknown type) followed by valid events
  (append-counter-events! "c-poison" [{:event-type "totally-unknown" :payload {}}])
  (append-counter-events! "c-good"   [{:event-type "counter-created" :payload {}}
                                       {:event-type "counter-incremented" :payload {:delta 42}}])
  ;; 2. Fail 3 times
  (dotimes [_ 3]
    (try (async-proj/process-new-events! ...) (catch Exception _)))
  (is (= 0 (checkpoint)))  ;; checkpoint must NOT advance

  ;; 3. Skip the poison event
  (let [failed-checkpoint (checkpoint)
        skip-fn (#'async-proj/skip-poison-event! *read-db-ds* "counter-async"
                  failed-checkpoint on-poison-callback)]
    (is (true? (skip-fn *event-store-ds*))))
  (is (= 1 (checkpoint)))  ;; now past the poison event

  ;; 4. Process remaining events normally
  (is (= 2 (do-catch-up!)))
  (is (= 42 (:count (get-counter *read-db-ds* "c-good")))))
```

**Why this matters:** Without poison event handling, a single bad event permanently blocks a projector. Every subsequent catch-up fails on the same event. The projector falls further and further behind. This test proves the skip mechanism works and the projector recovers.

---

## Layer 5: End-to-End Tests (Full Pipeline)

### What

`async_integration_test.clj` starts the full Component system — two Postgres instances + RabbitMQ — and verifies that commands flow through the entire async pipeline: command → event store → outbox → poller → RabbitMQ → consumer → projection → read database.

### Why

Integration tests verify individual pieces. End-to-end tests verify the wiring between all pieces. A bug in Component dependency ordering, a wrong exchange name, a misconfigured queue binding — these only manifest when the full system is running.

### How

```clojure
(defn- with-system [f]
  (let [system (component/start (bank-components/dev-full-system))]
    (try
      (binding [*system* system]
        (f))
      (finally
        (component/stop system)))))
```

The test sends commands and **polls the read database** until the expected balance appears:

```clojure
(defn- wait-for-balance [account-id expected-balance]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (let [result (account-projection/get-balance (read-db-ds) account-id)]
        (if (and result (= expected-balance (:balance result)))
          result
          (if (> (System/currentTimeMillis) deadline)
            result
            (do (Thread/sleep 50) (recur))))))))
```

**Why polling with a deadline?** The async pipeline has non-deterministic latency (outbox poll interval + RabbitMQ delivery + projection processing). A fixed `Thread/sleep` would either be too long (slow tests) or too short (flaky tests). Polling with a 10-second deadline is both fast (returns as soon as data appears) and reliable (generous timeout for slow CI environments).

### What is tested

| Test | What it proves |
|---|---|
| `commands-flow-through-async-pipeline` | Single account: open + deposit appears in read DB |
| `multiple-accounts-project-independently` | Two accounts don't interfere with each other |
| `transfer-saga-projects-to-read-db` | Transfer saga: both account balances + transfer status appear in read DB |

**Why only 3 tests?** End-to-end tests are slow (3 Testcontainers) and fragile (timing-dependent). They verify wiring, not logic. Logic is tested thoroughly at lower layers. Three tests are enough to prove the pipeline works.

---

## Layer 6: Property-Based (Fuzz) Tests

### What

Generative tests that run random command sequences and verify invariants hold.

### Why

Hand-written tests cover the cases the developer thought of. Property-based tests cover cases the developer didn't think of. They're particularly valuable for event sourcing because:

1. **The state space is huge.** An account can have any sequence of deposits and withdrawals in any order. Manually writing tests for every sequence is impractical.
2. **The interesting bugs are in edge cases.** What happens when you withdraw exactly the balance? When you do 40 operations? When all operations are deposits?
3. **test.check shrinks failures.** When a property fails, the framework finds the minimal failing case, making debugging easy.

### How: Generating valid command sequences

The fuzz tests use a careful generator that produces **always-valid** command sequences:

```clojure
(defn- operation-seeds->commands [seeds]
  (loop [remaining seeds, balance 0, commands [open-account-cmd]]
    (if-let [{:keys [op amount]} (first remaining)]
      (case op
        :deposit  (recur (rest remaining) (+ balance amount)
                         (conj commands deposit-cmd))
        :withdraw (if (zero? balance)
                    (recur (rest remaining) balance commands)  ;; skip
                    (let [bounded (inc (mod amount balance))]  ;; never exceed balance
                      (recur (rest remaining) (- balance bounded)
                             (conj commands withdraw-cmd))))))))
```

**Why bound withdrawals to current balance?** The fuzz test isn't testing "does withdraw reject insufficient funds?" — the pure unit tests do that. The fuzz test is testing "does a valid sequence of operations produce a consistent result?" Generating invalid commands would just hit the rejection path over and over.

### Pure fuzz tests (`fuzz_unit_test.clj`)

**Upcasting fuzz (200 runs):**

```clojure
(deftest legacy-money-deposit-events-upcast-to-latest
  (tc/quick-check 200
    (prop/for-all [event legacy-money-deposit-event-gen]
      (let [upcasted (account/validate-event! event)]
        (and (= 3 (:event-version upcasted))
             (string? (get-in upcasted [:payload :origin]))
             (= "USD" (get-in upcasted [:payload :currency])))))))
```

**Why this matters:** Upcasters must handle v1 (no version), v1 (explicit), and v2 events. This test generates random events from all three schemas and verifies they all upcast to v3 correctly.

**Determinism + correctness (150 runs):**

```clojure
(deftest valid-command-scripts-are-deterministic-and-safe
  (tc/quick-check 150
    (prop/for-all [seeds operation-seeds-gen]
      (let [{:keys [commands expected-balance]} (operation-seeds->commands seeds)
            final-a (run-commands commands)
            final-b (run-commands commands)]
        (and (= final-a final-b)              ;; deterministic
             (= expected-balance (:balance final-a))  ;; correct
             (not (neg? (:balance final-a)))))))) ;; invariant
```

**What this proves:**
1. **Determinism** — same commands produce same state (no hidden randomness)
2. **Correctness** — balance matches expected (sum of deposits minus sum of withdrawals)
3. **Invariant** — balance never goes negative

### Integration fuzz tests (`fuzz_integration_test.clj`)

**Idempotent replay (50 runs):**

```clojure
(deftest fuzz-idempotent-replay-does-not-append-duplicates
  (tc/quick-check 50
    (prop/for-all [amount amount-gen]
      (let [stream-id (str "idem-stream-" (random-uuid))
            idem-key  (str "idem-key-" (random-uuid))]
        (and (= :ok         (store/append-events! *ds* stream-id 0 idem-key ...))
             (= :idempotent (store/append-events! *ds* stream-id 999 idem-key ...))
             (= 1 (count (store/load-stream *ds* stream-id))))))))
```

**Why `stream-id 999` on the replay?** Even with a wrong expected-version, idempotency short-circuits before the version check. This proves idempotency is independent of version — a stronger guarantee.

**Rebuild matches incremental (25 runs):**

```clojure
(deftest fuzz-projection-rebuild-matches-incremental
  (tc/quick-check 25
    (prop/for-all [seeds operation-seeds-gen]
      (reset-db!)
      (let [stream-id (str "fuzz-" (random-uuid))
            ;; ... run commands, process events ...
            incremental (get-balance *ds* stream-id)
            _           (system/rebuild! *ds*)
            rebuilt     (get-balance *ds* stream-id)]
        (and (= (:balance incremental) (:balance rebuilt))
             (= (:last-global-sequence incremental)
                (:last-global-sequence rebuilt)))))))
```

**Why this matters:** If incremental projection and full rebuild produce different results, one of them has a bug. This test runs 25 random command sequences and verifies they always agree — a strong consistency check.

---

## Layer 7: Performance Tests

### What

Benchmarks that measure latency and throughput for core operations, with regression detection against a checked-in baseline.

### Why

Event sourcing has inherent performance costs: loading entire event streams, holding advisory locks, running projection catch-ups. Performance regressions are silent — the code still works, just slower. Without benchmarks, you won't notice until production load exposes the problem.

### How

**`perf.clj`** runs deterministic workloads and records p50/p95/avg metrics:

| Metric | What it measures |
|---|---|
| `handle-latency` | Single command round-trip (load stream + decide + append) |
| `idempotent-latency` | Replayed command short-circuit time |
| `append-throughput` | Events per second across multiple streams |
| `projection-throughput` | Events per second for projection processing |
| `rebuild-throughput` | Events per second for full rebuild |
| `search-sync-latency` | BM25 search against event store DB |
| `search-async-latency` | BM25 search against separate read DB |

**`perf_check.clj`** compares results against `perf/baseline.edn`:

```clojure
(def metrics-to-check
  [{:name "handle p50 latency"  :path [:metrics :handle-latency :p50-ms]  :direction :lower-is-better}
   {:name "append throughput"   :path [:metrics :append-throughput :events-per-sec] :direction :higher-is-better}
   ...])
```

Each metric has a direction (`:lower-is-better` for latency, `:higher-is-better` for throughput) and a threshold (default 30%). If a metric regresses beyond the threshold, the check fails.

**Environment fingerprinting** prevents comparing results across different machines:

```clojure
{:os-name "Mac OS X" :os-arch "aarch64" :java-version "21.0.2" ...}
```

### Why 30% threshold?

Benchmarks on shared CI have natural variance (10-20%). A 30% threshold catches genuine regressions (e.g. accidentally O(n²) projection) while avoiding false positives from CI noise.

---

## Test Infrastructure: Fixtures and Isolation

### The fixture hierarchy

```
Process start
  └── :once fixture (per namespace)
      ├── Start Testcontainer (Postgres/RabbitMQ)
      ├── Run migrations
      └── Bind *ds* (dynamic var)
          └── :each fixture (per test)
              ├── TRUNCATE ... RESTART IDENTITY
              └── Run test
                  └── (test uses *ds* from binding)
```

### Why dynamic vars?

Clojure's `use-fixtures` mechanism doesn't support passing arguments to tests. Dynamic vars (`^:dynamic *ds*`) are the idiomatic way to share test infrastructure:

```clojure
(def ^:dynamic *ds* nil)  ;; declared at namespace top

(defn with-system [f]
  (binding [*ds* ds]  ;; bound in :once fixture
    (f)))

(deftest my-test
  ;; *ds* is available because the fixture bound it
  (jdbc/execute! *ds* ["SELECT 1"]))
```

### Why TRUNCATE with RESTART IDENTITY?

`RESTART IDENTITY` resets the `BIGSERIAL` counter for `global_sequence`. Without it:

- Test A inserts 3 events → global_sequence = 1, 2, 3
- Test B (after Test A) inserts 2 events → global_sequence = 4, 5

Assertions like `(is (= 2 (checkpoint)))` would fail in Test B because the checkpoint would be 5, not 2. `RESTART IDENTITY` ensures every test sees global_sequence starting from 1.

### Why DELETE instead of TRUNCATE for search tests?

BM25 search tests use `DELETE FROM` instead of `TRUNCATE`:

```clojure
;; bank/search_test.clj
(defn- reset-db! []
  (jdbc/with-transaction [tx *ds*]
    (jdbc/execute-one! tx ["DELETE FROM account_balances"])
    ...))
```

**Why?** `TRUNCATE` invalidates the Tantivy index backing ParadeDB's BM25 search. `DELETE` removes rows but keeps the index valid. This is a real operational constraint that the tests must respect.

---

## Concurrency Testing

### The `run-concurrently` pattern

```clojure
(defn- run-concurrently [fns]
  (let [start   (promise)
        workers (mapv (fn [f]
                        (future
                          @start     ;; all threads block here
                          (f)))
                      fns)]
    (deliver start true)             ;; release all threads simultaneously
    (mapv deref workers)))
```

**Why a promise gate?** Without it, threads start at different times depending on JVM scheduling. The promise ensures all threads are blocked and ready before any of them start, maximising the chance of actual concurrency.

### What is tested concurrently

**Same stream, different commands → single winner:**

```clojure
(deftest concurrent-writes-same-stream-result-in-single-winner
  (let [results (run-concurrently [open-alice-fn open-alice-fn-2])]
    (is (= 1 (count (filter #{:ok} results))))
    (is (= 1 (count conflicts)))
    (is (= 1 (count (store/load-stream *ds* "race-stream-1"))))))
```

**Why this matters:** Advisory locks + version checks must ensure exactly one writer wins. If both succeed, the stream has conflicting events.

**Same command with same key → idempotent:**

```clojure
(deftest concurrent-identical-commands-share-idempotency-cleanly
  (let [results (run-concurrently [open-alice-fn open-alice-fn])]
    (is (= #{:ok :idempotent} (set results)))
    (is (= 1 (count (store/load-stream *ds* "race-stream-2"))))))
```

**Why this matters:** Two identical commands arriving simultaneously must produce exactly one event, not two. One gets `:ok`, the other gets `:idempotent`.

**Same key, different streams → collision detected:**

```clojure
(deftest concurrent-shared-key-across-streams-does-not-leak-db-errors
  (let [results (run-concurrently [open-alice-stream-a open-bob-stream-b])]
    (is (= 1 (count (filter #{:ok} results))))
    (is (= :idempotency/key-collision (:error/type (first errors))))))
```

**Why this matters:** Cross-stream key reuse is a caller bug, not a valid retry. The system must detect and report it cleanly (structured error type), not leak a database exception.

**Concurrent projection workers → serialised:**

```clojure
(deftest concurrent-projection-workers-are-serialized
  (let [results (sort (run-concurrently
                        [(fn [] (system/process-new-events! *ds*))
                         (fn [] (system/process-new-events! *ds*))]))]
    (is (= [0 2] results))))
```

**Why `[0 2]`?** One worker processes all 2 events and returns 2. The other acquires the advisory lock after the first finishes, reads the checkpoint (now at 2), finds no new events, and returns 0. The sorted result `[0 2]` proves serialisation. If both returned 2, they both processed the same events — a race condition.

---

## What the Tests Prove

Taking all layers together, the test suite proves:

| Property | How it's proven |
|---|---|
| Business rules are correct | Pure unit tests for every command/state combination |
| Events are never duplicated | Idempotency tests (unit + integration + fuzz) |
| Balances are always correct | Full lifecycle tests + projection rebuild = incremental fuzz |
| Concurrent writes don't corrupt | Advisory lock + version check integration tests |
| Projections never regress | Fail-fast tests (checkpoint doesn't advance on error) |
| Old events still work | Upcasting tests (pure + integration + fuzz) |
| Sagas don't lose money | Compensation tests + crash recovery tests for every state |
| Poison events don't block forever | Poison skip test with recovery |
| Outbox guarantees at-least-once | Transaction boundary tests (hook failure, publish failure) |
| Async pipeline works end-to-end | Full Component system tests with polling verification |
| Performance doesn't regress | Benchmark suite with baseline comparison |

---

## What the Tests Don't Cover

No test suite is complete. Known gaps:

| Gap | Why it's acceptable |
|---|---|
| Network partition between app and Postgres | Testcontainers are local; network failure testing requires chaos engineering tools |
| RabbitMQ message loss | Tested indirectly (catch-up timer handles missed messages), but no test explicitly kills RabbitMQ mid-operation |
| Long-running event streams (10K+ events) | Streams are naturally small (bank accounts); perf tests cover 1500 events |
| Multiple concurrent sagas on same accounts | Each saga step uses idempotency keys, so correctness follows from idempotency tests |
| JVM crash during transaction | Postgres guarantees rollback on connection loss; not testable from within the JVM |
| Clock skew affecting UUIDv7 ordering | UUIDv7 ordering is informational, not functional; the system uses `global_sequence` for ordering |
| Schema migration rollback under load | Migrations run at startup before traffic; testing under load isn't needed |

---

## Effectiveness Analysis

### Strengths

1. **Real database everywhere it matters.** Advisory locks, `FOR UPDATE SKIP LOCKED`, and `BIGSERIAL` gaps can't be mocked. Testing against real Postgres catches the bugs that matter most.

2. **Framework and domain tested independently.** The `es/projection_test.clj` uses a toy counter, not the bank domain. This proves the framework works with any domain — you can add a new aggregate confident that the projection engine is correct.

3. **Every error path is structured.** Tests check `:error/type` keywords, not string messages. This makes error handling reliable across the codebase (the saga can match on `:domain/insufficient-funds` because the test guarantees that keyword).

4. **Crash recovery is exhaustively tested.** The saga resume tests cover every non-terminal state. Combined with idempotency guarantees, this proves the system recovers correctly from any crash point.

5. **Property-based tests catch what humans miss.** The fuzz tests have found edge cases (zero-balance withdrawal sequences, specific upcasting combinations) that no developer would write by hand.

6. **Deterministic sequences via RESTART IDENTITY.** Tests don't depend on execution order or previous test state. Every test starts from `global_sequence = 1`.

### Weaknesses

1. **Testcontainer startup cost.** Each namespace starts a fresh container (~3-5 seconds). The full test suite takes ~60 seconds, mostly in container startup. This is acceptable for CI but can feel slow for local development.

2. **Concurrency tests are probabilistic.** The `run-concurrently` pattern increases the chance of actual concurrency but doesn't guarantee it. On a fast machine, one thread might finish before the other starts. In practice, the advisory lock ensures correctness regardless — the test just might not exercise the race condition.

3. **Async end-to-end tests use polling.** The `wait-for-balance` pattern has a 10-second timeout. If the pipeline is slow, the test passes but hides latency issues. If the pipeline is broken, the test waits 10 seconds before failing. This is inherent to testing async systems.

4. **No chaos testing.** The suite doesn't kill processes mid-operation, corrupt network packets, or simulate disk failures. These are valid failure modes in production that require specialised tools (Jepsen, Toxiproxy) to test.

### Test count and coverage

| Category | Files | Tests | Coverage |
|---|---|---|---|
| Pure unit | 4 | ~20 | Every command type, every error path |
| Framework unit | 7 | ~30 | All factories, retry logic, CLI |
| Integration (1 DB) | 8 | ~40 | Idempotency, concurrency, sagas, upcasting, fail-fast |
| Async (2+ DB) | 3 | ~15 | Two-DB projection, outbox, poison events |
| End-to-end | 1 | 3 | Full async pipeline |
| Property-based | 2 | 4 | ~400 generated test cases |
| Performance | 3 | ~5 | 7 metrics with regression detection |
| **Total** | **28** | **~117** | **~196 test functions, ~529 assertions** |

The numbers reflect a deliberate strategy: many simple unit tests at the bottom, fewer comprehensive integration tests in the middle, and a small number of expensive end-to-end tests at the top. Each layer trusts the layers below it and focuses on what only it can verify.
