(ns es.migrations
  "Database schema migrations via Migratus.

   Uses SQL migrations in resources/migrations and records execution in
   the schema_migrations table."
  (:require [migratus.core :as migratus]))

(def ^:private base-config
  {:store                :database
   :migration-dir        "migrations"
   :migration-table-name "schema_migrations"})

(defn- migratus-config
  [ds]
  (assoc base-config :db {:datasource ds}))

(defn migrate!
  "Applies all pending migrations."
  [ds]
  (migratus/migrate (migratus-config ds)))

(defn rollback!
  "Rolls back the most recently applied migration."
  [ds]
  (migratus/rollback (migratus-config ds)))

(defn pending
  "Returns pending migrations."
  [ds]
  (migratus/pending-list (migratus-config ds)))

(defn completed
  "Returns completed migrations."
  [ds]
  (migratus/completed-list (migratus-config ds)))
