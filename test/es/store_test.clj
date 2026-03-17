(ns es.store-test
  (:require [clojure.test :refer [deftest is]]
            [es.store :as store]))

(deftest optimistic-concurrency-conflict-predicate
  (is (true?
       (store/optimistic-concurrency-conflict?
        (ex-info "conflict"
                 {:error/type       :concurrency/optimistic-conflict
                  :error/retryable? true}))))
  (is (false?
       (store/optimistic-concurrency-conflict?
        (ex-info "conflict"
                 {:error/type :concurrency/optimistic-conflict}))))
  (is (false?
       (store/optimistic-concurrency-conflict?
        (ex-info "different"
                 {:error/type       :domain/invalid-command
                  :error/retryable? true})))))

(deftest uuid-v7-shape
  (let [id (store/uuid-v7)]
    (is (= 7 (.version id)))
    (is (= 2 (.variant id)))))

(deftest json-roundtrip
  (let [original {:account "acct-1" :amount 42}
        pg       (store/->pgobject original)
        decoded  (store/<-pgobject pg)]
    (is (= original decoded))))

(deftest pgobject-nil-returns-nil
  (is (nil? (store/<-pgobject nil))))

(deftest timestamp-nil-returns-nil
  (is (nil? (store/<-timestamp nil))))

(deftest timestamp-converts-to-instant
  (let [ts (java.sql.Timestamp. 1000)]
    (is (instance? java.time.Instant (store/<-timestamp ts)))))

(deftest uuid-v7-monotonic-timestamp-bits
  (let [ids (repeatedly 10 #(do (Thread/sleep 1) (store/uuid-v7)))
        msbs (mapv #(.getMostSignificantBits %) ids)]
    (is (= msbs (sort msbs))
        "UUIDv7 MSBs should be monotonically increasing across distinct milliseconds")))
