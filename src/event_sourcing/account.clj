(ns event-sourcing.account
  "Bank-account domain — the account Decider (Chassaing).

   Commands (intent):  :open-account, :deposit, :withdraw
   Events (facts):     account-opened, money-deposited, money-withdrawn
   State (truth):      {:status :not-found|:open, :balance N, :owner S}

   The Decider is a plain map:

     :initial-state — the state before any events have occurred
     :decide        — Command → State → [Event]  (business rules)
     :evolve        — State → Event → State       (state transitions)

   Everything here is pure: no I/O, no database, no side effects.
   The same decide and evolve functions work in tests with hand-crafted
   data, in a REPL, or against PostgreSQL — the domain never changes
   when infrastructure changes."
  (:require [event-sourcing.decider-kit :as kit]
            [event-sourcing.schema :as schema]))

;; ═══════════════════════════════════════════════════
;; Command schemas (boundary validation)
;; ═══════════════════════════════════════════════════

(def ^:private open-account-data-schema
  [:map
   [:owner schema/non-empty-string]])

(def ^:private money-data-schema
  [:map
   [:amount pos-int?]])

(def ^:private command-data-specs
  {:open-account open-account-data-schema
   :deposit      money-data-schema
   :withdraw     money-data-schema})

;; ═══════════════════════════════════════════════════
;; Event schemas (versioned)
;; ═══════════════════════════════════════════════════

(def ^:private latest-event-version
  {"account-opened" 1
   "money-deposited" 3
   "money-withdrawn" 1})

(def ^:private event-schemas
  {["account-opened" 1]
   [:map
    [:event-type [:= "account-opened"]]
    [:event-version [:= 1]]
    [:payload open-account-data-schema]]

   ["money-deposited" 1]
   [:map
    [:event-type [:= "money-deposited"]]
    [:event-version [:= 1]]
    [:payload money-data-schema]]

   ["money-deposited" 2]
   [:map
    [:event-type [:= "money-deposited"]]
    [:event-version [:= 2]]
    [:payload
     [:map
      [:amount pos-int?]
      [:origin schema/non-empty-string]]]]

   ["money-deposited" 3]
   [:map
    [:event-type [:= "money-deposited"]]
    [:event-version [:= 3]]
    [:payload
     [:map
      [:amount pos-int?]
      [:origin schema/non-empty-string]
      [:currency [:= "USD"]]]]]

   ["money-withdrawn" 1]
   [:map
    [:event-type [:= "money-withdrawn"]]
    [:event-version [:= 1]]
    [:payload money-data-schema]]})

(def ^:private event-upcasters
  "Maps [event-type from-version] -> upcaster fn.
   Upcasters move one version at a time (vN -> vN+1)."
  {["money-deposited" 1]
   (fn [{:keys [payload] :as event}]
     (assoc event
            :event-version 2
            :payload (if (map? payload)
                       (assoc payload :origin "legacy")
                       payload)))
   ["money-deposited" 2]
   (fn [{:keys [payload] :as event}]
     (assoc event
            :event-version 3
            :payload (if (map? payload)
                       (assoc payload :currency "USD")
                       payload)))})

;; ═══════════════════════════════════════════════════
;; Kit-derived functions
;; ═══════════════════════════════════════════════════

(def ^:private validate-command! (kit/make-command-validator command-data-specs))

(def upcast-event
  "Normalises a possibly-legacy event to the latest known schema version.
   Missing :event-version is treated as version 1 for backward compatibility."
  (kit/make-event-upcaster latest-event-version event-upcasters))

(def validate-event!
  "Upcasts + validates one domain event map.
   Returns the validated/upcasted event or throws ex-info."
  (kit/make-event-validator event-schemas upcast-event))

(def ^:private mk-event (kit/make-event-factory latest-event-version validate-event!))

;; ═══════════════════════════════════════════════════
;; Evolve — State → Event → State
;; ═══════════════════════════════════════════════════
;;
;; Given the current state and what happened,
;; produce the next state. Pure fold step.

(defn evolve
  "Applies a single event to the current state.
   No infrastructure concerns — no stream-sequence,
   no timestamps. Just domain state transitions."
  [state event]
  (let [{:keys [event-type payload]} (validate-event! event)]
    (case event-type
      "account-opened"  (assoc state :status :open
                               :owner  (:owner payload))
      "money-deposited" (update state :balance + (:amount payload))
      "money-withdrawn" (update state :balance - (:amount payload))
      state)))

;; ═══════════════════════════════════════════════════
;; Decide — Command → State → Event list
;; ═══════════════════════════════════════════════════
;;
;; Given what has been requested and the current state,
;; decide what should happen. Returns events or throws.

(defn- decide-open [state {:keys [owner]}]
  (when (= :open (:status state))
    (throw (ex-info "Account already open"
                    {:error/type :domain/account-already-open
                     :owner      (:owner state)})))
  [(mk-event "account-opened" {:owner owner})])

(defn- decide-deposit [state {:keys [amount]}]
  (when-not (= :open (:status state))
    (throw (ex-info "Account not open"
                    {:error/type :domain/account-not-open})))
  [(mk-event "money-deposited" {:amount amount
                                :origin "command"
                                :currency "USD"})])

(defn- decide-withdraw [state {:keys [amount]}]
  (when-not (= :open (:status state))
    (throw (ex-info "Account not open"
                    {:error/type :domain/account-not-open})))
  (when (> amount (:balance state))
    (throw (ex-info "Insufficient funds"
                    {:error/type :domain/insufficient-funds
                     :balance    (:balance state)
                     :amount     amount})))
  [(mk-event "money-withdrawn" {:amount amount})])

(def ^:private decisions
  "Routes command types to decision functions.
   A plain map — the simplest dispatch mechanism."
  {:open-account decide-open
   :deposit      decide-deposit
   :withdraw     decide-withdraw})

(def decide
  "Command → State → Event list.
   Dispatches on :command-type, delegates to the
   appropriate decision function."
  (kit/make-decide validate-command! validate-event! decisions))

;; ═══════════════════════════════════════════════════
;; The Decider — a plain map
;; ═══════════════════════════════════════════════════

(def decider
  {:initial-state {:status :not-found, :balance 0}
   :decide        decide
   :evolve        evolve})
