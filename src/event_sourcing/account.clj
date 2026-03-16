(ns event-sourcing.account
  "Bank-account domain — the account Decider.

   A Decider is a plain map with three elements:

     :initial-state — the state before any events have occurred
     :decide        — Command → State → Event list (business rules)
     :evolve        — State → Event → State (state transitions)

   Everything here is pure: no I/O, no database, no side effects.
   Uses malli for command + event boundary validation, including
   versioned event schemas.

   Tellman: data > functions > macros.
  Chassaing: the Decider pattern."
  (:require [event-sourcing.schema :as schema]
            [malli.core :as m]))

;; ═══════════════════════════════════════════════════
;; Command schemas (boundary validation)
;; ═══════════════════════════════════════════════════

(def ^:private command-schema
  [:map
   [:command-type keyword?]
   [:data map?]])

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

(defn- invalid-command!
  [command reason explain]
  (throw (ex-info "Invalid command"
                  {:error/type :domain/invalid-command
                   :reason     reason
                   :command    command
                   :explain    explain})))

(defn- validate-command!
  [{:keys [command-type data] :as command}]
  (when-not (m/validate command-schema command)
    (invalid-command! command :invalid-shape (m/explain command-schema command)))
  (let [data-spec (or (get command-data-specs command-type)
                      (throw (ex-info "Unknown command"
                                      {:error/type   :domain/unknown-command
                                       :command-type command-type})))]
    (when-not (m/validate data-spec data)
      (invalid-command! command :invalid-data (m/explain data-spec data)))))

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
     ;; For historical v1 rows, stamp a deterministic origin so v2
     ;; payload invariants hold after upcast.
     (assoc event
            :event-version 2
            :payload (if (map? payload)
                       (assoc payload :origin "legacy")
                       payload)))
   ["money-deposited" 2]
   (fn [{:keys [payload] :as event}]
     ;; Default currency for v2 rows so v3 payload invariants hold.
     (assoc event
            :event-version 3
            :payload (if (map? payload)
                       (assoc payload :currency "USD")
                       payload)))})

(defn- invalid-event!
  [event reason explain]
  (throw (ex-info "Invalid event"
                  {:error/type :domain/invalid-event
                   :reason     reason
                   :event      event
                   :explain    explain})))

(defn upcast-event
  "Normalises a possibly-legacy event to the latest known schema version.
   Missing :event-version is treated as version 1 for backward compatibility."
  [event]
  (let [event (update event :event-version #(or % 1))
        event-type (:event-type event)
        version (:event-version event)
        latest-version (get latest-event-version event-type)]
    (when-not latest-version
      (invalid-event! event :unknown-event-type nil))
    (when-not (pos-int? version)
      (invalid-event! event :invalid-version nil))
    (when (> version latest-version)
      (invalid-event! event :unsupported-future-version nil))
    (loop [{:keys [event-type event-version] :as current} event]
      (if (= event-version latest-version)
        current
        (let [upcaster (get event-upcasters [event-type event-version])]
          (when-not upcaster
            (invalid-event! current :missing-upcaster nil))
          (recur (upcaster current)))))))

(defn validate-event!
  "Upcasts + validates one domain event map.
   Returns the validated/upcasted event or throws ex-info."
  [event]
  (let [event (upcast-event event)
        schema (get event-schemas [(:event-type event) (:event-version event)])]
    (when-not schema
      (invalid-event! event :missing-schema nil))
    (when-not (m/validate schema event)
      (invalid-event! event :invalid-shape (m/explain schema event)))
    event))

(defn- mk-event
  [event-type payload]
  (validate-event! {:event-type    event-type
                    :event-version (get latest-event-version event-type)
                    :payload       payload}))

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
;;
;; Each decision function receives (state, data) and
;; returns a list of events. The dispatch function
;; below routes commands to the right decision.

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

(defn decide
  "Command → State → Event list.
   Dispatches on :command-type, delegates to the
   appropriate decision function."
  [{:keys [command-type data] :as command} state]
  (validate-command! command)
  (let [decision (or (get decisions command-type)
                     (throw (ex-info "Unknown command"
                                     {:error/type   :domain/unknown-command
                                      :command-type command-type})))]
    (mapv validate-event! (decision state data))))

;; ═══════════════════════════════════════════════════
;; The Decider — a plain map
;; ═══════════════════════════════════════════════════
;;
;; This is the entire domain definition for a bank account.
;; No classes, no protocols, no macros. Just data.

(def decider
  {:initial-state {:status :not-found, :balance 0}
   :decide        decide
   :evolve        evolve})
