(ns es.outbox-test
  "Tests for es.outbox — transactional outbox for event publishing."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [clojure.data.json :as json]
            [es.outbox :as outbox]
            [es.store :as store]
            [es.infra :as infra]
            [es.migrations :as migrations]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

;; ═══════════════════════════════════════════════════
;; Test infrastructure
;; ═══════════════════════════════════════════════════

(def ^:dynamic *ds* nil)

(defn- with-system [f]
  (let [pg (infra/start-postgres!)
        ds (infra/->datasource pg)]
    (try
      (migrations/migrate! ds)
      (binding [*ds* ds]
        (f))
      (finally
        (infra/stop-postgres! pg)))))

(defn- reset-db! []
  (jdbc/with-transaction [tx *ds*]
    (jdbc/execute-one! tx
                       ["TRUNCATE TABLE event_outbox, events, idempotency_keys
                         RESTART IDENTITY"])))

(defn- with-clean-db [f]
  (reset-db!)
  (f))

(use-fixtures :once with-system)
(use-fixtures :each with-clean-db)

(defn- append-test-events! [stream-id events & {:keys [on-events-appended]}]
  (let [current-version
        (or (-> (jdbc/execute-one! *ds*
                                   ["SELECT MAX(stream_sequence) AS v FROM events WHERE stream_id = ?"
                                    stream-id]
                                   {:builder-fn rs/as-unqualified-kebab-maps})
                :v)
            0)]
    (store/append-events! *ds* stream-id current-version nil
                          {:command-type :test :data {}}
                          events
                          :on-events-appended on-events-appended)))

(defn- outbox-rows []
  (jdbc/execute! *ds*
                 ["SELECT * FROM event_outbox ORDER BY id ASC"]
                 {:builder-fn rs/as-unqualified-kebab-maps}))

;; ═══════════════════════════════════════════════════
;; record! tests
;; ═══════════════════════════════════════════════════

(deftest record-writes-outbox-row
  (jdbc/with-transaction [tx *ds*]
    ;; Insert a dummy event first so the FK-like relationship holds
    (jdbc/execute-one! tx
                       ["INSERT INTO events (id, stream_id, stream_sequence, event_type, payload)
                         VALUES (?, 'test', 1, 'test', '{}'::jsonb)"
                        (store/uuid-v7)])
    (outbox/record! tx 1 {:event-type "test" :global-sequence 1}))
  (let [rows (outbox-rows)]
    (is (= 1 (count rows)))
    (is (= 1 (:global-sequence (first rows))))
    (is (nil? (:published-at (first rows))))
    (is (some? (:message (first rows))))))

;; ═══════════════════════════════════════════════════
;; make-outbox-hook tests
;; ═══════════════════════════════════════════════════

(deftest outbox-hook-records-all-sequences
  (let [hook (outbox/make-outbox-hook)]
    (append-test-events! "s-1"
                         [{:event-type "test-created" :payload {}}
                          {:event-type "test-updated" :payload {:v 1}}]
                         :on-events-appended hook)
    (let [rows (outbox-rows)]
      (is (= 2 (count rows)))
      (is (= [1 2] (mapv :global-sequence rows))))))

(deftest outbox-hook-not-called-on-idempotent-replay
  (let [hook (outbox/make-outbox-hook)]
    (store/append-events! *ds* "s-2" 0 "idem-key-1"
                          {:command-type :test :data {}}
                          [{:event-type "test-created" :payload {}}]
                          :on-events-appended hook)
    ;; Replay with same idempotency key
    (store/append-events! *ds* "s-2" 1 "idem-key-1"
                          {:command-type :test :data {}}
                          [{:event-type "test-created" :payload {}}]
                          :on-events-appended hook)
    (is (= 1 (count (outbox-rows)))
        "Idempotent replay should not create additional outbox rows")))

;; ═══════════════════════════════════════════════════
;; poll-and-publish! tests
;; ═══════════════════════════════════════════════════

(deftest poll-publishes-unpublished-events
  (let [hook     (outbox/make-outbox-hook)
        messages (atom [])]
    (append-test-events! "s-3"
                         [{:event-type "test-created" :payload {:name "a"}}
                          {:event-type "test-updated" :payload {:v 1}}]
                         :on-events-appended hook)
    (let [n (outbox/poll-and-publish! *ds*
                                      (fn [msg] (swap! messages conj msg))
                                      100)]
      (is (= 2 n))
      (is (= 2 (count @messages)))
      ;; Verify message format — now reads from stored message column
      (let [parsed (json/read-str (first @messages) :key-fn keyword)]
        (is (= 1 (:global-sequence parsed)))
        (is (= "s-3" (:stream-id parsed)))
        (is (= "test-created" (:event-type parsed)))
        (is (= {:name "a"} (:payload parsed)))))))

(deftest poll-marks-events-as-published
  (let [hook (outbox/make-outbox-hook)]
    (append-test-events! "s-4"
                         [{:event-type "test-created" :payload {}}]
                         :on-events-appended hook)
    (outbox/poll-and-publish! *ds* (fn [_] nil) 100)
    (let [rows (outbox-rows)]
      (is (= 1 (count rows)))
      (is (some? (:published-at (first rows)))))))

(deftest poll-returns-zero-when-nothing-to-publish
  (is (= 0 (outbox/poll-and-publish! *ds* (fn [_] nil) 100))))

(deftest poll-does-not-republish-already-published
  (let [hook     (outbox/make-outbox-hook)
        messages (atom [])]
    (append-test-events! "s-5"
                         [{:event-type "test-created" :payload {}}]
                         :on-events-appended hook)
    (outbox/poll-and-publish! *ds* (fn [msg] (swap! messages conj msg)) 100)
    ;; Second poll should find nothing
    (let [n (outbox/poll-and-publish! *ds*
                                      (fn [msg] (swap! messages conj msg))
                                      100)]
      (is (= 0 n))
      (is (= 1 (count @messages))))))

(deftest poll-respects-batch-size
  (let [hook     (outbox/make-outbox-hook)
        messages (atom [])]
    (append-test-events! "s-6"
                         [{:event-type "test-created" :payload {}}
                          {:event-type "test-updated" :payload {:v 1}}
                          {:event-type "test-updated" :payload {:v 2}}]
                         :on-events-appended hook)
    ;; Poll with batch-size 2
    (let [n1 (outbox/poll-and-publish! *ds*
                                       (fn [msg] (swap! messages conj msg))
                                       2)]
      (is (= 2 n1))
      ;; Second poll gets the remaining one
      (let [n2 (outbox/poll-and-publish! *ds*
                                         (fn [msg] (swap! messages conj msg))
                                         2)]
        (is (= 1 n2))
        (is (= 3 (count @messages)))))))

;; ═══════════════════════════════════════════════════
;; Integration event mapper tests
;; ═══════════════════════════════════════════════════

(deftest outbox-hook-with-custom-mapper
  (let [mapper (fn [event]
                 (when (= "test-created" (:event-type event))
                   {:event-type "my-module.created"
                    :global-sequence (:global-sequence event)
                    :id (:stream-id event)}))
        hook   (outbox/make-outbox-hook mapper)
        messages (atom [])]
    (append-test-events! "s-mapper"
                         [{:event-type "test-created" :payload {:name "a"}}
                          {:event-type "test-updated" :payload {:v 1}}]
                         :on-events-appended hook)
    ;; Only test-created should be in the outbox (mapper returns nil for test-updated)
    (let [rows (outbox-rows)]
      (is (= 1 (count rows))
          "Only events where mapper returns non-nil should be recorded"))
    ;; Verify the published message shape
    (outbox/poll-and-publish! *ds* (fn [msg] (swap! messages conj msg)) 100)
    (let [parsed (json/read-str (first @messages) :key-fn keyword)]
      (is (= "my-module.created" (:event-type parsed)))
      (is (= "s-mapper" (:id parsed))))))

;; ═══════════════════════════════════════════════════
;; Poller lifecycle tests
;; ═══════════════════════════════════════════════════

(deftest poller-starts-and-stops-cleanly
  (let [poller (outbox/start-poller! *ds* (fn [_] nil)
                                     :poll-interval-ms 50)]
    (is (some? (:thread poller)))
    (is (true? @(:running poller)))
    (outbox/stop-poller! poller)
    (is (false? @(:running poller)))))

(deftest hook-failure-rolls-back-events
  (let [failing-hook (fn [_tx _seqs]
                       (throw (ex-info "Hook exploded" {})))]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo #"Hook exploded"
                          (store/append-events! *ds* "s-hook-fail" 0 nil
                                                {:command-type :test :data {}}
                                                [{:event-type "test-created" :payload {}}]
                                                :on-events-appended failing-hook)))
    (is (empty? (store/load-stream *ds* "s-hook-fail"))
        "Events should be rolled back when hook throws")
    (is (empty? (outbox-rows))
        "No outbox rows should exist when hook throws")))

(deftest publish-failure-does-not-mark-as-published
  (let [hook     (outbox/make-outbox-hook)
        call-count (atom 0)]
    (append-test-events! "s-pub-fail"
                         [{:event-type "test-created" :payload {}}]
                         :on-events-appended hook)
    ;; publish-fn throws on first call
    (is (thrown? Exception
                 (outbox/poll-and-publish! *ds*
                                           (fn [_msg]
                                             (swap! call-count inc)
                                             (throw (ex-info "RabbitMQ down" {})))
                                           100)))
    ;; Row should still be unpublished
    (let [rows (outbox-rows)]
      (is (= 1 (count rows)))
      (is (nil? (:published-at (first rows)))
          "Outbox row should remain unpublished when publish-fn throws"))))

(deftest poll-skips-null-message-rows
  ;; Simulates a pre-migration outbox row that has no message column value.
  ;; The poller must skip it (not publish JSON null) and leave it unpublished.
  (jdbc/with-transaction [tx *ds*]
    (jdbc/execute-one! tx
                       ["INSERT INTO events (id, stream_id, stream_sequence, event_type, payload)
                         VALUES (?, 'test-null', 1, 'test', '{}'::jsonb)"
                        (store/uuid-v7)])
    (jdbc/execute-one! tx
                       ["INSERT INTO event_outbox (global_sequence, message)
                         VALUES (1, NULL)"]))
  (let [messages (atom [])
        n (outbox/poll-and-publish! *ds*
                                    (fn [msg] (swap! messages conj msg))
                                    100)]
    (is (= 0 n) "NULL-message rows must not be published")
    (is (empty? @messages))
    ;; Row should remain unpublished so migration backfill can fix it
    (let [rows (outbox-rows)]
      (is (nil? (:published-at (first rows)))))))

(deftest poller-publishes-events-asynchronously
  (let [hook     (outbox/make-outbox-hook)
        messages (atom [])
        poller   (outbox/start-poller! *ds*
                                       (fn [msg] (swap! messages conj msg))
                                       :poll-interval-ms 20)]
    (try
      (append-test-events! "s-7"
                           [{:event-type "test-created" :payload {:async true}}]
                           :on-events-appended hook)
      ;; Wait for poller to pick it up
      (Thread/sleep 200)
      (is (= 1 (count @messages)))
      (let [parsed (json/read-str (first @messages) :key-fn keyword)]
        (is (= "test-created" (:event-type parsed))))
      (finally
        (outbox/stop-poller! poller)))))
