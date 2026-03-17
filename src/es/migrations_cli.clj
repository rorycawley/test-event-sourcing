(ns es.migrations-cli
  "CLI helpers for running schema migrations against a configured database.

   Required environment:
   - JDBC_URL (e.g. jdbc:postgresql://localhost:5432/test_event_sourcing)

   Optional environment:
   - DB_USER
   - DB_PASSWORD"
  (:require [es.migrations :as migrations]
            [next.jdbc :as jdbc]))

(defn- env-non-empty
  [k]
  (let [v (System/getenv k)]
    (when (and v (not= "" v))
      v)))

(def ^:dynamic *exit-fn*
  (fn [code]
    (System/exit (int code))))

(defn- datasource-from-env!
  []
  (let [jdbc-url (env-non-empty "JDBC_URL")
        db-user (env-non-empty "DB_USER")
        db-password (env-non-empty "DB_PASSWORD")]
    (when-not jdbc-url
      (throw (ex-info "Missing JDBC_URL environment variable"
                      {:required-env ["JDBC_URL"]
                       :example "JDBC_URL=jdbc:postgresql://localhost:5432/test_event_sourcing"})))
    (jdbc/get-datasource
     (cond-> {:jdbcUrl jdbc-url}
       db-user (assoc :user db-user)
       db-password (assoc :password db-password)))))

(defn- print-migration-list
  [label rows]
  (let [rows (sort rows)]
    (println (str label ": " (count rows)))
    (doseq [row rows]
      (println " -" (pr-str row)))))

(defn- status!
  [ds]
  (let [completed (sort (migrations/completed ds))
        pending (sort (migrations/pending ds))]
    (print-migration-list "Completed migrations" completed)
    (print-migration-list "Pending migrations" pending)
    {:completed completed
     :pending pending}))

(def ^:private known-commands
  #{"migrate" "rollback" "status"})

(defn -main
  [& [command]]
  (let [command (or command "status")]
    (if (contains? known-commands command)
      (let [ds (datasource-from-env!)]
        (case command
          "migrate"
          (do
            (migrations/migrate! ds)
            (println "Applied pending migrations.")
            (status! ds))

          "rollback"
          (do
            (migrations/rollback! ds)
            (println "Rolled back the most recent migration.")
            (status! ds))

          "status"
          (status! ds)))
      (do
        (binding [*out* *err*]
          (println "Unknown migration command:" command)
          (println "Usage: clj -M -m es.migrations-cli [migrate|rollback|status]"))
        (*exit-fn* 1)))))
