(ns es.async-projection-test
  "Tests for es.async-projection — two-datasource projection engine.

   Uses two separate Postgres testcontainers: one for the event store,
   one for the read model. Verifies events flow from store to read DB."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [es.async-projection :as async-proj]
            [es.projection-kit :as kit]
            [es.store :as store]
            [es.infra :as infra]
            [es.migrations :as migrations]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; ═══════════════════════════════════════════════════
;; Test infrastructure — two Postgres containers
;; ═══════════════════════════════════════════════════

(def ^:dynamic *event-store-ds* nil)
(def ^:dynamic *read-db-ds* nil)

(defn- with-system [f]
  (let [event-pg (infra/start-postgres!)
        event-ds (infra/->datasource event-pg)
        read-pg  (infra/start-postgres!)
        read-ds  (infra/->datasource read-pg)]
    (try
      ;; Event store gets full migrations (events, idempotency_keys, etc.)
      (migrations/migrate! event-ds)
      ;; Read DB gets read-model-only migrations
      (migrations/migrate! read-ds :migration-dir "read-migrations")
      (binding [*event-store-ds* event-ds
                *read-db-ds*     read-ds]
        (f))
      (finally
        (infra/stop-postgres! read-pg)
        (infra/stop-postgres! event-pg)))))

(defn- reset-dbs! []
  (jdbc/with-transaction [tx *event-store-ds*]
    (jdbc/execute-one! tx
                       ["TRUNCATE TABLE events, idempotency_keys, event_outbox
                         RESTART IDENTITY"]))
  (jdbc/with-transaction [tx *read-db-ds*]
    (jdbc/execute-one! tx
                       ["TRUNCATE TABLE test_counters, projection_checkpoints
                         RESTART IDENTITY"])))

(defn- with-clean-dbs [f]
  ;; Create test_counters table in read DB if it doesn't exist
  (jdbc/execute-one! *read-db-ds*
                     ["CREATE TABLE IF NOT EXISTS test_counters (
                         counter_id TEXT PRIMARY KEY,
                         count BIGINT NOT NULL DEFAULT 0,
                         last_global_sequence BIGINT NOT NULL DEFAULT 0,
                         updated_at TIMESTAMPTZ DEFAULT NOW())"])
  (reset-dbs!)
  (f))

(use-fixtures :once with-system)
(use-fixtures :each with-clean-dbs)

;; ═══════════════════════════════════════════════════
;; Toy projection — same counter as projection_test
;; ═══════════════════════════════════════════════════

(def ^:private counter-handler-specs
  {"counter-created"
   (fn [tx {:keys [global-sequence stream-id]} _context]
     (jdbc/execute-one! tx
                        ["INSERT INTO test_counters (counter_id, count, last_global_sequence, updated_at)
                          VALUES (?, 0, ?, NOW())
                          ON CONFLICT (counter_id) DO UPDATE
                            SET last_global_sequence = GREATEST(
                                  test_counters.last_global_sequence,
                                  EXCLUDED.last_global_sequence)"
                         stream-id global-sequence]))

   "counter-incremented"
   (fn [tx {:keys [global-sequence stream-id] :as event}
        {:keys [ensure-single-row-updated!]}]
     (let [delta (get-in event [:payload :delta])
           result (jdbc/execute-one! tx
                                     ["UPDATE test_counters
                                         SET count = count + ?,
                                             last_global_sequence = ?,
                                             updated_at = NOW()
                                       WHERE counter_id = ?
                                         AND last_global_sequence < ?"
                                      delta global-sequence stream-id global-sequence])]
       (ensure-single-row-updated! result event)))})

(def ^:private counter-handler (kit/make-handler counter-handler-specs))

(def ^:private counter-config
  {:projection-name   "counter-async"
   :read-model-tables ["test_counters"]
   :handler           counter-handler})

(defn- get-counter [ds counter-id]
  (jdbc/execute-one! ds
                     ["SELECT counter_id, count, last_global_sequence
                       FROM test_counters WHERE counter_id = ?"
                      counter-id]
                     {:builder-fn rs/as-unqualified-kebab-maps}))

(defn- checkpoint []
  (or (-> (jdbc/execute-one! *read-db-ds*
                             ["SELECT last_global_sequence
                               FROM projection_checkpoints
                               WHERE projection_name = ?"
                              "counter-async"]
                             {:builder-fn rs/as-unqualified-kebab-maps})
          :last-global-sequence)
      0))

(defn- append-counter-events! [stream-id events]
  (let [current-version
        (or (-> (jdbc/execute-one! *event-store-ds*
                                   ["SELECT MAX(stream_sequence) AS v FROM events WHERE stream_id = ?"
                                    stream-id]
                                   {:builder-fn rs/as-unqualified-kebab-maps})
                :v)
            0)]
    (store/append-events! *event-store-ds* stream-id current-version nil
                          {:command-type :test :data {}}
                          events)))

;; ═══════════════════════════════════════════════════
;; Tests
;; ═══════════════════════════════════════════════════

(deftest process-new-events-across-databases
  (append-counter-events! "c-1"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 5}}
                           {:event-type "counter-incremented" :payload {:delta 3}}])
  (is (= 3 (async-proj/process-new-events! *event-store-ds* *read-db-ds* counter-config)))
  (is (= 3 (checkpoint)))
  (let [counter (get-counter *read-db-ds* "c-1")]
    (is (= 8 (:count counter)))
    (is (= 3 (:last-global-sequence counter)))))

(deftest process-new-events-is-idempotent
  (append-counter-events! "c-2"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 10}}])
  (is (= 2 (async-proj/process-new-events! *event-store-ds* *read-db-ds* counter-config)))
  (is (= 0 (async-proj/process-new-events! *event-store-ds* *read-db-ds* counter-config)))
  (is (= 10 (:count (get-counter *read-db-ds* "c-2")))))

(deftest process-new-events-incremental
  (append-counter-events! "c-3"
                          [{:event-type "counter-created" :payload {}}])
  (is (= 1 (async-proj/process-new-events! *event-store-ds* *read-db-ds* counter-config)))
  (append-counter-events! "c-3"
                          [{:event-type "counter-incremented" :payload {:delta 7}}])
  (is (= 1 (async-proj/process-new-events! *event-store-ds* *read-db-ds* counter-config)))
  (is (= 7 (:count (get-counter *read-db-ds* "c-3")))))

(deftest process-new-events-handles-multiple-streams
  (append-counter-events! "c-4a"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 1}}])
  (append-counter-events! "c-4b"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 2}}])
  (is (= 4 (async-proj/process-new-events! *event-store-ds* *read-db-ds* counter-config)))
  (is (= 1 (:count (get-counter *read-db-ds* "c-4a"))))
  (is (= 2 (:count (get-counter *read-db-ds* "c-4b")))))

