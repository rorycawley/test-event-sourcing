(ns modules.notification.domain.delivery-test
  "Tests for the notification aggregate and reactor.

   Pure aggregate tests (no I/O) verify the state machine:
   request → pending → sent/failed lifecycle with retry logic.

   Integration tests verify the reactor issues commands to the
   event store when processing integration events."
  (:require [clojure.test :refer [deftest is use-fixtures testing]]
            [modules.notification.domain.delivery :as notification]
            [modules.notification.infra.reactor :as reactor]
            [es.store :as store]
            [es.infra :as infra]
            [es.migrations :as migrations]
            [next.jdbc :as jdbc]))

;; ═══════════════════════════════════════════════════
;; Pure aggregate tests — no I/O, no database
;; ═══════════════════════════════════════════════════

(deftest request-notification-from-not-found
  (let [events (notification/decide
                {:command-type :request-notification
                 :data         {:notification-type "welcome-email"
                                :recipient-id      "acct-1"
                                :payload           {:message "Hello"}}}
                {:status :not-found :retry-count 0})]
    (is (= 1 (count events)))
    (is (= "notification-requested" (:event-type (first events))))
    (is (= "welcome-email" (get-in (first events) [:payload :notification-type])))))

(deftest request-notification-is-idempotent-when-pending
  (let [events (notification/decide
                {:command-type :request-notification
                 :data         {:notification-type "welcome-email"
                                :recipient-id      "acct-1"
                                :payload           {:message "Hello"}}}
                {:status :pending :retry-count 0})]
    (is (= 0 (count events))
        "Should emit no events when already requested")))

(deftest evolve-notification-requested
  (let [state (notification/evolve
               {:status :not-found :retry-count 0}
               {:event-type    "notification-requested"
                :event-version 1
                :payload       {:notification-type "welcome-email"
                                :recipient-id      "acct-1"
                                :payload           {:msg "hi"}}})]
    (is (= :pending (:status state)))
    (is (= "welcome-email" (:notification-type state)))
    (is (= "acct-1" (:recipient-id state)))))

(deftest mark-sent-from-pending
  (let [events (notification/decide
                {:command-type :mark-sent
                 :data         {}}
                {:status :pending :retry-count 0})]
    (is (= 1 (count events)))
    (is (= "notification-sent" (:event-type (first events))))))

(deftest mark-sent-rejects-non-pending
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Notification not pending"
                        (notification/decide
                         {:command-type :mark-sent :data {}}
                         {:status :sent :retry-count 0}))))

(deftest mark-sent-rejects-not-found
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Notification not pending"
                        (notification/decide
                         {:command-type :mark-sent :data {}}
                         {:status :not-found :retry-count 0}))))

(deftest mark-failed-retries-under-limit
  (let [events (notification/decide
                {:command-type :mark-failed
                 :data         {:reason "SMTP timeout"}}
                {:status :pending :retry-count 0})]
    (is (= 1 (count events)))
    (is (= "notification-failed" (:event-type (first events)))
        "First failure should retry, not abandon")))

(deftest mark-failed-abandons-at-max-retries
  ;; max-delivery-attempts = 3, so with retry-count = 2 (two prior
  ;; failures), the third failure should abandon.
  (let [events (notification/decide
                {:command-type :mark-failed
                 :data         {:reason "SMTP timeout"}}
                {:status :pending :retry-count 2})]
    (is (= 1 (count events)))
    (is (= "notification-abandoned" (:event-type (first events)))
        "Third failure should abandon the notification")))

(deftest mark-failed-rejects-non-pending
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"Notification not pending"
                        (notification/decide
                         {:command-type :mark-failed
                          :data         {:reason "error"}}
                         {:status :failed :retry-count 3}))))

(deftest full-lifecycle-request-to-sent
  (let [state-0 {:status :not-found :retry-count 0}
        events  (notification/decide
                 {:command-type :request-notification
                  :data         {:notification-type "alert"
                                 :recipient-id      "user-1"
                                 :payload           {:x 1}}}
                 state-0)
        state-1 (reduce notification/evolve state-0 events)
        _       (is (= :pending (:status state-1)))
        sent-events (notification/decide
                     {:command-type :mark-sent :data {}}
                     state-1)
        state-2 (reduce notification/evolve state-1 sent-events)]
    (is (= :sent (:status state-2)))
    (is (= 0 (:retry-count state-2)))))

(deftest full-lifecycle-request-to-abandoned
  (let [state-0 {:status :not-found :retry-count 0}
        events  (notification/decide
                 {:command-type :request-notification
                  :data         {:notification-type "alert"
                                 :recipient-id      "user-1"
                                 :payload           {:x 1}}}
                 state-0)
        state-1   (reduce notification/evolve state-0 events)
        fail-cmd  {:command-type :mark-failed :data {:reason "error"}}
        ;; Failure 1
        f1-events (notification/decide fail-cmd state-1)
        state-2   (reduce notification/evolve state-1 f1-events)
        ;; Failure 2
        f2-events (notification/decide fail-cmd state-2)
        state-3   (reduce notification/evolve state-2 f2-events)
        ;; Failure 3 — should abandon
        f3-events (notification/decide fail-cmd state-3)
        state-4   (reduce notification/evolve state-3 f3-events)]
    (is (= "notification-failed" (:event-type (first f1-events))))
    (is (= "notification-failed" (:event-type (first f2-events))))
    (is (= "notification-abandoned" (:event-type (first f3-events))))
    (is (= :failed (:status state-4)))
    (is (= 3 (:retry-count state-4))
        "retry-count should reflect all three delivery attempts")))

