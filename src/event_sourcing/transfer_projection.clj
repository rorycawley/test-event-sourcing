(ns event-sourcing.transfer-projection
  "Projection handlers for transfer events.

   Transfer events are recorded in the global event stream alongside
   account events. The account_balances projection ignores them, but
   they must be handled so the projection doesn't fail on unknown types.

   These handlers update a transfer_status read model that tracks
   the current state of each transfer.

   The four status-update handlers (debit-recorded, credit-recorded,
   transfer-completed, transfer-failed) share a single UPDATE helper
   driven by a data map — only the status string and optional
   failure_reason column differ."
  (:require [event-sourcing.transfer :as transfer]
            [event-sourcing.projection-dispatch :as projection-dispatch]
            [next.jdbc :as jdbc]))

;; ——— Shared update helper ———

(defn- update-transfer-status!
  "Updates a transfer_status row to a new status, optionally setting
   failure_reason. Validates the event, executes the UPDATE, and
   asserts exactly one row was changed."
  [tx {:keys [global-sequence stream-id] :as event}
   {:keys [ensure-single-row-updated!]}
   status
   & {:keys [failure-reason]}]
  (transfer/validate-event! event)
  (let [result
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
                              global-sequence stream-id global-sequence])
          (jdbc/execute-one! tx
                             ["UPDATE transfer_status
                                  SET status = ?,
                                      last_global_sequence = ?,
                                      updated_at = NOW()
                                WHERE transfer_id = ?
                                  AND last_global_sequence < ?"
                              status
                              global-sequence stream-id global-sequence]))]
    (ensure-single-row-updated! result event)))

;; ——— Event handlers ———

(defmethod projection-dispatch/project-event! "transfer-initiated"
  [tx {:keys [global-sequence stream-id] :as event} _]
  (transfer/validate-event! event)
  (let [{:keys [from-account to-account amount]} (:payload event)]
    (jdbc/execute-one! tx
                       ["INSERT INTO transfer_status
                           (transfer_id, from_account, to_account, amount,
                            status, last_global_sequence, updated_at)
                         VALUES (?, ?, ?, ?, 'initiated', ?, NOW())
                         ON CONFLICT (transfer_id) DO UPDATE
                           SET last_global_sequence = GREATEST(
                                 transfer_status.last_global_sequence,
                                 EXCLUDED.last_global_sequence)"
                        stream-id from-account to-account amount
                        global-sequence])))

(defmethod projection-dispatch/project-event! "debit-recorded"
  [tx event context]
  (update-transfer-status! tx event context "debited"))

(defmethod projection-dispatch/project-event! "credit-recorded"
  [tx event context]
  (update-transfer-status! tx event context "credited"))

(defmethod projection-dispatch/project-event! "transfer-completed"
  [tx event context]
  (update-transfer-status! tx event context "completed"))

(defmethod projection-dispatch/project-event! "transfer-failed"
  [tx event context]
  (update-transfer-status! tx event context "failed"
                           :failure-reason (get-in event [:payload :reason])))
