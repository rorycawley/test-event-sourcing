(ns bank.async-integration-test
  "End-to-end test: commands flow through the full async pipeline.

   Event Store (Postgres) → Outbox → RabbitMQ → Async Projectors → Read Store (Postgres)

   Uses Testcontainers for all three infrastructure services."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [com.stuartsierra.component :as component]
            [bank.components :as bank-components]
            [bank.account :as account]
            [bank.account-projection :as account-projection]
            [bank.transfer-projection :as transfer-projection]
            [bank.transfer-saga :as saga]
            [es.decider :as decider]
            [es.outbox :as outbox]
            [next.jdbc :as jdbc]))

;; ═══════════════════════════════════════════════════
;; Test infrastructure
;; ═══════════════════════════════════════════════════

(def ^:dynamic *system* nil)

(defn- event-store-ds []
  (get-in *system* [:event-store-ds :datasource]))

(defn- read-db-ds []
  (get-in *system* [:read-db-ds :datasource]))

(defn- with-system [f]
  (let [system (component/start (bank-components/dev-full-system))]
    (try
      (binding [*system* system]
        (f))
      (finally
        (component/stop system)))))

(defn- reset-dbs! []
  (jdbc/with-transaction [tx (event-store-ds)]
    (jdbc/execute-one! tx
                       ["TRUNCATE TABLE events, idempotency_keys, event_outbox
                         RESTART IDENTITY"]))
  (jdbc/with-transaction [tx (read-db-ds)]
    (jdbc/execute-one! tx
                       ["TRUNCATE TABLE account_balances, transfer_status,
                                        projection_checkpoints
                         RESTART IDENTITY"])))

(defn- with-clean-dbs [f]
  (reset-dbs!)
  (f))

(use-fixtures :once with-system)
(use-fixtures :each with-clean-dbs)

;; ═══════════════════════════════════════════════════
;; Helpers
;; ═══════════════════════════════════════════════════

(def ^:private outbox-hook (outbox/make-outbox-hook))

(defn- handle-command! [decider-map command]
  (decider/handle-with-retry! (event-store-ds) decider-map command
                              :on-events-appended outbox-hook))

(defn- wait-for-balance [account-id expected-balance]
  (let [deadline (+ (System/currentTimeMillis) 10000)]
    (loop []
      (let [result (account-projection/get-balance (read-db-ds) account-id)]
        (if (and result (= expected-balance (:balance result)))
          result
          (if (> (System/currentTimeMillis) deadline)
            result
            (do (Thread/sleep 50)
                (recur))))))))

;; ═══════════════════════════════════════════════════
;; Tests
;; ═══════════════════════════════════════════════════

(deftest commands-flow-through-async-pipeline
  ;; Open account and deposit money
  (handle-command! account/decider
                   {:command-type    :open-account
                    :stream-id       "account-async-1"
                    :idempotency-key "open-async-1"
                    :data            {:owner "Alice"}})
  (handle-command! account/decider
                   {:command-type    :deposit
                    :stream-id       "account-async-1"
                    :idempotency-key "dep-async-100"
                    :data            {:amount 100}})

  ;; Wait for the async projector to process events into the read DB
  (let [result (wait-for-balance "account-async-1" 100)]
    (is (some? result) "Balance should appear in read DB")
    (is (= 100 (:balance result)))))

(deftest multiple-accounts-project-independently
  (handle-command! account/decider
                   {:command-type    :open-account
                    :stream-id       "account-a"
                    :idempotency-key "open-a"
                    :data            {:owner "Alice"}})
  (handle-command! account/decider
                   {:command-type    :deposit
                    :stream-id       "account-a"
                    :idempotency-key "dep-a-50"
                    :data            {:amount 50}})
  (handle-command! account/decider
                   {:command-type    :open-account
                    :stream-id       "account-b"
                    :idempotency-key "open-b"
                    :data            {:owner "Bob"}})
  (handle-command! account/decider
                   {:command-type    :deposit
                    :stream-id       "account-b"
                    :idempotency-key "dep-b-200"
                    :data            {:amount 200}})

  (let [alice (wait-for-balance "account-a" 50)
        bob   (wait-for-balance "account-b" 200)]
    (is (= 50 (:balance alice)))
    (is (= 200 (:balance bob)))))

(deftest transfer-saga-projects-to-read-db
  ;; Set up two accounts
  (handle-command! account/decider
                   {:command-type    :open-account
                    :stream-id       "acct-from"
                    :idempotency-key "open-from"
                    :data            {:owner "Alice"}})
  (handle-command! account/decider
                   {:command-type    :deposit
                    :stream-id       "acct-from"
                    :idempotency-key "dep-from-500"
                    :data            {:amount 500}})
  (handle-command! account/decider
                   {:command-type    :open-account
                    :stream-id       "acct-to"
                    :idempotency-key "open-to"
                    :data            {:owner "Bob"}})
  (handle-command! account/decider
                   {:command-type    :deposit
                    :stream-id       "acct-to"
                    :idempotency-key "dep-to-100"
                    :data            {:amount 100}})

  ;; Execute transfer (uses the event store DS directly for saga)
  (saga/execute! (event-store-ds) "tx-async-1" "acct-from" "acct-to" 75)

  ;; Wait for projections
  (let [from-balance (wait-for-balance "acct-from" 425)
        to-balance   (wait-for-balance "acct-to" 175)]
    (is (= 425 (:balance from-balance)))
    (is (= 175 (:balance to-balance))))

  ;; Transfer status should also be projected — wait for completed status
  (let [deadline (+ (System/currentTimeMillis) 10000)
        transfer (loop []
                   (let [t (transfer-projection/get-transfer (read-db-ds) "transfer-tx-async-1")]
                     (if (and t (= "completed" (:status t)))
                       t
                       (if (> (System/currentTimeMillis) deadline)
                         t
                         (do (Thread/sleep 100) (recur))))))]
    (is (some? transfer) "Transfer should appear in read DB")
    (is (= "completed" (:status transfer)))))
