(ns es.consumer
  "Inbox-based consumer framework for event-driven actions.

   Unlike projectors (which use checkpoint-based catch-up and ignore
   message content), consumers process individual events and use an
   inbox table for deduplication. This supports:

   - Competing consumers: multiple worker threads racing on inbox claims
   - At-least-once delivery with exactly-once processing
   - Recovery from complete RabbitMQ loss via Postgres catch-up

   Why projectors don't need this
   ──────────────────────────────
   Projectors treat RabbitMQ messages as wake-up signals and read events
   from the event store using a checkpoint cursor. The checkpoint IS the
   deduplication mechanism. Consumers that ACT on events (send emails,
   trigger external calls) can't use checkpoints — they need per-event
   deduplication via the inbox.

   The inbox table (consumer_inbox) records which events each consumer
   group has processed. Deduplication uses INSERT ON CONFLICT DO NOTHING:
   the first worker to claim an event wins, others skip it.

   Architecture
   ────────────
   RabbitMQ delivers messages to a callback that enqueues them on a
   bounded LinkedBlockingQueue. N Clojure worker threads take from the
   queue, claim via inbox, handle, and ACK. A catch-up timer periodically
   reads from the event store to recover missed messages.

   All processing runs on Clojure threads managed by an ExecutorService,
   never on RabbitMQ's internal thread pool."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.data.json :as json]
            [clojure.tools.logging :as log]
            [es.store :as store]
            [es.rabbitmq :as rabbitmq])
  (:import [java.util.concurrent
            LinkedBlockingQueue
            ExecutorService
            Executors
            TimeUnit
            ScheduledExecutorService]
           [org.postgresql.util PGobject]))

;; ——— Inbox operations ———

(defn claim!
  "Attempts to claim an event for a consumer group.

   Uses INSERT ON CONFLICT DO NOTHING: the first caller to claim a
   given (consumer-group, global-sequence) pair wins. Returns true if
   claimed (this worker should process it), false if already claimed
   (another worker already processed it)."
  [tx consumer-group global-sequence]
  (let [result (jdbc/execute-one! tx
                                  ["INSERT INTO consumer_inbox
                                      (consumer_group, global_sequence)
                                    VALUES (?, ?)
                                    ON CONFLICT DO NOTHING
                                    RETURNING global_sequence"
                                   consumer-group
                                   global-sequence])]
    (some? result)))

(defn high-water-mark
  "Returns the highest global_sequence processed by a consumer group,
   or 0 if no events have been processed.

   NOTE: This returns MAX(global_sequence) from the inbox, which can
   jump ahead of gaps created by concurrent workers. For catch-up
   resume cursors, use get-catch-up-checkpoint instead."
  [ds consumer-group]
  (or (-> (jdbc/execute-one! ds
                             ["SELECT COALESCE(MAX(global_sequence), 0)
                                 AS high_water_mark
                               FROM consumer_inbox
                               WHERE consumer_group = ?"
                              consumer-group]
                             {:builder-fn rs/as-unqualified-kebab-maps})
          :high-water-mark)
      0))

;; ——— Catch-up checkpoint ———

(defn get-catch-up-checkpoint
  "Returns the last contiguous global_sequence processed by the catch-up
   timer, or 0 if no checkpoint exists.

   Unlike high-water-mark (which uses MAX and can skip gaps), this
   checkpoint only advances sequentially through catch-up processing,
   so it never jumps past unprocessed events."
  [ds consumer-group]
  (or (-> (jdbc/execute-one! ds
                             ["SELECT last_global_sequence
                               FROM consumer_checkpoints
                               WHERE consumer_group = ?"
                              consumer-group]
                             {:builder-fn rs/as-unqualified-kebab-maps})
          :last-global-sequence)
      0))

