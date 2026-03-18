(ns es.async-projection
  "Async projection engine for separate read databases.

   Like es.projection but designed for CQRS with independent read stores:
   events are read from the event-store database and projections are
   written to a separate read-model database.

   Since the two databases can't share a transaction, idempotency relies
   on the same last_global_sequence guards that projection handlers
   already implement. This makes duplicate processing safe (at-least-once).

   Concurrency safety
   ──────────────────
   process-new-events! and rebuild! acquire a Postgres advisory lock
   (on the read database, keyed by projection-name) to serialise access.
   This prevents concurrent catch-ups from regressing the checkpoint and
   protects rebuild! from racing with normal processing.

   Global_sequence gaps
   ────────────────────
   global_sequence is a Postgres BIGSERIAL and may have gaps after
   transaction rollbacks. Projections use it as a cursor (> checkpoint),
   so gaps are harmless — they're simply skipped.

   Poison event handling
   ─────────────────────
   If a projection handler throws on a specific event, repeated catch-ups
   would be stuck at that event forever. To prevent this:
   - Handler failures are annotated with the failing event's global_sequence
   - Consecutive failures are only counted when the same event fails
   - Infrastructure failures (DB outages, connection errors) do NOT count
     toward the poison threshold — only handler-specific errors do
   - After :max-consecutive-failures (default 5), skip-poison-event!
     processes any valid events before the poison, then skips the poison
   - Skipped events are logged via :on-poison for alerting/investigation
   - A full rebuild! will replay all events including previously skipped ones"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [es.store :as store]
            [es.projection-kit :as kit]
            [es.rabbitmq :as rabbitmq]))

;; ——— Two-datasource projection ———

(defn- acquire-projection-lock!
  "Acquires a transaction-scoped advisory lock on the read database,
   keyed by projection-name. Serialises all projection writes for a
   given projection to prevent concurrent catch-ups from racing."
  [tx projection-name]
  (jdbc/execute-one! tx
                     ["SELECT pg_advisory_xact_lock(
                            ('x' || substr(md5(?), 1, 16))::bit(64)::bigint
                          )"
                      (str "projection:" projection-name)]))

(defn- read-checkpoint
  "Reads the current checkpoint from the read database."
  [read-db-ds projection-name]
  (or (-> (jdbc/execute-one! read-db-ds
                             ["SELECT last_global_sequence
                               FROM projection_checkpoints
                               WHERE projection_name = ?"
                              projection-name]
                             {:builder-fn rs/as-unqualified-kebab-maps})
          :last-global-sequence)
      0))

