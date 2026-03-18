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
  [ds opts]
  (let [completed (sort (migrations/completed ds opts))
        pending   (sort (migrations/pending ds opts))]
    (print-migration-list "Completed migrations" completed)
    (print-migration-list "Pending migrations" pending)
    {:completed completed
     :pending pending}))

(def ^:private known-commands
  #{"migrate" "rollback" "status"})

(defn- usage! []
  (binding [*out* *err*]
    (println "Usage: clj -M -m es.migrations-cli [migrate|rollback|status] [migration-dir]")
    (println "")
    (println "migration-dir:")
    (println "  migrations               Event store (default)")
    (println "  read-migrations          Read store")
    (println "  notification-migrations  Notification database"))
  (*exit-fn* 1))

(defn -main
  "Runs migration commands against a database specified by JDBC_URL.

   Usage: clj -M -m es.migrations-cli [migrate|rollback|status] [migration-dir]

   migration-dir defaults to \"migrations\" (event store schema).
   Use \"read-migrations\" for the read store or \"notification-migrations\"
   for the notification database."
  [& [command migration-dir]]
  (let [command (or command "status")
        opts    (cond-> {}
                  migration-dir (assoc :migration-dir migration-dir))]
    (when-not (contains? known-commands command)
      (binding [*out* *err*]
        (println "Unknown migration command:" command))
      (usage!))
    (let [ds (datasource-from-env!)]
      (when migration-dir
        (println (str "Using migration directory: " migration-dir)))
      (case command
        "migrate"
        (do (migrations/migrate! ds opts)
            (println "Applied pending migrations.")
            (status! ds opts))

        "rollback"
        (do (migrations/rollback! ds opts)
            (println "Rolled back the most recent migration.")
            (status! ds opts))

        "status"
        (status! ds opts)))))