(deftest rebuild-destroys-and-rebuilds
  (append-counter-events! "c-5"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 10}}])
  (async-proj/process-new-events! *event-store-ds* *read-db-ds* counter-config)
  ;; Corrupt the read model
  (jdbc/execute-one! *read-db-ds*
                     ["UPDATE test_counters SET count = 999 WHERE counter_id = 'c-5'"])
  (is (= 999 (:count (get-counter *read-db-ds* "c-5"))))
  ;; Rebuild should fix it
  (async-proj/rebuild! *event-store-ds* *read-db-ds* counter-config)
  (is (= 10 (:count (get-counter *read-db-ds* "c-5")))))

(deftest fail-fast-on-unknown-event-type
  (append-counter-events! "c-6"
                          [{:event-type "totally-unknown" :payload {}}])
  (let [e (try (async-proj/process-new-events! *event-store-ds* *read-db-ds* counter-config) nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (true? (:handler-error (ex-data e))))
    (is (= "counter-async" (:projection-name (ex-data e))))
    (is (= "Unknown event type for projection" (.getMessage (ex-cause e)))))
  (is (= 0 (checkpoint))))

(deftest process-new-events-respects-batch-size
  (append-counter-events! "c-batch"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 1}}
                           {:event-type "counter-incremented" :payload {:delta 2}}
                           {:event-type "counter-incremented" :payload {:delta 3}}])
  ;; Process with batch-size 2 — should only process first 2 events
  (is (= 2 (async-proj/process-new-events! *event-store-ds* *read-db-ds* counter-config
                                           :batch-size 2)))
  (is (= 1 (:count (get-counter *read-db-ds* "c-batch")))
      "After batch 1: counter-created (0) + increment(1) = 1")
  ;; Process remaining 2
  (is (= 2 (async-proj/process-new-events! *event-store-ds* *read-db-ds* counter-config
                                           :batch-size 2)))
  (is (= 6 (:count (get-counter *read-db-ds* "c-batch")))
      "After all 4 events: 0 + 1 + 2 + 3 = 6"))

