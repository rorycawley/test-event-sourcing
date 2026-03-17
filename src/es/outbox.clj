(ns es.outbox
  "Transactional outbox for reliable event publishing.

   Events are written to the event_outbox table in the same transaction
   as the event store append. A simple polling loop reads unpublished
   rows and publishes them to RabbitMQ.

   This guarantees at-least-once delivery: if the process crashes between
   append and publish, the outbox row survives and the poller retries."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.data.json :as json]
            [es.store :as store]))

;; ——— Outbox writing (called within store transaction) ———

(defn record!
  "Records a global_sequence in the outbox. Called within the same
   transaction as append-events!."
  [tx global-sequence]
  (jdbc/execute-one! tx
                     ["INSERT INTO event_outbox (global_sequence) VALUES (?)"
                      global-sequence]))

(defn make-outbox-hook
  "Returns an on-events-appended hook function suitable for
   passing to es.store/append-events! or es.decider/handle!.

   Usage:
     (store/append-events! ds stream-id version key cmd events
       :on-events-appended (outbox/make-outbox-hook))"
  []
  (fn [tx global-sequences]
    (doseq [gs global-sequences]
      (record! tx gs))))

;; ——— Outbox polling (reads unpublished, publishes to RabbitMQ) ———

(defn poll-and-publish!
  "Single poll cycle: reads unpublished outbox rows, loads the
   corresponding events, publishes each to RabbitMQ, marks as published.

   Uses FOR UPDATE SKIP LOCKED so multiple pollers can run safely.
   Returns the count of events published."
  [ds publish-fn batch-size]
  (jdbc/with-transaction [tx ds]
    (let [rows (jdbc/execute! tx
                              ["SELECT o.id, o.global_sequence,
                                       e.stream_id, e.event_type,
                                       e.event_version, e.payload
                                FROM event_outbox o
                                JOIN events e ON e.global_sequence = o.global_sequence
                                WHERE o.published_at IS NULL
                                ORDER BY o.id ASC
                                LIMIT ?
                                FOR UPDATE OF o SKIP LOCKED"
                               batch-size]
                              {:builder-fn rs/as-unqualified-kebab-maps})]
      (doseq [row rows]
        (let [payload (store/<-pgobject (:payload row))
              message (json/write-str
                       {:global-sequence (:global-sequence row)
                        :stream-id       (:stream-id row)
                        :event-type      (:event-type row)
                        :event-version   (:event-version row)
                        :payload         payload})]
          (publish-fn message)
          (jdbc/execute-one! tx
                             ["UPDATE event_outbox
                               SET published_at = NOW()
                               WHERE id = ?"
                              (:id row)])))
      (count rows))))

;; ——— Poller loop ———

(defn start-poller!
  "Starts a daemon thread that polls the outbox and publishes events.

   publish-fn: (fn [message-string]) — called for each event
   opts:
     :poll-interval-ms — sleep between polls (default 100)
     :batch-size       — max events per poll (default 100)
     :on-error         — (fn [exception]) error handler (default: print)

   Returns {:thread Thread, :running (atom true)}."
  [ds publish-fn & {:keys [poll-interval-ms batch-size on-error]
                    :or   {poll-interval-ms 100
                           batch-size       100
                           on-error         (fn [e] (binding [*out* *err*]
                                                      (println "Outbox poller error:" (.getMessage e))))}}]
  (let [running (atom true)
        thread  (Thread.
                 (fn []
                   (while @running
                     (try
                       (poll-and-publish! ds publish-fn batch-size)
                       (catch Exception e
                         (on-error e)))
                     (try
                       (Thread/sleep (long poll-interval-ms))
                       (catch InterruptedException _
                         (reset! running false))))))]
    (.setDaemon thread true)
    (.setName thread "outbox-poller")
    (.start thread)
    {:thread thread :running running}))

(defn stop-poller!
  "Stops the outbox poller and waits for it to finish."
  [{:keys [thread running]}]
  (reset! running false)
  (.interrupt ^Thread thread)
  (.join ^Thread thread 5000))
