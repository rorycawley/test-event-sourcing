(ns es.rabbitmq
  "Thin wrapper around Langohr for RabbitMQ connection management.

   Isolates the rest of the system from the Langohr API so that
   only this file changes if the client library changes."
  (:require [langohr.core :as rmq]
            [langohr.channel :as lch]
            [langohr.exchange :as le]
            [langohr.queue :as lq]
            [langohr.basic :as lb]
            [langohr.consumers :as lc]))

;; ——— Connection lifecycle ———

(defn connect!
  "Opens a RabbitMQ connection. Returns the connection object.

   opts: {:host \"localhost\" :port 5672 :username \"guest\" :password \"guest\"}
   or:   {:uri \"amqp://user:pass@host:port/vhost\"}"
  [opts]
  (rmq/connect opts))

(defn disconnect!
  "Closes a RabbitMQ connection."
  [conn]
  (when (rmq/open? conn)
    (rmq/close conn)))

;; ——— Channel ———

(defn open-channel
  "Opens a new channel on the given connection."
  [conn]
  (lch/open conn))

(defn close-channel
  "Closes a channel."
  [ch]
  (when (lch/open? ch)
    (lch/close ch)))

;; ——— Channel configuration ———

(defn set-prefetch!
  "Sets the prefetch count (QoS) for the channel.
   With prefetch=1, RabbitMQ delivers one message at a time,
   waiting for an ACK before sending the next."
  [ch prefetch-count]
  (lb/qos ch prefetch-count))

;; ——— Exchange & Queue ———

(defn declare-fanout-exchange!
  "Declares a durable fanout exchange. Idempotent."
  [ch exchange-name]
  (le/declare ch exchange-name "fanout" {:durable true}))

(defn declare-queue!
  "Declares a durable queue and binds it to the given exchange. Idempotent.

   opts:
     :dead-letter-exchange — if provided, rejected messages (nack with
                             requeue=false) are routed to this exchange"
  [ch queue-name exchange-name & {:keys [dead-letter-exchange]}]
  (lq/declare ch queue-name
              (cond-> {:durable true :auto-delete false}
                dead-letter-exchange
                (assoc :arguments {"x-dead-letter-exchange" dead-letter-exchange})))
  (lq/bind ch queue-name exchange-name))

(defn declare-dlq!
  "Declares a dead letter exchange and queue for inspection of rejected messages.
   The DLQ is a simple fanout exchange bound to a durable queue.
   Returns the queue name."
  [ch dlx-name dlq-name]
  (le/declare ch dlx-name "fanout" {:durable true})
  (lq/declare ch dlq-name {:durable true :auto-delete false})
  (lq/bind ch dlq-name dlx-name)
  dlq-name)

;; ——— Publish & Consume ———

(defn publish!
  "Publishes a message (string payload) to the given exchange.
   Uses persistent delivery mode."
  [ch exchange-name routing-key ^String payload]
  (lb/publish ch exchange-name routing-key payload
              {:content-type  "application/json"
               :delivery-mode 2}))

(defn consume!
  "Starts a consumer on the given queue with explicit acknowledgment.
   handler-fn is called as (handler-fn ch metadata payload-bytes).
   Returns the consumer tag."
  [ch queue-name handler-fn]
  (lb/consume ch queue-name (lc/create-default ch {:handle-delivery-fn handler-fn})
              {:auto-ack false}))

(defn ack!
  "Acknowledges a message."
  [ch delivery-tag]
  (lb/ack ch delivery-tag))

(defn nack!
  "Negatively acknowledges a message. Requeues by default."
  [ch delivery-tag & {:keys [requeue] :or {requeue true}}]
  (lb/nack ch delivery-tag false requeue))

(defn cancel!
  "Cancels a consumer by its consumer tag."
  [ch consumer-tag]
  (lb/cancel ch consumer-tag))
