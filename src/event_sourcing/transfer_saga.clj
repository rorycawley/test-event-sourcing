(ns event-sourcing.transfer-saga
  "Saga coordinator for fund transfers.

   Orchestrates a transfer across two account aggregates and one
   transfer aggregate. The transfer stream is the saga's own
   event log — it tracks which steps have completed.

   The saga follows this protocol:

     1. Initiate  — create transfer stream with from/to/amount
     2. Debit     — withdraw from source account
     3. Credit    — deposit to destination account
     4. Complete  — mark transfer done

   On failure at step 2 (insufficient funds, account not open):
     → mark transfer failed (no compensation needed — nothing moved yet)

   On failure at step 3 (destination account not open):
     → compensate: re-credit the source account
     → mark transfer failed

   The saga is **resumable**: if the process crashes mid-transfer,
   resume! reads the transfer stream, recovers the current state,
   and picks up from the last completed step. Every step uses
   idempotency keys derived from the transfer-id, so re-execution
   is always safe."
  (:require [event-sourcing.account :as account]
            [event-sourcing.decider :as decider]
            [event-sourcing.store :as store]
            [event-sourcing.transfer :as transfer]))

(defn- transfer-stream-id [transfer-id]
  (str "transfer-" transfer-id))

(defn- step-key [transfer-id step]
  (str "transfer-" transfer-id "-" (name step)))

(defn- try-account-command!
  "Attempts an account command, returning :ok/:idempotent on success
   or {:error true :reason \"...\"} on domain failure."
  [ds command]
  (try
    (decider/handle-with-retry! ds account/decider command)
    (catch clojure.lang.ExceptionInfo e
      {:error  true
       :reason (or (some-> (ex-data e) :error/type name)
                   (.getMessage e))
       :ex     e})))

(defn- fail-transfer!
  [ds stream transfer-id reason]
  (decider/handle! ds transfer/decider
                   {:command-type    :fail-transfer
                    :stream-id       stream
                    :idempotency-key (step-key transfer-id :fail)
                    :data            {:reason reason}}))

;; ═══════════════════════════════════════════════════
;; Saga steps — each picks up from the named state
;; ═══════════════════════════════════════════════════

(declare continue-from-initiated continue-from-debited continue-from-credited)

(defn- continue-from-initiated
  "Saga is initiated — next: debit the source account."
  [ds transfer-id stream {:keys [from-account to-account amount]}]
  (let [debit-result (try-account-command!
                      ds
                      {:command-type    :withdraw
                       :stream-id       from-account
                       :idempotency-key (step-key transfer-id :debit)
                       :data            {:amount amount}})]
    (if (:error debit-result)
      (do (fail-transfer! ds stream transfer-id (:reason debit-result))
          {:status :failed :reason (:reason debit-result)})
      (do (decider/handle! ds transfer/decider
                           {:command-type    :record-debit
                            :stream-id       stream
                            :idempotency-key (step-key transfer-id :record-debit)
                            :data            {:account-id from-account
                                              :amount     amount}})
          (continue-from-debited ds transfer-id stream
                                 {:from-account from-account
                                  :to-account   to-account
                                  :amount       amount})))))

(defn- continue-from-debited
  "Source account debited — next: credit the destination account."
  [ds transfer-id stream {:keys [from-account to-account amount]}]
  (let [credit-result (try-account-command!
                       ds
                       {:command-type    :deposit
                        :stream-id       to-account
                        :idempotency-key (step-key transfer-id :credit)
                        :data            {:amount amount}})]
    (if (:error credit-result)
      ;; Credit failed — compensate: refund the source
      (do (try-account-command!
           ds
           {:command-type    :deposit
            :stream-id       from-account
            :idempotency-key (step-key transfer-id :compensate)
            :data            {:amount amount}})
          (fail-transfer! ds stream transfer-id (:reason credit-result))
          {:status :failed :reason (:reason credit-result)})
      (do (decider/handle! ds transfer/decider
                           {:command-type    :record-credit
                            :stream-id       stream
                            :idempotency-key (step-key transfer-id :record-credit)
                            :data            {:account-id to-account
                                              :amount     amount}})
          (continue-from-credited ds transfer-id stream)))))

(defn- continue-from-credited
  "Both accounts updated — next: mark transfer complete."
  [ds transfer-id stream]
  (decider/handle! ds transfer/decider
                   {:command-type    :complete-transfer
                    :stream-id       stream
                    :idempotency-key (step-key transfer-id :complete)
                    :data            {}})
  {:status :completed})

;; ═══════════════════════════════════════════════════
;; Public API
;; ═══════════════════════════════════════════════════

(defn execute!
  "Executes a fund transfer between two accounts.

   Parameters:
     ds          — datasource
     transfer-id — unique identifier for this transfer (used as stream suffix)
     from        — source account stream-id
     to          — destination account stream-id
     amount      — positive integer amount to transfer

   Returns a map:
     {:status :completed} on success
     {:status :failed, :reason \"...\"} on business rule failure

   The entire saga is idempotent — re-executing with the same
   transfer-id will skip already-completed steps."
  [ds transfer-id from to amount]
  (let [stream (transfer-stream-id transfer-id)]
    ;; Step 1: Initiate the transfer
    (decider/handle! ds transfer/decider
                     {:command-type    :initiate-transfer
                      :stream-id       stream
                      :idempotency-key (step-key transfer-id :initiate)
                      :data            {:from-account from
                                        :to-account   to
                                        :amount       amount}})
    (continue-from-initiated ds transfer-id stream
                             {:from-account from
                              :to-account   to
                              :amount       amount})))

(defn resume!
  "Resumes an in-progress transfer from its last completed step.

   Reads the transfer stream, evolves to current state, and picks
   up where the saga left off. Safe to call on any transfer —
   completed or failed transfers return their terminal status
   immediately.

   This is the crash-recovery mechanism: if the process dies
   mid-transfer, call resume! with the same transfer-id to
   continue from the last persisted step.

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
      :not-found
      (throw (ex-info "Transfer not found"
                      {:error/type   :saga/transfer-not-found
                       :transfer-id  transfer-id
                       :stream-id    stream}))

      :initiated
      (continue-from-initiated ds transfer-id stream state)

      :debited
      (continue-from-debited ds transfer-id stream state)

      :credited
      (continue-from-credited ds transfer-id stream)

      :completed
      {:status :already-completed}

      :failed
      {:status :already-failed :reason (:failure-reason state)})))
