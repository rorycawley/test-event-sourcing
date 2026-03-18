(ns notification.notification-projection
  "Projection handlers for the notification aggregate read model.

   Builds the notifications table from notification domain events.
   Follows the same pattern as bank.account-projection."
  (:require [notification.notification :as notification]
            [es.projection-kit :as kit]
            [next.jdbc :as jdbc]
            [clojure.data.json :as json]))

;; ——— Handlers ———

(def handler-specs
  "Data-driven handler map: {event-type -> (fn [tx event context])}."
  {"notification-requested"
   (fn [tx {:keys [global-sequence stream-id] :as event} _context]
     (let [{:keys [notification-type recipient-id payload]}
           (:payload (notification/validate-event! event))]
       (jdbc/execute-one! tx
                          ["INSERT INTO notifications
                              (stream_id, notification_type, recipient_id,
                               payload, status, last_global_sequence)
                            VALUES (?, ?, ?, ?::jsonb, 'pending', ?)
                            ON CONFLICT (stream_id) DO UPDATE
                              SET last_global_sequence = GREATEST(
                                    notifications.last_global_sequence,
                                    EXCLUDED.last_global_sequence)"
                           stream-id
                           notification-type
                           recipient-id
                           (json/write-str payload)
                           global-sequence])))

   "notification-sent"
   (fn [tx {:keys [global-sequence stream-id] :as event}
        {:keys [ensure-single-row-updated!]}]
     (notification/validate-event! event)
     (let [result (jdbc/execute-one! tx
                                     ["UPDATE notifications
                                        SET status = 'sent',
                                            last_global_sequence = ?,
                                            updated_at = NOW()
                                        WHERE stream_id = ?
                                          AND last_global_sequence < ?"
                                      global-sequence stream-id global-sequence])]
       (ensure-single-row-updated! result
                                   (select-keys event
                                                [:global-sequence :stream-id
                                                 :event-type]))))

   "notification-failed"
   (fn [tx {:keys [global-sequence stream-id] :as event}
        {:keys [ensure-single-row-updated!]}]
     (let [{:keys [reason]} (:payload (notification/validate-event! event))
           result (jdbc/execute-one! tx
                                     ["UPDATE notifications
                                        SET retry_count = retry_count + 1,
                                            failure_reason = ?,
                                            last_global_sequence = ?,
                                            updated_at = NOW()
                                        WHERE stream_id = ?
                                          AND last_global_sequence < ?"
                                      reason global-sequence stream-id global-sequence])]
       (ensure-single-row-updated! result
                                   (select-keys event
                                                [:global-sequence :stream-id
                                                 :event-type]))))

   "notification-abandoned"
   (fn [tx {:keys [global-sequence stream-id] :as event}
        {:keys [ensure-single-row-updated!]}]
     (let [{:keys [reason]} (:payload (notification/validate-event! event))
           result (jdbc/execute-one! tx
                                     ["UPDATE notifications
                                        SET status = 'failed',
                                            retry_count = retry_count + 1,
                                            failure_reason = ?,
                                            last_global_sequence = ?,
                                            updated_at = NOW()
                                        WHERE stream_id = ?
                                          AND last_global_sequence < ?"
                                      reason global-sequence stream-id global-sequence])]
       (ensure-single-row-updated! result
                                   (select-keys event
                                                [:global-sequence :stream-id
                                                 :event-type]))))})

;; ——— Query ———

(def get-notification
  "Reads a notification by stream-id."
  (kit/make-query "notifications" "stream_id"))
