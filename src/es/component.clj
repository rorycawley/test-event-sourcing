(ns es.component
  "Stuart Sierra Component records for event sourcing infrastructure.

   Provides lifecycle-managed wrappers for:
   - Datasource (Testcontainers or JDBC URL)
   - Migrator (runs migrations on start)
   - RabbitMQConnection (connect/disconnect)
   - OutboxPoller (poll outbox, publish to RabbitMQ)

   These are framework-level components — they know nothing about
   the bank domain or any specific application."
  (:require [com.stuartsierra.component :as component]
            [es.infra :as infra]
            [es.migrations :as migrations]
            [es.rabbitmq :as rabbitmq]
            [es.outbox :as outbox]
            [es.async-projection :as async-projection]
            [es.search :as search]
            [next.jdbc :as jdbc]))

;; ——— Datasource ———

(defrecord Datasource [mode jdbc-url user password image
                       ;; runtime state
                       container datasource]
  component/Lifecycle
  (start [this]
    (when-not (#{:testcontainers :jdbc-url} mode)
      (throw (ex-info "Invalid Datasource :mode — must be :testcontainers or :jdbc-url"
                      {:error/type :config/invalid-mode
                       :mode       mode})))
    (when (and (= :jdbc-url mode) (not jdbc-url))
      (throw (ex-info "Datasource :jdbc-url mode requires a :jdbc-url value"
                      {:error/type :config/missing-field
                       :mode       mode})))
    (case mode
      :testcontainers
      (let [pg (infra/start-postgres! :image (or image infra/default-postgres-image))
            ds (infra/->datasource pg)]
        (assoc this
               :container pg
               :datasource ds))

      :jdbc-url
      (let [opts (cond-> {:jdbcUrl jdbc-url}
                   user     (assoc :user user)
                   password (assoc :password password))
            ds (jdbc/get-datasource opts)]
        (assoc this :datasource ds))))

  (stop [this]
    (when (and (= :testcontainers mode) container)
      (infra/stop-postgres! container))
    (assoc this :container nil :datasource nil)))

(defn datasource-component
  "Creates a Datasource component.

   opts:
     {:mode :testcontainers}  — starts a ParadeDB container (dev/test)
     {:mode :testcontainers :image \"postgres:17-alpine\"}  — override image
     {:mode :jdbc-url :jdbc-url \"...\" :user \"...\" :password \"...\"}  — production"
  [{:keys [mode jdbc-url user password image]}]
  (map->Datasource {:mode     mode
                    :jdbc-url jdbc-url
                    :user     user
                    :password password
                    :image    image}))

;; ——— Migrator ———

(defrecord Migrator [migration-dir
                     ;; injected dependency
                     datasource]
  component/Lifecycle
  (start [this]
    (let [ds (:datasource datasource)]
      (migrations/migrate! ds :migration-dir (or migration-dir "migrations"))
      (assoc this :migrated true)))

  (stop [this]
    (assoc this :migrated nil)))

(defn migrator-component
  "Creates a Migrator component. Depends on :datasource.

   opts (optional):
     {:migration-dir \"read-migrations\"}  — custom migration directory"
  ([] (map->Migrator {}))
  ([{:keys [migration-dir]}]
   (map->Migrator {:migration-dir migration-dir})))

;; ——— Search Index ———

(defrecord SearchIndex [search-configs
                        ;; injected dependency
                        datasource]
  component/Lifecycle
  (start [this]
    (let [ds (:datasource datasource)]
      (doseq [config search-configs]
        (search/ensure-search! ds config))
      (assoc this :initialized true)))

  (stop [this]
    (assoc this :initialized nil)))

(defn search-index-component
  "Creates a SearchIndex component that ensures BM25 indexes exist on start.
   Depends on :datasource (must be started after migrator so tables exist).

   search-configs: vector of search config maps, each with:
     :table, :index-name, :key-field, :text-fields"
  [search-configs]
  (map->SearchIndex {:search-configs search-configs}))

;; ——— RabbitMQ Connection ———

(defrecord RabbitMQConnection [mode host port username password uri
                               ;; runtime state
                               container connection]
  component/Lifecycle
  (start [this]
    (when-not (#{:testcontainers :uri :direct} mode)
      (throw (ex-info "Invalid RabbitMQConnection :mode — must be :testcontainers, :uri, or :direct"
                      {:error/type :config/invalid-mode
                       :mode       mode})))
    (when (and (= :uri mode) (not uri))
      (throw (ex-info "RabbitMQConnection :uri mode requires a :uri value"
                      {:error/type :config/missing-field
                       :mode       mode})))
    (case mode
      :testcontainers
      (let [rmq  (infra/start-rabbitmq!)
            conn (rabbitmq/connect! {:host     (:host rmq)
                                     :port     (:port rmq)
                                     :username (:username rmq)
                                     :password (:password rmq)})]
        (assoc this
               :container rmq
               :connection conn
               :host (:host rmq)
               :port (:port rmq)))

      :uri
      (let [conn (rabbitmq/connect! {:uri uri})]
        (assoc this :connection conn))

      :direct
      (let [conn (rabbitmq/connect! (cond-> {}
                                      host     (assoc :host host)
                                      port     (assoc :port port)
                                      username (assoc :username username)
                                      password (assoc :password password)))]
        (assoc this :connection conn))))

  (stop [this]
    (try
      (when connection
        (rabbitmq/disconnect! connection))
      (finally
        (try
          (when (and (= :testcontainers mode) container)
            (infra/stop-rabbitmq! container))
          (finally
            (identity nil)))))
    (assoc this :connection nil :container nil)))

(defn rabbitmq-component
  "Creates a RabbitMQ connection component.

   opts:
     {:mode :testcontainers}  — starts a RabbitMQ container (dev/test)
     {:mode :uri :uri \"amqp://...\"}  — connect via URI
     {:mode :direct :host \"...\" :port 5672 :username \"...\" :password \"...\"}  — explicit"
  [opts]
  (map->RabbitMQConnection opts))

;; ——— Outbox Poller ———

(defrecord OutboxPoller [exchange-name poll-interval-ms batch-size
                         ;; injected dependencies
                         datasource rabbitmq
                         ;; runtime state
                         channel poller]
  component/Lifecycle
  (start [this]
    (let [ds   (:datasource datasource)
          conn (:connection rabbitmq)
          ch   (rabbitmq/open-channel conn)
          _    (rabbitmq/declare-fanout-exchange! ch (or exchange-name "events"))
          pub-fn (fn [message]
                   (rabbitmq/publish! ch (or exchange-name "events") "" message))
          p    (outbox/start-poller! ds pub-fn
                                     :poll-interval-ms (or poll-interval-ms 100)
                                     :batch-size (or batch-size 100))]
      (assoc this :channel ch :poller p)))

  (stop [this]
    (try
      (when poller
        (outbox/stop-poller! poller))
      (finally
        (when channel
          (rabbitmq/close-channel channel))))
    (assoc this :channel nil :poller nil)))

(defn outbox-poller-component
  "Creates an OutboxPoller component. Depends on :datasource and :rabbitmq.

   opts:
     :exchange-name    — RabbitMQ exchange name (default \"events\")
     :poll-interval-ms — sleep between polls (default 100)
     :batch-size       — max events per poll cycle (default 100)"
  [opts]
  (map->OutboxPoller opts))

;; ——— Async Projector ———

(defrecord AsyncProjector [queue-name exchange-name projection-config
                           catch-up-interval-ms max-consecutive-failures
                           batch-size
                           ;; injected dependencies
                           event-store-ds read-db-ds rabbitmq
                           ;; runtime state
                           channel consumer]
  component/Lifecycle
  (start [this]
    (let [es-ds   (:datasource event-store-ds)
          rd-ds   (:datasource read-db-ds)
          conn    (:connection rabbitmq)
          ch      (rabbitmq/open-channel conn)
          exch    (or exchange-name "events")
          dlx     (str exch ".dlx")
          dlq     (str queue-name ".dlq")
          _       (rabbitmq/declare-fanout-exchange! ch exch)
          _       (rabbitmq/declare-dlq! ch dlx dlq)
          _       (rabbitmq/declare-queue! ch queue-name exch
                                           :dead-letter-exchange dlx)
          c       (async-projection/make-consumer
                   ch queue-name es-ds rd-ds projection-config
                   :catch-up-interval-ms (or catch-up-interval-ms 30000)
                   :max-consecutive-failures (or max-consecutive-failures 5)
                   :batch-size (or batch-size 1000))]
      (assoc this :channel ch :consumer c)))

  (stop [this]
    (try
      (when consumer
        (async-projection/stop-consumer! consumer))
      (finally
        (when channel
          (rabbitmq/close-channel channel))))
    (assoc this :channel nil :consumer nil)))

(defn async-projector-component
  "Creates an AsyncProjector component.

   Depends on :event-store-ds, :read-db-ds, and :rabbitmq.

   queue-name:       RabbitMQ queue to consume from
   projection-config: {:projection-name, :read-model-tables, :handler}
   opts:
     :exchange-name            — RabbitMQ exchange (default \"events\")
     :catch-up-interval-ms     — periodic catch-up interval (default 30000)
     :max-consecutive-failures — skip poison event after N failures (default 5)
     :batch-size               — max events per catch-up cycle (default 1000)"
  [queue-name projection-config & {:keys [exchange-name catch-up-interval-ms
                                          max-consecutive-failures batch-size]}]
  (map->AsyncProjector {:queue-name               queue-name
                        :exchange-name             exchange-name
                        :projection-config         projection-config
                        :catch-up-interval-ms      catch-up-interval-ms
                        :max-consecutive-failures  max-consecutive-failures
                        :batch-size                batch-size}))
