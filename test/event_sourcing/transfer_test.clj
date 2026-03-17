(ns event-sourcing.transfer-test
  "Pure unit tests for the transfer Decider — no database."
  (:require [clojure.test :refer [deftest is]]
            [event-sourcing.transfer :as transfer]
            [event-sourcing.decider :as decider]))

(def initial {:status :not-found})

(deftest initiate-transfer-produces-event
  (let [events (transfer/decide
                {:command-type :initiate-transfer
                 :data         {:from-account "acct-a"
                                :to-account   "acct-b"
                                :amount       50}}
                initial)]
    (is (= 1 (count events)))
    (is (= "transfer-initiated" (:event-type (first events))))
    (is (= {:from-account "acct-a" :to-account "acct-b" :amount 50}
           (:payload (first events))))))

(deftest initiate-transfer-same-account-rejected
  (let [e (try
            (transfer/decide
             {:command-type :initiate-transfer
              :data         {:from-account "acct-a"
                             :to-account   "acct-a"
                             :amount       50}}
             initial)
            nil
            (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :domain/same-account-transfer (:error/type (ex-data e))))))

(deftest initiate-transfer-rejects-duplicate
  (let [state (transfer/evolve initial
                               {:event-type    "transfer-initiated"
                                :event-version 1
                                :payload       {:from-account "a"
                                                :to-account   "b"
                                                :amount       10}})
        e (try
            (transfer/decide
             {:command-type :initiate-transfer
              :data         {:from-account "a" :to-account "b" :amount 10}}
             state)
            nil
            (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :domain/transfer-already-exists (:error/type (ex-data e))))))

(deftest record-debit-requires-initiated-state
  (let [events (transfer/decide
                {:command-type :record-debit
                 :data         {:account-id "acct-a" :amount 50}}
                {:status :initiated :from-account "acct-a"
                 :to-account "acct-b" :amount 50})]
    (is (= "debit-recorded" (:event-type (first events))))))

(deftest record-debit-rejects-wrong-account
  (let [e (try
            (transfer/decide
             {:command-type :record-debit
              :data         {:account-id "wrong" :amount 50}}
             {:status :initiated :from-account "acct-a"
              :to-account "acct-b" :amount 50})
            nil
            (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :domain/account-mismatch (:error/type (ex-data e))))))

(deftest record-debit-rejects-wrong-amount
  (let [e (try
            (transfer/decide
             {:command-type :record-debit
              :data         {:account-id "acct-a" :amount 99}}
             {:status :initiated :from-account "acct-a"
              :to-account "acct-b" :amount 50})
            nil
            (catch clojure.lang.ExceptionInfo ex ex))]
    (is (= :domain/amount-mismatch (:error/type (ex-data e))))))

(deftest record-credit-requires-debited-state
  (let [events (transfer/decide
                {:command-type :record-credit
                 :data         {:account-id "acct-b" :amount 50}}
                {:status :debited :from-account "acct-a"
                 :to-account "acct-b" :amount 50})]
    (is (= "credit-recorded" (:event-type (first events))))))

(deftest complete-requires-credited-state
  (let [events (transfer/decide
                {:command-type :complete-transfer
                 :data         {}}
                {:status :credited :from-account "acct-a"
                 :to-account "acct-b" :amount 50})]
    (is (= "transfer-completed" (:event-type (first events))))))

(deftest fail-rejects-terminal-states
  (doseq [status [:completed :failed]]
    (let [e (try
              (transfer/decide
               {:command-type :fail-transfer
                :data         {:reason "test"}}
               {:status status :from-account "a"
                :to-account "b" :amount 10})
              nil
              (catch clojure.lang.ExceptionInfo ex ex))]
      (is (= :domain/transfer-already-terminal (:error/type (ex-data e)))
          (str "should reject " status)))))

(deftest evolve-full-lifecycle
  (let [state (reduce transfer/evolve
                      (:initial-state transfer/decider)
                      [{:event-type "transfer-initiated" :event-version 1
                        :payload {:from-account "a" :to-account "b" :amount 50}}
                       {:event-type "debit-recorded" :event-version 1
                        :payload {:account-id "a" :amount 50}}
                       {:event-type "credit-recorded" :event-version 1
                        :payload {:account-id "b" :amount 50}}
                       {:event-type "transfer-completed" :event-version 1
                        :payload {}}])]
    (is (= :completed (:status state)))
    (is (= "a" (:from-account state)))
    (is (= "b" (:to-account state)))
    (is (= 50 (:amount state)))))

(deftest evolve-failed-transfer
  (let [state (reduce transfer/evolve
                      (:initial-state transfer/decider)
                      [{:event-type "transfer-initiated" :event-version 1
                        :payload {:from-account "a" :to-account "b" :amount 50}}
                       {:event-type "transfer-failed" :event-version 1
                        :payload {:reason "insufficient-funds"}}])]
    (is (= :failed (:status state)))
    (is (= "insufficient-funds" (:failure-reason state)))))

(deftest evolve-state-via-decider-helper
  (let [state (decider/evolve-state
               transfer/decider
               [{:event-type "transfer-initiated" :event-version 1
                 :payload {:from-account "a" :to-account "b" :amount 25}}
                {:event-type "debit-recorded" :event-version 1
                 :payload {:account-id "a" :amount 25}}])]
    (is (= :debited (:status state)))
    (is (= 25 (:amount state)))))
