# clojure.test -- Testing Framework

`clojure.test` is Clojure's built-in testing library. It provides test definition, assertions, fixtures, and test runners.

**Built-in:** No extra dependency needed.

## Namespace Used

```clojure
(:require [clojure.test :refer [deftest is testing use-fixtures]])
```

---

## Defining Tests

### `deftest` -- Define a Test

```clojure
(deftest process-new-events-applies-events-and-advances-checkpoint
  (append-counter-events! "c-1"
    [{:event-type "counter-created" :payload {}}
     {:event-type "counter-incremented" :payload {:delta 5}}])
  (is (= 2 (projection/process-new-events! *ds* counter-config)))
  (is (= 5 (:count (get-counter "c-1")))))
```

Test names are symbols. By convention they describe the behaviour being tested.

---

## Assertions

### `is` -- Assert a Condition

The core assertion macro. If the expression is falsy, the test fails.

```clojure
;; Equality
(is (= :ok result))
(is (= 42 (:balance account)))
(is (= 3 (count events)))

;; Truthiness
(is (some? value))           ;; not nil
(is (nil? (:error result)))  ;; is nil

;; With custom message (shown on failure)
(is (= 6 (:count counter))
    "After all 4 events: 0 + 1 + 2 + 3 = 6")
```

### `is` with `thrown?` -- Assert Exception

```clojure
;; Assert that an exception of a specific type is thrown
(is (thrown? clojure.lang.ExceptionInfo
             (decider/handle! ds decider bad-command)))

;; Assert exception with message matching a regex
(is (thrown-with-msg? clojure.lang.ExceptionInfo
                      #"Account already open"
                      (open-account-twice!)))
```

### Checking Exception Data

```clojure
(let [e (try
          (decider/handle! ds decider command)
          nil                           ;; return nil if no exception
          (catch clojure.lang.ExceptionInfo ex
            ex))]                       ;; capture the exception
  (is (some? e))
  (is (= "Optimistic concurrency conflict" (.getMessage e)))
  (is (= :concurrency/optimistic-conflict
         (:error/type (ex-data e)))))
```

### `testing` -- Group Related Assertions

Provides context in failure messages:

```clojure
(deftest account-validations
  (testing "deposit requires positive amount"
    (is (thrown? clojure.lang.ExceptionInfo
                 (decide {:command-type :deposit :data {:amount 0}} open-state))))
  (testing "withdraw checks sufficient funds"
    (is (thrown? clojure.lang.ExceptionInfo
                 (decide {:command-type :withdraw :data {:amount 1000}} low-balance-state)))))
```

Failure output includes the `testing` label:

```
FAIL in (account-validations)
deposit requires positive amount
expected: (thrown? ...)
  actual: (not (thrown? ...))
```

---

## Fixtures

Fixtures run setup/teardown code around tests.

### `:once` -- Run Once Per Namespace

```clojure
(defn- with-system [f]
  (let [pg (infra/start-postgres!)
        ds (infra/->datasource pg)]
    (try
      (migrations/migrate! ds)
      (binding [*ds* ds]
        (f))                    ;; <-- runs ALL tests in this namespace
      (finally
        (infra/stop-postgres! pg)))))

(use-fixtures :once with-system)
```

### `:each` -- Run Before/After Each Test

```clojure
(defn- with-clean-db [f]
  (jdbc/with-transaction [tx *ds*]
    (jdbc/execute-one! tx ["TRUNCATE TABLE events RESTART IDENTITY"]))
  (f))                          ;; <-- runs ONE test

(use-fixtures :each with-clean-db)
```

### Combined

```clojure
(use-fixtures :once with-system)     ;; start DB once
(use-fixtures :each with-clean-db)   ;; clean before each test
```

---

## Dynamic Variables in Tests

Tests use `^:dynamic` vars to share state from fixtures:

```clojure
(def ^:dynamic *ds* nil)             ;; declared at top of test file

(defn- with-system [f]
  (let [ds (create-datasource)]
    (binding [*ds* ds]               ;; bind for this scope
      (f))))

(deftest my-test
  ;; *ds* is available here because the fixture bound it
  (jdbc/execute! *ds* ["SELECT 1"]))
```

---

## `with-redefs` -- Mock Functions

Temporarily replaces function definitions for testing:

```clojure
(deftest handle-calls-decide-then-appends
  (let [appended (atom nil)]
    (with-redefs [es.store/load-stream   (fn [_ _] test-events)
                  es.store/append-events! (fn [_ _ _ _ _ events & _]
                                            (reset! appended events)
                                            :ok)]
      (let [result (decider/handle! :fake-ds account/decider command)]
        (is (= :ok result))
        (is (= 1 (count @appended)))))))
```

**Warning:** `with-redefs` is global and not thread-safe. Only use in tests, and only when tests run single-threaded.

---

## Testing Private Functions

Use `#'` (var quote) to access private functions:

```clojure
;; skip-poison-event! is defn- (private)
(#'async-proj/skip-poison-event! read-db-ds "counter-async" checkpoint callback)
```

---

## Running Tests

```bash
# All tests
bb test

# The test runner discovers all namespaces matching test/**/*_test.clj
# and runs every deftest in them
```

The project uses `cognitect-labs/test-runner`:

```clojure
;; deps.edn
:test {:extra-paths ["test"]
       :extra-deps  {io.github.cognitect-labs/test-runner {:mvn/version "0.5.1"}}
       :main-opts   ["-m" "cognitect.test-runner"]}
```

---

## Property-Based Testing

For randomised testing, the project uses `clojure.test.check`:

```clojure
(:require [clojure.test.check :as tc]
          [clojure.test.check.generators :as gen]
          [clojure.test.check.properties :as prop])

(deftest random-deposits-never-go-negative
  (let [result
        (tc/quick-check 200
          (prop/for-all [amounts (gen/vector (gen/such-that pos? gen/nat) 1 20)]
            (let [events (map #(deposit-event %) amounts)
                  state  (reduce evolve initial-state events)]
              (>= (:balance state) 0))))]
    (is (:pass? result))))
```

- `tc/quick-check` runs the property 200 times with random inputs
- `gen/vector` generates random vectors
- `prop/for-all` defines what must always be true
- If a failure is found, test.check shrinks to the minimal failing case