(deftest request-notification-rejects-terminal-sent
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"terminal"
                        (notification/decide
                         {:command-type :request-notification
                          :data         {:notification-type "alert"
                                         :recipient-id      "user-1"
                                         :payload           {:x 1}}}
                         {:status :sent :retry-count 0}))))

(deftest request-notification-rejects-terminal-failed
  (is (thrown-with-msg? clojure.lang.ExceptionInfo
                        #"terminal"
                        (notification/decide
                         {:command-type :request-notification
                          :data         {:notification-type "alert"
                                         :recipient-id      "user-1"
                                         :payload           {:x 1}}}
                         {:status :failed :retry-count 3}))))

;; ═══════════════════════════════════════════════════
;; Integration tests — reactor with event store
;; ═══════════════════════════════════════════════════

(def ^:dynamic *ds* nil)

(defn- with-event-store [f]
  (let [pg (infra/start-postgres!)
        ds (infra/->datasource pg)]
    (try
      (migrations/migrate! ds)
      (binding [*ds* ds]
        (f))
      (finally
        (infra/stop-postgres! pg)))))

(defn- reset-event-store! []
  (jdbc/with-transaction [tx *ds*]
    (jdbc/execute-one! tx
                       ["TRUNCATE TABLE events, idempotency_keys, event_outbox
                         RESTART IDENTITY"])))

(defn- with-clean-store [f]
  (reset-event-store!)
  (f))

(use-fixtures :once with-event-store)
(use-fixtures :each with-clean-store)

(deftest reactor-creates-notification-on-account-opened
  (let [specs (reactor/make-handler-specs *ds*)
        handler (get specs "bank.account-opened")]
    (handler nil {:event-type      "bank.account-opened"
                  :global-sequence 1
                  :account-id      "acct-1"
                  :owner           "Alice"})
    ;; Verify notification aggregate was created in the event store
    (let [events (store/load-stream *ds* "notification-welcome-email-1")]
      (is (= 1 (count events)))
      (is (= "notification-requested" (:event-type (first events))))
      (is (= "welcome-email"
             (get-in (first events) [:payload :notification-type])))
      (is (= "acct-1"
             (get-in (first events) [:payload :recipient-id]))))))

(deftest reactor-creates-notification-on-large-deposit
  (let [specs (reactor/make-handler-specs *ds*)
        handler (get specs "bank.money-deposited")]
    (handler nil {:event-type      "bank.money-deposited"
                  :global-sequence 5
                  :account-id      "acct-1"
                  :amount          15000})
    (let [events (store/load-stream *ds* "notification-large-deposit-alert-5")]
      (is (= 1 (count events)))
      (is (= "notification-requested" (:event-type (first events))))
      (is (= "large-deposit-alert"
             (get-in (first events) [:payload :notification-type]))))))

(deftest reactor-ignores-small-deposit
  (let [specs (reactor/make-handler-specs *ds*)
        handler (get specs "bank.money-deposited")]
    (handler nil {:event-type      "bank.money-deposited"
                  :global-sequence 3
                  :account-id      "acct-1"
                  :amount          500})
    (let [events (store/load-stream *ds* "notification-large-deposit-alert-3")]
      (is (= 0 (count events))
          "Small deposits should not create notifications"))))

(deftest reactor-is-idempotent
  (let [specs (reactor/make-handler-specs *ds*)
        handler (get specs "bank.account-opened")
        event   {:event-type      "bank.account-opened"
                 :global-sequence 1
                 :account-id      "acct-1"
                 :owner           "Alice"}]
    ;; Process same event twice
    (handler nil event)
    (handler nil event)
    ;; Should still have exactly one notification event
    (let [events (store/load-stream *ds* "notification-welcome-email-1")]
      (is (= 1 (count events))
          "Idempotency key should prevent duplicate notification"))))

(deftest reactor-handles-multiple-events
  (testing "Multiple integration events create separate notification aggregates"
    (let [specs (reactor/make-handler-specs *ds*)]
      ;; Account opened
      ((get specs "bank.account-opened")
       nil {:event-type "bank.account-opened"
            :global-sequence 1 :account-id "acct-1" :owner "Alice"})
      ;; Large deposit
      ((get specs "bank.money-deposited")
       nil {:event-type "bank.money-deposited"
            :global-sequence 2 :account-id "acct-1" :amount 50000})
      ;; Verify two separate notification streams
      (is (= 1 (count (store/load-stream *ds* "notification-welcome-email-1"))))
      (is (= 1 (count (store/load-stream *ds* "notification-large-deposit-alert-2")))))))
