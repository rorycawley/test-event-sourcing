# Migratus -- Database Migrations

[Migratus](https://github.com/yogthos/migratus) manages database schema changes through versioned SQL migration files. Each migration has an "up" (apply) and "down" (rollback) script.

**Dependency:** `migratus/migratus {:mvn/version "1.6.4"}`

## Namespace Used

```clojure
(:require [migratus.core :as migratus])
```

---

## Migration Files

Migrations live in `resources/` and follow a naming convention:

```
resources/
  migrations/                           # Event store
    001-schema.up.sql                   # Apply: create tables
    001-schema.down.sql                 # Rollback: drop tables
  read-migrations/                      # Read store (separate DB)
    001-schema.up.sql
    001-schema.down.sql
```

The number prefix (`001`) determines ordering. Migratus tracks which migrations have been applied in a `schema_migrations` table it creates automatically.

### Example Up Migration

```sql
-- 001-schema.up.sql
CREATE TABLE IF NOT EXISTS events (
    id               UUID PRIMARY KEY,
    global_sequence  BIGSERIAL UNIQUE NOT NULL,
    stream_id        TEXT NOT NULL,
    stream_sequence  BIGINT NOT NULL,
    event_type       TEXT NOT NULL,
    event_version    INTEGER NOT NULL DEFAULT 1,
    payload          JSONB NOT NULL,
    created_at       TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE (stream_id, stream_sequence)
);
```

### Example Down Migration

```sql
-- 001-schema.down.sql
DROP TABLE IF EXISTS event_outbox;
DROP TABLE IF EXISTS transfer_status;
DROP TABLE IF EXISTS account_balances;
DROP TABLE IF EXISTS projection_checkpoints;
DROP TABLE IF EXISTS idempotency_keys;
DROP TABLE IF EXISTS events;
```

---

## Configuration

Migratus needs a config map telling it where to find migrations and how to connect:

```clojure
;; es/migrations.clj
(def ^:private base-config
  {:store         :database
   :migration-dir "migrations"})    ;; looks in resources/migrations/

(defn- migratus-config
  [ds & {:keys [migration-dir]}]
  (cond-> (assoc base-config :db {:datasource ds})
    migration-dir (assoc :migration-dir migration-dir)))
```

The `:migration-dir` is relative to the classpath (i.e., inside `resources/`).

---

## Core Functions

### `migratus/migrate` -- Apply All Pending Migrations

```clojure
(migratus/migrate (migratus-config ds))
;; Applies all migrations that haven't been run yet
;; Idempotent: running twice is safe
```

This project wraps it:

```clojure
(defn migrate!
  "Applies pending migrations."
  [ds & {:keys [migration-dir]}]
  (migratus/migrate (migratus-config ds :migration-dir migration-dir)))
```

Usage:

```clojure
;; Event store migrations (default directory)
(migrations/migrate! event-store-ds)

;; Read store migrations (custom directory)
(migrations/migrate! read-db-ds :migration-dir "read-migrations")
```

### `migratus/rollback` -- Roll Back Most Recent Migration

```clojure
(migratus/rollback (migratus-config ds))
;; Runs the down script of the most recently applied migration
```

### `migratus/pending-list` -- List Unapplied Migrations

```clojure
(migratus/pending-list (migratus-config ds))
;; => [#migratus.migration{:id 1, :name "001-schema", ...}]
;; Empty list if all applied
```

### `migratus/completed-list` -- List Applied Migrations

```clojure
(migratus/completed-list (migratus-config ds))
;; => [#migratus.migration{:id 1, :name "001-schema", ...}]
```

---

## How It Works

1. On first `migrate`, Migratus creates a `schema_migrations` table
2. It scans the migration directory for files matching `NNN-name.up.sql`
3. Compares against `schema_migrations` to find pending ones
4. Applies pending migrations in order, recording each in `schema_migrations`
5. `rollback` reads `schema_migrations`, finds the latest, runs its `.down.sql`

---

## Multiple Migration Directories

This project uses two databases with different schemas:

```clojure
;; Event store: full schema (events, idempotency_keys, outbox, projections)
(migrations/migrate! event-store-ds)

;; Read store: projections only (checkpoints, account_balances, transfer_status)
(migrations/migrate! read-db-ds :migration-dir "read-migrations")
```

This is a key pattern for CQRS: the read database has a simpler schema than the write database.
