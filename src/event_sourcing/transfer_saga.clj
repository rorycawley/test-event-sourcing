(ns event-sourcing.transfer-saga
  "Saga coordinator for fund transfers — infrastructure, not domain.

   This namespace is the I/O orchestrator that coordinates three
   Deciders (Chassaing): two account Deciders (source and destination)
   and one transfer Decider (saga progress tracker). The Deciders
   themselves are pure; this namespace provides the wiring that moves
   commands and events between them via the store.

   The saga protocol is a state machine driven by the transfer Decider:

     :not-found → :initiated → :debited → :credited → :completed
                       ↓            ↓
                    :failed      :failed (with compensation)

   Each step:
     1. Executes an account command (debit or credit)     — I/O
     2. Records progress on the transfer stream            — I/O
     3. Advances to the next state                         — loop

   On failure:
     - At debit:  mark transfer failed (nothing to undo)
     - At credit: compensate (refund source), then mark failed

   Crash recovery uses the same Pull → Transform pattern as the
   command handler: load the transfer stream, evolve to current state,
   and resume from whatever step the state machine says is next.
   Every step uses idempotency keys derived from the transfer-id,
   so re-execution is always safe — money is never lost or duplicated."
  (:require [event-sourcing.account :as account]
            [event-sourcing.decider :as decider]
            [event-sourcing.store :as store]
            [event-sourcing.transfer :as transfer]))

(defn- transfer-stream-id [transfer-id]
  (str "transfer-" transfer-id))

(defn- step-key [transfer-id step]
  (str "transfer-" transfer-id "-" (name step)))

;; ═══════════════════════════════════════════════════
;; Account command helpers
;; ═══════════════════════════════════════════════════

(defn- domain-error?
  "Returns true when an exception is a domain-level rejection
   (business rule violation), not an infrastructure failure."
  [e]
  (let [error-type (:error/type (ex-data e))]
    (and (keyword? error-type)
         (= "domain" (namespace error-type)))))

