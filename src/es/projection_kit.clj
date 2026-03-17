(ns es.projection-kit
  "Data-driven toolkit for building projection event handlers.

   Mirrors decider-kit: domain files declare *what* as data, this
   namespace provides the *how*.

   Two factory functions:

     make-handler   handler-specs  → (fn [tx event context])
     make-query     table, id-col  → (fn [ds id] row-or-nil)

   Handler specs are a map of {event-type -> (fn [tx event context])}.
   Domain namespaces define their handlers as plain maps; bank.system
   merges them and passes the combined handler to the projection config."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(defn make-handler
  "Returns a handler function (fn [tx event context]) that dispatches
   on :event-type. handler-specs is a map of {event-type -> handler-fn}.
   Throws on unknown event types."
  [handler-specs]
  (fn [tx {:keys [event-type] :as event} context]
    (let [handler (get handler-specs event-type)]
      (when-not handler
        (throw (ex-info "Unknown event type for projection"
                        {:projection-name (:projection-name context)
                         :event-type      event-type
                         :event-version   (:event-version event)
                         :global-sequence (:global-sequence event)
                         :stream-id       (:stream-id event)})))
      (handler tx event context))))

(defn make-query
  "Returns a query function (fn [ds id] row-or-nil) for a read model table."
  [table id-column & {:keys [columns] :or {columns "*"}}]
  (fn [ds id]
    (jdbc/execute-one! ds
                       [(str "SELECT " columns " FROM " table
                             " WHERE " id-column " = ?")
                        id]
                       {:builder-fn rs/as-unqualified-kebab-maps})))
