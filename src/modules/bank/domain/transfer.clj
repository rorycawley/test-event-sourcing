(ns modules.bank.domain.transfer
  "Fund transfer domain — the transfer Decider (Chassaing).

   Commands (intent):  :initiate-transfer, :record-debit, :record-credit,
                       :complete-transfer, :record-compensation, :fail-transfer
   Events (facts):     transfer-initiated, debit-recorded, credit-recorded,
                       transfer-completed, compensation-recorded, transfer-failed
   State (truth):      {:status :not-found|:initiated|:debited|:credited|
                                :compensating|:completed|:failed, ...}

   Models a transfer between two accounts as its own aggregate
   with a state machine: not-found → initiated → debited → credited
   → completed (or → failed from any non-terminal state).
   When a credit fails after debit, the transfer enters :compensating
   before the refund, preventing resume from crediting the destination.

   This is a saga/process manager expressed as a Decider. The transfer
   stream tracks progress; actual balance changes happen on the account
   streams (coordinated by transfer_saga.clj).

   Everything here is pure: no I/O, no database, no side effects."
  (:require [es.decider-kit :as kit]
            [es.schema :as schema]))

;; ═══════════════════════════════════════════════════
;; Command schemas
;; ═══════════════════════════════════════════════════

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

(def ^:private record-compensation-data-schema
  [:map
   [:reason schema/non-empty-string]])

(def ^:private complete-data-schema
  [:map])

(def ^:private fail-data-schema
  [:map
   [:reason schema/non-empty-string]])

(def ^:private command-data-specs
  {:initiate-transfer    initiate-data-schema
   :record-debit         record-debit-data-schema
   :record-credit        record-credit-data-schema
   :complete-transfer    complete-data-schema
   :record-compensation  record-compensation-data-schema
   :fail-transfer        fail-data-schema})

;; ═══════════════════════════════════════════════════
;; Event schemas (all v1)
;; ═══════════════════════════════════════════════════

(def ^:private latest-event-version
  {"transfer-initiated"    1
   "debit-recorded"        1
   "credit-recorded"       1
   "transfer-completed"    1
   "compensation-recorded" 1
   "transfer-failed"       1})

(def ^:private event-schemas
  {["transfer-initiated" 1]    [:map
                                [:from-account schema/non-empty-string]
                                [:to-account schema/non-empty-string]
                                [:amount pos-int?]]
   ["debit-recorded" 1]        [:map
                                [:account-id schema/non-empty-string]
                                [:amount pos-int?]]
   ["credit-recorded" 1]       [:map
                                [:account-id schema/non-empty-string]
                                [:amount pos-int?]]
   ["transfer-completed" 1]    [:map]
   ["compensation-recorded" 1] [:map
                                [:reason schema/non-empty-string]]
   ["transfer-failed" 1]       [:map
                                [:reason schema/non-empty-string]]})

;; ═══════════════════════════════════════════════════
;; Kit-derived functions
;; ═══════════════════════════════════════════════════

(def ^:private validate-command! (kit/make-command-validator command-data-specs))

(def upcast-event
  "Normalises a possibly-legacy event to the latest known schema version."
  (kit/make-event-upcaster latest-event-version {}))

(def validate-event!
  "Upcasts + validates one transfer domain event map."
  (kit/make-event-validator event-schemas upcast-event))

(def ^:private mk-event (kit/make-event-factory latest-event-version validate-event!))

;; ═══════════════════════════════════════════════════
;; Stream naming
;; ═══════════════════════════════════════════════════

(def stream-prefix
  "Prefix applied to logical transfer IDs to form stream IDs."
  "transfer-")

(defn transfer-stream-id
  "Converts a logical transfer-id to its stream-id."
  [transfer-id]
  (str stream-prefix transfer-id))

(defn logical-transfer-id
  "Extracts the logical transfer-id from a stream-id by stripping
   the stream prefix. Returns the stream-id unchanged if it does
   not carry the prefix."
  [stream-id]
  (if (.startsWith ^String stream-id stream-prefix)
    (subs stream-id (count stream-prefix))
    stream-id))

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
      "debit-recorded"        (assoc state :status :debited)
      "credit-recorded"       (assoc state :status :credited)
      "transfer-completed"    (assoc state :status :completed)
      "compensation-recorded" (assoc state
                                     :status :compensating
                                     :compensation-reason (:reason payload))
      "transfer-failed"       (assoc state
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

(defn- guard-step!
  "Shared guard for record-debit and record-credit: validates status,
   account, and amount match the transfer state."
  [state expected-status account-key account-id amount]
  (when-not (= expected-status (:status state))
    (throw (ex-info (str "Transfer not in " (name expected-status) " state")
                    {:error/type :domain/invalid-transfer-state
                     :expected   expected-status
                     :actual     (:status state)})))
  (when (not= account-id (get state account-key))
    (throw (ex-info (str (if (= account-key :from-account) "Debit" "Credit")
                         " account mismatch")
                    {:error/type :domain/account-mismatch
                     :expected   (get state account-key)
                     :actual     account-id})))
  (when (not= amount (:amount state))
    (throw (ex-info (str (if (= account-key :from-account) "Debit" "Credit")
                         " amount mismatch")
                    {:error/type :domain/amount-mismatch
                     :expected   (:amount state)
                     :actual     amount}))))

(defn- decide-record-debit [state {:keys [account-id amount]}]
  (guard-step! state :initiated :from-account account-id amount)
  [(mk-event "debit-recorded" {:account-id account-id
                               :amount     amount})])

(defn- decide-record-credit [state {:keys [account-id amount]}]
  (guard-step! state :debited :to-account account-id amount)
  [(mk-event "credit-recorded" {:account-id account-id
                                :amount     amount})])

(defn- decide-complete [state _data]
  (when-not (= :credited (:status state))
    (throw (ex-info "Transfer not in credited state"
                    {:error/type :domain/invalid-transfer-state
                     :expected   :credited
                     :actual     (:status state)})))
  [(mk-event "transfer-completed" {})])

(defn- decide-record-compensation [state {:keys [reason]}]
  (when-not (= :debited (:status state))
    (throw (ex-info "Transfer not in debited state"
                    {:error/type :domain/invalid-transfer-state
                     :expected   :debited
                     :actual     (:status state)})))
  [(mk-event "compensation-recorded" {:reason reason})])

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
  {:initiate-transfer    decide-initiate
   :record-debit         decide-record-debit
   :record-credit        decide-record-credit
   :complete-transfer    decide-complete
   :record-compensation  decide-record-compensation
   :fail-transfer        decide-fail})

(def decide
  "Command → State → Event list."
  (kit/make-decide validate-command! validate-event! decisions))

;; ═══════════════════════════════════════════════════
;; The Decider
;; ═══════════════════════════════════════════════════

(def decider
  {:initial-state {:status :not-found}
   :decide        decide
   :evolve        evolve})
