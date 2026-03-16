(ns event-sourcing.store-test
  (:require [clojure.test :refer [deftest is]]
            [event-sourcing.store :as store]))

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
