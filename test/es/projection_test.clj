(ns es.projection-test
  "Integration tests for es.projection — the projection machinery.

   Uses a trivial 'counter' read model (not the bank domain) to prove
   the framework handles arbitrary projections correctly:
   - Checkpoint advancement
   - Fail-fast rollback on error
   - Unknown event type handling
   - Full rebuild
   - Advisory lock serialisation
   - make-query integration"
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [es.projection :as projection]
            [es.projection-kit :as kit]
            [es.store :as store]
            [es.migrations :as migrations]
            [es.infra :as infra]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; ═══════════════════════════════════════════════════
;; Test infrastructure — independent of bank domain
;; ═══════════════════════════════════════════════════

(def ^:dynamic *ds* nil)

(defn- with-system [f]
  (let [pg (infra/start-postgres!)
        ds (infra/->datasource pg)]
    (try
      (migrations/migrate! ds)
      ;; Create a toy counter table for projection tests
      (jdbc/execute-one! ds
                         ["CREATE TABLE IF NOT EXISTS test_counters (
                             counter_id TEXT PRIMARY KEY,
                             count BIGINT NOT NULL DEFAULT 0,
                             last_global_sequence BIGINT NOT NULL DEFAULT 0,
                             updated_at TIMESTAMPTZ DEFAULT NOW())"])
      (binding [*ds* ds]
        (f))
      (finally
        (infra/stop-postgres! pg)))))

(defn- reset-db! []
  (jdbc/with-transaction [tx *ds*]
    (jdbc/execute-one! tx
                       ["TRUNCATE TABLE test_counters,
                                        projection_checkpoints,
                                        events,
                                        idempotency_keys
                         RESTART IDENTITY"])))

(defn- with-clean-db [f]
  (reset-db!)
  (f))

(use-fixtures :once with-system)
(use-fixtures :each with-clean-db)

;; ═══════════════════════════════════════════════════
;; Toy projection — a simple counter
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
  {:projection-name   "counter-test"
   :read-model-tables ["test_counters"]
   :handler           counter-handler})

(defn- get-counter [ds counter-id]
  (jdbc/execute-one! ds
                     ["SELECT counter_id, count, last_global_sequence
                       FROM test_counters WHERE counter_id = ?"
                      counter-id]
                     {:builder-fn rs/as-unqualified-kebab-maps}))

(defn- checkpoint []
  (or (-> (jdbc/execute-one! *ds*
                             ["SELECT last_global_sequence
                               FROM projection_checkpoints
                               WHERE projection_name = ?"
                              "counter-test"]
                             {:builder-fn rs/as-unqualified-kebab-maps})
          :last-global-sequence)
      0))

(defn- append-counter-events! [stream-id events]
  (let [current-version
        (or (-> (jdbc/execute-one! *ds*
                                   ["SELECT MAX(stream_sequence) AS v FROM events WHERE stream_id = ?"
                                    stream-id]
                                   {:builder-fn rs/as-unqualified-kebab-maps})
                :v)
            0)]
    (store/append-events! *ds* stream-id current-version nil
                          {:command-type :test :data {}}
                          events)))

;; ═══════════════════════════════════════════════════
;; Tests
;; ═══════════════════════════════════════════════════

(deftest process-new-events-applies-events-and-advances-checkpoint
  (append-counter-events! "c-1"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 5}}
                           {:event-type "counter-incremented" :payload {:delta 3}}])
  (is (= 3 (projection/process-new-events! *ds* counter-config)))
  (is (= 3 (checkpoint)))
  (let [counter (get-counter *ds* "c-1")]
    (is (= 8 (:count counter)))
    (is (= 3 (:last-global-sequence counter)))))

(deftest process-new-events-is-idempotent
  (append-counter-events! "c-2"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 10}}])
  (is (= 2 (projection/process-new-events! *ds* counter-config)))
  (is (= 0 (projection/process-new-events! *ds* counter-config)))
  (is (= 10 (:count (get-counter *ds* "c-2")))))

