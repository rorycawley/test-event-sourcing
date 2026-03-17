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

   The saga uses idempotency keys derived from the transfer-id
   so every step is safe to retry."
  (:require [event-sourcing.account :as account]
            [event-sourcing.decider :as decider]
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

    ;; Step 2: Debit the source account
    (let [debit-result (try-account-command!
                        ds
                        {:command-type    :withdraw
                         :stream-id       from
                         :idempotency-key (step-key transfer-id :debit)
                         :data            {:amount amount}})]

      (if (:error debit-result)
        ;; Debit failed — mark transfer failed, no compensation needed
        (do (fail-transfer! ds stream transfer-id (:reason debit-result))
            {:status :failed :reason (:reason debit-result)})

        (do
          ;; Record debit on transfer stream
          (decider/handle! ds transfer/decider
                           {:command-type    :record-debit
                            :stream-id       stream
                            :idempotency-key (step-key transfer-id :record-debit)
                            :data            {:account-id from
                                              :amount     amount}})

          ;; Step 3: Credit the destination account
          (let [credit-result (try-account-command!
                               ds
                               {:command-type    :deposit
                                :stream-id       to
                                :idempotency-key (step-key transfer-id :credit)
                                :data            {:amount amount}})]

            (if (:error credit-result)
              ;; Credit failed — compensate: refund the source
              (do (try-account-command!
                   ds
                   {:command-type    :deposit
                    :stream-id       from
                    :idempotency-key (step-key transfer-id :compensate)
                    :data            {:amount amount}})
                  (fail-transfer! ds stream transfer-id (:reason credit-result))
                  {:status :failed :reason (:reason credit-result)})

              (do
                ;; Record credit on transfer stream
                (decider/handle! ds transfer/decider
                                 {:command-type    :record-credit
                                  :stream-id       stream
                                  :idempotency-key (step-key transfer-id :record-credit)
                                  :data            {:account-id to
                                                    :amount     amount}})

                ;; Step 4: Mark transfer complete
                (decider/handle! ds transfer/decider
                                 {:command-type    :complete-transfer
                                  :stream-id       stream
                                  :idempotency-key (step-key transfer-id :complete)
                                  :data            {}})

                {:status :completed}))))))))
