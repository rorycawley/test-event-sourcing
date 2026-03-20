(ns es.store-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [es.store :as store]
            [modules.bank.test-support :as support]))

(use-fixtures :once support/with-system)
(use-fixtures :each support/with-clean-db)

(deftest append-events-without-idempotency-key
  (is (= :ok
         (store/append-events! support/*ds*
                               "stream-no-key"
                               0
                               nil
                               {:command-type :seed
                                :data         {:value 1}}
                               [{:event-type "seeded"
                                 :payload    {:value 1}}])))
  (is (= :ok
         (store/append-events! support/*ds*
                               "stream-no-key"
                               1
                               nil
                               {:command-type :seed
                                :data         {:value 2}}
                               [{:event-type "seeded"
                                 :payload    {:value 2}}])))
  (is (= 2 (count (store/load-stream support/*ds* "stream-no-key")))))

(deftest append-events-defaults-event-version-to-1
  (is (= :ok
         (store/append-events! support/*ds*
                               "stream-version-default"
                               0
                               nil
                               {:command-type :seed
                                :data         {:value 1}}
                               [{:event-type "seeded"
                                 :payload    {:value 1}}])))
  (is (= [1]
         (mapv :event-version
               (store/load-stream support/*ds* "stream-version-default")))))

(deftest append-events-replay-short-circuits-to-idempotent
  (is (= :ok
         (store/append-events! support/*ds*
                               "stream-replay"
                               0
                               "idem-replay-1"
                               {:command-type :seed
                                :data         {:value 1}}
                               [{:event-type "seeded"
                                 :payload    {:value 1}}])))
  ;; Even with a stale/wrong expected-version, replay should return :idempotent
  ;; because the command key has already been claimed.
  (is (= :idempotent
         (store/append-events! support/*ds*
                               "stream-replay"
                               99
                               "idem-replay-1"
                               {:command-type :seed
                                :data         {:value 1}}
                               [{:event-type "seeded"
                                 :payload    {:value 1}}])))
  (is (= 1 (count (store/load-stream support/*ds* "stream-replay")))))

(deftest append-events-collision-throws
  (is (= :ok
         (store/append-events! support/*ds*
                               "stream-collision"
                               0
                               "idem-collision-1"
                               {:command-type :seed
                                :data         {:value 1}}
                               [{:event-type "seeded"
                                 :payload    {:value 1}}])))
  (let [e (try
            (store/append-events! support/*ds*
                                  "stream-collision"
                                  1
                                  "idem-collision-1"
                                  {:command-type :seed
                                   :data         {:value 2}}
                                  [{:event-type "seeded"
                                    :payload    {:value 2}}])
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Idempotency key collision" (.getMessage e)))
    (is (= :idempotency/key-collision (:error/type (ex-data e))))
    (is (= "idem-collision-1" (:idempotency-key (ex-data e))))))

(deftest append-events-invalid-envelope-throws-and-writes-nothing
  (let [e (try
            (store/append-events! support/*ds*
                                  "stream-invalid-envelope"
                                  0
                                  "idem-invalid-envelope"
                                  {:command-type :seed
                                   :data         {:value 1}}
                                  [{:event-type "seeded"
                                    :payload    123}])
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Invalid event envelope" (.getMessage e)))
    (is (= :store/invalid-event-envelope (:error/type (ex-data e))))
    (is (empty? (store/load-stream support/*ds* "stream-invalid-envelope")))))

(deftest append-events-optimistic-conflict-has-typed-ex-data
  (is (= :ok
         (store/append-events! support/*ds*
                               "stream-conflict"
                               0
                               nil
                               {:command-type :seed
                                :data         {:value 1}}
                               [{:event-type "seeded"
                                 :payload    {:value 1}}])))
  (let [e (try
            (store/append-events! support/*ds*
                                  "stream-conflict"
                                  0
                                  nil
                                  {:command-type :seed
                                   :data         {:value 2}}
                                  [{:event-type "seeded"
                                    :payload    {:value 2}}])
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))
        data (ex-data e)]
    (is (some? e))
    (is (= "Optimistic concurrency conflict" (.getMessage e)))
    (is (= :concurrency/optimistic-conflict (:error/type data)))
    (is (true? (:error/retryable? data)))
    (is (= 0 (:expected-version data)))
    (is (= 1 (:actual-version data)))))

(deftest check-idempotency-cases
  (is (= :new
         (store/check-idempotency! support/*ds*
                                   "stream-check"
                                   nil
                                   {:command-type :seed
                                    :data         {:value 1}})))
  (is (= :new
         (store/check-idempotency! support/*ds*
                                   "stream-check"
                                   "idem-check-1"
                                   {:command-type :seed
                                    :data         {:value 1}})))
  (is (= :ok
         (store/append-events! support/*ds*
                               "stream-check"
                               0
                               "idem-check-1"
                               {:command-type :seed
                                :data         {:value 1}}
                               [{:event-type "seeded"
                                 :payload    {:value 1}}])))
  (is (= :idempotent
         (store/check-idempotency! support/*ds*
                                   "stream-check"
                                   "idem-check-1"
                                   {:command-type :seed
                                    :data         {:value 1}})))
  (let [e (try
            (store/check-idempotency! support/*ds*
                                      "stream-check"
                                      "idem-check-1"
                                      {:command-type :seed
                                       :data         {:value 99}})
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Idempotency key collision" (.getMessage e)))
    (is (= :idempotency/key-collision (:error/type (ex-data e))))))
