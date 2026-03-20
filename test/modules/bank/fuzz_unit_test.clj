(ns modules.bank.fuzz-unit-test
  (:require [clojure.test :refer [deftest is]]
            [clojure.test.check :as tc]
            [clojure.test.check.generators :as gen]
            [clojure.test.check.properties :as prop]
            [modules.bank.domain.account :as account]
            [es.schema :as schema]
            [malli.generator :as mg]))

(def ^:private legacy-money-deposit-event-gen
  (gen/one-of
   [(mg/generator
     [:map
      [:event-type [:= "money-deposited"]]
      [:payload
       [:map
        [:amount [:int {:min 1 :max 1000000}]]]]])
    (mg/generator
     [:map
      [:event-type [:= "money-deposited"]]
      [:event-version [:= 1]]
      [:payload
       [:map
        [:amount [:int {:min 1 :max 1000000}]]]]])
    (mg/generator
     [:map
      [:event-type [:= "money-deposited"]]
      [:event-version [:= 2]]
      [:payload
       [:map
        [:amount [:int {:min 1 :max 1000000}]]
        [:origin schema/non-empty-string]]]])]))

(def ^:private operation-seeds-gen
  (mg/generator
   [:vector {:min 0 :max 40}
    [:map
     [:op [:enum :deposit :withdraw]]
     [:amount [:int {:min 1 :max 5000}]]]]))

(defn- operation-seeds->commands
  [seeds]
  (loop [remaining seeds
         balance 0
         commands [{:command-type :open-account
                    :data         {:owner "Fuzz"}}]]
    (if-let [{:keys [op amount]} (first remaining)]
      (case op
        :deposit
        (recur (rest remaining)
               (+ balance amount)
               (conj commands {:command-type :deposit
                               :data         {:amount amount}}))

        :withdraw
        (if (zero? balance)
          (recur (rest remaining) balance commands)
          (let [bounded (inc (mod amount balance))]
            (recur (rest remaining)
                   (- balance bounded)
                   (conj commands {:command-type :withdraw
                                   :data         {:amount bounded}}))))

        (recur (rest remaining) balance commands))
      {:commands         commands
       :expected-balance balance})))

(defn- run-commands
  [commands]
  (reduce (fn [state command]
            (let [events (account/decide command state)]
              (reduce account/evolve state events)))
          (:initial-state account/decider)
          commands))

(deftest legacy-money-deposit-events-upcast-to-latest
  (let [result (tc/quick-check
                200
                (prop/for-all [event legacy-money-deposit-event-gen]
                              (let [upcasted (account/validate-event! event)]
                                (and (= "money-deposited" (:event-type upcasted))
                                     (= 3 (:event-version upcasted))
                                     (pos-int? (get-in upcasted [:payload :amount]))
                                     (string? (get-in upcasted [:payload :origin]))
                                     (seq (get-in upcasted [:payload :origin]))
                                     (= "USD" (get-in upcasted [:payload :currency]))))))]
    (is (:pass? result) (pr-str result))))

(deftest valid-command-scripts-are-deterministic-and-safe
  (let [result (tc/quick-check
                150
                (prop/for-all [seeds operation-seeds-gen]
                              (let [{:keys [commands expected-balance]} (operation-seeds->commands seeds)
                                    final-a (run-commands commands)
                                    final-b (run-commands commands)]
                                (and (= final-a final-b)
                                     (= :open (:status final-a))
                                     (= expected-balance (:balance final-a))
                                     (not (neg? (:balance final-a)))))))]
    (is (:pass? result) (pr-str result))))
