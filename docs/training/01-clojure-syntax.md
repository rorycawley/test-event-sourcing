# Clojure Syntax Guide

Everything you need to understand the Clojure syntax used in this codebase. Each section explains the syntax with examples drawn directly from the project.

## Table of Contents

- [Namespace Declaration](#namespace-declaration)
- [Definitions](#definitions)
- [Functions](#functions)
- [Let Bindings](#let-bindings)
- [Destructuring](#destructuring)
- [Control Flow](#control-flow)
- [Loops and Recursion](#loops-and-recursion)
- [Exception Handling](#exception-handling)
- [Atoms and State](#atoms-and-state)
- [Java Interop](#java-interop)
- [Reader Syntax](#reader-syntax)
- [Metadata](#metadata)
- [Data Literals](#data-literals)
- [Threading Macros](#threading-macros)

---

## Namespace Declaration

Every Clojure file starts with `ns`, which names the namespace, optionally provides a docstring, and declares dependencies.

```clojure
(ns es.store
  "Append-only event store backed by Postgres."
  (:require [next.jdbc :as jdbc]                    ;; require with alias
            [next.jdbc.result-set :as rs]            ;; another alias
            [clojure.data.json :as json]
            [es.schema :as schema]
            [malli.core :as m])
  (:import [org.postgresql.util PGobject]            ;; Java class imports
           [java.sql Timestamp]
           [java.security SecureRandom]
           [java.util UUID]))
```

**`:require`** loads Clojure namespaces. `:as` creates a short alias so you write `jdbc/execute!` instead of `next.jdbc/execute!`.

**`:import`** loads Java classes. The vector format is `[package Class1 Class2]`.

**`:refer`** pulls specific symbols into the current namespace (no alias needed):

```clojure
(ns es.projection-test
  (:require [clojure.test :refer [deftest is use-fixtures]]))
```

After this, you can write `deftest` directly instead of `clojure.test/deftest`.

**`:reload`** forces re-reading a namespace (used at the REPL, not in source files):

```clojure
(require '[bank.account :as account] :reload)
```

---

## Definitions

### `def` -- Define a Value

Binds a name to a value at the top level of a namespace.

```clojure
;; Simple value
(def default-batch-size 1000)

;; With metadata (see Metadata section)
(def ^:private ^SecureRandom secure-random (SecureRandom.))
```

### `defn` -- Define a Function

```clojure
(defn evolve-state
  "Reconstructs aggregate state from its event history."
  [decider events]
  (reduce (:evolve decider) (:initial-state decider) events))
```

The parts: name, optional docstring, parameter vector `[...]`, body.

### `defn-` -- Define a Private Function

Same as `defn` but not accessible from other namespaces.

```clojure
(defn- normalize-events
  "Validates generic event envelope shape."
  [events]
  (mapv (fn [index event]
          (when-not (m/validate event-envelope-schema event)
            (invalid-event-envelope! events index event))
          (update event :event-version #(or % 1)))
        (range)
        events))
```

### Multi-arity Functions

A single function can accept different numbers of arguments:

```clojure
(defn migrator-component
  ([] (map->Migrator {}))                              ;; zero args
  ([{:keys [migration-dir]}]                           ;; one arg
   (map->Migrator {:migration-dir migration-dir})))
```

### Variadic Functions (& rest args)

`&` collects remaining arguments. Combined with `:keys`, this creates keyword arguments:

```clojure
(defn append-events!
  [ds stream-id expected-version idempotency-key command events
   & {:keys [on-events-appended]}]       ;; optional keyword arg
  ...)
```

Called as: `(append-events! ds "s1" 0 nil cmd evts :on-events-appended hook)`

### `defrecord` -- Define a Record Type

Records are named types with fixed fields. Used here for Component lifecycle:

```clojure
(defrecord Datasource [mode jdbc-url user password image
                       container datasource]
  component/Lifecycle
  (start [this]
    (case mode
      :testcontainers
      (let [pg (infra/start-postgres!)]
        (assoc this :container pg :datasource (infra/->datasource pg)))
      :jdbc-url
      (assoc this :datasource (jdbc/get-datasource {:jdbcUrl jdbc-url}))))
  (stop [this]
    (when (and (= :testcontainers mode) container)
      (infra/stop-postgres! container))
    (assoc this :container nil :datasource nil)))
```

`defrecord` creates a Java class with the listed fields. `component/Lifecycle` is a protocol (interface) being implemented with `start` and `stop` methods.

---

## Functions

### Anonymous Functions (`fn`)

```clojure
;; Full form
(fn [tx event context]
  (handler tx event context))

;; With name (useful for stack traces)
(fn handle-event [tx event context]
  (handler tx event context))
```

### Short Anonymous Functions (`#()`)

`#()` is shorthand. `%` is the first arg, `%2` the second, etc.

```clojure
;; These are equivalent:
(update event :event-version #(or % 1))
(update event :event-version (fn [v] (or v 1)))
```

### `letfn` -- Local Function Definitions

Defines mutually-recursive local functions:

```clojure
(letfn [(backoff-ms [attempt]
          (let [base (* base-backoff-ms (bit-shift-left 1 (dec (max 1 attempt))))
                jitter-ms (rand-int (max 1 (/ base 2)))]
            (min (+ base jitter-ms) max-backoff-ms)))]
  ...)
```

---

## Let Bindings

`let` creates local names. Bindings are evaluated left to right, and later bindings can reference earlier ones.

```clojure
(let [events    (store/load-stream ds stream-id)      ;; first binding
      state     (evolve-state decider events)          ;; uses `events`
      version   (current-version events)               ;; uses `events`
      new-events ((:decide decider) command state)]    ;; uses `state`
  ;; body -- all bindings are available here
  (store/append-events! ds stream-id version ...))
```

### `binding` -- Dynamic Variable Rebinding

Temporarily overrides `^:dynamic` vars within a scope:

```clojure
;; Redirect stdout to stderr
(binding [*out* *err*]
  (println "Outbox poller error:" (.getMessage e)))
```

---

## Destructuring

Destructuring extracts values from data structures directly in binding positions (let, fn args, loop, etc.).

### Map Destructuring with `:keys`

```clojure
;; Instead of:
(let [name (get command :command-type)
      data (get command :data)]
  ...)

;; Write:
(let [{:keys [command-type data]} command]
  ...)
```

### Map Destructuring with `:or` (defaults) and `:as` (whole value)

```clojure
(defn start-poller!
  [ds publish-fn & {:keys [poll-interval-ms batch-size on-error]
                    :or   {poll-interval-ms 100        ;; defaults
                           batch-size       100
                           on-error         default-handler}}]
  ...)
```

`:as` binds the entire map alongside destructured keys:

```clojure
(defn process-new-events!
  [event-store-ds read-db-ds {:keys [projection-name] :as config}]
  ;; config is the full map, projection-name is extracted
  ...)
```

### Sequential Destructuring

```clojure
;; Extract first element, bind rest
(let [[command & remaining] commands]
  ...)

;; CLI args: extract first from variadic
(defn -main [& [command]]
  (case command
    "migrate" (migrations/migrate! ds)
    ...))
```

### Nested Destructuring

```clojure
(fn [ch' {:keys [delivery-tag]} ^bytes _payload]
  (rabbitmq/ack! ch' delivery-tag))
```

The second argument is a map, and we extract `:delivery-tag` from it.

---

## Control Flow

### `if` -- Two Branches

```clojure
(if (= ::idempotent idem-status)
  :idempotent                    ;; then
  (let [events ...]             ;; else
    (append! ...)))
```

### `when` -- One Branch (nil if false)

```clojure
(when (seq new-events)
  (doseq [event new-events]
    (project-event! tx event config))
  (advance-checkpoint! tx ...))
```

### `when-not` -- Negated when

```clojure
(when-not (m/validate event-envelope-schema event)
  (throw (ex-info "Invalid event envelope" {:event event})))
```

### `if-let` / `when-let` -- Bind and Branch

```clojure
(if-let [handler (get step-handlers status)]
  (handler ds transfer-id)        ;; handler is bound and truthy
  (throw (ex-info "No handler" {:status status})))

(when-let [existing (load-idempotency-record conn key)]
  ;; existing is bound and non-nil
  (check-collision! existing incoming))
```

### `if-not` -- Negated if

```clojure
(if-not idempotency-key
  :new
  (inspect-idempotency-key! ...))
```

### `case` -- Constant Dispatch (like switch)

```clojure
(case (:command-type command)
  :open-account  (open-account state command)
  :deposit       (deposit state command)
  :withdraw      (withdraw state command)
  (throw (ex-info "Unknown command" {:command-type (:command-type command)})))
```

The last form (no test value) is the default.

### `cond` -- Multi-branch Conditional

```clojure
(cond
  (keyword? command-type) (name command-type)
  (string? command-type)  command-type
  (nil? command-type)     nil
  :else                   (str command-type))
```

`:else` is conventional for the default (any truthy value works).

### `cond->` / `cond->>` -- Conditional Threading

Threads a value through forms, but only applies a step when its test is true:

```clojure
(cond-> {:jdbcUrl jdbc-url}
  db-user     (assoc :user db-user)          ;; only if db-user is truthy
  db-password (assoc :password db-password))  ;; only if db-password is truthy
```

---

## Loops and Recursion

### `loop` / `recur` -- Tail-Recursive Loop

```clojure
(loop [total 0]
  (let [n (process-new-events! ...)]
    (if (pos? n)
      (recur (+ total n))     ;; jump back to loop with new total
      total)))                 ;; return total
```

`recur` must be in tail position (the last expression). It jumps back to `loop` with new bindings.

### `doseq` -- Iterate for Side Effects

```clojure
(doseq [[i event] (map-indexed vector events)]
  (jdbc/execute-one! tx
    ["INSERT INTO events (...) VALUES (?, ?, ?, ?)"
     (uuid-v7) stream-id (+ expected-version i 1)
     (:event-type event)]))
```

Like `for` but doesn't build a return value. Used for database writes, printing, etc.

### `dotimes` -- Loop N Times

```clojure
(dotimes [_ 3]
  (try (do-catch-up!) (catch Exception _)))
```

### `while` -- Loop While Condition is True

```clojure
(while @running
  (try
    (poll-and-publish! ds publish-fn batch-size)
    (catch Exception e (on-error e)))
  (Thread/sleep poll-interval-ms))
```

### `for` -- List Comprehension (lazy sequence)

```clojure
(for [{:keys [name baseline current delta-pct status]} rows]
  (str "| " name " | " (format-value baseline) " | ..."))
```

---

## Exception Handling

### `try` / `catch` / `finally`

```clojure
(try
  (handle! ds decider command)
  (catch clojure.lang.ExceptionInfo e        ;; catch specific type
    (if (optimistic-concurrency-conflict? e)
      (recur (inc attempt))                   ;; retry
      (throw e)))                             ;; re-throw others
  (finally
    (reset! catching-up? false)))             ;; always runs
```

### `throw` and `ex-info`

`ex-info` creates an exception with a data map attached:

```clojure
(throw (ex-info "Optimistic concurrency conflict"
                {:error/type       :concurrency/optimistic-conflict
                 :error/retryable? true
                 :stream-id        stream-id
                 :expected-version expected-version
                 :actual-version   actual-version}))
```

### `ex-data` -- Extract Data from ExceptionInfo

```clojure
(catch clojure.lang.ExceptionInfo e
  (let [data (ex-data e)]
    (when (:error/retryable? data)
      (recur (inc attempt)))))
```

---

## Atoms and State

Atoms provide thread-safe mutable state.

```clojure
;; Create
(let [running (atom true)
      consecutive-fails (atom 0)]

  ;; Read (dereference)
  (while @running ...)           ;; @ is shorthand for (deref running)

  ;; Set unconditionally
  (reset! running false)

  ;; Update with a function
  (swap! consecutive-fails inc)  ;; atomically increments

  ;; Compare-and-set (CAS) -- only sets if current value matches expected
  (when (compare-and-set! catching-up? false true)
    ;; only one thread enters here
    ...))
```

---

## Java Interop

### Constructor Call (`ClassName.`)

```clojure
(PGobject.)                      ;; new PGobject()
(UUID. msb lsb)                  ;; new UUID(msb, lsb)
(Thread. (fn [] ...))            ;; new Thread(Runnable)
(PostgreSQLContainer. image)     ;; new PostgreSQLContainer(image)
```

### Instance Method Call (`.method`)

```clojure
(.setType pg-object "jsonb")     ;; pgObject.setType("jsonb")
(.getValue pg)                   ;; pg.getValue()
(.getJdbcUrl container)          ;; container.getJdbcUrl()
(.start container)               ;; container.start()
(.join thread 5000)              ;; thread.join(5000)
(.isAlive thread)                ;; thread.isAlive()
```

### Static Method Call (`Class/method`)

```clojure
(System/currentTimeMillis)       ;; System.currentTimeMillis()
(System/getenv "JDBC_URL")       ;; System.getenv("JDBC_URL")
(DockerImageName/parse image)    ;; DockerImageName.parse(image)
(Integer/parseInt s)             ;; Integer.parseInt(s)
(Math/ceil x)                    ;; Math.ceil(x)
```

### `doto` -- Thread Object Through Methods

Calls multiple methods on the same object, returns the object:

```clojure
;; These are equivalent:
(doto (PGobject.)
  (.setType "jsonb")
  (.setValue (json/write-str m)))

;; vs:
(let [pg (PGobject.)]
  (.setType pg "jsonb")
  (.setValue pg (json/write-str m))
  pg)
```

Used heavily for configuring Java objects:

```clojure
(doto (Thread. (fn [] ...))
  (.setDaemon true)
  (.setName "outbox-poller")
  (.start))
```

---

## Reader Syntax

### `@` -- Dereference

Shorthand for `(deref x)`. Works on atoms, refs, promises, futures, delays.

```clojure
@running                ;; (deref running) -- read atom value
@(promise)              ;; block until promise is delivered
```

### `#()` -- Anonymous Function Literal

```clojure
#(or % 1)              ;; (fn [x] (or x 1))
#(+ %1 %2)             ;; (fn [a b] (+ a b))
```

### `#{}` -- Set Literal

```clojure
#{:completed :failed}   ;; hash set of two keywords
#{"migrate" "rollback" "status"}
```

### `#""` -- Regex Literal

```clojure
#"^[a-zA-Z_][a-zA-Z0-9_]*$"   ;; compiled regex pattern
```

### `'` -- Quote

Prevents evaluation. Rarely needed in source, common at the REPL:

```clojure
(require '[bank.account :as account])
```

### `_` -- Unused Binding Convention

Not special syntax, just a naming convention for ignored values:

```clojure
(catch InterruptedException _     ;; we don't use the exception
  (reset! running false))

(fn [_ {:keys [delivery-tag]} _]  ;; ignore first and third args
  ...)
```

### `#'` -- Var Quote

References the var itself rather than its value. Used to call private functions in tests:

```clojure
(#'async-proj/skip-poison-event! read-db-ds ...)
```

---

## Metadata

Metadata is extra information attached to vars, not visible during normal use.

### `^:private` -- Private Var

```clojure
(def ^:private base-config {...})
```

### `^:dynamic` -- Dynamic Var (rebindable with `binding`)

```clojure
(def ^:dynamic *ds* nil)          ;; earmuffs (*name*) are conventional

(binding [*ds* (create-datasource)]
  ;; code here sees the new value of *ds*
  ...)
```

### `^:const` -- Compile-time Constant

```clojure
(def ^:const default-postgres-image "paradedb/paradedb:latest")
```

### `^Type` -- Type Hints

Tell the compiler what Java type to expect, avoiding reflection:

```clojure
(def ^:private ^SecureRandom secure-random (SecureRandom.))

(defn <-pgobject [^PGobject pg]
  (when pg (json/read-str (.getValue pg) :key-fn keyword)))

(.interrupt ^Thread catch-up-timer)
```

---

## Data Literals

### Keywords

```clojure
:open-account            ;; simple keyword
:error/type              ;; namespaced keyword
:domain/insufficient-funds
::idempotent             ;; auto-resolved to current namespace (e.g., :es.store/idempotent)
```

### Maps

```clojure
{:event-type    "account-opened"
 :event-version 1
 :payload       {:owner "Alice"}}
```

### Vectors

```clojure
[{:event-type "money-deposited" :payload {:amount 100}}
 {:event-type "money-deposited" :payload {:amount 50}}]
```

### Sets

```clojure
#{:completed :failed}
```

### Strings

```clojure
"plain string"
"SQL with ? placeholders"
```

### Regex

```clojure
#"^[a-zA-Z_][a-zA-Z0-9_]*$"
```

### Numbers

```clojure
42         ;; long
3.14       ;; double
0xFFF      ;; hex literal
0x8000000000000000   ;; hex long
```

---

## Threading Macros

Threading macros reduce nesting by "threading" a value through a series of forms.

### `->` -- Thread First

Inserts the result as the **first** argument of each form:

```clojure
;; These are equivalent:
(-> (jdbc/execute-one! tx ["SELECT MAX(stream_sequence) ..."])
    :stream-sequence)

(:stream-sequence (jdbc/execute-one! tx ["SELECT MAX(stream_sequence) ..."]))
```

Longer chains:

```clojure
(-> row
    (update :payload store/<-pgobject)
    (update :created-at store/<-timestamp))
```

### `->>` -- Thread Last

Inserts as the **last** argument. Useful for sequence operations:

```clojure
(->> (range 10)
     (map inc)
     (filter even?))
;; => (2 4 6 8 10)
```

### `some->` -- Thread First, Short-Circuit on Nil

Like `->` but stops and returns `nil` if any step produces `nil`:

```clojure
(some-> (jdbc/execute-one! conn ["SELECT ... WHERE key = ?" key])
        (update :command-payload <-pgobject))
;; Returns nil if the query returns nil, instead of crashing on update
```

### `cond->` -- Conditional Thread First

Only applies a step when its test is true:

```clojure
(cond-> {:jdbcUrl jdbc-url}
  user     (assoc :user user)       ;; only if user is truthy
  password (assoc :password password))
```

---

## `comment` -- Development Scratch Pad

`comment` forms are ignored by the compiler. Used as an inline REPL scratch pad:

```clojure
(comment
  ;; Start a database for experimentation
  (def pg (infra/start-postgres!))
  (def ds (infra/->datasource pg))
  (migrations/migrate! ds)

  ;; Try commands interactively
  (decider/handle! ds account/decider
    {:command-type :open-account
     :stream-id "acct-1"
     :data {:owner "Alice"}})
  )
```

This is idiomatic Clojure -- rich comment blocks serve as executable documentation.

---

## `with-redefs` -- Test Double

Temporarily replaces function definitions. Used in tests to mock dependencies:

```clojure
(with-redefs [es.store/load-stream   (fn [_ _] stored-events)
              es.store/append-events! (fn [_ _ _ _ _ events & _]
                                        (reset! appended events)
                                        :ok)]
  ;; Within this body, store functions are replaced with test doubles
  (decider/handle! :fake-ds account/decider command))
```

**Warning:** `with-redefs` is not thread-safe. Only use in single-threaded tests.
