# next.jdbc -- Database Access

[next.jdbc](https://github.com/seancorfield/next-jdbc) is the standard Clojure library for JDBC database access. It provides a thin, functional wrapper over Java's JDBC API. This project uses it for all PostgreSQL interactions.

**Dependency:** `com.github.seancorfield/next.jdbc {:mvn/version "1.3.909"}`

## Namespaces Used

```clojure
(:require [next.jdbc :as jdbc]
          [next.jdbc.result-set :as rs])
```

---

## Core Functions

### `jdbc/get-datasource` -- Create a Connection Pool

A datasource is a connection factory. Create one at startup and share it.

```clojure
(jdbc/get-datasource {:jdbcUrl  "jdbc:postgresql://localhost:5432/mydb"
                      :user     "postgres"
                      :password "secret"})
```

In this project, datasources are created from Testcontainer connection details:

```clojure
;; es/infra.clj
(defn ->datasource [pg]
  (jdbc/get-datasource {:jdbcUrl  (:jdbc-url pg)
                        :user     (:username pg)
                        :password (:password pg)}))
```

### `jdbc/execute!` -- Query, Return Multiple Rows

Returns a vector of maps, one per row.

```clojure
(jdbc/execute! ds
               ["SELECT global_sequence, stream_id, event_type, payload
                 FROM events
                 WHERE global_sequence > ?
                 ORDER BY global_sequence ASC
                 LIMIT ?"
                checkpoint
                batch-size]
               {:builder-fn rs/as-unqualified-kebab-maps})
```

**Key points:**
- First arg is a datasource, connection, or transaction
- SQL is a vector: `["SQL with ? placeholders" param1 param2]`
- Options map customises result handling

**Result without builder-fn:**
```clojure
[{:events/global_sequence 1, :events/stream_id "acct-1", ...}]
;;  ^ table-qualified, snake_case keys
```

**Result with `rs/as-unqualified-kebab-maps`:**
```clojure
[{:global-sequence 1, :stream-id "acct-1", ...}]
;;  ^ no table prefix, kebab-case keys
```

### `jdbc/execute-one!` -- Query, Return Single Row (or nil)

Same as `execute!` but returns just the first row, or `nil` if no results.

```clojure
;; Read a single record
(jdbc/execute-one! ds
                   ["SELECT * FROM projection_checkpoints
                     WHERE projection_name = ?"
                    "main"]
                   {:builder-fn rs/as-unqualified-kebab-maps})
;; => {:projection-name "main", :last-global-sequence 42}
;; => nil (if not found)
```

Also used for INSERT/UPDATE/DELETE to get the affected row or update count:

```clojure
;; INSERT with RETURNING
(jdbc/execute-one! tx
                   ["INSERT INTO idempotency_keys (idempotency_key, stream_id)
                     VALUES (?, ?)
                     ON CONFLICT (idempotency_key) DO NOTHING
                     RETURNING idempotency_key"
                    key stream-id])
;; => {:idempotency_keys/idempotency_key "key-1"}  (if inserted)
;; => nil                                           (if conflict, DO NOTHING)
```

```clojure
;; UPDATE returning affected row count
(let [result (jdbc/execute-one! tx
               ["UPDATE account_balances
                 SET balance = balance + ?, last_global_sequence = ?
                 WHERE account_id = ? AND last_global_sequence < ?"
                amount global-seq account-id global-seq])]
  (:next.jdbc/update-count result))
;; => 1 (one row updated)
;; => 0 (no rows matched -- idempotent guard caught a duplicate)
```

### `jdbc/with-transaction` -- Execute in a Transaction

Wraps a block in a database transaction. If the body throws, the transaction rolls back. If it completes normally, it commits.

```clojure
(jdbc/with-transaction [tx ds]
  ;; tx is a transaction-bound connection
  ;; Use tx (not ds) for all operations within
  (jdbc/execute-one! tx ["INSERT INTO events ..."])
  (jdbc/execute-one! tx ["INSERT INTO event_outbox ..."]))
;; Both inserts commit together, or both roll back
```

**Critical pattern -- advisory lock + version check + insert:**

```clojure
(jdbc/with-transaction [tx ds]
  ;; 1. Acquire advisory lock (serialises writes per stream)
  (jdbc/execute-one! tx
    ["SELECT pg_advisory_xact_lock(
        ('x' || substr(md5(?), 1, 16))::bit(64)::bigint)"
     (str "stream:" stream-id)])

  ;; 2. Check current version
  (let [actual (or (-> (jdbc/execute-one! tx
                         ["SELECT MAX(stream_sequence) AS stream_sequence
                           FROM events WHERE stream_id = ?" stream-id]
                         {:builder-fn rs/as-unqualified-kebab-maps})
                       :stream-sequence)
                   0)]
    (when (not= actual expected-version)
      (throw (ex-info "Conflict" {...})))

    ;; 3. Append events
    (doseq [[i event] (map-indexed vector events)]
      (jdbc/execute-one! tx
        ["INSERT INTO events (id, stream_id, stream_sequence, event_type, payload)
          VALUES (?, ?, ?, ?, ?)"
         (uuid-v7) stream-id (+ expected-version i 1)
         (:event-type event) (->pgobject (:payload event))]))))
```

### Parameterised Queries (SQL Injection Prevention)

**Always use `?` placeholders**, never string concatenation:

```clojure
;; SAFE -- parameterised
(jdbc/execute-one! tx
  ["SELECT * FROM events WHERE stream_id = ? AND stream_sequence > ?"
   stream-id version])

;; DANGEROUS -- SQL injection
(jdbc/execute-one! tx
  [(str "SELECT * FROM events WHERE stream_id = '" stream-id "'")])
```

The `?` placeholders are sent to PostgreSQL separately from the SQL, so user input can never be interpreted as SQL.

**Exception:** DDL statements (CREATE INDEX, etc.) can't use `?` for identifiers. The project validates identifiers with a regex allowlist:

```clojure
;; es/search.clj
(defn- validate-identifier! [id]
  (when-not (re-matches #"^[a-zA-Z_][a-zA-Z0-9_]*$" id)
    (throw (ex-info "Invalid SQL identifier" {:identifier id}))))
```

---

## Result Set Builders

### `rs/as-unqualified-kebab-maps`

The most commonly used builder. Transforms column names:

| SQL Column | Default Key | With `as-unqualified-kebab-maps` |
|---|---|---|
| `global_sequence` | `:events/global_sequence` | `:global-sequence` |
| `stream_id` | `:events/stream_id` | `:stream-id` |
| `last_global_sequence` | `:projection_checkpoints/last_global_sequence` | `:last-global-sequence` |

```clojure
;; Used on almost every query:
{:builder-fn rs/as-unqualified-kebab-maps}
```

---

## Common Patterns in This Project

### The "load or default" Pattern

```clojure
(or (-> (jdbc/execute-one! ds
          ["SELECT last_global_sequence FROM projection_checkpoints
            WHERE projection_name = ?" name]
          {:builder-fn rs/as-unqualified-kebab-maps})
        :last-global-sequence)
    0)
;; Returns the checkpoint value, or 0 if no row exists
```

### The "idempotent upsert" Pattern

```clojure
(jdbc/execute-one! tx
  ["INSERT INTO projection_checkpoints (projection_name, last_global_sequence)
    VALUES (?, ?)
    ON CONFLICT (projection_name)
    DO UPDATE SET last_global_sequence = GREATEST(
      projection_checkpoints.last_global_sequence,
      EXCLUDED.last_global_sequence)"
   projection-name global-sequence])
;; GREATEST prevents checkpoint regression
```

### The "claim or skip" Pattern

```clojure
(let [claimed? (jdbc/execute-one! tx
                 ["INSERT INTO idempotency_keys (idempotency_key, stream_id, ...)
                   VALUES (?, ?, ...)
                   ON CONFLICT (idempotency_key) DO NOTHING
                   RETURNING idempotency_key"
                  key stream-id ...])]
  (if claimed?
    ::claimed      ;; we got the key
    ::idempotent)) ;; someone else already claimed it
```

### The "select for update skip locked" Pattern (Outbox Polling)

```clojure
(jdbc/execute! tx
  ["SELECT o.id, o.global_sequence, e.stream_id, e.event_type, e.payload
    FROM event_outbox o
    JOIN events e ON e.global_sequence = o.global_sequence
    WHERE o.published_at IS NULL
    ORDER BY o.id ASC
    LIMIT ?
    FOR UPDATE OF o SKIP LOCKED"
   batch-size]
  {:builder-fn rs/as-unqualified-kebab-maps})
;; FOR UPDATE locks the rows so other pollers skip them
;; SKIP LOCKED means other pollers don't wait -- they just get different rows
```

---

## Datasource vs Connection vs Transaction

| Type | What it is | When to use |
|---|---|---|
| **Datasource** (`ds`) | Connection factory | Pass around, share across threads |
| **Connection** | Single DB connection | Rarely used directly |
| **Transaction** (`tx`) | Connection with auto-commit off | Inside `with-transaction` block |

```clojure
;; Use ds for read-only queries
(jdbc/execute! ds ["SELECT ..."])

;; Use tx for multi-statement writes
(jdbc/with-transaction [tx ds]
  (jdbc/execute-one! tx ["INSERT ..."])
  (jdbc/execute-one! tx ["UPDATE ..."]))
```

---

## JSONB Support

PostgreSQL's JSONB type needs special handling via `PGobject`:

```clojure
;; Clojure map -> JSONB
(defn ->pgobject [m]
  (doto (PGobject.)
    (.setType "jsonb")
    (.setValue (json/write-str m))))

;; JSONB -> Clojure map
(defn <-pgobject [^PGobject pg]
  (when pg
    (json/read-str (.getValue pg) :key-fn keyword)))

;; Usage in INSERT
(jdbc/execute-one! tx
  ["INSERT INTO events (payload) VALUES (?)"
   (->pgobject {:amount 100 :currency "USD"})])

;; Usage after SELECT
(update row :payload <-pgobject)
```