(defn save-catch-up-checkpoint!
  "Advances the catch-up checkpoint to the given global_sequence.

   Only called by the catch-up timer after sequentially processing
   events up to this point."
  [tx consumer-group global-sequence]
  (jdbc/execute-one! tx
                     ["INSERT INTO consumer_checkpoints
                         (consumer_group, last_global_sequence)
                       VALUES (?, ?)
                       ON CONFLICT (consumer_group) DO UPDATE
                         SET last_global_sequence = EXCLUDED.last_global_sequence,
                             updated_at = NOW()"
                      consumer-group
                      global-sequence]))

;; ——— Event processing ———

(defn process-event!
  "Claims an event via the inbox and dispatches to the appropriate handler.

   All work runs in a single transaction on the consumer database:
   the inbox claim and handler side-effects either both commit or
   neither does. This makes the inbox + handler atomically consistent.

   handler-specs: {event-type → (fn [tx event])}
   Events whose type is not in handler-specs are NOT claimed. This
   prevents permanent loss of events that arrive before a handler is
   deployed — the catch-up timer will reprocess them once the handler
   exists.

   Returns :processed if claimed and handled, :duplicate if already
   claimed by another worker, :unknown if no handler exists for the
   event type."
  [consumer-db-ds consumer-group event handler-specs]
  (if-let [handler (get handler-specs (:event-type event))]
    (jdbc/with-transaction [tx consumer-db-ds]
      (if (claim! tx consumer-group (:global-sequence event))
        (do (handler tx event)
            :processed)
        :duplicate))
    (do (log/debug "No handler for event type, leaving unclaimed"
                   {:event-type (:event-type event)
                    :global-sequence (:global-sequence event)
                    :consumer-group consumer-group})
        :unknown)))

;; ——— Catch-up from Postgres ———

(def ^:private default-catch-up-batch-size
  "Max events to read per catch-up cycle."
  1000)

(defn- parse-outbox-message
  "Parses an outbox message column (JSONB PGobject) into a Clojure map."
  [message-value]
  (when message-value
    (let [json-str (if (instance? PGobject message-value)
                     (.getValue ^PGobject message-value)
                     (str message-value))]
      (json/read-str json-str :key-fn keyword))))

(defn catch-up!
  "Reads integration events from the outbox that haven't been processed
   by this consumer group and processes them through the inbox.

   Reads from the event_outbox table (not the events table) so that
   consumers only ever see integration events — the same shape they
   receive from RabbitMQ. This is what makes RabbitMQ disposable:
   after failover to a secondary site, this function fills the gap
   with zero message loss.

   Unknown event types (no handler in handler-specs) are NOT claimed,
   and the catch-up checkpoint halts before the first unknown event.
   This prevents permanent loss of events that arrive before a handler
   is deployed. Known events after the gap are still claimed and
   processed (inbox dedup handles re-reads), but the checkpoint won't
   advance past the gap until a handler exists.

   Acquires an advisory lock to prevent multiple catch-up timers
   from racing (across processes sharing the same consumer DB).

   Returns :full-batch if the number of rows fetched equals the batch
   size (signaling that more rows may remain), or the count of events
   processed (claimed + handled) otherwise."
  [event-store-ds consumer-db-ds consumer-group handler-specs
   & {:keys [batch-size] :or {batch-size default-catch-up-batch-size}}]
  (jdbc/with-transaction [tx consumer-db-ds]
    (store/advisory-lock! tx (str "consumer-catchup:" consumer-group))
    (let [checkpoint (get-catch-up-checkpoint tx consumer-group)
          rows (jdbc/execute! event-store-ds
                              ["SELECT global_sequence, message
                                FROM event_outbox
                                WHERE global_sequence > ?
                                  AND message IS NOT NULL
                                ORDER BY global_sequence ASC
                                LIMIT ?"
                               checkpoint
                               batch-size]
                              {:builder-fn rs/as-unqualified-kebab-maps})
          last-seq           (atom checkpoint)
          checkpoint-halted? (atom false)
          processed-count    (atom 0)]
      (doseq [row rows]
        (let [event (-> (parse-outbox-message (:message row))
                        (assoc :global-sequence (:global-sequence row)))]
          (if-let [handler (get handler-specs (:event-type event))]
            (do
              (when (claim! tx consumer-group (:global-sequence event))
                (handler tx event)
                (swap! processed-count inc))
              ;; Only advance checkpoint if no unknown events before this one
              (when-not @checkpoint-halted?
                (reset! last-seq (:global-sequence row))))
            ;; Unknown event type — halt checkpoint advancement
            (when-not @checkpoint-halted?
              (reset! checkpoint-halted? true)
              (log/warn "Unknown event type halted catch-up checkpoint"
                        {:event-type (:event-type event)
                         :global-sequence (:global-sequence row)
                         :consumer-group consumer-group})))))
      (when (> @last-seq checkpoint)
        (save-catch-up-checkpoint! tx consumer-group @last-seq))
      (if (= (count rows) batch-size)
        :full-batch
        @processed-count))))

