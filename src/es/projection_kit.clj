(ns es.projection-kit
  "Data-driven toolkit for building projection event handlers.

   Mirrors decider-kit: domain files declare *what* as data, this
   namespace provides the *how*.

   Factory functions:

     make-handler   handler-specs  → (fn [tx event context])
     make-query     table, id-col  → (fn [ds id] row-or-nil)

   Shared helpers (used by es.projection and es.async-projection):

     validate-identifier!          — SQL identifier safety check
     ensure-single-row-updated!    — fail-fast invariant for update handlers

   Handler specs are a map of {event-type -> (fn [tx event context])}.
   Domain namespaces define their handlers as plain maps; bank.system
   merges them and passes the combined handler to the projection config."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]
            [clojure.tools.logging :as log]))

;; ——— Identifier validation ———

(def identifier-pattern
  "SQL identifiers must be alphanumeric + underscores only."
  #"^[a-zA-Z_][a-zA-Z0-9_]*$")

(defn validate-identifier!
  "Validates that a string is a safe SQL identifier.
   Returns s on success, throws on invalid input."
  [s label]
  (when-not (re-matches identifier-pattern s)
    (throw (ex-info (str "Invalid SQL identifier for " label)
                    {:label label :value s})))
  s)

;; ——— Row-update invariant ———

(defn update-count
  "Extracts update count from next.jdbc DML result maps."
  [result]
  (or (:next.jdbc/update-count result)
      (:update-count result)
      0))

(defn ensure-single-row-updated!
  "Fail-fast invariant for update-style projection handlers.

   Update events (e.g. money-deposited, status changes) MUST affect
   exactly one read-model row. This catches two classes of bug:

     rows == 0 → the row does not exist (update-before-create, missing
                  or corrupted read model). Silent no-ops here would
                  advance the checkpoint past an event that was never
                  applied, causing data loss.

     rows  > 1 → multi-row update on what should be a PK match.

   Idempotent replays (same event re-applied) cannot produce rows == 0
   in normal operation: the checkpoint prevents re-reading events, and
   rebuild! deletes + replays from scratch. The last_global_sequence < ?
   guard is defence-in-depth for edge cases, not an expected code path."
  [projection-name result event]
  (let [rows (update-count result)]
    (when (not= rows 1)
      (throw (ex-info (if (zero? rows)
                        "Projection invariant violation: expected single-row update"
                        "Projection invariant violation: multi-row update")
                      {:projection-name projection-name
                       :update-count    rows
                       :event           event})))))

(defn make-handler
  "Returns a handler function (fn [tx event context]) that dispatches
   on :event-type. handler-specs is a map of {event-type -> handler-fn}.

   opts:
     :skip-unknown? — if true, silently skip unknown event types instead
                      of throwing. Use this for independent async projectors
                      that only handle a subset of all event types."
  [handler-specs & {:keys [skip-unknown?] :or {skip-unknown? false}}]
  (fn [tx {:keys [event-type] :as event} context]
    (let [handler (get handler-specs event-type)]
      (if handler
        (handler tx event context)
        (if skip-unknown?
          (log/debug "Skipping unknown event type"
                     {:projection-name (:projection-name context)
                      :event-type      event-type
                      :global-sequence (:global-sequence event)})
          (throw (ex-info "Unknown event type for projection"
                          {:projection-name (:projection-name context)
                           :event-type      event-type
                           :event-version   (:event-version event)
                           :global-sequence (:global-sequence event)
                           :stream-id       (:stream-id event)})))))))

(defn make-query
  "Returns a query function (fn [ds id] row-or-nil) for a read model table.
   All identifiers are validated against [a-zA-Z_][a-zA-Z0-9_]* to
   prevent SQL injection (DDL/identifiers cannot use parameterized queries)."
  [table id-column & {:keys [columns] :or {columns "*"}}]
  (validate-identifier! table "table")
  (validate-identifier! id-column "id-column")
  (when-not (= "*" columns)
    (doseq [col (str/split columns #",\s*")]
      (validate-identifier! (str/trim col) "column")))
  (fn [ds id]
    (jdbc/execute-one! ds
                       [(str "SELECT " columns " FROM " table
                             " WHERE " id-column " = ?")
                        id]
                       {:builder-fn rs/as-unqualified-kebab-maps})))