(defn- try-account-command!
  "Attempts an account command, returning :ok/:idempotent on success
   or {:error true :reason \"...\"} on domain rejection.
   Infrastructure errors (DB down, connection lost) propagate — they
   should not be confused with domain rejections."
  [ds command]
  (try
    (decider/handle-with-retry! ds account/decider command)
    (catch clojure.lang.ExceptionInfo e
      (if (domain-error? e)
        {:error  true
         :reason (name (:error/type (ex-data e)))}
        (throw e)))))

;; ═══════════════════════════════════════════════════
;; Transfer stream helpers
;; ═══════════════════════════════════════════════════

(defn- record-transfer-command!
  "Sends a command to the transfer Decider."
  [ds stream transfer-id command-type step data]
  (decider/handle! ds transfer/decider
                   {:command-type    command-type
                    :stream-id       stream
                    :idempotency-key (step-key transfer-id step)
                    :data            data}))

(defn- fail-transfer!
  [ds stream transfer-id reason]
  (record-transfer-command! ds stream transfer-id
                            :fail-transfer :fail
                            {:reason reason}))

;; ═══════════════════════════════════════════════════
;; Step execution — one function per state transition
;; ═══════════════════════════════════════════════════
;;
;; Each returns either:
;;   {:status :completed}               — saga done
;;   {:status :failed :reason "..."}    — saga done (domain rejection)
;;   {:next-status :debited ...}        — advance the loop
;;   {:next-status :credited ...}       — advance the loop

(defn- execute-initiated
  "State is :initiated — debit the source account."
  [ds transfer-id stream {:keys [from-account amount]}]
  (let [result (try-account-command!
                ds
                {:command-type    :withdraw
                 :stream-id       from-account
                 :idempotency-key (step-key transfer-id :debit)
                 :data            {:amount amount}})]
    (if (:error result)
      (do (fail-transfer! ds stream transfer-id (:reason result))
          {:status :failed :reason (:reason result)})
      (do (record-transfer-command! ds stream transfer-id
                                    :record-debit :record-debit
                                    {:account-id from-account :amount amount})
          {:next-status :debited}))))

(defn- execute-debited
  "State is :debited — credit the destination account."
  [ds transfer-id stream {:keys [from-account to-account amount]}]
  (let [result (try-account-command!
                ds
                {:command-type    :deposit
                 :stream-id       to-account
                 :idempotency-key (step-key transfer-id :credit)
                 :data            {:amount amount}})]
    (if (:error result)
      ;; Credit failed — compensate: refund the source.
      ;; The refund MUST succeed; if it fails, propagate the error
      ;; rather than silently losing money.
      (let [refund-result (try-account-command!
                           ds
                           {:command-type    :deposit
                            :stream-id       from-account
                            :idempotency-key (step-key transfer-id :compensate)
                            :data            {:amount amount}})]
        (when (:error refund-result)
          (throw (ex-info "Compensation failed: unable to refund source account"
                          {:error/type    :saga/compensation-failed
                           :transfer-id   transfer-id
                           :from-account  from-account
                           :amount        amount
                           :credit-reason (:reason result)
                           :refund-reason (:reason refund-result)})))
        (fail-transfer! ds stream transfer-id (:reason result))
        {:status :failed :reason (:reason result)})
      (do (record-transfer-command! ds stream transfer-id
                                    :record-credit :record-credit
                                    {:account-id to-account :amount amount})
          {:next-status :credited}))))

(defn- execute-credited
  "State is :credited — mark the transfer complete."
  [ds transfer-id stream]
  (record-transfer-command! ds stream transfer-id
                            :complete-transfer :complete {})
  {:status :completed})

;; ═══════════════════════════════════════════════════
;; State machine loop
;; ═══════════════════════════════════════════════════

(defn- run-from
  "Drives the saga from the given state until it reaches a terminal
   status (:completed or :failed). The transfer Decider's state
   machine determines what happens at each step.
   If the state is already terminal (e.g. idempotent re-execution),
   returns immediately."
  [ds transfer-id stream state]
  (loop [status (:status state)
         state  state]
    (case status
      :completed {:status :completed}
      :failed    {:status :failed :reason (:failure-reason state)}
      (let [result (case status
                     :initiated (execute-initiated ds transfer-id stream state)
                     :debited   (execute-debited ds transfer-id stream state)
                     :credited  (execute-credited ds transfer-id stream))]
        (if (:next-status result)
          (recur (:next-status result) state)
          result)))))

;; ═══════════════════════════════════════════════════
;; Public API
;; ═══════════════════════════════════════════════════

(defn execute!
  "Executes a fund transfer between two accounts.

   Parameters:
     ds          — datasource
     transfer-id — unique identifier for this transfer
     from        — source account stream-id
     to          — destination account stream-id
     amount      — positive integer amount to transfer

   Returns a map:
     {:status :completed}                on success
     {:status :failed, :reason \"...\"}  on domain rejection

   The entire saga is idempotent — re-executing with the same
   transfer-id will skip already-completed steps."
  [ds transfer-id from to amount]
  (let [stream (transfer-stream-id transfer-id)]
    ;; Step 1: Record the initiation event
    (record-transfer-command! ds stream transfer-id
                              :initiate-transfer :initiate
                              {:from-account from :to-account to :amount amount})
    ;; Pull → Transform: let the Decider be the single source of truth
    ;; for state, rather than constructing it by hand.
    (let [state (decider/evolve-state transfer/decider
                                      (store/load-stream ds stream))]
      (run-from ds transfer-id stream state))))

(defn resume!
  "Resumes an in-progress transfer from its last completed step.

   Uses the same Pull → Transform pattern as the command handler:
   loads the transfer stream, evolves to current state via the
   transfer Decider, then enters the state machine loop at whatever
   step is next. Safe to call on any transfer — terminal states
   return immediately.

   This is the crash-recovery mechanism: if the process dies
   mid-transfer, call resume! with the same transfer-id to
   continue. Every step is idempotent, so resumption is always safe.

   Returns a map:
     {:status :completed}                        — transfer finished
     {:status :failed, :reason \"...\"}          — transfer failed
     {:status :already-completed}                — was already done
     {:status :already-failed, :reason \"...\"}  — was already failed"
  [ds transfer-id]
  (let [stream (transfer-stream-id transfer-id)
        events (store/load-stream ds stream)
        state  (decider/evolve-state transfer/decider events)]
    (case (:status state)
      :not-found  (throw (ex-info "Transfer not found"
                                  {:error/type  :saga/transfer-not-found
                                   :transfer-id transfer-id
                                   :stream-id   stream}))
      :completed  {:status :already-completed}
      :failed     {:status :already-failed :reason (:failure-reason state)}
      ;; Non-terminal: re-enter the state machine loop
      (run-from ds transfer-id stream state))))