;; ——— Worker group ———

(defn- parse-message
  "Parses a RabbitMQ message payload (integration event JSON) into a
   Clojure map. The message shape is whatever the outbox stored —
   integration events, not raw domain events."
  [^bytes payload]
  (json/read-str (String. payload "UTF-8") :key-fn keyword))

(defn- start-worker
  "Starts a single worker with its own RabbitMQ channel.

   Each worker owns its own channel, consumer subscription, and
   in-process queue — no channel sharing across threads. This follows
   the RabbitMQ best practice of one channel per thread."
  [conn queue-name consumer-db-ds consumer-group handler-specs
   running on-error ^ExecutorService worker-pool queue-capacity]
  (let [ch          (rabbitmq/open-channel conn)
        work-queue  (LinkedBlockingQueue. (int queue-capacity))
        _           (rabbitmq/set-prefetch! ch 1)
        callback-fn (fn [_ch {:keys [delivery-tag]} ^bytes payload]
                      (try
                        (.put work-queue {:delivery-tag delivery-tag
                                          :payload      payload})
                        (catch InterruptedException _
                          nil)))
        consumer-tag (rabbitmq/consume! ch queue-name callback-fn)
        worker-fn   (fn []
                      (while @running
                        (try
                          (when-let [item (.poll work-queue 1 TimeUnit/SECONDS)]
                            (let [event (parse-message (:payload item))]
                              (try
                                (process-event! consumer-db-ds consumer-group
                                                event handler-specs)
                                (catch Exception e
                                  (on-error e)))
                              (try
                                (rabbitmq/ack! ch (:delivery-tag item))
                                (catch Exception e
                                  (log/warn e "Failed to ACK message"
                                            {:delivery-tag (:delivery-tag item)})))))
                          (catch InterruptedException _
                            (reset! running false))
                          (catch Exception e
                            (on-error e)))))]
    (.submit worker-pool ^Runnable worker-fn)
    {:channel ch :consumer-tag consumer-tag}))

