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
    (is (= "Unknown event type for projection" (.getMessage e)))
    (is (= "counter-async" (:projection-name (ex-data e)))))
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

    ;; Now skip the poison event explicitly using the two-step API:
    ;; skip-poison-event! returns a function that takes event-store-ds
    (let [failed-checkpoint (checkpoint)
          skip-fn (#'async-proj/skip-poison-event!
                   *read-db-ds* "counter-async" failed-checkpoint
                   (fn [evt] (swap! poison-events conj evt)))]
      (is (true? (skip-fn *event-store-ds*))))
    (is (= 1 (count @poison-events))
        "on-poison callback should have been called")
    (is (= 1 (checkpoint))
        "Checkpoint should advance past the poison event")

    ;; Now catch-up should process the remaining valid events
    (is (= 2 (do-catch-up!)))
    (is (= 42 (:count (get-counter *read-db-ds* "c-good-after-poison"))))))

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
