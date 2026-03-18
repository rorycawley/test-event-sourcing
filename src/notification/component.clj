(ns notification.component
  "Component wiring for the notification module.

   Provides a self-contained ConsumerGroup component that manages:
   - RabbitMQ queue subscription
   - Worker threads (via es.consumer)
   - Catch-up timer (for disaster recovery from Postgres)

   Dependencies (injected):
   - :event-store-ds — read-only access to the event store (for catch-up)
   - :consumer-db-ds — the notification module's own database
   - :rabbitmq       — shared RabbitMQ connection

   This component knows nothing about bank.* — it receives either static
   handler-specs or a make-handler-specs-fn that constructs them at start
   time (for reactors that need event-store-ds to issue commands)."
  (:require [com.stuartsierra.component :as component]
            [es.consumer :as consumer]
            [es.rabbitmq :as rabbitmq]))

(defrecord ConsumerGroup [consumer-group queue-name exchange-name
                          handler-specs make-handler-specs-fn
                          worker-count queue-capacity
                          catch-up-interval-ms
                          ;; injected dependencies
                          event-store-ds consumer-db-ds rabbitmq
                          ;; runtime state
                          setup-channel worker-group]
  component/Lifecycle
  (start [this]
    (let [es-ds   (:datasource event-store-ds)
          cd-ds   (:datasource consumer-db-ds)
          conn    (:connection rabbitmq)
          ;; Use a dedicated channel for queue/exchange declarations only.
          ;; Worker channels are created inside make-worker-group (one per worker).
          setup-ch (rabbitmq/open-channel conn)
          exch    (or exchange-name "events")
          dlx     (str exch ".dlx")
          dlq     (str queue-name ".dlq")
          specs   (if make-handler-specs-fn
                    (make-handler-specs-fn es-ds)
                    handler-specs)
          _       (rabbitmq/declare-fanout-exchange! setup-ch exch)
          _       (rabbitmq/declare-dlq! setup-ch dlx dlq)
          _       (rabbitmq/declare-queue! setup-ch queue-name exch
                                           :dead-letter-exchange dlx)
          wg      (consumer/make-worker-group
                   conn queue-name es-ds cd-ds consumer-group specs
                   :worker-count (or worker-count 3)
                   :queue-capacity (or queue-capacity 10)
                   :catch-up-interval-ms (or catch-up-interval-ms 30000))]
      (assoc this :setup-channel setup-ch :worker-group wg)))

  (stop [this]
    (try
      (when worker-group
        (consumer/stop-worker-group! worker-group))
      (finally
        (when setup-channel
          (rabbitmq/close-channel setup-channel))))
    (assoc this :setup-channel nil :worker-group nil)))

(defn consumer-group-component
  "Creates a ConsumerGroup component for a notification module.

   config:
     :consumer-group       — string identifier (e.g. \"account-notifications\")
     :queue-name           — RabbitMQ queue (e.g. \"notifications.account-events\")
     :handler-specs        — {event-type → (fn [tx event])} (static)
     :make-handler-specs-fn — (fn [event-store-ds] handler-specs) (dynamic)
     :exchange-name        — RabbitMQ exchange (default \"events\")
     :worker-count         — number of worker threads (default 3)
     :queue-capacity       — bounded queue size (default 100)
     :catch-up-interval-ms — catch-up timer interval (default 30000)

   Provide either :handler-specs or :make-handler-specs-fn, not both.
   The fn variant is used when handlers need access to event-store-ds
   at runtime (e.g. reactors that issue commands to aggregates)."
  [config]
  (map->ConsumerGroup (select-keys config [:consumer-group :queue-name
                                           :exchange-name :handler-specs
                                           :make-handler-specs-fn
                                           :worker-count :queue-capacity
                                           :catch-up-interval-ms])))
