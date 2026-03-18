(ns es.outbox
  "Transactional outbox for reliable event publishing.

   Events are written to the event_outbox table in the same transaction
   as the event store append. A simple polling loop reads unpublished
   rows and publishes them to RabbitMQ.

   This guarantees at-least-once delivery: if the process crashes between
   append and publish, the outbox row survives and the poller retries.

   Ordering
   ────────
   Events are published in global_sequence order within a single poller.
   If multiple pollers run concurrently (via FOR UPDATE SKIP LOCKED),
   inter-poller ordering is NOT guaranteed. Consumers must be idempotent
   and tolerate out-of-order delivery."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
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

   Each row is published and marked individually: if publish-fn throws
   on row N, rows 1..N-1 are already committed (not rolled back).
   This avoids re-publishing events that were already sent to RabbitMQ.

   The SELECT uses FOR UPDATE SKIP LOCKED to avoid contention between
   concurrent pollers. However, publish happens after the SELECT
   transaction releases its locks, so a second poller can select and
   publish the same row before the first poller's UPDATE runs. The
   UPDATE's published_at IS NULL guard prevents double-marking but
   not double-publishing. Consumers must be idempotent.

   Returns the count of events published."
  [ds publish-fn batch-size]
  (let [rows (jdbc/with-transaction [tx ds]
               (jdbc/execute! tx
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
                              {:builder-fn rs/as-unqualified-kebab-maps}))
        ;; Publish outside the SELECT transaction, then mark each row
        ;; in its own transaction so that a failure on row N doesn't
        ;; roll back the already-published rows 1..N-1.
        ;; Each mark transaction uses a published_at IS NULL guard
        ;; so that if two pollers select the same row, the second
        ;; poller's UPDATE is a no-op. Note: the publish above can
        ;; still produce duplicates — consumers must be idempotent.
        published
        (reduce
         (fn [acc row]
           (let [payload (store/<-pgobject (:payload row))
                 message (json/write-str
                          {:global-sequence (:global-sequence row)
                           :stream-id       (:stream-id row)
                           :event-type      (:event-type row)
                           :event-version   (:event-version row)
                           :payload         payload})]
             (publish-fn message)
             (jdbc/with-transaction [tx ds]
               (jdbc/execute-one! tx
                                  ["UPDATE event_outbox
                                    SET published_at = NOW()
                                    WHERE id = ?
                                      AND published_at IS NULL"
                                   (:id row)]))
             (inc acc)))
         0
         rows)]
    published))

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
                           on-error         (fn [e] (log/error e "Outbox poller error"))}}]
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
  "Stops the outbox poller and waits up to 5 seconds for it to finish.
   Logs a warning if the thread is still alive after the timeout."
  [{:keys [thread running]}]
  (reset! running false)
  (.interrupt ^Thread thread)
  (.join ^Thread thread 5000)
  (when (.isAlive ^Thread thread)
    (log/warn "Outbox poller thread did not stop within 5 seconds")))
