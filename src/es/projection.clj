(ns es.projection
  "Read model projection: derived, disposable views built from the event stream.

   ┌────────────────────────────────────────────────────────────────┐
   │  READ MODEL vs EVENT STREAM                                    │
   │                                                                │
   │  Event stream (events table):                                  │
   │    The authoritative source of truth. Append-only, immutable.  │
   │    One stream per aggregate (stream_id = account id).          │
   │                                                                │
   │  Read models (e.g. account_balances, transfer_status):         │
   │    Derived, query-optimised views. Disposable — can be          │
   │    rebuilt from the event stream at any time.                   │
   │    Track last_global_sequence for idempotent catch-up.          │
   │                                                                 │
   │  Correctness model: fail-fast + transactional checkpointing.    │
   │  If any event cannot be applied (invariant violation, unknown   │
   │  event type), the transaction is rolled back and the checkpoint │
   │  does not advance.                                              │
   └────────────────────────────────────────────────────────────────┘

   This namespace is domain-agnostic. It does not know which read model
   tables exist or which event types are handled. Callers pass a config
   map with:
     :projection-name   — string identifier for this projection
     :read-model-tables — vector of table name strings
     :handler           — function (fn [tx event context]) built via
                          es.projection-kit/make-handler"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [es.store :as store]
            [es.projection-kit :as kit]))

(def default-batch-size
  "Default number of events to read per catch-up cycle."
  1000)

;; ——— Projection identity/locking ———

(defn- lock-projection!
  "Acquires a transaction-scoped advisory lock for this projection.
   This serialises projection workers so checkpoint advancement and
   event application are deterministic and race-free."
  [tx projection-name]
  (store/advisory-lock! tx (str "projection:" projection-name)))

;; ——— Single-event projector ———

(defn- decode-event-row
  [row]
  (-> row
      (update :payload store/<-pgobject)
      (update :event-version #(or % 1))))

(defn- project-event!
  "Applies one event to the read model via the handler function from config."
  [tx event {:keys [projection-name handler]}]
  (handler
   tx
   event
   {:projection-name            projection-name
    :ensure-single-row-updated! (partial kit/ensure-single-row-updated! projection-name)}))

;; ——— Catch-up: process new events since last checkpoint ———

(defn process-new-events!
  "Reads events the projection hasn't seen yet and applies them.
   Returns the count of events processed.

   config is a map with:
     :projection-name   — string identifier for this projection
     :read-model-tables — vector of table name strings (used by rebuild!)
   opts:
     :batch-size — max events per transaction (default 1000)

   Concurrency + correctness guarantees:
   • projection-level advisory lock serialises workers
   • all work runs in one transaction (bounded by batch-size)
   • on any apply failure, transaction rolls back and checkpoint
     does not advance."
  [ds {:keys [projection-name] :as config}
   & {:keys [batch-size] :or {batch-size default-batch-size}}]
  (jdbc/with-transaction [tx ds]
    (lock-projection! tx projection-name)
    (let [checkpoint
          (or (-> (jdbc/execute-one! tx
                                     ["SELECT last_global_sequence
                      FROM projection_checkpoints
                      WHERE projection_name = ?"
                                      projection-name]
                                     {:builder-fn rs/as-unqualified-kebab-maps})
                  :last-global-sequence)
              0)

          new-events
          (jdbc/execute! tx
                         ["SELECT global_sequence, stream_id, stream_sequence,
                     event_type, event_version, payload
              FROM events
              WHERE global_sequence > ?
              ORDER BY global_sequence ASC
              LIMIT ?"
                          checkpoint
                          batch-size]
                         {:builder-fn rs/as-unqualified-kebab-maps})

          new-events
          (mapv decode-event-row new-events)]

      (doseq [event new-events]
        (project-event! tx event config))

      (when (seq new-events)
        (jdbc/execute-one! tx
                           ["INSERT INTO projection_checkpoints (projection_name, last_global_sequence)
            VALUES (?, ?)
            ON CONFLICT (projection_name)
            DO UPDATE SET last_global_sequence = GREATEST(
              projection_checkpoints.last_global_sequence,
              EXCLUDED.last_global_sequence)"
                            projection-name
                            (:global-sequence (peek new-events))]))

      (count new-events))))

;; ——— Full rebuild ———

(defn rebuild!
  "Destroys and rebuilds the read model from the complete event stream.
   Useful after schema changes or bug fixes in the projector.

   Truncates the read model tables and resets the checkpoint in one
   transaction, then replays events in batches via process-new-events!.

   config is a map with:
     :projection-name   — string identifier for this projection
     :read-model-tables — vector of table name strings to truncate"
  [ds {:keys [projection-name read-model-tables] :as config}]
  ;; Truncate and reset checkpoint in one transaction
  (jdbc/with-transaction [tx ds]
    (lock-projection! tx projection-name)
    (doseq [table read-model-tables]
      (kit/validate-identifier! table "read-model-table")
      (jdbc/execute-one! tx [(str "DELETE FROM " table)]))
    (jdbc/execute-one! tx ["DELETE FROM projection_checkpoints WHERE projection_name = ?"
                           projection-name]))
  ;; Replay in batches — each batch is its own bounded transaction
  (loop [total 0]
    (let [n (process-new-events! ds config :batch-size default-batch-size)]
      (if (pos? n)
        (recur (+ total n))
        total))))
