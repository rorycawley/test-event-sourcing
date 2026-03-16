(ns event-sourcing.account-test
  (:require [clojure.test :refer [deftest is testing]]
            [event-sourcing.account :as account]))

(defn- capture-exception [f]
  (try
    (f)
    nil
    (catch clojure.lang.ExceptionInfo e
      e)))

(deftest invalid-amounts-return-domain-validation-errors
  (doseq [amount [nil "10"]]
    (testing (str "amount=" (pr-str amount))
      (let [e (capture-exception
               #(account/decide {:command-type :deposit
                                 :data         {:amount amount}}
                                {:status :open :balance 100}))
            data (ex-data e)]
        (is (some? e))
        (is (= "Invalid command" (.getMessage e)))
        (is (= :domain/invalid-command (:error/type data)))
        (is (= :invalid-data (:reason data)))))))

(deftest unknown-command-has-structured-error
  (let [e (capture-exception
           #(account/decide {:command-type :close-account
                             :data         {}}
                            {:status :open :balance 100}))]
    (is (some? e))
    (is (= "Unknown command" (.getMessage e)))
    (is (= :domain/unknown-command (:error/type (ex-data e))))))

(deftest invalid-command-shape-has-structured-error
  (let [e (capture-exception
           #(account/decide {:command-type :deposit}
                            {:status :open :balance 100}))]
    (is (some? e))
    (is (= "Invalid command" (.getMessage e)))
    (is (= :domain/invalid-command (:error/type (ex-data e))))
    (is (= :invalid-shape (:reason (ex-data e))))))

(deftest domain-rule-violations-are-typed
  (let [open-e (capture-exception
                #(account/decide {:command-type :open-account
                                  :data         {:owner "Alice"}}
                                 {:status :open :owner "Alice" :balance 0}))
        funds-e (capture-exception
                 #(account/decide {:command-type :withdraw
                                   :data         {:amount 200}}
                                  {:status :open :balance 100}))]
    (is (= :domain/account-already-open (:error/type (ex-data open-e))))
    (is (= :domain/insufficient-funds (:error/type (ex-data funds-e))))))

(deftest valid-deposit-decision-still-works
  (is (= [{:event-type    "money-deposited"
           :event-version 3
           :payload       {:amount 10
                           :origin "command"
                           :currency "USD"}}]
         (account/decide {:command-type :deposit
                          :data         {:amount 10}}
                         {:status :open :balance 100}))))

(deftest legacy-event-without-version-upcasts-to-v3
  (is (= {:event-type    "money-deposited"
          :event-version 3
          :payload       {:amount 25
                          :origin "legacy"
                          :currency "USD"}}
         (account/validate-event! {:event-type "money-deposited"
                                   :payload    {:amount 25}}))))

(deftest v1-money-deposited-upcasts-to-v3-through-chain
  (is (= {:event-type    "money-deposited"
          :event-version 3
          :payload       {:amount 40
                          :origin "legacy"
                          :currency "USD"}}
         (account/validate-event! {:event-type    "money-deposited"
                                   :event-version 1
                                   :payload       {:amount 40}}))))

(deftest v2-money-deposited-upcasts-to-v3
  (is (= {:event-type    "money-deposited"
          :event-version 3
          :payload       {:amount 50
                          :origin "legacy"
                          :currency "USD"}}
         (account/validate-event! {:event-type    "money-deposited"
                                   :event-version 2
                                   :payload       {:amount 50
                                                   :origin "legacy"}}))))

(deftest invalid-event-shape-has-structured-error
  (let [e (capture-exception
           #(account/validate-event! {:event-type    "money-deposited"
                                      :event-version 3
                                      :payload       {:amount "oops"
                                                      :origin "command"
                                                      :currency "USD"}}))]
    (is (some? e))
    (is (= "Invalid event" (.getMessage e)))
    (is (= :domain/invalid-event (:error/type (ex-data e))))
    (is (= :invalid-shape (:reason (ex-data e))))))

(deftest unsupported-future-event-version-has-structured-error
  (let [e (capture-exception
           #(account/validate-event! {:event-type    "money-deposited"
                                      :event-version 4
                                      :payload       {:amount 10
                                                      :origin "command"
                                                      :currency "USD"}}))]
    (is (some? e))
    (is (= "Invalid event" (.getMessage e)))
    (is (= :domain/invalid-event (:error/type (ex-data e))))
    (is (= :unsupported-future-version (:reason (ex-data e))))))
