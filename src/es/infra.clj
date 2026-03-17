(ns es.infra
  "Testcontainer lifecycle and datasource construction.

   Start a disposable Postgres for REPL-driven development.
   Every value is plain data or a closeable resource —
   no hidden state, no singletons."
  (:require [next.jdbc :as jdbc])
  (:import [org.testcontainers.containers PostgreSQLContainer]))

;; ——— Container lifecycle ———

(defn start-postgres!
  "Starts a Postgres 16 container. Returns a map of connection details
   plus the container reference (for stopping later)."
  []
  (let [container (doto (PostgreSQLContainer. "postgres:16-alpine")
                    (.start))]
    {:container container
     :jdbc-url  (.getJdbcUrl container)
     :username  (.getUsername container)
     :password  (.getPassword container)}))

(defn stop-postgres!
  "Stops a previously started container."
  [{:keys [container]}]
  (when container
    (.stop container)))

;; ——— Datasource ———

(defn ->datasource
  "Builds a next.jdbc datasource from container connection details."
  [{:keys [jdbc-url username password]}]
  (jdbc/get-datasource {:jdbcUrl  jdbc-url
                        :user     username
                        :password password}))
