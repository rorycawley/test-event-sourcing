# Clojure Core Functions Guide

Every `clojure.core` function and macro used in this codebase, organised by category. Each entry includes the function signature, what it does, and real examples from the project.

## Table of Contents

- [Collection Creation](#collection-creation)
- [Map Operations](#map-operations)
- [Sequence Operations](#sequence-operations)
- [Predicates and Comparison](#predicates-and-comparison)
- [Arithmetic](#arithmetic)
- [Bitwise Operations](#bitwise-operations)
- [String and Naming](#string-and-naming)
- [Type Conversion](#type-conversion)
- [Function Utilities](#function-utilities)
- [I/O and Printing](#io-and-printing)
- [Error Handling](#error-handling)
- [Concurrency Primitives](#concurrency-primitives)
- [Iteration and Side Effects](#iteration-and-side-effects)
- [Miscellaneous](#miscellaneous)

---

## Collection Creation

### `vec` / `vector`

Convert or create a vector.

```clojure
(vec (sort durations))      ;; convert sorted seq to vector
(vector 1 2 3)              ;; => [1 2 3]
```

### `set`

Convert a collection to a set.

```clojure
(set (map :table-name tables))   ;; => #{"events" "idempotency_keys" ...}
```

---

## Map Operations

### `assoc` -- Associate Key-Value Pairs

Returns a new map with the key-value pair(s) added or replaced.

```clojure
(assoc state :status :open :owner (:owner payload))
;; state = {:status :not-found, :balance 0}
;; => {:status :open, :owner "Alice", :balance 0}

(assoc this :container pg :datasource ds)
```

### `dissoc` -- Remove Keys

```clojure
(dissoc m :temporary-field)
```

### `get` -- Lookup by Key

```clojure
(get handler-specs event-type)   ;; returns nil if not found
(get handler-specs "account-opened")
```

Keywords are also functions that look themselves up in maps:

```clojure
(:status state)         ;; same as (get state :status)
(:balance state 0)      ;; with default value 0
```

### `get-in` -- Nested Lookup

```clojure
(get-in event [:payload :reason])
;; event = {:payload {:reason "insufficient funds"}}
;; => "insufficient funds"

(get-in metrics [:decider-handle-latency :p50-ms])
```

### `update` -- Transform a Value

Applies a function to the value at a key:

```clojure
(update state :balance + amount)
;; state = {:balance 100}, amount = 50
;; => {:balance 150}

(update event :event-version #(or % 1))
;; If :event-version is nil, set it to 1
```

### `update-in` -- Nested Transform

```clojure
(update-in config [:metrics :handle] merge new-metrics)
```

### `merge` -- Combine Maps

Later maps override earlier ones:

```clojure
(merge account-projection/handler-specs
       transfer-projection/handler-specs)
;; Combines two handler spec maps into one
```

### `select-keys` -- Extract Subset

```clojure
(select-keys event [:global-sequence :stream-id])
;; event = {:global-sequence 42, :stream-id "acct-1", :event-type "...", ...}
;; => {:global-sequence 42, :stream-id "acct-1"}
```

### `keys` / `vals` -- Extract Keys or Values

```clojure
(keys {:a 1 :b 2})   ;; => (:a :b)
(vals {:a 1 :b 2})   ;; => (1 2)
```

### `contains?` -- Check Key Existence

**Note:** Checks for keys, not values.

```clojure
(contains? #{:completed :failed} status)
;; true if status is :completed or :failed

(contains? table-names "events")
;; true if "events" is in the set
```

### `into` -- Pour Elements into a Collection

```clojure
(into {} (map (fn [[[etype ever] schema]]
                [etype {:version ever :schema schema}])
              specs))
;; Convert sequence of pairs into a map
```

---

## Sequence Operations

### `map` / `mapv` -- Transform Each Element

`map` is lazy, `mapv` is eager (returns a vector).

```clojure
;; mapv -- used when you want all results immediately
(mapv (fn [row]
        (-> row
            (update :payload store/<-pgobject)
            (update :event-version #(or % 1))))
      rows)

;; map with multiple collections (stops at shortest)
(mapv (fn [index event]
        (validate! event index))
      (range)        ;; 0, 1, 2, ...
      events)        ;; list of events
```

### `map-indexed` -- Map with Index

```clojure
(doseq [[i event] (map-indexed vector events)]
  ;; i = 0, 1, 2, ...
  (insert-event! tx stream-id (+ expected-version i 1) event))
```

`(map-indexed vector coll)` produces `([0 first-elem] [1 second-elem] ...)`.

### `filter` / `filterv` -- Keep Matching Elements

```clojure
(filterv :regressed? rows)
;; Keep only rows where :regressed? is truthy
```

### `reduce` -- Fold a Collection

The most important function in event sourcing -- it rebuilds state from events:

```clojure
(reduce (:evolve decider) (:initial-state decider) events)
;; Starting from initial-state, apply evolve for each event
;; (evolve (evolve (evolve initial-state e1) e2) e3)

(reduce + 0 durations)
;; Sum all durations
```

### `first` / `rest` / `next`

```clojure
(first events)         ;; first element, or nil if empty
(rest events)          ;; everything but the first (lazy seq)
(next events)          ;; like rest, but returns nil instead of ()
```

### `peek` -- Last Element of a Vector

```clojure
(:global-sequence (peek new-events))
;; peek on a vector returns the LAST element (O(1))
;; peek on a list returns the FIRST element
```

### `conj` -- Add to a Collection

Adds to the "natural" position: end of vector, front of list.

```clojure
(conj [1 2 3] 4)      ;; => [1 2 3 4]
(conj '(1 2 3) 0)     ;; => (0 1 2 3)
```

### `cons` -- Prepend to a Sequence

```clojure
(distinct (cons key-field text-fields))
;; Ensure key-field is at the front of the list
```

### `concat` -- Join Sequences

```clojure
(concat existing-events new-events)
```

### `distinct` -- Remove Duplicates

```clojure
(distinct (cons key-field text-fields))
;; If key-field is already in text-fields, no duplicate
```

### `sort` -- Sort a Collection

```clojure
(vec (sort durations))   ;; sort numbers ascending
```

### `seq` -- Test for Non-Empty / Convert to Sequence

Returns `nil` for empty collections, which is falsy. The idiomatic "is this empty?" check:

```clojure
(when (seq new-events)        ;; nil if empty, truthy if not
  (process! new-events))

(if (seq events)
  (:stream-sequence (peek events))
  0)
```

### `count` -- Number of Elements

```clojure
(count rows)           ;; how many rows returned
(count new-events)     ;; how many events to process
```

### `empty?` -- True if Collection is Empty

```clojure
(is (empty? (store/load-stream ds "nonexistent")))
```

### `range` -- Generate Number Sequence

```clojure
(range)          ;; 0, 1, 2, 3, ... (infinite, lazy)
(range 10)       ;; 0, 1, 2, ..., 9
(range 1 5)      ;; 1, 2, 3, 4
```

### `repeat` / `repeatedly`

```clojure
(repeat 5 :x)         ;; (:x :x :x :x :x)
(repeatedly 5 uuid-v7) ;; call uuid-v7 five times, collect results
```

### `nth` -- Get by Index

```clojure
(nth sorted-values idx)       ;; get element at position idx
(nth queries (mod i (count queries)))  ;; cycle through queries
```

### `apply` -- Spread Collection as Arguments

```clojure
(apply str lines)       ;; (str line1 line2 line3 ...)
```

---

## Predicates and Comparison

### Equality and Comparison

```clojure
(= actual-version expected-version)   ;; value equality
(not= :open status)                    ;; not equal
(< current-version expected-version)
(> balance amount)
(<= attempt max-retries)
(>= fails max-consecutive-failures)
```

### Type Predicates

```clojure
(nil? value)           ;; true if nil
(some? value)          ;; true if NOT nil (opposite of nil?)
(true? value)          ;; true only for boolean true
(false? value)

(keyword? command-type)
(string? command-type)
(map? command)
(number? value)
(pos? n)               ;; true if n > 0
(pos-int? n)           ;; true if n is a positive integer
(zero? exit-code)
(neg? n)
(empty? coll)
(instance? java.time.Instant ts)
```

### Logic

```clojure
(and (= :testcontainers mode) container)    ;; short-circuit AND
(or (:event-version event) 1)               ;; first truthy value (default pattern)
(not (contains? table-names "events"))

;; `some` finds first truthy result of applying a predicate
(some :regressed? rows)   ;; first row where :regressed? is truthy
```

---

## Arithmetic

```clojure
(+ expected-version i 1)         ;; addition
(- (System/nanoTime) start)      ;; subtraction
(* base-backoff-ms multiplier)   ;; multiplication
(/ (double total-ns) count)      ;; division
(inc attempt)                     ;; (+ attempt 1)
(dec n)                           ;; (- n 1)
(max 1 attempt)                   ;; larger of two values
(min (+ base jitter) cap)         ;; smaller of two values
(mod i 4)                         ;; modulo (remainder)
(rand-int n)                      ;; random integer in [0, n)
(double value)                    ;; coerce to double
(long value)                      ;; coerce to long
```

---

## Bitwise Operations

Used in UUIDv7 generation and exponential backoff:

```clojure
(bit-shift-left ts 16)                    ;; ts << 16
(bit-shift-left 1 (dec (max 1 attempt)))  ;; 1 << (attempt-1) for exponential backoff
(bit-and (.nextInt rng) 0xFFF)            ;; mask to 12 bits
(bit-or (bit-shift-left 7 12) rand-a)     ;; combine with OR
(unchecked-long 0x8000000000000000)        ;; avoid overflow check for large hex
```

---

## String and Naming

### `str` -- Concatenate / Convert to String

```clojure
(str "stream:" stream-id)        ;; "stream:acct-1"
(str "transfer-" transfer-id)    ;; "transfer-42"
(str table " already has index") ;; string interpolation via concatenation
```

### `name` -- Keyword/Symbol to String

```clojure
(name :deposit)          ;; "deposit"
(name :domain/error)     ;; "error" (strips namespace)
```

### `keyword` -- String to Keyword

```clojure
(keyword "deposit")      ;; :deposit
```

### `format` -- Printf-style Formatting

```clojure
(format "%.3f" (double value))    ;; "12.345"
(format "%.1f" throughput)        ;; "1500.0"
(format "%+.2f%%" delta)          ;; "+32.14%"
(format "%.0f%%" (* 100.0 threshold))  ;; "30%"
```

### `clojure.string` Functions

```clojure
(require '[clojure.string :as str])

(str/join ", " ["owner" "account_id"])   ;; "owner, account_id"
(str/split columns #",\s*")              ;; split on comma+space
(str/trim raw-string)                    ;; strip whitespace
(str/lower-case raw)                     ;; lowercase
```

---

## Type Conversion

```clojure
(int char-value)        ;; character to integer
(long poll-interval-ms) ;; ensure long for Thread/sleep
(double value)          ;; to floating point
(str anything)          ;; to string
(name :keyword)         ;; keyword to string (without colon)
(keyword "string")      ;; string to keyword
(char (+ (int \A) i))   ;; integer to character
```

---

## Function Utilities

### `identity` -- Return Argument Unchanged

```clojure
(identity nil)    ;; => nil
;; Used as a no-op placeholder, e.g. in finally blocks
```

### `partial` -- Partially Apply Arguments

```clojure
;; If you need the same first arg every time:
(partial ensure-single-row-updated! projection-name)
;; Returns a function that already has projection-name filled in
```

### `comp` -- Compose Functions

```clojure
(comp str inc)    ;; (fn [x] (str (inc x)))
```

### `constantly` -- Ignore Arguments, Return Fixed Value

```clojure
(constantly nil)  ;; (fn [& _] nil)
```

---

## I/O and Printing

### `println` / `print` / `prn`

```clojure
(println "Wrote performance results:" results-path)

;; Redirect to stderr:
(binding [*out* *err*]
  (println "ERROR: something went wrong"))
```

### `pr-str` -- Print to String (readable)

```clojure
(spit path (pr-str results))     ;; write Clojure data as readable text
```

### `slurp` / `spit` -- Read/Write Entire Files

```clojure
(slurp "config.edn")              ;; read entire file to string
(spit "results.edn" (pr-str data)) ;; write string to file
```

### `clojure.edn/read-string` -- Parse EDN

```clojure
(require '[clojure.edn :as edn])
(edn/read-string (slurp "baseline.edn"))
;; Parse EDN (like JSON but richer) into Clojure data
```

---

## Error Handling

### `ex-info` -- Create Exception with Data

```clojure
(ex-info "Optimistic concurrency conflict"
         {:error/type       :concurrency/optimistic-conflict
          :error/retryable? true
          :stream-id        stream-id})
```

### `ex-data` -- Extract Data from Exception

```clojure
(let [data (ex-data e)]
  (when (= :concurrency/optimistic-conflict (:error/type data))
    (retry!)))
```

### `ex-message` -- Get Exception Message

```clojure
(.getMessage e)    ;; Java interop equivalent
```

---

## Concurrency Primitives

### `atom` / `reset!` / `swap!` / `deref` / `compare-and-set!`

```clojure
(def counter (atom 0))

@counter                         ;; read: 0
(swap! counter inc)              ;; atomically increment: 1
(swap! counter + 10)             ;; atomically add 10: 11
(reset! counter 0)               ;; set to 0
(compare-and-set! counter 0 1)   ;; set to 1 ONLY if currently 0
```

### `promise` / `deliver`

A one-shot value that blocks readers until a value is delivered:

```clojure
(let [p (promise)]
  ;; In another thread:
  (deliver p :done)

  ;; This blocks until delivered:
  @p)  ;; => :done
```

---

## Iteration and Side Effects

### `doseq` -- Iterate for Side Effects

```clojure
(doseq [event new-events]
  (project-event! tx event config))

;; With destructuring:
(doseq [[i event] (map-indexed vector events)]
  (insert! tx i event))
```

### `dotimes` -- Repeat N Times

```clojure
(dotimes [_ 3]
  (try (do-catch-up!) (catch Exception _)))
```

### `run!` -- Apply Function to Each Element (eager, for side effects)

```clojure
(run! println lines)   ;; print each line
```

---

## Miscellaneous

### `do` -- Sequential Evaluation

Evaluates forms in order, returns the last:

```clojure
(do
  (println "step 1")
  (println "step 2")
  :done)                ;; returns :done
```

Usually implicit in `let`, `when`, `defn` bodies. Explicit `do` is needed in `if` branches:

```clojure
(if condition
  (do (log! "yes") :yes)
  (do (log! "no") :no))
```

### `comment` -- Ignored Block

```clojure
(comment
  ;; REPL scratch pad -- these forms are never evaluated
  (def pg (infra/start-postgres!))
  (decider/handle! ds account/decider {:command-type :deposit ...})
  )
```

### `doto` -- Thread Object Through Methods

Returns the first argument after applying all side effects:

```clojure
(doto (Thread. (fn [] (while @running ...)))
  (.setDaemon true)
  (.setName "outbox-poller")
  (.start))
```

### `while` -- Loop While True

```clojure
(while @running
  (poll!)
  (Thread/sleep 100))
```

### `with-redefs` -- Temporary Redefinition (testing)

```clojure
(with-redefs [store/load-stream (fn [_ _] test-events)]
  (is (= :ok (handle! ...))))
```

### `clojure.java.io` Functions

```clojure
(require '[clojure.java.io :as io])

(io/file "target/perf/results.edn")   ;; create java.io.File
(io/make-parents path)                  ;; create parent directories
(.exists (io/file path))                ;; check if file exists
```
