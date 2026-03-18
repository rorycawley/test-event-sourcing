# clojure.data.json -- JSON Serialisation

[clojure.data.json](https://github.com/clojure/data.json) is Clojure's official JSON library. It converts between Clojure data structures and JSON strings.

**Dependency:** `org.clojure/data.json {:mvn/version "2.4.0"}`

## Namespace Used

```clojure
(:require [clojure.data.json :as json])
```

---

## Core Functions

### `json/write-str` -- Clojure Data to JSON String

```clojure
(json/write-str {:amount 100 :currency "USD"})
;; => "{\"amount\":100,\"currency\":\"USD\"}"

(json/write-str {:name "Alice" :tags ["admin" "user"]})
;; => "{\"name\":\"Alice\",\"tags\":[\"admin\",\"user\"]}"
```

### `json/read-str` -- JSON String to Clojure Data

```clojure
(json/read-str "{\"amount\":100,\"currency\":\"USD\"}")
;; => {"amount" 100, "currency" "USD"}
;;    ^ string keys by default

(json/read-str "{\"amount\":100}" :key-fn keyword)
;; => {:amount 100}
;;    ^ keyword keys with :key-fn
```

---

## Usage in This Project

### JSONB Storage (Event Payloads)

PostgreSQL stores event payloads as JSONB. Conversion happens through `PGobject`:

```clojure
;; es/store.clj -- Clojure map to JSONB for INSERT
(defn ->pgobject [m]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str m))))

;; es/store.clj -- JSONB to Clojure map after SELECT
(defn <-pgobject [^PGobject pg]
  (when pg
    (json/read-str (.getValue pg) :key-fn keyword)))
```

### RabbitMQ Message Serialisation

Outbox events are published to RabbitMQ as JSON strings:

```clojure
;; es/outbox.clj
(let [message (json/write-str
                {:global-sequence (:global-sequence row)
                 :stream-id       (:stream-id row)
                 :event-type      (:event-type row)
                 :event-version   (:event-version row)
                 :payload         payload})]
  (publish-fn message))
```

Consumers parse them back:

```clojure
;; test/es/outbox_test.clj
(let [parsed (json/read-str (first @messages) :key-fn keyword)]
  (is (= 1 (:global-sequence parsed)))
  (is (= "test-created" (:event-type parsed))))
```

---

## Key Options

| Option | Purpose | Example |
|---|---|---|
| `:key-fn keyword` | Convert JSON keys to keywords | `{"a":1}` -> `{:a 1}` |
| `:key-fn str` | Keep JSON keys as strings (default) | `{"a":1}` -> `{"a" 1}` |

---

## Type Mapping

| JSON | Clojure |
|---|---|
| `"string"` | `"string"` |
| `123` | `123` (long) |
| `1.5` | `1.5` (double) |
| `true` / `false` | `true` / `false` |
| `null` | `nil` |
| `[1, 2, 3]` | `[1 2 3]` (vector) |
| `{"key": "val"}` | `{"key" "val"}` or `{:key "val"}` |