(deftest poison-event-skipped-after-max-failures
  ;; Append a poison event (unknown type) followed by a valid event
  (append-counter-events! "c-poison"
                          [{:event-type "totally-unknown" :payload {}}])
  (append-counter-events! "c-good-after-poison"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 42}}])

  ;; Simulate the consumer's catch-up loop with max-consecutive-failures = 3
  (let [errors       (atom [])
        poison-events (atom [])
        do-catch-up! (fn []
                       (try
                         (async-proj/process-new-events! *event-store-ds* *read-db-ds*
                                                         counter-config)
                         (catch Exception e
                           (swap! errors conj e)
                           (throw e))))]

    ;; Fail 3 times on the poison event
    (dotimes [_ 3]
      (try (do-catch-up!) (catch Exception _)))
    (is (= 3 (count @errors))
        "Should fail 3 times on the poison event")
    (is (= 0 (checkpoint))
        "Checkpoint should not advance past the poison event")

    ;; Extract the failing global_sequence from the exception
    (let [failing-gs (-> @errors first ex-data :global-sequence)]
      (is (some? failing-gs) "Exception should carry :global-sequence")
      (is (:handler-error (ex-data (first @errors)))
          "Exception should carry :handler-error flag")

      ;; Skip the poison event using the new API
      (is (true? (#'async-proj/skip-poison-event!
                  *event-store-ds* *read-db-ds* counter-config
                  failing-gs
                  (fn [evt] (swap! poison-events conj evt))))))
    (is (= 1 (count @poison-events))
        "on-poison callback should have been called")
    (is (= 1 (checkpoint))
        "Checkpoint should advance past the poison event")

    ;; Now catch-up should process the remaining valid events
    (is (= 2 (do-catch-up!)))
    (is (= 42 (:count (get-counter *read-db-ds* "c-good-after-poison"))))))

(deftest poison-event-in-middle-of-batch-skips-only-poison
  ;; Good event, then poison, then good event — all in one batch.
  ;; Verifies that skip-poison-event! processes the good event before
  ;; the poison, skips only the poison, and leaves the later event for
  ;; normal catch-up.
  (append-counter-events! "c-mid-1"
                          [{:event-type "counter-created" :payload {}}])
  (append-counter-events! "c-mid-poison"
                          [{:event-type "totally-unknown" :payload {}}])
  (append-counter-events! "c-mid-2"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 7}}])

  ;; Attempt catch-up — should fail on the poison event (gs=2)
  (let [e (try (async-proj/process-new-events! *event-store-ds* *read-db-ds*
                                               counter-config)
               nil
               (catch Exception ex ex))
        failing-gs (-> e ex-data :global-sequence)]
    (is (some? e) "Should throw on poison event")
    (is (= 2 failing-gs) "Poison event should be the second event (gs=2)")
    (is (= 0 (checkpoint)) "Transaction should have rolled back")

    ;; skip-poison-event! should process good event before poison, then skip
    (let [skipped (atom [])]
      (is (true? (#'async-proj/skip-poison-event!
                  *event-store-ds* *read-db-ds* counter-config
                  failing-gs
                  (fn [evt] (swap! skipped conj evt)))))
      (is (= 1 (count @skipped)))
      (is (= failing-gs (:global-sequence (first @skipped)))))

    ;; Good event before poison should have been processed
    (is (some? (get-counter *read-db-ds* "c-mid-1"))
        "First good event should be projected")
    (is (= 0 (:count (get-counter *read-db-ds* "c-mid-1")))
        "Counter c-mid-1 should exist with count 0")

    ;; Checkpoint should be at the poison event (skipped past it)
    (is (= 2 (checkpoint)))

    ;; Process remaining events
    (is (= 2 (async-proj/process-new-events! *event-store-ds* *read-db-ds*
                                             counter-config)))
    (is (= 7 (:count (get-counter *read-db-ds* "c-mid-2"))))))

