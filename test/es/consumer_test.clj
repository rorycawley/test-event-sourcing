(ns es.consumer-test
  "Tests for es.consumer — inbox-based consumer framework.

   Uses two Postgres testcontainers: one for the event store (with outbox),
   one for the consumer database (with inbox)."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [es.consumer :as consumer]
            [es.outbox :as outbox]
            [es.store :as store]
            [es.infra :as infra]
            [es.migrations :as migrations]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; ═══════════════════════════════════════════════════
;; Test infrastructure — two Postgres containers
;; ═══════════════════════════════════════════════════

(def ^:dynamic *event-store-ds* nil)
(def ^:dynamic *consumer-db-ds* nil)

(defn- with-system [f]
  (let [event-pg (infra/start-postgres!)
        event-ds (infra/->datasource event-pg)
        consumer-pg (infra/start-postgres!)
        consumer-ds (infra/->datasource consumer-pg)]
    (try
      (migrations/migrate! event-ds)
      (migrations/migrate! consumer-ds :migration-dir "notification-migrations")
      (binding [*event-store-ds* event-ds
                *consumer-db-ds* consumer-ds]
        (f))
      (finally
        (infra/stop-postgres! consumer-pg)
        (infra/stop-postgres! event-pg)))))

(defn- reset-dbs! []
  (jdbc/with-transaction [tx *event-store-ds*]
    (jdbc/execute-one! tx
                       ["TRUNCATE TABLE events, idempotency_keys, event_outbox
                         RESTART IDENTITY"]))
  (jdbc/with-transaction [tx *consumer-db-ds*]
    (jdbc/execute-one! tx
                       ["TRUNCATE TABLE consumer_inbox, consumer_checkpoints,
                                        notifications, projection_checkpoints
                         RESTART IDENTITY"])))

(defn- with-clean-dbs [f]
  (reset-dbs!)
  (f))

(use-fixtures :once with-system)
(use-fixtures :each with-clean-dbs)

;; ═══════════════════════════════════════════════════
;; Helpers
;; ═══════════════════════════════════════════════════

(defn- append-events-with-outbox!
  "Appends domain events and records integration events in the outbox."
  [stream-id events & {:keys [event->message]}]
  (let [current-version
        (or (-> (jdbc/execute-one! *event-store-ds*
                                   ["SELECT MAX(stream_sequence) AS v
                                     FROM events WHERE stream_id = ?"
                                    stream-id]
                                   {:builder-fn rs/as-unqualified-kebab-maps})
                :v)
            0)
        hook (outbox/make-outbox-hook (or event->message identity))]
    (store/append-events! *event-store-ds* stream-id current-version nil
                          {:command-type :test :data {}}
                          events
                          :on-events-appended hook)))

(defn- inbox-rows []
  (jdbc/execute! *consumer-db-ds*
                 ["SELECT * FROM consumer_inbox ORDER BY global_sequence ASC"]
                 {:builder-fn rs/as-unqualified-kebab-maps}))

(defn- notification-rows []
  (jdbc/execute! *consumer-db-ds*
                 ["SELECT * FROM notifications ORDER BY id ASC"]
                 {:builder-fn rs/as-unqualified-kebab-maps}))

;; ═══════════════════════════════════════════════════
;; Inbox claim tests
;; ═══════════════════════════════════════════════════

(deftest claim-returns-true-for-first-claim
  (jdbc/with-transaction [tx *consumer-db-ds*]
    (is (true? (consumer/claim! tx "test-group" 1)))))

(deftest claim-returns-false-for-duplicate
  (jdbc/with-transaction [tx *consumer-db-ds*]
    (consumer/claim! tx "test-group" 1)
    (is (false? (consumer/claim! tx "test-group" 1)))))

(deftest claim-different-groups-are-independent
  (jdbc/with-transaction [tx *consumer-db-ds*]
    (is (true? (consumer/claim! tx "group-a" 1)))
    (is (true? (consumer/claim! tx "group-b" 1)))))

(deftest high-water-mark-returns-zero-when-empty
  (is (= 0 (consumer/high-water-mark *consumer-db-ds* "test-group"))))

(deftest high-water-mark-returns-max-sequence
  (jdbc/with-transaction [tx *consumer-db-ds*]
    (consumer/claim! tx "test-group" 5)
    (consumer/claim! tx "test-group" 3)
    (consumer/claim! tx "test-group" 10))
  (is (= 10 (consumer/high-water-mark *consumer-db-ds* "test-group"))))

;; ═══════════════════════════════════════════════════
;; Catch-up checkpoint tests
;; ═══════════════════════════════════════════════════

(deftest catch-up-checkpoint-returns-zero-when-empty
  (is (= 0 (consumer/get-catch-up-checkpoint *consumer-db-ds* "test-group"))))

(deftest catch-up-checkpoint-advances-sequentially
  (jdbc/with-transaction [tx *consumer-db-ds*]
    (consumer/save-catch-up-checkpoint! tx "test-group" 5))
  (is (= 5 (consumer/get-catch-up-checkpoint *consumer-db-ds* "test-group")))
  (jdbc/with-transaction [tx *consumer-db-ds*]
    (consumer/save-catch-up-checkpoint! tx "test-group" 10))
  (is (= 10 (consumer/get-catch-up-checkpoint *consumer-db-ds* "test-group"))))

;; ═══════════════════════════════════════════════════
;; process-event! tests
;; ═══════════════════════════════════════════════════

(deftest process-event-claims-and-handles
  (let [handled (atom [])
        specs   {"test-type" (fn [_tx event]
                               (swap! handled conj event))}
        result  (consumer/process-event!
                 *consumer-db-ds* "test-group"
                 {:global-sequence 1 :event-type "test-type" :data "hello"}
                 specs)]
    (is (= :processed result))
    (is (= 1 (count @handled)))
    (is (= 1 (count (inbox-rows))))))

(deftest process-event-leaves-unknown-types-unclaimed
  (let [result (consumer/process-event!
                *consumer-db-ds* "test-group"
                {:global-sequence 1 :event-type "unknown" :data "x"}
                {"other-type" (fn [_ _])})]
    (is (= :unknown result))
    (is (= 0 (count (inbox-rows)))
        "Unknown events must NOT be claimed — they may be handled by a future deployment")))

(deftest process-event-detects-duplicates
  (let [specs {"test" (fn [_tx _event] nil)}]
    (consumer/process-event!
     *consumer-db-ds* "test-group"
     {:global-sequence 1 :event-type "test" :data "first"}
     specs)
    (let [result (consumer/process-event!
                  *consumer-db-ds* "test-group"
                  {:global-sequence 1 :event-type "test" :data "second"}
                  specs)]
      (is (= :duplicate result)))))

(deftest process-event-concurrent-claim
  ;; Simulate two workers trying to claim the same event
  (let [results (atom [])
        specs   {"test" (fn [_tx _event] nil)}
        event   {:global-sequence 1 :event-type "test"}
        futures (mapv (fn [_]
                        (future
                          (swap! results conj
                                 (consumer/process-event!
                                  *consumer-db-ds* "test-group"
                                  event specs))))
                      (range 5))]
    (doseq [f futures] @f)
    ;; Exactly one should be :processed or :skipped, rest are :duplicate
    (let [non-dupes (remove #{:duplicate} @results)]
      (is (= 1 (count non-dupes))
          "Exactly one worker should win the claim"))))

;; ═══════════════════════════════════════════════════
;; catch-up! tests
;; ═══════════════════════════════════════════════════

(deftest catch-up-processes-outbox-events
  (let [handled (atom [])
        mapper  (fn [event]
                  {:event-type      (:event-type event)
                   :global-sequence (:global-sequence event)
                   :stream-id       (:stream-id event)
                   :payload         (:payload event)})
        specs   {"test-created" (fn [_tx event]
                                  (swap! handled conj event))}]
    (append-events-with-outbox! "s-1"
                                [{:event-type "test-created" :payload {:v 1}}
                                 {:event-type "test-created" :payload {:v 2}}]
                                :event->message mapper)
    (let [n (consumer/catch-up! *event-store-ds* *consumer-db-ds*
                                "test-group" specs)]
      (is (= 2 n))
      (is (= 2 (count @handled)))
      (is (= 2 (count (inbox-rows)))))))

(deftest catch-up-is-idempotent
  (let [mapper (fn [event]
                 {:event-type      (:event-type event)
                  :global-sequence (:global-sequence event)
                  :stream-id       (:stream-id event)})
        specs  {}]
    (append-events-with-outbox! "s-2"
                                [{:event-type "test" :payload {}}]
                                :event->message mapper)
    (consumer/catch-up! *event-store-ds* *consumer-db-ds*
                        "test-group" specs)
    (let [n (consumer/catch-up! *event-store-ds* *consumer-db-ds*
                                "test-group" specs)]
      (is (= 0 n) "Second catch-up should find no new events"))))

(deftest catch-up-halts-checkpoint-on-unknown-events
  (let [mapper  (fn [event]
                  {:event-type      (:event-type event)
                   :global-sequence (:global-sequence event)
                   :stream-id       (:stream-id event)
                   :payload         (:payload event)})
        handled (atom [])
        ;; Only handle "test-created", not "test-unknown"
        specs   {"test-created" (fn [_tx event]
                                  (swap! handled conj event))}]
    ;; Append: known, unknown, known
    (append-events-with-outbox! "s-halt"
                                [{:event-type "test-created" :payload {:v 1}}
                                 {:event-type "test-unknown" :payload {:v 2}}
                                 {:event-type "test-created" :payload {:v 3}}]
                                :event->message mapper)
    (let [n (consumer/catch-up! *event-store-ds* *consumer-db-ds*
                                "test-group" specs)]
      ;; Both known events are processed (claimed via inbox)
      (is (= 2 n))
      (is (= 2 (count @handled)))
      ;; Checkpoint should halt at event 1 (before the unknown at event 2)
      (is (= 1 (consumer/get-catch-up-checkpoint *consumer-db-ds* "test-group"))
          "Checkpoint must not advance past unknown event types")
      ;; Inbox should have 2 rows (only the known events were claimed)
      (is (= 2 (count (inbox-rows)))))))

(deftest catch-up-only-publishes-mapped-events
  (let [;; Only map test-created, return nil for test-updated
        mapper  (fn [event]
                  (when (= "test-created" (:event-type event))
                    {:event-type      "my.created"
                     :global-sequence (:global-sequence event)}))
        handled (atom [])
        specs   {"my.created" (fn [_tx event]
                                (swap! handled conj event))}]
    (append-events-with-outbox! "s-3"
                                [{:event-type "test-created" :payload {}}
                                 {:event-type "test-updated" :payload {:v 1}}]
                                :event->message mapper)
    (let [n (consumer/catch-up! *event-store-ds* *consumer-db-ds*
                                "test-group" specs)]
      ;; Only 1 event in outbox (the mapped one)
      (is (= 1 n))
      (is (= 1 (count @handled))))))

;; ═══════════════════════════════════════════════════
;; Integration: notification handler via catch-up
;; ═══════════════════════════════════════════════════

(deftest notification-handler-via-catch-up
  (let [;; Simulate bank integration event mapper
        mapper (fn [event]
                 (case (:event-type event)
                   "account-opened"
                   {:event-type      "bank.account-opened"
                    :global-sequence (:global-sequence event)
                    :account-id      (:stream-id event)
                    :owner           (:owner (:payload event))}
                   "money-deposited"
                   {:event-type      "bank.money-deposited"
                    :global-sequence (:global-sequence event)
                    :account-id      (:stream-id event)
                    :amount          (:amount (:payload event))}
                   nil))
        ;; Inline notification handler specs (testing consumer framework,
        ;; not the real reactor — uses direct INSERT for simplicity)
        specs {"bank.account-opened"
               (fn [tx event]
                 (jdbc/execute-one! tx
                                    ["INSERT INTO notifications
                                       (stream_id, notification_type, recipient_id,
                                        payload, last_global_sequence)
                                     VALUES (?, ?, ?, ?::jsonb, ?)
                                     ON CONFLICT (stream_id) DO NOTHING"
                                     (str "test-welcome-" (:global-sequence event))
                                     "welcome-email"
                                     (:account-id event)
                                     (str "{\"owner\":\"" (:owner event) "\"}")
                                     (:global-sequence event)]))
               "bank.money-deposited"
               (fn [tx event]
                 (when (>= (:amount event) 10000)
                   (jdbc/execute-one! tx
                                      ["INSERT INTO notifications
                                         (stream_id, notification_type, recipient_id,
                                          payload, last_global_sequence)
                                       VALUES (?, ?, ?, ?::jsonb, ?)
                                       ON CONFLICT (stream_id) DO NOTHING"
                                       (str "test-deposit-" (:global-sequence event))
                                       "large-deposit-alert"
                                       (:account-id event)
                                       (str "{\"amount\":" (:amount event) "}")
                                       (:global-sequence event)])))}]
    ;; Append domain events with integration event mapping
    (append-events-with-outbox! "acct-1"
                                [{:event-type "account-opened"
                                  :payload {:owner "Alice"}}]
                                :event->message mapper)
    (append-events-with-outbox! "acct-1"
                                [{:event-type "money-deposited"
                                  :payload {:amount 500}}]
                                :event->message mapper)
    (append-events-with-outbox! "acct-1"
                                [{:event-type "money-deposited"
                                  :payload {:amount 15000}}]
                                :event->message mapper)
    ;; Run catch-up
    (consumer/catch-up! *event-store-ds* *consumer-db-ds*
                        "account-notifications" specs)
    ;; Verify notifications
    (let [notifs (notification-rows)]
      (is (= 2 (count notifs))
          "Should have welcome email + large deposit alert (500 is below threshold)")
      (is (= "welcome-email" (:notification-type (first notifs))))
      (is (= "acct-1" (:recipient-id (first notifs))))
      (is (= "large-deposit-alert" (:notification-type (second notifs)))))))