(defn make-worker-group
  "Creates a consumer worker group: N Clojure worker threads consuming
   from a RabbitMQ queue, plus a periodic catch-up timer for disaster
   recovery.

   Each worker gets its own RabbitMQ channel and consumer subscription
   (competing consumers on the same queue). This avoids sharing a
   channel across threads, which is not thread-safe.

   conn:            RabbitMQ connection
   queue-name:      RabbitMQ queue to consume from
   event-store-ds:  event store datasource (read-only, for catch-up)
   consumer-db-ds:  consumer database datasource (owns inbox table)
   consumer-group:  string identifier for this consumer group
   handler-specs:   {event-type → (fn [tx event])}

   opts:
     :worker-count          — number of worker threads (default 3)
     :catch-up-interval-ms  — catch-up timer interval (default 30000)
     :on-error              — (fn [exception]) error handler

   Returns a map of runtime state for use with stop-worker-group!."
  [conn queue-name event-store-ds consumer-db-ds consumer-group handler-specs
   & {:keys [worker-count catch-up-interval-ms on-error queue-capacity]
      :or   {worker-count         3
             catch-up-interval-ms 30000
             queue-capacity       10
             on-error (fn [e] (log/error e "Consumer worker error"
                                         {:consumer-group consumer-group}))}}]
  (let [running      (atom true)

        ;; ── Worker threads ──
        ;; Each worker has its own channel, consumer, and in-process queue.
        ^ExecutorService
        worker-pool  (Executors/newFixedThreadPool (int worker-count))
        workers      (mapv (fn [_]
                             (start-worker conn queue-name consumer-db-ds
                                           consumer-group handler-specs
                                           running on-error worker-pool
                                           queue-capacity))
                           (range worker-count))

        ;; ── Catch-up timer ──
        ;; Periodically reads from Postgres to fill gaps from lost
        ;; RabbitMQ messages. This is what makes RabbitMQ disposable.
        ^ScheduledExecutorService
        catch-up-pool (Executors/newSingleThreadScheduledExecutor)

        catch-up-fn
        (fn []
          (when @running
            (try
              (loop []
                (let [n (catch-up! event-store-ds consumer-db-ds
                                   consumer-group handler-specs)]
                  ;; Keep draining while we got a full batch — there may
                  ;; be more rows.  Compare against the batch size, not
                  ;; processed-count, because duplicates and unknown-event
                  ;; skips reduce processed-count without meaning the
                  ;; backlog is exhausted.
                  (when (= n :full-batch)
                    (recur))))
              (catch Exception e
                (on-error e)))))

        ;; Run immediate catch-up before starting the timer.
        ;; This ensures events written while the consumer was down are
        ;; processed right away.
        _  (catch-up-fn)

        _  (.scheduleWithFixedDelay catch-up-pool
                                    ^Runnable catch-up-fn
                                    (long catch-up-interval-ms)
                                    (long catch-up-interval-ms)
                                    TimeUnit/MILLISECONDS)]
    {:workers        workers
     :running        running
     :worker-pool    worker-pool
     :catch-up-pool  catch-up-pool
     :consumer-group consumer-group}))

(def ^:private shutdown-timeout-ms
  "Maximum time to wait for workers during shutdown."
  5000)

(defn stop-worker-group!
  "Gracefully stops the worker group.

   Shutdown sequence:
   1. Signal stop (no new work accepted)
   2. Cancel all RabbitMQ consumers (no new deliveries)
   3. Shutdown catch-up timer
   4. Shutdown worker pool (drain remaining queue items)
   5. Close all worker channels"
  [{:keys [workers running worker-pool catch-up-pool
           consumer-group]}]
  ;; 1. Signal stop
  (reset! running false)
  ;; 2. Cancel all RabbitMQ consumers
  (doseq [{:keys [channel consumer-tag]} workers]
    (try
      (rabbitmq/cancel! channel consumer-tag)
      (catch Exception _)))
  ;; 3. Shutdown catch-up timer
  (.shutdown ^ScheduledExecutorService catch-up-pool)
  (when-not (.awaitTermination ^ScheduledExecutorService catch-up-pool
                               shutdown-timeout-ms TimeUnit/MILLISECONDS)
    (log/warn "Catch-up timer did not stop within timeout"
              {:consumer-group consumer-group}))
  ;; 4. Shutdown worker pool
  (.shutdown ^ExecutorService worker-pool)
  (when-not (.awaitTermination ^ExecutorService worker-pool
                               shutdown-timeout-ms TimeUnit/MILLISECONDS)
    (log/warn "Worker pool did not stop within timeout"
              {:consumer-group consumer-group})
    (.shutdownNow ^ExecutorService worker-pool))
  ;; 5. Close all worker channels
  (doseq [{:keys [channel]} workers]
    (try
      (rabbitmq/close-channel channel)
      (catch Exception _))))
