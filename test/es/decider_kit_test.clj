(ns es.decider-kit-test
  "Unit tests for es.decider-kit — the data-driven Decider factories.

   Tests use minimal toy schemas (not bank domain) to prove the
   framework handles arbitrary domains correctly."
  (:require [clojure.test :refer [deftest is testing]]
            [es.decider-kit :as kit]))

;; ═══════════════════════════════════════════════════
;; Test fixtures — minimal toy domain
;; ═══════════════════════════════════════════════════

(def ^:private toy-command-specs
  {:create [:map [:name string?]]
   :bump   [:map [:delta int?]]})

(def ^:private toy-latest-versions
  {"created" 2
   "bumped"  1})

(def ^:private toy-upcasters
  {["created" 1]
   (fn [event]
     (assoc event
            :event-version 2
            :payload (assoc (:payload event) :source "legacy")))})

(def ^:private toy-event-schemas
  {["created" 1] [:map [:name string?]]
   ["created" 2] [:map [:name string?] [:source string?]]
   ["bumped" 1]  [:map [:delta int?]]})

;; ═══════════════════════════════════════════════════
;; make-command-validator
;; ═══════════════════════════════════════════════════

(deftest command-validator-accepts-valid-command
  (let [validate! (kit/make-command-validator toy-command-specs)]
    (is (nil? (validate! {:command-type :create :data {:name "Alice"}})))))

(deftest command-validator-rejects-missing-command-type
  (let [validate! (kit/make-command-validator toy-command-specs)
        e (try (validate! {:data {:name "Alice"}}) nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= "Invalid command" (.getMessage e)))
    (is (= :domain/invalid-command (:error/type (ex-data e))))
    (is (= :invalid-shape (:reason (ex-data e))))))

(deftest command-validator-rejects-unknown-command-type
  (let [validate! (kit/make-command-validator toy-command-specs)
        e (try (validate! {:command-type :destroy :data {}}) nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= "Unknown command" (.getMessage e)))
    (is (= :domain/unknown-command (:error/type (ex-data e))))
    (is (= :destroy (:command-type (ex-data e))))))

(deftest command-validator-rejects-invalid-data-payload
  (let [validate! (kit/make-command-validator toy-command-specs)
        e (try (validate! {:command-type :create :data {:name 123}}) nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= "Invalid command" (.getMessage e)))
    (is (= :invalid-data (:reason (ex-data e))))))

(deftest command-validator-rejects-non-map-data
  (let [validate! (kit/make-command-validator toy-command-specs)
        e (try (validate! {:command-type :create :data "not a map"}) nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :invalid-shape (:reason (ex-data e))))))

;; ═══════════════════════════════════════════════════
;; make-event-upcaster
;; ═══════════════════════════════════════════════════

