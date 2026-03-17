(ns event-sourcing.transfer-projection
  "Projection handlers for transfer events.

   Transfer events are recorded in the global event stream alongside
   account events. The account_balances projection ignores them, but
   they must be handled so the projection doesn't fail on unknown types.

   These handlers update a transfer_status read model that tracks
   the current state of each transfer."
  (:require [event-sourcing.transfer :as transfer]
            [event-sourcing.projection-dispatch :as projection-dispatch]
            [next.jdbc :as jdbc]))

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
  [tx {:keys [global-sequence stream-id] :as event}
   {:keys [ensure-single-row-updated!]}]
  (transfer/validate-event! event)
  (let [result (jdbc/execute-one! tx
                                  ["UPDATE transfer_status
                                      SET status = 'debited',
                                          last_global_sequence = ?,
                                          updated_at = NOW()
                                    WHERE transfer_id = ?
                                      AND last_global_sequence < ?"
                                   global-sequence stream-id global-sequence])]
    (ensure-single-row-updated! result event)))

(defmethod projection-dispatch/project-event! "credit-recorded"
  [tx {:keys [global-sequence stream-id] :as event}
   {:keys [ensure-single-row-updated!]}]
  (transfer/validate-event! event)
  (let [result (jdbc/execute-one! tx
                                  ["UPDATE transfer_status
                                      SET status = 'credited',
                                          last_global_sequence = ?,
                                          updated_at = NOW()
                                    WHERE transfer_id = ?
                                      AND last_global_sequence < ?"
                                   global-sequence stream-id global-sequence])]
    (ensure-single-row-updated! result event)))

(defmethod projection-dispatch/project-event! "transfer-completed"
  [tx {:keys [global-sequence stream-id] :as event}
   {:keys [ensure-single-row-updated!]}]
  (transfer/validate-event! event)
  (let [result (jdbc/execute-one! tx
                                  ["UPDATE transfer_status
                                      SET status = 'completed',
                                          last_global_sequence = ?,
                                          updated_at = NOW()
                                    WHERE transfer_id = ?
                                      AND last_global_sequence < ?"
                                   global-sequence stream-id global-sequence])]
    (ensure-single-row-updated! result event)))

(defmethod projection-dispatch/project-event! "transfer-failed"
  [tx {:keys [global-sequence stream-id] :as event}
   {:keys [ensure-single-row-updated!]}]
  (transfer/validate-event! event)
  (let [{:keys [reason]} (:payload event)
        result (jdbc/execute-one! tx
                                  ["UPDATE transfer_status
                                      SET status = 'failed',
                                          failure_reason = ?,
                                          last_global_sequence = ?,
                                          updated_at = NOW()
                                    WHERE transfer_id = ?
                                      AND last_global_sequence < ?"
                                   reason global-sequence stream-id
                                   global-sequence])]
    (ensure-single-row-updated! result event)))
