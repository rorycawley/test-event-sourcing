(ns bank.components
  "Component system assembly for the bank domain.

   Provides system-map constructors for development, testing, and production.
   The full system wires together:
   - Event store (Postgres)
   - Read store (separate Postgres)
   - RabbitMQ connection
   - Outbox poller
   - Async projectors (account balances, transfer status)

   ┌─────────────┐    ┌──────────────┐    ┌─────────────┐
   │ Event Store  │───>│ Outbox Poller │───>│  RabbitMQ   │
   │  (Postgres)  │    └──────────────┘    └──────┬──────┘
   └─────────────┘                                │
                                         ┌────────┼────────┐
                                         │                  │
                                    ┌────┴─────┐     ┌─────┴────┐
                                    │ Account  │     │ Transfer │
                                    │ Projector│     │ Projector│
                                    └────┬─────┘     └─────┬────┘
                                         │                  │
                                    ┌────┴──────────────────┴────┐
                                    │    Read Store (Postgres)   │
                                    └────────────────────────────┘"
  (:require [com.stuartsierra.component :as component]
            [es.component :refer [datasource-component migrator-component
                                  search-index-component
                                  rabbitmq-component outbox-poller-component
                                  async-projector-component]]
            [es.projection-kit :as kit]
            [bank.account-projection :as account-projection]
            [bank.transfer-projection :as transfer-projection]))

(defn dev-system
  "Returns a minimal Component system for development/testing.
   Single Postgres, no RabbitMQ, no async projectors.
   Use bank.system/process-new-events! for synchronous projection."
  []
  (component/system-map
   :datasource   (datasource-component {:mode :testcontainers})
   :migrator     (component/using (migrator-component) [:datasource])
   :search-index (component/using
                  (search-index-component
                   [account-projection/search-index-config])
                  {:datasource :datasource
                   :migrator   :migrator})))

(defn full-system
  "Returns the full Component system with RabbitMQ and async projectors.

   config:
     {:event-store {:mode :testcontainers}  ;; or {:mode :jdbc-url :jdbc-url ...}
      :read-db     {:mode :testcontainers}  ;; separate read database
      :rabbitmq    {:mode :testcontainers}  ;; or {:mode :uri :uri ...}
      :catch-up-interval-ms     30000       ;; optional, default 30s
      :batch-size               1000        ;; optional, default 1000
      :max-consecutive-failures 5}          ;; optional, default 5"
  [config]
  (let [projector-opts (select-keys config [:catch-up-interval-ms
                                            :batch-size
                                            :max-consecutive-failures])
        catch-up-opts  (mapcat identity projector-opts)]
    (component/system-map
     ;; Event store
     :event-store-ds (datasource-component (:event-store config))
     :event-migrator (component/using (migrator-component)
                                      {:datasource :event-store-ds})

     ;; Read store
     :read-db-ds     (datasource-component (:read-db config))
     :read-migrator  (component/using
                      (migrator-component {:migration-dir "read-migrations"})
                      {:datasource :read-db-ds})

     ;; RabbitMQ
     :rabbitmq       (rabbitmq-component (:rabbitmq config))

     ;; Search indexes — on the read store, after migrations
     :search-index   (component/using
                      (search-index-component
                       [account-projection/search-index-config])
                      {:datasource   :read-db-ds
                       :read-migrator :read-migrator})

     ;; Outbox poller — depends on event-migrator to ensure schema exists
     :outbox-poller  (component/using
                      (outbox-poller-component
                       {:exchange-name    "events"
                        :poll-interval-ms 100})
                      {:datasource     :event-store-ds
                       :rabbitmq       :rabbitmq
                       :event-migrator :event-migrator})

     ;; Async projectors — depend on both migrators to ensure schemas exist
     :account-projector
     (component/using
      (apply async-projector-component
             "projector.account-balances"
             {:projection-name   "account-balances"
              :read-model-tables ["account_balances"]
              :handler           (kit/make-handler account-projection/handler-specs
                                                   :skip-unknown? true)}
             :exchange-name "events"
             catch-up-opts)
      {:event-store-ds :event-store-ds
       :read-db-ds     :read-db-ds
       :rabbitmq       :rabbitmq
       :event-migrator :event-migrator
       :read-migrator  :read-migrator})

     :transfer-projector
     (component/using
      (apply async-projector-component
             "projector.transfer-status"
             {:projection-name   "transfer-status"
              :read-model-tables ["transfer_status"]
              :handler           (kit/make-handler transfer-projection/handler-specs
                                                   :skip-unknown? true)}
             :exchange-name "events"
             catch-up-opts)
      {:event-store-ds :event-store-ds
       :read-db-ds     :read-db-ds
       :rabbitmq       :rabbitmq
       :event-migrator :event-migrator
       :read-migrator  :read-migrator}))))

(defn dev-full-system
  "Returns the full system using Testcontainers for all infrastructure.
   Uses a 1s catch-up interval for fast test feedback."
  []
  (full-system {:event-store           {:mode :testcontainers}
                :read-db               {:mode :testcontainers}
                :rabbitmq              {:mode :testcontainers}
                :catch-up-interval-ms  1000}))