(deftest upcaster-returns-latest-version-unchanged
  (let [upcast (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        event  {:event-type "created" :event-version 2
                :payload {:name "Alice" :source "api"}}]
    (is (= event (upcast event)))))

(deftest upcaster-upgrades-v1-to-v2
  (let [upcast (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        result (upcast {:event-type "created" :event-version 1
                        :payload {:name "Alice"}})]
    (is (= 2 (:event-version result)))
    (is (= "legacy" (get-in result [:payload :source])))))

(deftest upcaster-defaults-missing-version-to-1
  (let [upcast (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        result (upcast {:event-type "created"
                        :payload {:name "Alice"}})]
    (is (= 2 (:event-version result)))
    (is (= "legacy" (get-in result [:payload :source])))))

(deftest upcaster-rejects-unknown-event-type
  (let [upcast (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        e (try (upcast {:event-type "deleted" :event-version 1 :payload {}})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :domain/invalid-event (:error/type (ex-data e))))
    (is (= :unknown-event-type (:reason (ex-data e))))))

(deftest upcaster-rejects-future-version
  (let [upcast (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        e (try (upcast {:event-type "created" :event-version 99 :payload {}})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :unsupported-future-version (:reason (ex-data e))))))

(deftest upcaster-rejects-invalid-version-number
  (let [upcast (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        e (try (upcast {:event-type "created" :event-version 0 :payload {}})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :invalid-version (:reason (ex-data e))))))

(deftest upcaster-rejects-negative-version
  (let [upcast (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        e (try (upcast {:event-type "created" :event-version -1 :payload {}})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :invalid-version (:reason (ex-data e))))))

(deftest upcaster-rejects-missing-upcaster-in-chain
  (let [;; Version 3 is latest but no upcaster from 2->3
        upcast (kit/make-event-upcaster {"widget" 3}
                                        {["widget" 1] (fn [e] (assoc e :event-version 2))})
        e (try (upcast {:event-type "widget" :event-version 1 :payload {}})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :missing-upcaster (:reason (ex-data e))))))

(deftest upcaster-chains-multiple-steps
  (let [upcast (kit/make-event-upcaster
                {"widget" 3}
                {["widget" 1] (fn [e] (assoc e :event-version 2
                                             :payload (assoc (:payload e) :v2 true)))
                 ["widget" 2] (fn [e] (assoc e :event-version 3
                                             :payload (assoc (:payload e) :v3 true)))})
        result (upcast {:event-type "widget" :event-version 1
                        :payload {:original true}})]
    (is (= 3 (:event-version result)))
    (is (= {:original true :v2 true :v3 true} (:payload result)))))

;; ═══════════════════════════════════════════════════
;; make-event-validator
;; ═══════════════════════════════════════════════════

(deftest event-validator-accepts-valid-latest-event
  (let [upcast   (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        validate (kit/make-event-validator toy-event-schemas upcast)
        event    {:event-type "created" :event-version 2
                  :payload {:name "Alice" :source "api"}}]
    (is (= event (validate event)))))

(deftest event-validator-upcasts-then-validates
  (let [upcast   (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        validate (kit/make-event-validator toy-event-schemas upcast)
        result   (validate {:event-type "created" :event-version 1
                            :payload {:name "Alice"}})]
    (is (= 2 (:event-version result)))
    (is (= "legacy" (get-in result [:payload :source])))))

(deftest event-validator-rejects-invalid-payload-shape
  (let [upcast   (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        validate (kit/make-event-validator toy-event-schemas upcast)
        e (try (validate {:event-type "bumped" :event-version 1
                          :payload {:delta "not-an-int"}})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :domain/invalid-event (:error/type (ex-data e))))
    (is (= :invalid-shape (:reason (ex-data e))))))

(deftest event-validator-rejects-missing-schema
  (let [;; Only has schema for v1 but latest is v2, and upcast produces v2
        upcast   (kit/make-event-upcaster {"thing" 2}
                                          {["thing" 1] (fn [e] (assoc e :event-version 2))})
        ;; But we only declare schema for v1, not v2
        validate (kit/make-event-validator {["thing" 1] [:map]} upcast)
        e (try (validate {:event-type "thing" :event-version 2 :payload {}})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :missing-schema (:reason (ex-data e))))))

(deftest event-validator-auto-wraps-envelope
  (testing "payload-only schemas are auto-wrapped with event-type/version/payload"
    (let [upcast   (kit/make-event-upcaster {"simple" 1} {})
          validate (kit/make-event-validator {["simple" 1] [:map [:value int?]]} upcast)
          ;; Valid: payload matches the payload schema
          result   (validate {:event-type "simple" :event-version 1
                              :payload {:value 42}})]
      (is (= 42 (get-in result [:payload :value]))))
    ;; Invalid: wrong event-type in envelope
    (let [upcast   (kit/make-event-upcaster {"simple" 1} {})
          validate (kit/make-event-validator {["simple" 1] [:map [:value int?]]} upcast)]
      ;; This should fail at the upcaster level (unknown event type)
      (is (thrown? clojure.lang.ExceptionInfo
                   (validate {:event-type "wrong" :event-version 1
                              :payload {:value 42}}))))))

;; ═══════════════════════════════════════════════════
;; make-event-factory
;; ═══════════════════════════════════════════════════

(deftest event-factory-produces-valid-events
  (let [upcast   (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        validate (kit/make-event-validator toy-event-schemas upcast)
        mk-event (kit/make-event-factory toy-latest-versions validate)
        event    (mk-event "created" {:name "Bob" :source "test"})]
    (is (= "created" (:event-type event)))
    (is (= 2 (:event-version event)))
    (is (= {:name "Bob" :source "test"} (:payload event)))))

(deftest event-factory-uses-latest-version
  (let [upcast   (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        validate (kit/make-event-validator toy-event-schemas upcast)
        mk-event (kit/make-event-factory toy-latest-versions validate)
        event    (mk-event "bumped" {:delta 5})]
    (is (= 1 (:event-version event)))))

(deftest event-factory-rejects-invalid-payload
  (let [upcast   (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        validate (kit/make-event-validator toy-event-schemas upcast)
        mk-event (kit/make-event-factory toy-latest-versions validate)
        e (try (mk-event "bumped" {:delta "bad"}) nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :invalid-shape (:reason (ex-data e))))))

;; ═══════════════════════════════════════════════════
;; make-decide
;; ═══════════════════════════════════════════════════

(deftest decide-dispatches-and-validates
  (let [upcast    (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        validate! (kit/make-command-validator toy-command-specs)
        validate-evt (kit/make-event-validator toy-event-schemas upcast)
        mk-event  (kit/make-event-factory toy-latest-versions validate-evt)
        decisions {:create (fn [_state {:keys [name]}]
                             [(mk-event "created" {:name name :source "decide"})])
                   :bump   (fn [state {:keys [delta]}]
                             [(mk-event "bumped" {:delta (+ (:count state 0) delta)})])}
        decide    (kit/make-decide validate! validate-evt decisions)
        events    (decide {:command-type :create :data {:name "Alice"}}
                          {})]
    (is (= 1 (count events)))
    (is (= "created" (:event-type (first events))))
    (is (= "Alice" (get-in (first events) [:payload :name])))))

(deftest decide-rejects-invalid-command
  (let [upcast    (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        validate! (kit/make-command-validator toy-command-specs)
        validate-evt (kit/make-event-validator toy-event-schemas upcast)
        decide    (kit/make-decide validate! validate-evt {})
        e (try (decide {:command-type :create :data {:name 123}} {})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= "Invalid command" (.getMessage e)))))

(deftest decide-rejects-unknown-command
  (let [upcast    (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        validate! (kit/make-command-validator toy-command-specs)
        validate-evt (kit/make-event-validator toy-event-schemas upcast)
        decide    (kit/make-decide validate! validate-evt {:create (fn [_ _] [])})
        e (try (decide {:command-type :bump :data {:delta 1}} {})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    ;; Goes through command validation (bump is valid) but decision map
    ;; only has :create, so make-decide's own lookup throws
    (is (= :domain/unknown-command (:error/type (ex-data e))))))

(deftest decide-validates-emitted-events
  (let [upcast    (kit/make-event-upcaster toy-latest-versions toy-upcasters)
        validate! (kit/make-command-validator toy-command-specs)
        validate-evt (kit/make-event-validator toy-event-schemas upcast)
        ;; Decision emits event with wrong payload shape
        decisions {:create (fn [_ _]
                             [{:event-type "bumped" :event-version 1
                               :payload {:delta "not-an-int"}}])}
        decide    (kit/make-decide validate! validate-evt decisions)
        e (try (decide {:command-type :create :data {:name "Alice"}} {})
               nil
               (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :invalid-shape (:reason (ex-data e))))))