(defn- read-new-events
  "Reads events from the event store since the given checkpoint.
   Reads at most batch-size events to avoid unbounded memory usage."
  [event-store-ds checkpoint batch-size]
  (mapv (fn [row]
          (-> row
              (update :payload store/<-pgobject)
              (update :event-version #(or % 1))))
        (jdbc/execute! event-store-ds
                       ["SELECT global_sequence, stream_id, stream_sequence,
                               event_type, event_version, payload
                        FROM events
                        WHERE global_sequence > ?
                        ORDER BY global_sequence ASC
                        LIMIT ?"
                        checkpoint
                        batch-size]
                       {:builder-fn rs/as-unqualified-kebab-maps})))

(defn- project-event!
  "Applies one event to the read model via the handler function."
  [tx event {:keys [projection-name handler]}]
  (handler
   tx
   event
   {:projection-name            projection-name
    :ensure-single-row-updated! (partial kit/ensure-single-row-updated! projection-name)}))

(defn- advance-checkpoint!
  "Advances the checkpoint for a projection to the given global sequence."
  [tx projection-name global-sequence]
  (jdbc/execute-one! tx
                     ["INSERT INTO projection_checkpoints
                         (projection_name, last_global_sequence)
                       VALUES (?, ?)
                       ON CONFLICT (projection_name)
                       DO UPDATE SET last_global_sequence = GREATEST(
                         projection_checkpoints.last_global_sequence,
                         EXCLUDED.last_global_sequence)"
                      projection-name
                      global-sequence]))

(def default-batch-size
  "Default number of events to read per catch-up cycle."
  1000)

(defn process-new-events!
  "Reads events from the event store and applies them to the read database.

   Acquires an advisory lock on the read database (keyed by projection-name)
   to serialise concurrent catch-ups. The checkpoint is read inside the
   lock to prevent two callers from seeing the same checkpoint.

   event-store-ds: datasource for the event store (read-only access)
   read-db-ds:     datasource for the read model (read-write access)
   config:         {:projection-name, :read-model-tables, :handler}
   opts:
     :batch-size — max events per catch-up cycle (default 1000)

   Returns the count of events processed."
  [event-store-ds read-db-ds {:keys [projection-name] :as config}
   & {:keys [batch-size] :or {batch-size default-batch-size}}]
  ;; Acquire advisory lock, read checkpoint, and apply events all within
  ;; a single transaction on the read database. This prevents concurrent
  ;; catch-ups from reading the same checkpoint and regressing it.
  (jdbc/with-transaction [tx read-db-ds]
    (acquire-projection-lock! tx projection-name)
    (let [checkpoint (read-checkpoint tx projection-name)
          new-events (read-new-events event-store-ds checkpoint batch-size)]
      (when (seq new-events)
        (doseq [event new-events]
          (try
            (project-event! tx event config)
            (catch Throwable t
              (throw (ex-info (str "Projection handler failed on event "
                                   (:global-sequence event))
                              {:global-sequence (:global-sequence event)
                               :event-type      (:event-type event)
                               :projection-name projection-name
                               :handler-error   true}
                              t)))))
        (advance-checkpoint! tx projection-name
                             (:global-sequence (peek new-events))))
      (count new-events))))

(defn- skip-poison-event!
  "Processes valid events before the poison event one-by-one, then
   advances the checkpoint past the poison event. This ensures earlier
   events in the same batch are not lost when a later event is poison.

   Returns true if the poison event was found and skipped, false if
   the poison event was not found (e.g., already processed)."
  [event-store-ds read-db-ds {:keys [projection-name] :as config}
   poison-global-sequence on-poison]
  (loop []
    (let [result
          (jdbc/with-transaction [tx read-db-ds]
            (acquire-projection-lock! tx projection-name)
            (let [checkpoint (read-checkpoint tx projection-name)
                  next-event (first (read-new-events event-store-ds checkpoint 1))]
              (cond
                (nil? next-event)
                :not-found

                (= (:global-sequence next-event) poison-global-sequence)
                (do
                  (on-poison next-event)
                  (advance-checkpoint! tx projection-name poison-global-sequence)
                  :skipped)

                :else
                (do
                  (project-event! tx next-event config)
                  (advance-checkpoint! tx projection-name
                                       (:global-sequence next-event))
                  :continue))))]
      (case result
        :skipped   true
        :not-found false
        :continue  (recur)))))

(defn rebuild!
  "Destroys and rebuilds the read model from the complete event stream.

   Acquires an advisory lock to prevent concurrent processing during rebuild.

   event-store-ds: datasource for the event store
   read-db-ds:     datasource for the read model
   config:         {:projection-name, :read-model-tables, :handler}"
  [event-store-ds read-db-ds {:keys [projection-name read-model-tables] :as config}]
  ;; Delete read model and checkpoint under the advisory lock
  (jdbc/with-transaction [tx read-db-ds]
    (acquire-projection-lock! tx projection-name)
    (doseq [table read-model-tables]
      (kit/validate-identifier! table "read-model-table")
      (jdbc/execute-one! tx [(str "DELETE FROM " table)]))
    (jdbc/execute-one! tx
                       ["DELETE FROM projection_checkpoints
                         WHERE projection_name = ?"
                        projection-name]))
  ;; Process in batches. Each batch acquires the advisory lock via
  ;; process-new-events!, so concurrent callers will wait.
  (loop [total 0]
    (let [n (process-new-events! event-store-ds read-db-ds config
                                 :batch-size default-batch-size)]
      (if (pos? n)
        (recur (+ total n))
        total))))

;; ——— RabbitMQ consumer ———

(defn make-consumer
  "Creates a RabbitMQ consumer that triggers projection catch-up on each message.

   ch:             RabbitMQ channel
   queue-name:     queue to consume from
   event-store-ds: event store datasource
   read-db-ds:     read model datasource
   config:         projection config
   opts:
     :catch-up-interval-ms     — periodic catch-up timer interval (default 30000)
     :max-consecutive-failures — skip poison event after this many failures (default 5)
     :batch-size               — max events per batch (default 1000); each
                                  catch-up drains all pending batches
     :on-error                 — error handler (fn [exception])
     :on-poison                — poison event handler (fn [event])

   Returns {:consumer-tag, :catch-up-timer, :running}."
  [ch queue-name event-store-ds read-db-ds config
   & {:keys [catch-up-interval-ms max-consecutive-failures batch-size
             on-error on-poison]
      :or   {catch-up-interval-ms     30000
             max-consecutive-failures 5
             batch-size               default-batch-size
             on-error (fn [e] (binding [*out* *err*]
                                (println "Async projector error:" (.getMessage e))))
             on-poison (fn [event]
                         (binding [*out* *err*]
                           (println "POISON EVENT SKIPPED:"
                                    (:projection-name config)
                                    "global_sequence=" (:global-sequence event)
                                    "event_type=" (:event-type event))))}}]
  (let [running           (atom true)
        catching-up?      (atom false)
        consecutive-fails (atom 0)
        ;; Track the global_sequence of the event whose handler failed,
        ;; so poison-skip targets the right event (not earlier ones in the batch).
        last-failed-global-sequence (atom nil)

        do-catch-up!
        (fn []
          ;; Serialise catch-ups: if one is already running, skip.
          ;; This prevents concurrent catch-ups from the timer and
          ;; RabbitMQ messages from racing on the same events.
          ;; The advisory lock in process-new-events! is the real
          ;; serialisation mechanism; this atom avoids unnecessary
          ;; lock contention.
          (when (compare-and-set! catching-up? false true)
            (try
              ;; Loop until all pending events are drained. A single
              ;; process-new-events! call is capped at batch-size, so
              ;; if the projector is far behind we must loop to catch up
              ;; fully — otherwise recovery is limited to one batch per
              ;; wake-up signal.
              (let [total (loop [total 0]
                            (let [n (process-new-events! event-store-ds read-db-ds config
                                                         :batch-size batch-size)]
                              (if (< n batch-size)
                                (+ total n)
                                (recur (+ total n)))))]
                ;; Reset on any successful catch-up, not just when events
                ;; were processed. An empty poll still proves the projection
                ;; is healthy — prior transient failures should not accumulate
                ;; across successful empty polls.
                (when (pos? @consecutive-fails)
                  (reset! consecutive-fails 0)
                  (reset! last-failed-global-sequence nil))
                total)
              (catch Exception e
                (on-error e)
                ;; Only count toward poison threshold when a specific event's
                ;; handler failed. Infrastructure failures (DB outages, etc.)
                ;; should NOT advance the checkpoint or skip events.
                (let [ed (ex-data e)
                      failing-gs (:global-sequence ed)]
                  (when (:handler-error ed)
                    (let [fails (if (= failing-gs @last-failed-global-sequence)
                                  (swap! consecutive-fails inc)
                                  (do (reset! consecutive-fails 1)
                                      (reset! last-failed-global-sequence failing-gs)
                                      1))]
                      (when (>= fails max-consecutive-failures)
                        (try
                          (when (skip-poison-event!
                                 event-store-ds read-db-ds config
                                 failing-gs on-poison)
                            (reset! consecutive-fails 0)
                            (reset! last-failed-global-sequence nil))
                          (catch Exception skip-err
                            (on-error skip-err)))))))
                nil)
              (finally
                (reset! catching-up? false)))))

        handler-fn
        ;; Messages are wake-up signals, not event carriers. Failures
        ;; are handled inside do-catch-up! (consecutive-failure tracking,
        ;; poison-event skipping) and retried by the periodic catch-up
        ;; timer. Always ACK so a failing projection doesn't cause a
        ;; tight redelivery loop.
        (fn [ch' {:keys [delivery-tag]} ^bytes _payload]
          (try
            (do-catch-up!)
            (finally
              (rabbitmq/ack! ch' delivery-tag))))

        ;; Set prefetch to 1: process one message at a time.
        ;; Since messages are just "wake up" signals and each catch-up
        ;; processes ALL pending events, there's no benefit to prefetching.
        _  (rabbitmq/set-prefetch! ch 1)

        consumer-tag
        (rabbitmq/consume! ch queue-name handler-fn)

        ;; Periodic catch-up timer for missed messages
        catch-up-timer
        (doto (Thread.
               (fn []
                 (while @running
                   (try
                     (Thread/sleep (long catch-up-interval-ms))
                     (catch InterruptedException _
                       (reset! running false)))
                   (when @running
                     (do-catch-up!)))))
          (.setDaemon true)
          (.setName (str "catch-up-" (:projection-name config)))
          (.start))]

    {:consumer-tag    consumer-tag
     :catch-up-timer  catch-up-timer
     :running         running
     :channel         ch}))

(defn stop-consumer!
  "Stops the consumer and catch-up timer.
   Waits up to 5 seconds for the catch-up timer to finish.
   Logs a warning if the timer thread is still alive after the timeout."
  [{:keys [catch-up-timer running channel consumer-tag]}]
  (reset! running false)
  (try
    (rabbitmq/cancel! channel consumer-tag)
    (catch Exception _))
  (.interrupt ^Thread catch-up-timer)
  (.join ^Thread catch-up-timer 5000)
  (when (.isAlive ^Thread catch-up-timer)
    (binding [*out* *err*]
      (println "WARNING: catch-up timer thread did not stop within 5 seconds"
               (.getName ^Thread catch-up-timer)))))
