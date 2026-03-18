# Malli -- Data Validation and Generation

[Malli](https://github.com/metosin/malli) is a data-driven schema library for Clojure. It validates data, generates test data, and provides human-readable error explanations. This project uses it for command and event validation.

**Dependency:** `metosin/malli {:mvn/version "0.16.4"}`

## Namespaces Used

```clojure
(:require [malli.core :as m])        ;; validation
(:require [malli.generator :as mg])  ;; test data generation
```

---

## Schema Definition

Malli schemas are plain Clojure data (vectors and maps):

```clojure
;; A string that can't be empty
[:string {:min 1}]

;; A positive integer
pos-int?

;; A map with required keys
[:map
 [:owner [:string {:min 1}]]]

;; A map with required and optional keys
[:map
 [:amount pos-int?]
 [:origin {:optional true} :string]
 [:currency {:optional true} :string]]
```

### Schemas in This Project

**Shared schemas** (`es/schema.clj`):

```clojure
(def non-empty-string [:string {:min 1}])
```

**Event envelope schema** (`es/store.clj`):

```clojure
(def ^:private event-envelope-schema
  [:map
   [:event-type non-empty-string]
   [:payload map?]
   [:event-version {:optional true} pos-int?]])
```

**Domain command schemas** (`bank/account.clj`):

```clojure
;; Defined as a map of {command-type -> payload-schema}
(def command-schemas
  {:open-account [:map [:owner [:string {:min 1}]]]
   :deposit      [:map [:amount pos-int?]]
   :withdraw     [:map [:amount pos-int?]]
   :close-account [:map]})
```

**Domain event schemas** (`bank/account.clj`):

```clojure
(def event-schemas
  {["account-opened" 1]   [:map [:owner :string]]
   ["money-deposited" 3]  [:map [:amount pos-int?]
                                [:origin :string]
                                [:currency :string]]
   ["money-withdrawn" 1]  [:map [:amount pos-int?]]
   ["account-closed" 1]   [:map]})
```

---

## Core Functions

### `m/validate` -- Check if Data Matches Schema

Returns `true` or `false`.

```clojure
(m/validate pos-int? 42)        ;; => true
(m/validate pos-int? -1)        ;; => false
(m/validate pos-int? "hello")   ;; => false

(m/validate [:map [:owner [:string {:min 1}]]]
            {:owner "Alice"})
;; => true

(m/validate [:map [:owner [:string {:min 1}]]]
            {:owner ""})
;; => false (string too short)
```

Used in the codebase for validation before processing:

```clojure
;; es/store.clj -- validate event envelopes
(when-not (m/validate event-envelope-schema event)
  (invalid-event-envelope! events index event))

;; es/decider_kit.clj -- validate command payloads
(when-not (m/validate command-schema command)
  (throw (ex-info "Invalid command" {:explain (m/explain command-schema command)})))
```

### `m/explain` -- Get Detailed Validation Errors

Returns a map describing what went wrong, or `nil` if valid.

```clojure
(m/explain [:map [:amount pos-int?]]
           {:amount -5})
;; => {:schema [:map [:amount pos-int?]]
;;     :value {:amount -5}
;;     :errors [{:path [:amount]
;;               :in [:amount]
;;               :schema pos-int?
;;               :value -5}]}

(m/explain [:map [:amount pos-int?]]
           {:amount 42})
;; => nil (valid)
```

Used in error messages:

```clojure
(throw (ex-info "Invalid event envelope"
                {:error/type :store/invalid-event-envelope
                 :event      event
                 :explain    (m/explain event-envelope-schema event)}))
```

---

## Test Data Generation

### `mg/generator` -- Create a Data Generator

Malli can automatically generate random data that matches a schema. Used for property-based testing.

```clojure
(require '[malli.generator :as mg])

;; Generate random positive integers
(mg/generate pos-int?)
;; => 42 (random each time)

;; Generate random maps matching a schema
(mg/generate [:map [:owner [:string {:min 1}]]])
;; => {:owner "xK2p"} (random each time)
```

### `mg/generator` with test.check

Returns a generator compatible with `clojure.test.check`:

```clojure
(def deposit-event-gen
  (mg/generator [:map
                 [:amount pos-int?]
                 [:origin [:string {:min 1}]]
                 [:currency [:string {:min 1}]]]))

;; Used in property-based tests:
(tc/quick-check 200
  (prop/for-all [payload deposit-event-gen]
    (let [event {:event-type "money-deposited"
                 :event-version 3
                 :payload payload}]
      (pos-int? (:amount (:payload event))))))
```

---

## Schema Types Reference

| Schema | Matches | Example |
|---|---|---|
| `:string` | Any string | `"hello"` |
| `[:string {:min 1}]` | Non-empty string | `"a"` |
| `pos-int?` | Positive integer | `42` |
| `:int` | Any integer | `-3`, `0`, `42` |
| `map?` | Any map | `{}`, `{:a 1}` |
| `:keyword` | Any keyword | `:foo` |
| `[:map [:k schema]]` | Map with required key `:k` | `{:k "value"}` |
| `[:map [:k {:optional true} schema]]` | Map with optional key | `{}` or `{:k "v"}` |

---

## Why Malli (vs spec, Schema)

- **Data-driven:** Schemas are plain data (vectors, maps), not macros or protocols. They can be stored in vars, merged, transformed.
- **Generators included:** No separate library for test data generation.
- **Performance:** Validation is fast, suitable for hot paths.
- **Error messages:** `m/explain` gives structured, programmatic errors.
