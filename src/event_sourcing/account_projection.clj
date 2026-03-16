(ns event-sourcing.account-projection
  "Built-in account event handlers for the account_balances projection."
  (:require [event-sourcing.account :as account]
            [event-sourcing.projection-dispatch :as projection-dispatch]
            [next.jdbc :as jdbc]))

(defn- apply-balance-delta!
  [tx {:keys [global-sequence stream-id] :as event} delta ensure-single-row-updated!]
  (let [result (jdbc/execute-one! tx
                                  ["UPDATE account_balances
        SET balance = balance + ?,
            last_global_sequence = ?,
            updated_at = NOW()
        WHERE account_id = ?
          AND last_global_sequence < ?"
                                   delta global-sequence stream-id global-sequence])]
    (ensure-single-row-updated! result
                                (select-keys event
                                             [:global-sequence
                                              :stream-id
                                              :event-type
                                              :event-version
                                              :payload]))))

(defmethod projection-dispatch/project-event! "account-opened"
  [tx {:keys [global-sequence stream-id] :as event} _]
  (account/validate-event! event)
  (jdbc/execute-one! tx
                     ["INSERT INTO account_balances (account_id, balance, last_global_sequence, updated_at)
        VALUES (?, 0, ?, NOW())
        ON CONFLICT (account_id) DO UPDATE
          SET last_global_sequence = GREATEST(
                account_balances.last_global_sequence,
                EXCLUDED.last_global_sequence)"
                      stream-id global-sequence]))

(defmethod projection-dispatch/project-event! "money-deposited"
  [tx event {:keys [ensure-single-row-updated!]}]
  (let [{amount :amount} (:payload (account/validate-event! event))]
    (apply-balance-delta! tx
                          event
                          amount
                          ensure-single-row-updated!)))

(defmethod projection-dispatch/project-event! "money-withdrawn"
  [tx event {:keys [ensure-single-row-updated!]}]
  (let [{amount :amount} (:payload (account/validate-event! event))]
    (apply-balance-delta! tx
                          event
                          (- amount)
                          ensure-single-row-updated!)))
