(ns event-sourcing.functional-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [event-sourcing.account :as account]
            [event-sourcing.decider :as decider]
            [event-sourcing.projection :as projection]
            [event-sourcing.store :as store]
            [event-sourcing.test-support :as support]))

(use-fixtures :once support/with-system)
(use-fixtures :each support/with-clean-db)

(deftest account-lifecycle-end-to-end
  (is (= :ok
         (decider/handle! support/*ds* account/decider
                          {:command-type    :open-account
                           :stream-id       "acct-f1"
                           :idempotency-key "cmd-open-f1"
                           :data            {:owner "Alice"}})))
  (is (= :ok
         (decider/handle! support/*ds* account/decider
                          {:command-type    :deposit
                           :stream-id       "acct-f1"
                           :idempotency-key "cmd-deposit-100-f1"
                           :data            {:amount 100}})))
  (is (= :ok
         (decider/handle! support/*ds* account/decider
                          {:command-type    :deposit
                           :stream-id       "acct-f1"
                           :idempotency-key "cmd-deposit-50-f1"
                           :data            {:amount 50}})))
  (is (= :ok
         (decider/handle! support/*ds* account/decider
                          {:command-type    :withdraw
                           :stream-id       "acct-f1"
                           :idempotency-key "cmd-withdraw-30-f1"
                           :data            {:amount 30}})))

  (is (= 4 (projection/process-new-events! support/*ds*)))
  (is (= {:account-id "acct-f1" :balance 120 :last-global-sequence 4}
         (select-keys (projection/get-balance support/*ds* "acct-f1")
                      [:account-id :balance :last-global-sequence])))

  (is (= [1 3 3 1]
         (mapv :event-version (store/load-stream support/*ds* "acct-f1"))))

  (is (= 4 (projection/rebuild! support/*ds*)))
  (is (= {:account-id "acct-f1" :balance 120 :last-global-sequence 4}
         (select-keys (projection/get-balance support/*ds* "acct-f1")
                      [:account-id :balance :last-global-sequence]))))

(deftest functional-invalid-command-surface
  (let [e (try
            (decider/handle! support/*ds* account/decider
                             {:command-type    :deposit
                              :stream-id       "acct-f2"
                              :idempotency-key "cmd-invalid-f2"
                              :data            {:amount "100"}})
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))
        data (ex-data e)]
    (is (some? e))
    (is (= "Invalid command" (.getMessage e)))
    (is (= :domain/invalid-command (:error/type data)))
    (is (= :invalid-data (:reason data)))))
