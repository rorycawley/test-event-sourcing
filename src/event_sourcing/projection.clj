(ns event-sourcing.projection
  "Read model projection: derived, disposable views built from the event stream.

   ┌────────────────────────────────────────────────────────────────┐
   │  READ MODEL vs EVENT STREAM                                    │
   │                                                                │
   │  Event stream (events table):                                  │
   │    The authoritative source of truth. Append-only, immutable.  │
   │    One stream per aggregate (stream_id = account id).          │
   │                                                                │
   │  Read models (account_balances, transfer_status):              │
   │    Derived, query-optimised views. Disposable — can be          │
   │    rebuilt from the event stream at any time.                   │
   │    Track last_global_sequence for idempotent catch-up.          │
   │                                                                 │
   │  Correctness model: fail-fast + transactional checkpointing.    │
   │  If any event cannot be applied (invariant violation, unknown   │
   │  event type), the transaction is rolled back and the checkpoint │
   │  does not advance.                                              │
  └────────────────────────────────────────────────────────────────┘"
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [event-sourcing.account-projection]
            [event-sourcing.transfer-projection]
            [event-sourcing.projection-dispatch :as projection-dispatch]
            [event-sourcing.store :as store]))

;; ——— Projection identity/locking ———

(def ^:private projection-name "main")
(def ^:private read-model-tables ["account_balances" "transfer_status"])
(defn- lock-projection!
  "Acquires a transaction-scoped advisory lock for this projection.
   This serialises projection workers so checkpoint advancement and
   event application are deterministic and race-free."
  [tx]
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
  [result event]
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
  "Applies one event to the read model via multimethod dispatch."
  [tx event]
  (projection-dispatch/project-event!
   tx
   event
   {:projection-name            projection-name
    :ensure-single-row-updated! ensure-single-row-updated!}))

;; ——— Catch-up: process new events since last checkpoint ———

(defn process-new-events!
  "Reads events the projection hasn't seen yet and applies them.
   Returns the count of events processed.

   Concurrency + correctness guarantees:
   • projection-level advisory lock serialises workers
   • all work runs in one transaction
   • on any apply failure, transaction rolls back and checkpoint
     does not advance."
  [ds]
  (jdbc/with-transaction [tx ds]
    (lock-projection! tx)
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
        (project-event! tx event))

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
   Useful after schema changes or bug fixes in the projector."
  [ds]
  (jdbc/with-transaction [tx ds]
    (lock-projection! tx)
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
        (project-event! tx event))
      (when (seq all-events)
        (jdbc/execute-one! tx
                           ["INSERT INTO projection_checkpoints (projection_name, last_global_sequence)
            VALUES (?, ?)
            ON CONFLICT (projection_name)
            DO UPDATE SET last_global_sequence = EXCLUDED.last_global_sequence"
                            projection-name
                            (:global-sequence (peek all-events))]))
      (count all-events))))

;; ——— Query ———

(defn get-balance
  "Reads the projected balance for one account."
  [ds account-id]
  (jdbc/execute-one! ds
                     ["SELECT account_id, balance, last_global_sequence, updated_at
      FROM account_balances
      WHERE account_id = ?"
                      account-id]
                     {:builder-fn rs/as-unqualified-kebab-maps}))

(defn get-transfer
  "Reads the projected status for one transfer."
  [ds transfer-id]
  (jdbc/execute-one! ds
                     ["SELECT transfer_id, from_account, to_account, amount,
                             status, failure_reason, last_global_sequence, updated_at
      FROM transfer_status
      WHERE transfer_id = ?"
                      transfer-id]
                     {:builder-fn rs/as-unqualified-kebab-maps}))
