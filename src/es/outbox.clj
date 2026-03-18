(ns es.outbox
  "Transactional outbox for reliable integration event publishing.

   Domain events are written to the events table. The outbox hook
   transforms selected domain events into integration events and
   stores them in the event_outbox table (same transaction). A
   polling loop reads unpublished rows and publishes them to RabbitMQ.

   Integration events are the module's public API — they decouple
   the publishing module from consumers. Consumers see only integration
   events, never raw domain events. Not all domain events need to
   become integration events; the mapper controls which are published.

   This guarantees at-least-once delivery: if the process crashes
   between append and publish, the outbox row survives and the
   poller retries.

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
            [es.store :as store])
  (:import [org.postgresql.util PGobject]))

;; ——— Outbox writing (called within store transaction) ———

(defn record!
  "Records an integration event in the outbox. Called within the same
   transaction as append-events!.

   global-sequence: the event's global_sequence (for ordering)
   message:         the integration event map to publish"
  [tx global-sequence message]
  (jdbc/execute-one! tx
                     ["INSERT INTO event_outbox (global_sequence, message)
                       VALUES (?, ?::jsonb)"
                      global-sequence
                      (json/write-str message)]))

(defn- raw-event-mapper
  "Default mapper that publishes raw event data as-is.
   Used when no integration event mapper is provided."
  [event]
  {:global-sequence (:global-sequence event)
   :stream-id       (:stream-id event)
   :event-type      (:event-type event)
   :event-version   (:event-version event)
   :payload         (:payload event)})

(defn make-outbox-hook
  "Returns an on-events-appended hook that records integration events.

   event->message: (fn [event-row] integration-event-map-or-nil)
     Transforms a domain event into an integration event for publishing.
     Return nil to skip publishing for that event.

   The hook re-reads newly appended events within the same transaction
   to build integration events. This runs inside the append transaction,
   so the event data is guaranteed to be visible.

   Usage:
     ;; With integration event mapper:
     (store/append-events! ds stream-id version key cmd events
       :on-events-appended (outbox/make-outbox-hook my-mapper))

     ;; With default raw mapper (backward compat):
     (store/append-events! ds stream-id version key cmd events
       :on-events-appended (outbox/make-outbox-hook))"
  ([] (make-outbox-hook raw-event-mapper))
  ([event->message]
   (fn [tx global-sequences]
     (doseq [gs global-sequences]
       (let [event (-> (jdbc/execute-one! tx
                                          ["SELECT global_sequence, stream_id,
                                                   event_type, event_version, payload
                                            FROM events
                                            WHERE global_sequence = ?"
                                           gs]
                                          {:builder-fn rs/as-unqualified-kebab-maps})
                       (update :payload store/<-pgobject))
             message (event->message event)]
         (when message
           (record! tx gs message)))))))

;; ——— Outbox polling (reads unpublished, publishes to RabbitMQ) ———

(defn- message->str
  "Converts a message column value (PGobject JSONB) to a JSON string."
  [message-value]
  (if (instance? PGobject message-value)
    (.getValue ^PGobject message-value)
    (json/write-str message-value)))

(defn poll-and-publish!
  "Single poll cycle: reads unpublished outbox rows and publishes
   their stored integration event messages to RabbitMQ.

   Each row is published and marked individually: if publish-fn throws
   on row N, rows 1..N-1 are already committed (not rolled back).

   The SELECT uses FOR UPDATE SKIP LOCKED to avoid contention between
   concurrent pollers. The UPDATE's published_at IS NULL guard prevents
   double-marking but not double-publishing. Consumers must be idempotent.

   Returns the count of events published."
  [ds publish-fn batch-size]
  (let [rows (jdbc/with-transaction [tx ds]
               (jdbc/execute! tx
                              ["SELECT o.id, o.global_sequence, o.message
                                FROM event_outbox o
                                WHERE o.published_at IS NULL
                                ORDER BY o.id ASC
                                LIMIT ?
                                FOR UPDATE OF o SKIP LOCKED"
                               batch-size]
                              {:builder-fn rs/as-unqualified-kebab-maps}))
        published
        (reduce
         (fn [acc row]
           (if (nil? (:message row))
             (do (log/warn "Skipping outbox row with NULL message"
                           {:id (:id row)
                            :global-sequence (:global-sequence row)})
                 acc)
             (let [message (message->str (:message row))]
               (publish-fn message)
               (jdbc/with-transaction [tx ds]
                 (jdbc/execute-one! tx
                                    ["UPDATE event_outbox
                                      SET published_at = NOW()
                                      WHERE id = ?
                                        AND published_at IS NULL"
                                     (:id row)]))
               (inc acc))))
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
     :on-error         — (fn [exception]) error handler (default: log)

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