(deftest handler-failure-carries-event-identity
  ;; Verify that handler failures include :handler-error and :global-sequence
  ;; so the consumer can distinguish them from infrastructure errors.
  ;; Infrastructure errors (connection failures, etc.) will not carry
  ;; :handler-error, preventing them from triggering poison-event skipping.
  (append-counter-events! "c-handler-id"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "totally-unknown" :payload {}}])
  (let [e (try (async-proj/process-new-events! *event-store-ds* *read-db-ds*
                                               counter-config)
               nil
               (catch Exception ex ex))]
    (is (some? e))
    (is (true? (:handler-error (ex-data e)))
        "Handler failures must carry :handler-error flag")
    (is (= 2 (:global-sequence (ex-data e)))
        "Handler failures must carry :global-sequence of the failing event")
    (is (= "totally-unknown" (:event-type (ex-data e)))
        "Handler failures must carry :event-type of the failing event")))

(deftest event-store-and-read-db-are-independent
  ;; Verify the read DB doesn't have the events table
  (let [tables (jdbc/execute! *read-db-ds*
                              ["SELECT table_name FROM information_schema.tables
                                WHERE table_schema = 'public'"]
                              {:builder-fn rs/as-unqualified-kebab-maps})
        table-names (set (map :table-name tables))]
    (is (contains? table-names "projection_checkpoints"))
    (is (contains? table-names "account_balances"))
    (is (not (contains? table-names "events"))
        "Read DB should not have the events table")
    (is (not (contains? table-names "idempotency_keys"))
        "Read DB should not have the idempotency_keys table")))

;; ═══════════════════════════════════════════════════
;; Failure tracker (pure, no DB/RabbitMQ needed)
;; ═══════════════════════════════════════════════════

(deftest failure-tracker-ignores-infrastructure-errors
  (let [tracker (async-proj/make-failure-tracker 3)]
    (is (nil? ((:record-failure! tracker) {:some "infra-error"}))
        "Non-handler errors should not count toward poison threshold")
    (is (nil? ((:record-failure! tracker) {:some "infra-error"})))
    (is (nil? ((:record-failure! tracker) {:some "infra-error"})))
    (is (nil? ((:record-failure! tracker) {:some "infra-error"}))
        "Even after many infra errors, no poison signal")))

(deftest failure-tracker-counts-handler-errors
  (let [tracker (async-proj/make-failure-tracker 3)]
    (is (nil? ((:record-failure! tracker)
               {:handler-error true :global-sequence 42})))
    (is (nil? ((:record-failure! tracker)
               {:handler-error true :global-sequence 42})))
    (is (= {:action :skip-poison :global-sequence 42}
           ((:record-failure! tracker)
            {:handler-error true :global-sequence 42}))
        "Third consecutive failure on same event triggers skip")))

(deftest failure-tracker-resets-on-success
  (let [tracker (async-proj/make-failure-tracker 3)]
    ;; Two failures, then success
    ((:record-failure! tracker) {:handler-error true :global-sequence 42})
    ((:record-failure! tracker) {:handler-error true :global-sequence 42})
    ((:record-success! tracker))
    ;; Counter should be reset — need 3 more to trigger
    (is (nil? ((:record-failure! tracker)
               {:handler-error true :global-sequence 42})))
    (is (nil? ((:record-failure! tracker)
               {:handler-error true :global-sequence 42})))
    (is (= {:action :skip-poison :global-sequence 42}
           ((:record-failure! tracker)
            {:handler-error true :global-sequence 42})))))

(deftest failure-tracker-resets-on-different-event
  (let [tracker (async-proj/make-failure-tracker 3)]
    ;; Two failures on event 42
    ((:record-failure! tracker) {:handler-error true :global-sequence 42})
    ((:record-failure! tracker) {:handler-error true :global-sequence 42})
    ;; Different event resets the counter
    (is (nil? ((:record-failure! tracker)
               {:handler-error true :global-sequence 99})))
    (is (nil? ((:record-failure! tracker)
               {:handler-error true :global-sequence 99})))
    (is (= {:action :skip-poison :global-sequence 99}
           ((:record-failure! tracker)
            {:handler-error true :global-sequence 99}))
        "Third failure on event 99 triggers skip")))

(deftest failure-tracker-reset-clears-state
  (let [tracker (async-proj/make-failure-tracker 2)]
    ((:record-failure! tracker) {:handler-error true :global-sequence 42})
    ((:reset! tracker))
    ;; After explicit reset, need full count again
    (is (nil? ((:record-failure! tracker)
               {:handler-error true :global-sequence 42})))
    (is (= {:action :skip-poison :global-sequence 42}
           ((:record-failure! tracker)
            {:handler-error true :global-sequence 42})))))
