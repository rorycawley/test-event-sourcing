(ns event-sourcing.transfer
  "Fund transfer domain — the transfer Decider.

   Models a transfer between two accounts as its own aggregate
   with a lifecycle: initiated → debited → completed (or failed).

   This is a saga/process manager expressed as a Decider.
   The transfer stream tracks the progress of the cross-account
   operation, while the actual balance changes happen on the
   account streams (coordinated by transfer-saga.clj).

   Everything here is pure: no I/O, no database, no side effects."
  (:require [event-sourcing.schema :as schema]
            [malli.core :as m]))

;; ═══════════════════════════════════════════════════
;; Command schemas
;; ═══════════════════════════════════════════════════

(def ^:private command-schema
  [:map
   [:command-type keyword?]
   [:data map?]])

(def ^:private initiate-data-schema
  [:map
   [:from-account schema/non-empty-string]
   [:to-account schema/non-empty-string]
   [:amount pos-int?]])

(def ^:private record-debit-data-schema
  [:map
   [:account-id schema/non-empty-string]
   [:amount pos-int?]])

(def ^:private record-credit-data-schema
  [:map
   [:account-id schema/non-empty-string]
   [:amount pos-int?]])

(def ^:private complete-data-schema
  [:map])

(def ^:private fail-data-schema
  [:map
   [:reason schema/non-empty-string]])

(def ^:private command-data-specs
  {:initiate-transfer  initiate-data-schema
   :record-debit       record-debit-data-schema
   :record-credit      record-credit-data-schema
   :complete-transfer  complete-data-schema
   :fail-transfer      fail-data-schema})

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
;; Event schemas (all v1)
;; ═══════════════════════════════════════════════════

(def ^:private latest-event-version
  {"transfer-initiated" 1
   "debit-recorded"     1
   "credit-recorded"    1
   "transfer-completed" 1
   "transfer-failed"    1})

(def ^:private event-schemas
  {["transfer-initiated" 1]
   [:map
    [:event-type [:= "transfer-initiated"]]
    [:event-version [:= 1]]
    [:payload [:map
               [:from-account schema/non-empty-string]
               [:to-account schema/non-empty-string]
               [:amount pos-int?]]]]

   ["debit-recorded" 1]
   [:map
    [:event-type [:= "debit-recorded"]]
    [:event-version [:= 1]]
    [:payload [:map
               [:account-id schema/non-empty-string]
               [:amount pos-int?]]]]

   ["credit-recorded" 1]
   [:map
    [:event-type [:= "credit-recorded"]]
    [:event-version [:= 1]]
    [:payload [:map
               [:account-id schema/non-empty-string]
               [:amount pos-int?]]]]

   ["transfer-completed" 1]
   [:map
    [:event-type [:= "transfer-completed"]]
    [:event-version [:= 1]]
    [:payload [:map]]]

   ["transfer-failed" 1]
   [:map
    [:event-type [:= "transfer-failed"]]
    [:event-version [:= 1]]
    [:payload [:map
               [:reason schema/non-empty-string]]]]})

(defn- invalid-event!
  [event reason explain]
  (throw (ex-info "Invalid event"
                  {:error/type :domain/invalid-event
                   :reason     reason
                   :event      event
                   :explain    explain})))

(defn upcast-event
  "Normalises a possibly-legacy event to the latest known schema version."
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
    ;; All transfer events are v1, no upcasting needed yet
    event))

