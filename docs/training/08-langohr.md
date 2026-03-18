# Langohr -- RabbitMQ Client

[Langohr](https://github.com/michaelklishin/langohr) is a Clojure client for RabbitMQ (AMQP 0-9-1). This project wraps it in a thin `es.rabbitmq` namespace so the rest of the codebase doesn't import Langohr directly.

**Dependency:** `com.novemberain/langohr {:mvn/version "5.5.0"}`

## Namespaces Used

```clojure
(:require [langohr.core :as rmq]           ;; connection management
          [langohr.channel :as lch]         ;; channel management
          [langohr.exchange :as le]         ;; exchange declaration
          [langohr.queue :as lq]            ;; queue declaration and binding
          [langohr.basic :as lb]            ;; publish, consume, ack, nack
          [langohr.consumers :as lc])       ;; consumer helpers
```

---

## RabbitMQ Concepts

```
Producer --> Exchange --> Queue --> Consumer

  Exchange: routes messages to queues (fanout = copy to all bound queues)
  Queue:    ordered buffer of messages
  Consumer: subscribes to a queue, receives messages
  Channel:  lightweight connection multiplexer (one per thread)
```

---

## Connection and Channel

### `rmq/connect` -- Open a Connection

```clojure
;; Connect with explicit parameters
(rmq/connect {:host "localhost" :port 5672 :username "guest" :password "guest"})

;; Connect with URI
(rmq/connect {:uri "amqp://guest:guest@localhost:5672"})
```

### `rmq/close` -- Close a Connection

```clojure
(when (rmq/open? conn)
  (rmq/close conn))
```

### `lch/open` -- Open a Channel

Channels are lightweight and cheap. One per thread.

```clojure
(let [ch (lch/open conn)]
  ;; use ch for publishing or consuming
  ...)
```

### `lch/close` -- Close a Channel

```clojure
(when (lch/open? ch)
  (lch/close ch))
```

**Important:** Channels are NOT thread-safe. Don't share a channel across threads. Each thread (poller, consumer) should have its own channel.

---

## Exchanges and Queues

### `le/declare` -- Declare an Exchange

```clojure
(le/declare ch "events" "fanout" {:durable true})
;;               ^name    ^type     ^options
```

Exchange types:
- **fanout** -- copies every message to every bound queue (used in this project)
- **direct** -- routes by routing key
- **topic** -- routes by pattern matching on routing key

### `lq/declare` -- Declare a Queue

```clojure
(lq/declare ch "projector.account-balances"
            {:durable    true
             :exclusive  false
             :auto-delete false
             :arguments  {"x-dead-letter-exchange" "events.dlx"}})
```

Options:
- `:durable true` -- survives broker restart
- `:exclusive false` -- not limited to this connection
- `:auto-delete false` -- not deleted when last consumer disconnects
- `"x-dead-letter-exchange"` -- where rejected messages go

### `lq/bind` -- Bind a Queue to an Exchange

```clojure
(lq/bind ch "projector.account-balances" "events")
;;           ^queue name                  ^exchange name
```

After binding, messages published to the "events" exchange are copied to this queue.

---

## Publishing

### `lb/publish` -- Send a Message

```clojure
(lb/publish ch "events" "" message-string
            {:content-type "application/json"
             :persistent   true})
;;              ^exchange ^routing-key ^payload  ^options
```

- **Exchange:** where to send
- **Routing key:** `""` for fanout (ignored), meaningful for direct/topic
- **Payload:** string or bytes
- `:persistent true` -- message survives broker restart (with durable queue)

In this project, the outbox poller publishes:

```clojure
(defn publish! [ch exchange-name routing-key payload]
  (lb/publish ch exchange-name routing-key payload
              {:content-type "application/json"
               :persistent   true}))
```

---

## Consuming

### `lb/consume` + `lc/create-default` -- Subscribe to a Queue

```clojure
(let [handler-fn (fn [ch' {:keys [delivery-tag]} ^bytes payload]
                   ;; ch' is the channel
                   ;; delivery-tag identifies this specific message
                   ;; payload is the message body as bytes
                   (process-message! (String. payload))
                   (lb/ack ch' delivery-tag))

      consumer-tag (lb/consume ch "projector.account-balances"
                               (lc/create-default ch
                                 {:handle-delivery-fn handler-fn}))]
  ;; consumer-tag identifies this consumer for later cancellation
  consumer-tag)
```

The handler function receives three arguments:
1. **Channel** -- for ack/nack
2. **Metadata** -- contains `:delivery-tag`, `:routing-key`, etc.
3. **Payload** -- raw bytes of the message body

### `lb/ack` -- Acknowledge a Message

Tells RabbitMQ the message was processed successfully:

```clojure
(lb/ack ch delivery-tag)
;; Message is removed from the queue
```

### `lb/nack` -- Negative Acknowledge

Tells RabbitMQ the message was NOT processed:

```clojure
(lb/nack ch delivery-tag false true)
;;                        ^multiple ^requeue
;; false = only this message (not all unacked)
;; true  = put it back in the queue for retry
```

In this project:

```clojure
(fn [ch' {:keys [delivery-tag]} ^bytes _payload]
  (try
    (do-catch-up!)
    (rabbitmq/ack! ch' delivery-tag)
    (catch Exception e
      (on-error e)
      (try
        (rabbitmq/nack! ch' delivery-tag :requeue true)
        (catch Exception _)))))
```

### `lb/cancel` -- Stop Consuming

```clojure
(lb/cancel ch consumer-tag)
;; Unsubscribes from the queue
```

---

## Quality of Service

### `lb/qos` -- Set Prefetch Count

Controls how many unacknowledged messages a consumer can have:

```clojure
(lb/qos ch 1)
;; Deliver at most 1 message before waiting for ACK
;; This serialises processing: one message at a time
```

This project uses `prefetch=1` because each message triggers a catch-up that processes ALL pending events. There's no benefit to prefetching multiple "wake up" signals.

---

## Dead Letter Queues (DLQ)

Messages that are rejected (nacked without requeue, or that exceed TTL) go to a dead letter exchange:

```clojure
;; Declare the dead letter exchange and queue
(le/declare ch "events.dlx" "fanout" {:durable true})
(lq/declare ch "projector.account-balances.dlq"
            {:durable true :exclusive false :auto-delete false})
(lq/bind ch "projector.account-balances.dlq" "events.dlx")

;; Declare the main queue with DLX configuration
(lq/declare ch "projector.account-balances"
            {:durable true
             :arguments {"x-dead-letter-exchange" "events.dlx"}})
```

The DLQ serves as observability: you can inspect rejected messages to diagnose problems.

---

## Complete Flow in This Project

```
1. Event appended to Postgres
2. Outbox row written (same transaction)
3. Outbox poller reads unpublished rows
4. Poller publishes JSON to "events" exchange (fanout)
5. RabbitMQ copies message to each bound queue
6. Consumer receives message on "projector.account-balances" queue
7. Consumer calls process-new-events! (reads from event store, writes to read DB)
8. Consumer ACKs the message
```

The message content doesn't matter -- it's just a "wake up" signal. The actual event data is always read from the event store.
