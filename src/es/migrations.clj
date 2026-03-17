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
  [ds & {:keys [migration-dir]}]
  (cond-> (assoc base-config :db {:datasource ds})
    migration-dir (assoc :migration-dir migration-dir)))

(defn migrate!
  "Applies all pending migrations.
   Optional :migration-dir overrides the default \"migrations\" directory."
  [ds & {:keys [migration-dir]}]
  (migratus/migrate (migratus-config ds :migration-dir migration-dir)))

(defn rollback!
  "Rolls back the most recently applied migration.
   Optional :migration-dir overrides the default \"migrations\" directory."
  [ds & {:keys [migration-dir]}]
  (migratus/rollback (migratus-config ds :migration-dir migration-dir)))

(defn pending
  "Returns pending migrations.
   Optional :migration-dir overrides the default \"migrations\" directory."
  [ds & {:keys [migration-dir]}]
  (migratus/pending-list (migratus-config ds :migration-dir migration-dir)))

(defn completed
  "Returns completed migrations.
   Optional :migration-dir overrides the default \"migrations\" directory."
  [ds & {:keys [migration-dir]}]
  (migratus/completed-list (migratus-config ds :migration-dir migration-dir)))