(deftest process-new-events-incremental-across-multiple-calls
  (append-counter-events! "c-3"
                          [{:event-type "counter-created" :payload {}}])
  (is (= 1 (projection/process-new-events! *ds* counter-config)))
  (append-counter-events! "c-3"
                          [{:event-type "counter-incremented" :payload {:delta 7}}])
  (is (= 1 (projection/process-new-events! *ds* counter-config)))
  (is (= 7 (:count (get-counter *ds* "c-3")))))

(deftest process-new-events-handles-multiple-streams
  (append-counter-events! "c-4a"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 1}}])
  (append-counter-events! "c-4b"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 2}}])
  (is (= 4 (projection/process-new-events! *ds* counter-config)))
  (is (= 1 (:count (get-counter *ds* "c-4a"))))
  (is (= 2 (:count (get-counter *ds* "c-4b")))))

(deftest fail-fast-on-invariant-violation-does-not-advance-checkpoint
  ;; Increment without create — no row to update
  (append-counter-events! "c-5"
                          [{:event-type "counter-incremented" :payload {:delta 1}}])
  (let [e (try (projection/process-new-events! *ds* counter-config) nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= "Projection invariant violation: expected single-row update"
           (.getMessage e))))
  (is (= 0 (checkpoint)))
  (is (nil? (get-counter *ds* "c-5"))))

(deftest fail-fast-on-unknown-event-type-does-not-advance-checkpoint
  (append-counter-events! "c-6"
                          [{:event-type "totally-unknown" :payload {}}])
  (let [e (try (projection/process-new-events! *ds* counter-config) nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= "Unknown event type for projection" (.getMessage e)))
    (is (= "counter-test" (:projection-name (ex-data e))))
    (is (= "totally-unknown" (:event-type (ex-data e)))))
  (is (= 0 (checkpoint))))

(deftest rebuild-destroys-and-rebuilds-from-scratch
  (append-counter-events! "c-7"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 10}}])
  ;; Process normally first
  (is (= 2 (projection/process-new-events! *ds* counter-config)))
  (is (= 10 (:count (get-counter *ds* "c-7"))))
  ;; Rebuild — should produce the same result
  (is (= 2 (projection/rebuild! *ds* counter-config)))
  (is (= 10 (:count (get-counter *ds* "c-7"))))
  (is (= 2 (checkpoint))))

(deftest rebuild-clears-stale-data
  (append-counter-events! "c-8"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 5}}])
  (projection/process-new-events! *ds* counter-config)
  ;; Manually corrupt the read model
  (jdbc/execute-one! *ds* ["UPDATE test_counters SET count = 999 WHERE counter_id = 'c-8'"])
  (is (= 999 (:count (get-counter *ds* "c-8"))))
  ;; Rebuild should fix it
  (projection/rebuild! *ds* counter-config)
  (is (= 5 (:count (get-counter *ds* "c-8")))))

(deftest concurrent-projection-workers-are-serialized
  (append-counter-events! "c-9"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 3}}])
  (let [start   (promise)
        workers (mapv (fn [_]
                        (future
                          @start
                          (projection/process-new-events! *ds* counter-config)))
                      (range 2))]
    (deliver start true)
    (let [results (sort (mapv deref workers))]
      (is (= [0 2] results))
      (is (= 2 (checkpoint)))
      (is (= 3 (:count (get-counter *ds* "c-9")))))))

(deftest make-query-integration
  (append-counter-events! "c-10"
                          [{:event-type "counter-created" :payload {}}
                           {:event-type "counter-incremented" :payload {:delta 42}}])
  (projection/process-new-events! *ds* counter-config)
  (let [query-fn (kit/make-query "test_counters" "counter_id")
        result   (query-fn *ds* "c-10")]
    (is (= "c-10" (:counter-id result)))
    (is (= 42 (:count result)))))

(deftest make-query-returns-nil-for-missing-row
  (let [query-fn (kit/make-query "test_counters" "counter_id")]
    (is (nil? (query-fn *ds* "nonexistent")))))