(defn validate-event!
  "Upcasts + validates one transfer domain event map."
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

(defn evolve
  "Applies a single event to the current transfer state."
  [state event]
  (let [{:keys [event-type payload]} (validate-event! event)]
    (case event-type
      "transfer-initiated" (assoc state
                                  :status       :initiated
                                  :from-account (:from-account payload)
                                  :to-account   (:to-account payload)
                                  :amount       (:amount payload))
      "debit-recorded"     (assoc state :status :debited)
      "credit-recorded"    (assoc state :status :credited)
      "transfer-completed" (assoc state :status :completed)
      "transfer-failed"    (assoc state
                                  :status :failed
                                  :failure-reason (:reason payload))
      state)))

;; ═══════════════════════════════════════════════════
;; Decide — Command → State → Event list
;; ═══════════════════════════════════════════════════

(defn- decide-initiate [state {:keys [from-account to-account amount]}]
  (when-not (= :not-found (:status state))
    (throw (ex-info "Transfer already exists"
                    {:error/type :domain/transfer-already-exists
                     :status     (:status state)})))
  (when (= from-account to-account)
    (throw (ex-info "Cannot transfer to same account"
                    {:error/type :domain/same-account-transfer
                     :account    from-account})))
  [(mk-event "transfer-initiated" {:from-account from-account
                                   :to-account   to-account
                                   :amount       amount})])

(defn- decide-record-debit [state {:keys [account-id amount]}]
  (when-not (= :initiated (:status state))
    (throw (ex-info "Transfer not in initiated state"
                    {:error/type :domain/invalid-transfer-state
                     :expected   :initiated
                     :actual     (:status state)})))
  (when (not= account-id (:from-account state))
    (throw (ex-info "Debit account mismatch"
                    {:error/type :domain/account-mismatch
                     :expected   (:from-account state)
                     :actual     account-id})))
  (when (not= amount (:amount state))
    (throw (ex-info "Debit amount mismatch"
                    {:error/type :domain/amount-mismatch
                     :expected   (:amount state)
                     :actual     amount})))
  [(mk-event "debit-recorded" {:account-id account-id
                               :amount     amount})])

(defn- decide-record-credit [state {:keys [account-id amount]}]
  (when-not (= :debited (:status state))
    (throw (ex-info "Transfer not in debited state"
                    {:error/type :domain/invalid-transfer-state
                     :expected   :debited
                     :actual     (:status state)})))
  (when (not= account-id (:to-account state))
    (throw (ex-info "Credit account mismatch"
                    {:error/type :domain/account-mismatch
                     :expected   (:to-account state)
                     :actual     account-id})))
  (when (not= amount (:amount state))
    (throw (ex-info "Credit amount mismatch"
                    {:error/type :domain/amount-mismatch
                     :expected   (:amount state)
                     :actual     amount})))
  [(mk-event "credit-recorded" {:account-id account-id
                                :amount     amount})])

(defn- decide-complete [state _data]
  (when-not (= :credited (:status state))
    (throw (ex-info "Transfer not in credited state"
                    {:error/type :domain/invalid-transfer-state
                     :expected   :credited
                     :actual     (:status state)})))
  [(mk-event "transfer-completed" {})])

(defn- decide-fail [state {:keys [reason]}]
  (when (contains? #{:completed :failed} (:status state))
    (throw (ex-info "Transfer already terminal"
                    {:error/type :domain/transfer-already-terminal
                     :status     (:status state)})))
  (when (= :not-found (:status state))
    (throw (ex-info "Transfer not found"
                    {:error/type :domain/transfer-not-found})))
  [(mk-event "transfer-failed" {:reason reason})])

(def ^:private decisions
  {:initiate-transfer  decide-initiate
   :record-debit       decide-record-debit
   :record-credit      decide-record-credit
   :complete-transfer  decide-complete
   :fail-transfer      decide-fail})

(defn decide
  "Command → State → Event list."
  [{:keys [command-type data] :as command} state]
  (validate-command! command)
  (let [decision (or (get decisions command-type)
                     (throw (ex-info "Unknown command"
                                     {:error/type   :domain/unknown-command
                                      :command-type command-type})))]
    (mapv validate-event! (decision state data))))

;; ═══════════════════════════════════════════════════
;; The Decider
;; ═══════════════════════════════════════════════════

(def decider
  {:initial-state {:status :not-found}
   :decide        decide
   :evolve        evolve})
