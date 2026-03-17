(ns es.async-projection
  "Async projection engine for separate read databases.

   Like es.projection but designed for CQRS with independent read stores:
   events are read from the event-store database and projections are
   written to a separate read-model database.

   Since the two databases can't share a transaction, idempotency relies
   on the same last_global_sequence guards that projection handlers
   already implement. This makes duplicate processing safe (at-least-once).

   Poison event handling
   ─────────────────────
   If a projection handler throws on a specific event, repeated catch-ups
   would be stuck at that event forever. To prevent this:
   - Consecutive failures are tracked per projector
   - After :max-consecutive-failures (default 5), the poison event is
     skipped and the checkpoint advances past it
   - Skipped events are logged via :on-poison for alerting/investigation
   - A full rebuild! will replay all events including previously skipped ones"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [es.store :as store]
            [es.rabbitmq :as rabbitmq]))

;; ——— Two-datasource projection ———

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
    :ensure-single-row-updated!
    (fn [result evt]
      (let [rows (or (:next.jdbc/update-count result)
                     (:update-count result)
                     0)]
        (when (not= 1 rows)
          (throw (ex-info "Projection invariant violation: expected single-row update"
                          {:projection-name projection-name
                           :update-count    rows
                           :event           evt})))))}))

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

   event-store-ds: datasource for the event store (read-only access)
   read-db-ds:     datasource for the read model (read-write access)
   config:         {:projection-name, :read-model-tables, :handler}
   opts:
     :batch-size — max events per catch-up cycle (default 1000)

   Returns the count of events processed."
  [event-store-ds read-db-ds {:keys [projection-name] :as config}
   & {:keys [batch-size] :or {batch-size default-batch-size}}]
  (let [checkpoint  (read-checkpoint read-db-ds projection-name)
        new-events  (read-new-events event-store-ds checkpoint batch-size)]
    (when (seq new-events)
      (jdbc/with-transaction [tx read-db-ds]
        (doseq [event new-events]
          (project-event! tx event config))
        (advance-checkpoint! tx projection-name
                             (:global-sequence (peek new-events)))))
    (count new-events)))

(defn- skip-poison-event!
  "Advances the checkpoint past a poison event that cannot be projected.
   Called after max-consecutive-failures is reached."
  [read-db-ds projection-name poison-event on-poison]
  (on-poison poison-event)
  (jdbc/with-transaction [tx read-db-ds]
    (advance-checkpoint! tx projection-name
                         (:global-sequence poison-event))))

(defn rebuild!
  "Destroys and rebuilds the read model from the complete event stream.

   event-store-ds: datasource for the event store
   read-db-ds:     datasource for the read model
   config:         {:projection-name, :read-model-tables, :handler}"
  [event-store-ds read-db-ds {:keys [projection-name read-model-tables] :as config}]
  (jdbc/with-transaction [tx read-db-ds]
    (doseq [table read-model-tables]
      (jdbc/execute-one! tx [(str "DELETE FROM " table)]))
    (jdbc/execute-one! tx
                       ["DELETE FROM projection_checkpoints
                         WHERE projection_name = ?"
                        projection-name]))
  ;; Process in batches to avoid unbounded memory usage
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
     :batch-size               — max events per catch-up cycle (default 1000)
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

        do-catch-up!
        (fn []
          ;; Serialise catch-ups: if one is already running, skip.
          ;; This prevents concurrent catch-ups from the timer and
          ;; RabbitMQ messages from racing on the same events.
          (when (compare-and-set! catching-up? false true)
            (try
              (let [n (process-new-events! event-store-ds read-db-ds config
                                           :batch-size batch-size)]
                (when (pos? n)
                  (reset! consecutive-fails 0))
                n)
              (catch Exception e
                (on-error e)
                (let [fails (swap! consecutive-fails inc)]
                  (when (>= fails max-consecutive-failures)
                    ;; Poison event: read the next event and skip it
                    (try
                      (let [checkpoint (read-checkpoint read-db-ds
                                                        (:projection-name config))
                            poison     (first (read-new-events event-store-ds
                                                               checkpoint 1))]
                        (when poison
                          (skip-poison-event! read-db-ds (:projection-name config)
                                              poison on-poison)
                          (reset! consecutive-fails 0)))
                      (catch Exception skip-err
                        (on-error skip-err)))))
                nil)
              (finally
                (reset! catching-up? false)))))

        handler-fn
        (fn [ch' {:keys [delivery-tag]} ^bytes _payload]
          (try
            (do-catch-up!)
            (rabbitmq/ack! ch' delivery-tag)
            (catch Exception e
              (on-error e)
              (try
                (rabbitmq/nack! ch' delivery-tag :requeue true)
                (catch Exception _)))))

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
  "Stops the consumer and catch-up timer."
  [{:keys [catch-up-timer running channel consumer-tag]}]
  (reset! running false)
  (try
    (rabbitmq/cancel! channel consumer-tag)
    (catch Exception _))
  (.interrupt ^Thread catch-up-timer)
  (.join ^Thread catch-up-timer 5000))
