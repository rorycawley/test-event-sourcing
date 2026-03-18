(ns bank.transfer-projection
  "Projection handlers for transfer events, plus query functions
   for the transfer_status read model.

   Handlers are declared as a data map keyed by event-type string,
   composed into the projection via bank.system."
  (:require [bank.transfer :as transfer]
            [es.projection-kit :as kit]
            [next.jdbc :as jdbc]))

;; ——— Shared update helper ———

(defn- update-transfer-status!
  "Updates a transfer_status row to a new status, optionally setting
   failure_reason. Validates the event, executes the UPDATE, and
   asserts exactly one row was changed."
  [tx event
   {:keys [ensure-single-row-updated!]}
   status
   & {:keys [failure-reason]}]
  (let [{:keys [global-sequence stream-id]} (transfer/validate-event! event)
        transfer-id (transfer/logical-transfer-id stream-id)
        result
        (if failure-reason
          (jdbc/execute-one! tx
                             ["UPDATE transfer_status
                                  SET status = ?,
                                      failure_reason = ?,
                                      last_global_sequence = ?,
                                      updated_at = NOW()
                                WHERE transfer_id = ?
                                  AND last_global_sequence < ?"
                              status failure-reason
                              global-sequence transfer-id global-sequence])
          (jdbc/execute-one! tx
                             ["UPDATE transfer_status
                                  SET status = ?,
                                      last_global_sequence = ?,
                                      updated_at = NOW()
                                WHERE transfer_id = ?
                                  AND last_global_sequence < ?"
                              status
                              global-sequence transfer-id global-sequence]))]
    (ensure-single-row-updated! result event)))

;; ——— Handler specs ———

(def handler-specs
  "Data-driven handler map: {event-type -> (fn [tx event context])}."
  {"transfer-initiated"
   (fn [tx event _context]
     (let [{:keys [global-sequence stream-id payload]} (transfer/validate-event! event)
           {:keys [from-account to-account amount]} payload
           transfer-id (transfer/logical-transfer-id stream-id)]
       (jdbc/execute-one! tx
                          ["INSERT INTO transfer_status
                           (transfer_id, from_account, to_account, amount,
                            status, last_global_sequence, updated_at)
                         VALUES (?, ?, ?, ?, 'initiated', ?, NOW())
                         ON CONFLICT (transfer_id) DO UPDATE
                           SET last_global_sequence = GREATEST(
                                 transfer_status.last_global_sequence,
                                 EXCLUDED.last_global_sequence)"
                           transfer-id from-account to-account amount
                           global-sequence])))

   "debit-recorded"
   (fn [tx event context]
     (update-transfer-status! tx event context "debited"))

   "credit-recorded"
   (fn [tx event context]
     (update-transfer-status! tx event context "credited"))

   "compensation-recorded"
   (fn [tx event context]
     (update-transfer-status! tx event context "compensating"))

   "transfer-completed"
   (fn [tx event context]
     (update-transfer-status! tx event context "completed"))

   "transfer-failed"
   (fn [tx event context]
     (let [validated (transfer/validate-event! event)]
       (update-transfer-status! tx validated context "failed"
                                :failure-reason (get-in validated [:payload :reason]))))})

;; ——— Query ———

(def get-transfer
  "Reads the projected status for one transfer."
  (kit/make-query "transfer_status" "transfer_id"))
