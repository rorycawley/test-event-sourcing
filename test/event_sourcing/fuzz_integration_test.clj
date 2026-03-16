(ns event-sourcing.fuzz-integration-test
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.test.check :as tc]
            [clojure.test.check.properties :as prop]
            [event-sourcing.account :as account]
            [event-sourcing.decider :as decider]
            [event-sourcing.projection :as projection]
            [event-sourcing.store :as store]
            [event-sourcing.test-support :as support]
            [malli.generator :as mg]))

(use-fixtures :once support/with-system)
(use-fixtures :each support/with-clean-db)

(def ^:private operation-seeds-gen
  (mg/generator
   [:vector {:min 0 :max 20}
    [:map
     [:op [:enum :deposit :withdraw]]
     [:amount [:int {:min 1 :max 1000}]]]]))

(defn- operation-seeds->commands
  [stream-id seeds]
  (loop [remaining seeds
         balance 0
         idx 1
         commands [{:command-type    :open-account
                    :stream-id       stream-id
                    :idempotency-key (str stream-id "-cmd-0")
                    :data            {:owner "Fuzz"}}]]
    (if-let [{:keys [op amount]} (first remaining)]
      (case op
        :deposit
        (recur (rest remaining)
               (+ balance amount)
               (inc idx)
               (conj commands {:command-type    :deposit
                               :stream-id       stream-id
                               :idempotency-key (str stream-id "-cmd-" idx)
                               :data            {:amount amount}}))

        :withdraw
        (if (zero? balance)
          (recur (rest remaining) balance (inc idx) commands)
          (let [bounded (inc (mod amount balance))]
            (recur (rest remaining)
                   (- balance bounded)
                   (inc idx)
                   (conj commands {:command-type    :withdraw
                                   :stream-id       stream-id
                                   :idempotency-key (str stream-id "-cmd-" idx)
                                   :data            {:amount bounded}}))))

        (recur (rest remaining) balance (inc idx) commands))
      {:commands         commands
       :expected-balance balance})))

(deftest fuzz-idempotent-replay-does-not-append-duplicates
  (let [amount-gen (mg/generator [:int {:min 1 :max 10000}])
        result (tc/quick-check
                50
                (prop/for-all [amount amount-gen]
                              (let [stream-id (str "idem-stream-" (random-uuid))
                                    idem-key  (str "idem-key-" (random-uuid))
                                    command   {:command-type :seed
                                               :data         {:amount amount}}
                                    events    [{:event-type "seeded"
                                                :payload    {:amount amount}}]]
                                (and (= :ok
                                        (store/append-events! support/*ds* stream-id 0 idem-key command events))
                                     (= :idempotent
                                        (store/append-events! support/*ds* stream-id 999 idem-key command events))
                                     (= 1 (count (store/load-stream support/*ds* stream-id)))))))]
    (is (:pass? result) (pr-str result))))

(deftest fuzz-projection-rebuild-matches-incremental
  (let [result (tc/quick-check
                25
                (prop/for-all [seeds operation-seeds-gen]
                              (support/reset-db!)
                              (let [stream-id (str "fuzz-stream-" (random-uuid))
                                    {:keys [commands expected-balance]}
                                    (operation-seeds->commands stream-id seeds)
                                    results (mapv #(decider/handle! support/*ds* account/decider %)
                                                  commands)
                                    processed (projection/process-new-events! support/*ds*)
                                    incremental (projection/get-balance support/*ds* stream-id)
                                    _ (projection/rebuild! support/*ds*)
                                    rebuilt (projection/get-balance support/*ds* stream-id)]
                                (and (every? #{:ok} results)
                                     (= (count commands) processed)
                                     (= expected-balance (:balance incremental))
                                     (= (:balance incremental) (:balance rebuilt))
                                     (= (:last-global-sequence incremental)
                                        (:last-global-sequence rebuilt))
                                     (= 0 (projection/process-new-events! support/*ds*))))))]
    (is (:pass? result) (pr-str result))))
