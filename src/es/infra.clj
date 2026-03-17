(ns es.infra
  "Testcontainer lifecycle and datasource construction.

   Start disposable Postgres and RabbitMQ containers for development.
   Every value is plain data or a closeable resource —
   no hidden state, no singletons."
  (:require [next.jdbc :as jdbc])
  (:import [org.testcontainers.containers PostgreSQLContainer RabbitMQContainer]))

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

;; ——— RabbitMQ container lifecycle ———

(defn start-rabbitmq!
  "Starts a RabbitMQ container. Returns a map of connection details
   plus the container reference (for stopping later)."
  []
  (let [container (doto (RabbitMQContainer. "rabbitmq:3-management-alpine")
                    (.start))]
    {:container container
     :host      (.getHost container)
     :port      (.getAmqpPort container)
     :username  "guest"
     :password  "guest"}))

(defn stop-rabbitmq!
  "Stops a previously started RabbitMQ container."
  [{:keys [container]}]
  (when container
    (.stop container)))
