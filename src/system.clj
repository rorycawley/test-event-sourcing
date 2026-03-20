(ns system
  "Top-level composition root: wires all modules together.

   This is the only namespace that knows about all modules. Each module
   provides its own component wiring; this namespace connects them to
   shared infrastructure (RabbitMQ, event store) and to each other
   via dependency injection.

   Module structure:
     bank.*          — Event producer (accounts, transfers, projections)
     notification.*  — Event consumer (subscribes to integration events)
     es.*            — Framework (event store, outbox, consumer, projections)

   Adding a new module:
     1. Create module-name/reactor.clj and module-name/component.clj
     2. Add its datasource, migrator, consumer group, and projector here
     3. No changes needed to bank.* or other modules"
  (:require [com.stuartsierra.component :as component]
            [es.component :refer [datasource-component migrator-component
                                  search-index-component
                                  rabbitmq-component outbox-poller-component
                                  async-projector-component]]
            [es.projection-kit :as kit]
            [es.outbox :as outbox]
            [modules.bank.infra.account-projection :as account-projection]
            [modules.bank.infra.transfer-projection :as transfer-projection]
            [modules.bank.infra.integration-events :as integration-events]
            [modules.notification.infra.reactor :as reactor]
            [modules.notification.infra.delivery-projection :as notification-projection]
            [modules.notification.infra.components :as notification]))

(defn full-system
  "Returns the full Component system with all modules.

   config:
     {:event-store {:mode :testcontainers}
      :read-db     {:mode :testcontainers}
      :rabbitmq    {:mode :testcontainers}
      :notification-db {:mode :testcontainers}
      :catch-up-interval-ms     30000
      :batch-size               1000
      :max-consecutive-failures 5}"
  [config]
  (let [projector-opts (select-keys config [:catch-up-interval-ms
                                            :batch-size
                                            :max-consecutive-failures])
        catch-up-opts  (mapcat identity projector-opts)]
    (component/system-map
     ;; ── Shared infrastructure ──
     :rabbitmq       (rabbitmq-component (:rabbitmq config))

     ;; ── Bank module: event store ──
     :event-store-ds (datasource-component (:event-store config))
     :event-migrator (component/using (migrator-component)
                                      {:datasource :event-store-ds})

     ;; ── Bank module: read store + projectors ──
     :read-db-ds     (datasource-component (:read-db config))
     :read-migrator  (component/using
                      (migrator-component {:migration-dir "read-migrations"})
                      {:datasource :read-db-ds})

     :search-index   (component/using
                      (search-index-component
                       [account-projection/search-index-config])
                      {:datasource   :read-db-ds
                       :read-migrator :read-migrator})

     :outbox-poller  (component/using
                      (outbox-poller-component
                       {:exchange-name    "events"
                        :poll-interval-ms 100})
                      {:datasource     :event-store-ds
                       :rabbitmq       :rabbitmq
                       :event-migrator :event-migrator})

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
       :read-migrator  :read-migrator})

     ;; ── Notification module ──
     ;; Own database, own migrations, own worker threads.
     ;; The reactor (consumer handler) issues commands to the notification
     ;; aggregate in the shared event store. The notification projector
     ;; reads those events and builds the read model in the notification DB.
     :notification-db
     (datasource-component (:notification-db config))

     :notification-migrator
     (component/using
      (migrator-component {:migration-dir "notification-migrations"})
      {:datasource :notification-db})

     :notification-consumers
     (component/using
      (notification/consumer-group-component
       {:consumer-group         "account-notifications"
        :queue-name             "notifications.account-events"
        :exchange-name          "events"
        :worker-count           3
        :make-handler-specs-fn  reactor/make-handler-specs
        :catch-up-interval-ms   (or (:catch-up-interval-ms config) 30000)})
      {:event-store-ds :event-store-ds
       :consumer-db-ds :notification-db
       :rabbitmq       :rabbitmq
       :event-migrator :event-migrator
       :notification-migrator :notification-migrator})

     ;; Notification projector — reads notification aggregate events from
     ;; the shared event store, writes to the notification module's own DB.
     ;; Note: the outbox hook only publishes bank integration events, so
     ;; notification aggregate events written by the reactor do NOT trigger
     ;; a RabbitMQ wake-up. The projector relies on the catch-up timer to
     ;; pick up new notification events. This is acceptable for notification
     ;; status reads; if real-time freshness is needed, add a second outbox
     ;; hook for notification aggregate writes in the reactor.
     :notification-projector
     (component/using
      (apply async-projector-component
             "projector.notification-status"
             {:projection-name   "notification-status"
              :read-model-tables ["notifications"]
              :handler           (kit/make-handler notification-projection/handler-specs
                                                   :skip-unknown? true)}
             :exchange-name "events"
             catch-up-opts)
      {:event-store-ds        :event-store-ds
       :read-db-ds            :notification-db
       :rabbitmq              :rabbitmq
       :event-migrator        :event-migrator
       :notification-migrator :notification-migrator}))))

(defn make-outbox-hook
  "Creates the outbox hook for the bank module.
   Transforms domain events into integration events before recording."
  []
  (outbox/make-outbox-hook integration-events/domain->integration))

(defn dev-full-system
  "Returns the full system using Testcontainers for all infrastructure.
   Uses a 1s catch-up interval for fast test feedback."
  []
  (full-system {:event-store           {:mode :testcontainers}
                :read-db               {:mode :testcontainers}
                :rabbitmq              {:mode :testcontainers}
                :notification-db       {:mode :testcontainers}
                :catch-up-interval-ms  1000}))
