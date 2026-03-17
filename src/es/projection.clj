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
            [es.store :as store]))

;; ——— Projection identity/locking ———

(defn- lock-projection!
  "Acquires a transaction-scoped advisory lock for this projection.
   This serialises projection workers so checkpoint advancement and
   event application are deterministic and race-free."
  [tx projection-name]
  (store/advisory-lock! tx (str "projection:" projection-name)))

;; ——— Validation helpers ———

(defn- update-count
  "Extracts update count from next.jdbc DML result maps."
  [result]
  (or (:next.jdbc/update-count result)
      (:update-count result)
      0))

(defn- ensure-single-row-updated!
  "Update events must affect exactly one read-model row.
   If not, fail fast so the caller's transaction rolls back and
   checkpoint advancement is prevented."
  [projection-name result event]
  (let [rows (update-count result)]
    (when (not= 1 rows)
      (throw (ex-info "Projection invariant violation: expected single-row update"
                      {:projection-name projection-name
                       :update-count    rows
                       :event           event})))))

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
    :ensure-single-row-updated! (partial ensure-single-row-updated! projection-name)}))

;; ——— Catch-up: process new events since last checkpoint ———

(defn process-new-events!
  "Reads events the projection hasn't seen yet and applies them.
   Returns the count of events processed.

   config is a map with:
     :projection-name   — string identifier for this projection
     :read-model-tables — vector of table name strings (used by rebuild!)

   Concurrency + correctness guarantees:
   • projection-level advisory lock serialises workers
   • all work runs in one transaction
   • on any apply failure, transaction rolls back and checkpoint
     does not advance."
  [ds {:keys [projection-name] :as config}]
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
              ORDER BY global_sequence ASC"
                          checkpoint]
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

   config is a map with:
     :projection-name   — string identifier for this projection
     :read-model-tables — vector of table name strings to truncate"
  [ds {:keys [projection-name read-model-tables] :as config}]
  (jdbc/with-transaction [tx ds]
    (lock-projection! tx projection-name)
    (doseq [table read-model-tables]
      (jdbc/execute-one! tx [(str "DELETE FROM " table)]))
    (jdbc/execute-one! tx ["DELETE FROM projection_checkpoints"])
    (let [all-events
          (mapv decode-event-row
                (jdbc/execute! tx
                               ["SELECT global_sequence, stream_id, stream_sequence,
                          event_type, event_version, payload
                    FROM events ORDER BY global_sequence ASC"]
                               {:builder-fn rs/as-unqualified-kebab-maps}))]
      (doseq [event all-events]
        (project-event! tx event config))
      (when (seq all-events)
        (jdbc/execute-one! tx
                           ["INSERT INTO projection_checkpoints (projection_name, last_global_sequence)
            VALUES (?, ?)
            ON CONFLICT (projection_name)
            DO UPDATE SET last_global_sequence = EXCLUDED.last_global_sequence"
                            projection-name
                            (:global-sequence (peek all-events))]))
      (count all-events))))
