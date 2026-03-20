(ns system-integration-test
  "End-to-end test: full multi-module system from bank command to
   notification projection.

   Proves the complete pipeline:
   Bank command → Event Store → Outbox → RabbitMQ → Inbox Consumer →
   Reactor → Notification Aggregate → Notification Projector → Read Model

   Uses system/dev-full-system which boots all modules with Testcontainers."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [system :as system]
            [modules.bank.domain.account :as account]
            [es.decider :as decider]
            [next.jdbc :as jdbc]))

;; ═══════════════════════════════════════════════════
;; Test infrastructure
;; ═══════════════════════════════════════════════════

(def ^:dynamic *system* nil)

(defn- event-store-ds []
  (get-in *system* [:event-store-ds :datasource]))

(defn- notification-db-ds []
  (get-in *system* [:notification-db :datasource]))

(defn- with-system [f]
  (let [system (component/start (system/dev-full-system))]
    (binding [*system* system]
      (try
        (f)
        (finally
          (component/stop *system*))))))

(use-fixtures :once with-system)

;; ═══════════════════════════════════════════════════
;; Helpers
;; ═══════════════════════════════════════════════════

(defn- handle-command! [decider-map command]
  (decider/handle-with-retry! (event-store-ds) decider-map command
                              :on-events-appended (system/make-outbox-hook)))

;; ═══════════════════════════════════════════════════
;; Tests
;; ═══════════════════════════════════════════════════

(deftest full-pipeline-account-opened-to-notification
  ;; Open an account — this produces a bank.account-opened integration event
  (handle-command! account/decider
                   {:command-type    :open-account
                    :stream-id       "acct-e2e-1"
                    :idempotency-key "open-e2e-1"
                    :data            {:owner "Alice"}})

  ;; The notification reactor should receive the integration event via
  ;; RabbitMQ (or catch-up), create a notification aggregate in the event
  ;; store, and the notification projector should project it to the
  ;; notification DB. The stream-id is deterministic: notification-welcome-email-{global-seq}
  ;; but we don't know the exact global-sequence, so poll for any welcome notification.
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (let [rows (jdbc/execute! (notification-db-ds)
                                ["SELECT * FROM notifications
                                  WHERE notification_type = 'welcome-email'
                                    AND recipient_id = ?"
                                 "acct-e2e-1"])]
        (if (seq rows)
          (do
            (is (= 1 (count rows)))
            ;; The delivery worker runs concurrently, so the notification
            ;; may be pending or already sent by the time we read it.
            (is (contains? #{"pending" "sent"} (:notifications/status (first rows)))))
          (if (> (System/currentTimeMillis) deadline)
            (is false "Timed out waiting for welcome notification projection")
            (do (Thread/sleep 200)
                (recur))))))))

(deftest full-pipeline-large-deposit-to-notification
  ;; Open account first
  (handle-command! account/decider
                   {:command-type    :open-account
                    :stream-id       "acct-e2e-2"
                    :idempotency-key "open-e2e-2"
                    :data            {:owner "Bob"}})
  ;; Deposit above threshold
  (handle-command! account/decider
                   {:command-type    :deposit
                    :stream-id       "acct-e2e-2"
                    :idempotency-key "dep-e2e-15k"
                    :data            {:amount 15000}})

  ;; Should get both a welcome email AND a large deposit alert
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (let [rows (jdbc/execute! (notification-db-ds)
                                ["SELECT * FROM notifications
                                  WHERE recipient_id = ?
                                  ORDER BY notification_type"
                                 "acct-e2e-2"])]
        (if (>= (count rows) 2)
          (let [types (set (map :notifications/notification_type rows))]
            (is (contains? types "large-deposit-alert"))
            (is (contains? types "welcome-email")))
          (if (> (System/currentTimeMillis) deadline)
            (is false
                (str "Timed out waiting for notifications. Found: "
                     (count rows) " rows"))
            (do (Thread/sleep 200)
                (recur))))))))

(deftest full-pipeline-small-deposit-no-alert
  ;; Open account and make small deposit
  (handle-command! account/decider
                   {:command-type    :open-account
                    :stream-id       "acct-e2e-3"
                    :idempotency-key "open-e2e-3"
                    :data            {:owner "Carol"}})
  (handle-command! account/decider
                   {:command-type    :deposit
                    :stream-id       "acct-e2e-3"
                    :idempotency-key "dep-e2e-500"
                    :data            {:amount 500}})

  ;; Wait for the welcome notification to arrive (proves pipeline is working)
  (let [deadline (+ (System/currentTimeMillis) 15000)]
    (loop []
      (let [rows (jdbc/execute! (notification-db-ds)
                                ["SELECT * FROM notifications
                                  WHERE recipient_id = ?"
                                 "acct-e2e-3"])]
        (if (seq rows)
          (do
            ;; Should only have welcome email, no deposit alert
            (is (= 1 (count rows)))
            (is (= "welcome-email"
                   (:notifications/notification_type (first rows)))))
          (if (> (System/currentTimeMillis) deadline)
            (is false "Timed out waiting for welcome notification")
            (do (Thread/sleep 200)
                (recur))))))))
