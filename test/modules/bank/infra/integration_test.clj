(ns modules.bank.infra.integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [modules.bank.domain.account :as account]
            [modules.bank.infra.account-projection :as account-projection]
            [modules.bank.infra.system :as system]
            [es.decider :as decider]
            [es.migrations :as migrations]
            [es.store :as store]
            [modules.bank.test-support :as support]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(use-fixtures :once support/with-system)
(use-fixtures :each support/with-clean-db)

(defn- run-concurrently
  [fns]
  (let [start   (promise)
        workers (mapv (fn [f]
                        (future
                          @start
                          (f)))
                      fns)]
    (deliver start true)
    (mapv deref workers)))

(deftest append-events-requires-explicit-command-metadata
  (let [append-events (resolve 'es.store/append-events!)]
    (is (thrown-with-msg? clojure.lang.ArityException
                          #"Wrong number of args"
                          (apply append-events
                                 [support/*ds*
                                  "legacy-arity"
                                  0
                                  "legacy-key"
                                  []]))))
  (let [e (try
            (store/append-events! support/*ds*
                                  "missing-command"
                                  0
                                  "k-1"
                                  nil
                                  [{:event-type "noop"
                                    :payload    {}}])
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "append-events! requires command metadata map" (.getMessage e)))))

(deftest schema-migrations-are-applied-and-migrate-is-idempotent
  (let [ids-before (mapv :id
                         (jdbc/execute! support/*ds*
                                        ["SELECT id FROM schema_migrations ORDER BY id"]
                                        {:builder-fn rs/as-unqualified-kebab-maps}))]
    (is (every? (set ids-before) [1]))
    (is (nil? (migrations/migrate! support/*ds*)))
    (let [ids-after (mapv :id
                          (jdbc/execute! support/*ds*
                                         ["SELECT id FROM schema_migrations ORDER BY id"]
                                         {:builder-fn rs/as-unqualified-kebab-maps}))]
      (is (= ids-before ids-after)))))

(deftest replayed-command-returns-idempotent
  (is (= :ok
         (decider/handle! support/*ds* account/decider
                          {:command-type    :open-account
                           :stream-id       "acct-1"
                           :idempotency-key "cmd-open-1"
                           :data            {:owner "Alice"}})))

  (is (= :idempotent
         (decider/handle! support/*ds* account/decider
                          {:command-type    :open-account
                           :stream-id       "acct-1"
                           :idempotency-key "cmd-open-1"
                           :data            {:owner "Alice"}}))))

(deftest idempotency-key-collision-throws
  (is (= :ok
         (decider/handle! support/*ds* account/decider
                          {:command-type    :open-account
                           :stream-id       "acct-1"
                           :idempotency-key "shared-key"
                           :data            {:owner "Alice"}})))
  (let [e (try
            (decider/handle! support/*ds* account/decider
                             {:command-type    :open-account
                              :stream-id       "acct-2"
                              :idempotency-key "shared-key"
                              :data            {:owner "Bob"}})
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Idempotency key collision" (.getMessage e)))
    (is (= :idempotency/key-collision (:error/type (ex-data e))))
    (is (= "shared-key" (:idempotency-key (ex-data e))))))

(deftest idempotency-key-collision-same-stream-different-command-throws
  (is (= :ok
         (decider/handle! support/*ds* account/decider
                          {:command-type    :open-account
                           :stream-id       "acct-3"
                           :idempotency-key "reused-key"
                           :data            {:owner "Alice"}})))
  (let [e (try
            (decider/handle! support/*ds* account/decider
                             {:command-type    :deposit
                              :stream-id       "acct-3"
                              :idempotency-key "reused-key"
                              :data            {:amount 10}})
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Idempotency key collision" (.getMessage e)))
    (is (= :idempotency/key-collision (:error/type (ex-data e))))))

(deftest projection-processes-events-and-balance
  (is (= :ok
         (decider/handle! support/*ds* account/decider
                          {:command-type    :open-account
                           :stream-id       "acct-42"
                           :idempotency-key "cmd-open-42"
                           :data            {:owner "Alice"}})))
  (is (= :ok
         (decider/handle! support/*ds* account/decider
                          {:command-type    :deposit
                           :stream-id       "acct-42"
                           :idempotency-key "cmd-deposit-100"
                           :data            {:amount 100}})))
  (is (= :ok
         (decider/handle! support/*ds* account/decider
                          {:command-type    :withdraw
                           :stream-id       "acct-42"
                           :idempotency-key "cmd-withdraw-30"
                           :data            {:amount 30}})))

  (is (= 3 (system/process-new-events! support/*ds*)))
  (is (= 0 (system/process-new-events! support/*ds*)))

  (let [balance-row (account-projection/get-balance support/*ds* "acct-42")]
    (is (= 70 (:balance balance-row)))
    (is (= 3 (:last-global-sequence balance-row)))))

(deftest projection-upcasts-legacy-v1-deposit-events
  ;; Simulate historical persisted data before money-deposited v2.
  (is (= :ok
         (store/append-events! support/*ds*
                               "legacy-upcast-1"
                               0
                               "cmd-legacy-upcast-1"
                               {:command-type :seed
                                :data         {:case :legacy-upcast}}
                               [{:event-type    "account-opened"
                                 :event-version 1
                                 :payload       {:owner "Alice"}}
                                {:event-type    "money-deposited"
                                 :event-version 1
                                 :payload       {:amount 10}}])))
  (is (= 2 (system/process-new-events! support/*ds*)))
  (let [balance-row (account-projection/get-balance support/*ds* "legacy-upcast-1")]
    (is (= 10 (:balance balance-row)))
    (is (= (support/checkpoint) (:last-global-sequence balance-row)))))

(deftest projection-upcasts-v2-deposit-events-to-v3
  ;; Simulate data already on v2 that must hop to v3.
  (is (= :ok
         (store/append-events! support/*ds*
                               "legacy-upcast-2"
                               0
                               "cmd-legacy-upcast-2"
                               {:command-type :seed
                                :data         {:case :legacy-upcast-v2}}
                               [{:event-type    "account-opened"
                                 :event-version 1
                                 :payload       {:owner "Alice"}}
                                {:event-type    "money-deposited"
                                 :event-version 2
                                 :payload       {:amount 7
                                                 :origin "legacy"}}])))
  (is (= 2 (system/process-new-events! support/*ds*)))
  (let [balance-row (account-projection/get-balance support/*ds* "legacy-upcast-2")]
    (is (= 7 (:balance balance-row)))
    (is (= (support/checkpoint) (:last-global-sequence balance-row)))))

(deftest projection-fail-fast-does-not-advance-checkpoint
  ;; Bypass the domain to intentionally produce an invalid projection case:
  ;; a money event with no account-opened event for that stream.
  (is (= :ok
         (store/append-events! support/*ds*
                               "broken-1"
                               0
                               "cmd-broken-deposit"
                               {:command-type :deposit
                                :data         {:amount 15}}
                               [{:event-type "money-deposited"
                                 :payload    {:amount 15}}])))

  (let [e (try
            (system/process-new-events! support/*ds*)
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Projection invariant violation: expected single-row update"
           (.getMessage e))))

  (is (= 0 (support/checkpoint)))
  (is (nil? (account-projection/get-balance support/*ds* "broken-1"))))

(deftest projection-invalid-event-shape-fails-and-keeps-checkpoint
  ;; Open is valid, deposit payload is structurally invalid for the domain.
  ;; Projection must fail fast and rollback all intermediate writes.
  (is (= :ok
         (store/append-events! support/*ds*
                               "broken-2"
                               0
                               "cmd-broken-invalid-shape"
                               {:command-type :seed
                                :data         {:amount "oops"}}
                               [{:event-type "account-opened"
                                 :payload    {:owner "Alice"}}
                                {:event-type "money-deposited"
                                 :payload    {:amount "oops"}}])))

  (let [e (try
            (system/process-new-events! support/*ds*)
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Invalid event" (.getMessage e)))
    (is (= :domain/invalid-event (:error/type (ex-data e)))))

  (is (= 0 (support/checkpoint)))
  (is (nil? (account-projection/get-balance support/*ds* "broken-2"))))

(deftest projection-unknown-event-fails-and-keeps-checkpoint
  (is (= :ok
         (store/append-events! support/*ds*
                               "weird-1"
                               0
                               "cmd-weird-event"
                               {:command-type :open-account
                                :data         {:owner "Alice"}}
                               [{:event-type "unknown-event"
                                 :payload    {}}])))
  (let [e (try
            (system/process-new-events! support/*ds*)
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Unknown event type for projection" (.getMessage e))))
  (is (= 0 (support/checkpoint))))

(deftest concurrent-writes-same-stream-result-in-single-winner
  (let [results
        (run-concurrently
         [(fn []
            (try
              (decider/handle! support/*ds* account/decider
                               {:command-type    :open-account
                                :stream-id       "race-stream-1"
                                :idempotency-key "race-open-1"
                                :data            {:owner "Alice"}})
              (catch clojure.lang.ExceptionInfo e
                (assoc (ex-data e) :message (.getMessage e)))))
          (fn []
            (try
              (decider/handle! support/*ds* account/decider
                               {:command-type    :open-account
                                :stream-id       "race-stream-1"
                                :idempotency-key "race-open-2"
                                :data            {:owner "Alice"}})
              (catch clojure.lang.ExceptionInfo e
                (assoc (ex-data e) :message (.getMessage e)))))])
        oks (count (filter #{:ok} results))
        conflicts (filter #(= :concurrency/optimistic-conflict (:error/type %))
                          results)]
    (is (= 1 oks))
    (is (= 1 (count conflicts)))
    (is (= 1 (count (store/load-stream support/*ds* "race-stream-1"))))))

(deftest concurrent-identical-commands-share-idempotency-cleanly
  (let [command {:command-type    :open-account
                 :stream-id       "race-stream-2"
                 :idempotency-key "race-same-command-key"
                 :data            {:owner "Alice"}}
        results (run-concurrently
                 [(fn [] (decider/handle! support/*ds* account/decider command))
                  (fn [] (decider/handle! support/*ds* account/decider command))])]
    (is (= #{:ok :idempotent} (set results)))
    (is (= 1 (count (store/load-stream support/*ds* "race-stream-2"))))))

(deftest concurrent-shared-key-across-streams-does-not-leak-db-errors
  (let [results
        (run-concurrently
         [(fn []
            (try
              (decider/handle! support/*ds* account/decider
                               {:command-type    :open-account
                                :stream-id       "race-stream-3a"
                                :idempotency-key "race-global-key"
                                :data            {:owner "Alice"}})
              (catch clojure.lang.ExceptionInfo e
                {:message (.getMessage e)
                 :data    (ex-data e)})))
          (fn []
            (try
              (decider/handle! support/*ds* account/decider
                               {:command-type    :open-account
                                :stream-id       "race-stream-3b"
                                :idempotency-key "race-global-key"
                                :data            {:owner "Bob"}})
              (catch clojure.lang.ExceptionInfo e
                {:message (.getMessage e)
                 :data    (ex-data e)})))])
        oks (count (filter #{:ok} results))
        errors (filter map? results)]
    (is (= 1 oks))
    (is (= 1 (count errors)))
    (is (= "Idempotency key collision" (:message (first errors))))
    (is (= :idempotency/key-collision
           (get-in (first errors) [:data :error/type])))))

(deftest concurrent-projection-workers-are-serialized
  (doseq [cmd [{:command-type    :open-account
                :stream-id       "proj-race-1"
                :idempotency-key "proj-open-1"
                :data            {:owner "Alice"}}
               {:command-type    :deposit
                :stream-id       "proj-race-1"
                :idempotency-key "proj-deposit-1"
                :data            {:amount 5}}]]
    (is (= :ok (decider/handle! support/*ds* account/decider cmd))))
  (let [results (sort (run-concurrently
                       [(fn [] (system/process-new-events! support/*ds*))
                        (fn [] (system/process-new-events! support/*ds*))]))]
    (is (= [0 2] results))
    (is (= 2 (support/checkpoint)))
    (is (= 5 (:balance (account-projection/get-balance support/*ds* "proj-race-1"))))))
