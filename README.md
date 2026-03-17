# Event Sourcing in Clojure

A reference implementation of event sourcing using the **Decider pattern** (Chassaing) and **Pull-Transform-Push** (Tellman), backed by PostgreSQL and RabbitMQ.

Implements a bank account domain to demonstrate: append-only event storage, optimistic concurrency with retry, command idempotency, event versioning with upcasting, data-driven projections, cross-aggregate saga coordination (fund transfers), transactional outbox publishing, and async CQRS projections to a separate read database.

## Architecture

The system supports two modes: **synchronous** (single database, direct projection) and **async** (separate read database, RabbitMQ-driven projections). Both share the same pure domain logic.

### Synchronous Mode

Single Postgres database. Events and read models live side by side. Projections run on demand via `system/process-new-events!`.

```
                          COMMAND FLOW
  ┌─────────┐    ┌──────────────────────┐    ┌───────────────────┐
  │         │    │       Decider        │    │      Store        │
  │ Command │───>│                      │───>│                   │
  │         │    │  decide() -> [Event] │    │  append-events!   │
  └─────────┘    │  evolve() -> State   │    │  (append-only     │
                 │                      │    │   event log in    │
                 │  Pure functions —    │    │   PostgreSQL)     │
                 │  no I/O, no DB      │    │                   │
                 └──────────────────────┘    └─────────┬─────────┘
                                                       │
                          PROJECTION FLOW              │
  ┌──────────────┐    ┌────────────────────┐    ┌──────┴──────┐
  │  Read Model  │<───│    Projection      │<───│   Events    │
  │              │    │                    │    │   table     │
  │  - balances  │    │  process-new-      │    │             │
  │  - transfers │    │   events!          │    │  (global    │
  │              │    │  (data-driven      │    │   sequence  │
  │  Disposable, │    │   handler per      │    │   cursor)   │
  │  rebuildable │    │   event type)      │    │             │
  └──────────────┘    └────────────────────┘    └─────────────┘
```

### Async Mode (CQRS)

Separate databases for writes and reads. Events flow through a transactional outbox and RabbitMQ to independent async projectors. Each projector owns its queue and writes to the read database.

```
  ┌─────────────┐    ┌──────────────┐    ┌─────────────┐
  │ Event Store  │───>│ Outbox Poller │───>│  RabbitMQ   │
  │  (Postgres)  │    └──────────────┘    └──────┬──────┘
  └─────────────┘                                │
                                        ┌────────┼────────┐
                                        │                  │
                                   ┌────┴─────┐     ┌─────┴────┐
                                   │ Account  │     │ Transfer │
                                   │ Projector│     │ Projector│
                                   └────┬─────┘     └─────┬────┘
                                        │                  │
                                   ┌────┴──────────────────┴────┐
                                   │    Read Store (Postgres)   │
                                   └────────────────────────────┘
```

**Key design: notifications, not event carriers.** RabbitMQ messages are "wake up" signals — they tell projectors that new events exist, but don't carry event data. Each projector reads directly from the event store using its checkpoint. This means:

- **Lost messages are harmless** — periodic catch-up timer (every 30s) reads any events the projector missed
- **Duplicate messages are harmless** — checkpoint-based idempotency skips already-processed events
- **At-least-once delivery** without complex acknowledgment schemes

**Transactional outbox pattern.** The outbox row is written in the same Postgres transaction as the events. The poller reads unpublished rows (`FOR UPDATE SKIP LOCKED` for safe concurrent polling) and publishes to RabbitMQ. If the process crashes between append and publish, the outbox row survives and the poller retries.

**Concurrency safety.** Each projector serialises catch-ups via `compare-and-set!` — if a RabbitMQ message and the catch-up timer fire simultaneously, only one catch-up runs. Channels use `prefetch=1` since each catch-up processes all pending events.

**Poison event handling.** If a projection handler throws on a specific event, repeated catch-ups would be stuck at that checkpoint forever. After 5 consecutive failures (configurable), the poison event is skipped, the `on-poison` callback fires for alerting, and the projector resumes. A full `rebuild!` replays all events including previously skipped ones. See [RUNBOOK.md](RUNBOOK.md) for operational procedures.

**Dead letter queues.** Each projector queue is configured with a DLQ (`<queue>.dlq`) via `x-dead-letter-exchange`. Since messages are notifications, the DLQ serves as observability infrastructure rather than a recovery mechanism.

**Bounded memory.** Event reads use a configurable `LIMIT` (default 1000 per batch). Projectors far behind catch up in batches rather than loading all events at once. Rebuilds also process in batches.

### The Decider Pattern

The Decider pattern ([Chassaing, 2021](https://thinkbeforecoding.com/post/2021/12/17/functional-event-sourcing-decider)) separates *what should happen* from *how it is stored*. A Decider is defined by three elements:

```
Command  — an intent: "please do this"          (imperative: open-account, deposit, withdraw)
Event    — a fact: "this happened"               (past tense: account-opened, money-deposited)
State    — derived from the event history         (the current truth, reconstructed via left fold)
```

The Decider itself is a plain Clojure map:

```clojure
{:initial-state {:status :not-found, :balance 0}  ;; State before any events
 :decide        decide                             ;; Command → State → [Event]
 :evolve        evolve}                            ;; State → Event → State
```

- **`decide`** embodies the business rules: given what is requested (command) and what is true (state), produce new facts (events) -- or reject the command.
- **`evolve`** is a pure fold step: given current state and what happened (event), compute the next state.
- **`initial-state`** is the state before anything has occurred.

The Decider is **pure**: no I/O, no database, no side effects. The same Decider can run in-memory for tests, against PostgreSQL in production, or in a REPL with hand-crafted event vectors. Domain logic never changes when infrastructure changes.

The command handler (`es.decider`) provides the infrastructure wiring using Tellman's Pull-Transform-Push:

1. **Pull** -- load events from the store (I/O)
2. **Transform** -- `(reduce evolve initial-state events)` reconstructs current state, then `(decide command state)` produces new events (pure)
3. **Push** -- append new events to the store (I/O)

### Data-Driven Toolkit

Both deciders and projections are built from **data declarations**, not boilerplate:

- **`es.decider-kit`** — Five factory functions that take schemas, upcasters, and decision functions as data and return the wired-up functions. Event schemas are declared as payload-only maps; the envelope (`event-type`, `event-version`, `payload`) is wrapped automatically.

- **`es.projection-kit`** — `make-handler` takes a map of `{event-type -> handler-fn}` and returns a dispatch function. Supports `:skip-unknown? true` for independent async projectors that only handle a subset of event types. `make-query` builds reusable query functions. Domain projections declare their handler maps as data; the composition root merges them.

Adding a new aggregate means writing schemas, `evolve`, decision functions, and a projection handler map — no macros, no multimethods, no boilerplate to copy.

### Event Store

The event store (`es.store`) is an append-only log in PostgreSQL with:

| Field | Purpose |
|---|---|
| `id` (UUIDv7) | Globally unique, time-ordered, application-generated |
| `global_sequence` (BIGSERIAL) | Monotonic position across all streams; projection cursor |
| `stream_sequence` (BIGINT) | Per-stream version; optimistic concurrency control |
| `event_version` (INTEGER) | Schema version for payload validation and upcasting |
| `payload` (JSONB) | Domain event data |

**Concurrency strategy:**
1. `pg_advisory_xact_lock` serialises writes per stream within Postgres
2. Version check: `expected-version` must equal `max(stream_sequence)` -- detects stale reads
3. `UNIQUE(stream_id, stream_sequence)` as a safety net

**Idempotency:**
- Dedicated `idempotency_keys` table (not the events table)
- Detects **replay** (same key, same command) vs **collision** (same key, different command)
- Fast-path short-circuit before domain processing

**Post-append hook:** `append-events!` accepts an optional `:on-events-appended` callback, invoked within the same transaction with the global sequences of the newly written events. The transactional outbox uses this hook to atomically record outbox rows alongside events.

### Event Versioning

Events carry a schema version. Upcasters transform old versions to current, one step at a time:

```
money-deposited v1: {amount}
       ↓ upcast: add :origin "legacy"
money-deposited v2: {amount, origin}
       ↓ upcast: add :currency "USD"
money-deposited v3: {amount, origin, currency}
```

Old events stored as v1 are transparently upcasted to v3 on read. New events are always written at the latest version.

### Fund Transfer Saga

The transfer saga (`bank.transfer-saga`) demonstrates cross-aggregate coordination:

```
┌──────────────────────────────────────────────────────────────┐
│                    Transfer Saga                              │
│                                                              │
│  1. Initiate    → transfer stream: transfer-initiated        │
│  2. Debit       → source account: money-withdrawn            │
│                 → transfer stream: debit-recorded             │
│  3. Credit      → dest account: money-deposited              │
│                 → transfer stream: credit-recorded            │
│  4. Complete    → transfer stream: transfer-completed         │
│                                                              │
│  On debit failure:   mark transfer failed (nothing to undo)  │
│  On credit failure:  refund source, then mark failed         │
└──────────────────────────────────────────────────────────────┘
```

The transfer is its own Decider (`bank.transfer`) with a state machine: `not-found → initiated → debited → credited → completed` (or `→ failed` from any non-terminal state). The saga coordinator uses idempotency keys derived from the transfer-id, so every step is safe to retry.

**Crash recovery**: if the process dies mid-transfer, `resume!` reads the transfer stream, evolves to the current state, and picks up from the last completed step. Because every step is idempotent, resumption is always safe — money is never lost or duplicated.

**Async note**: the saga does not use the outbox hook, so its events are picked up by the catch-up timer rather than RabbitMQ notifications. Transfer status projections may be delayed by up to the catch-up interval (default 30s, configurable).

### Projections

Read models are derived, disposable views built from the event stream.

**Synchronous mode** (`es.projection`): single database, single transaction. Catch-up processing reads events after the last checkpoint, applies them, and advances the checkpoint. Advisory lock serialises workers. Full rebuild destroys and rebuilds from the complete event stream.

**Async mode** (`es.async-projection`): two databases. Events are read from the event store (bounded by batch size), projections are written to a separate read database. Since the two databases can't share a transaction, idempotency relies on `last_global_sequence` guards in each projection handler. RabbitMQ consumers trigger catch-up on each message; a periodic timer handles missed messages. Concurrent catch-ups are serialised via `compare-and-set!` to prevent races.

Both modes use data-driven dispatch via `es.projection-kit/make-handler`.

### Component Lifecycle

Infrastructure is managed by Stuart Sierra's [Component](https://github.com/stuartsierra/component) library. All component records live in `es.component` (domain-agnostic); system assembly lives in `bank.components` (domain-specific).

| Component | Depends on | Purpose |
|---|---|---|
| `Datasource` | — | Postgres connection (Testcontainers or JDBC URL) |
| `Migrator` | `Datasource` | Runs Migratus migrations on start |
| `RabbitMQConnection` | — | RabbitMQ connection (Testcontainers, URI, or direct) |
| `OutboxPoller` | `Datasource`, `RabbitMQ` | Polls outbox table, publishes to RabbitMQ exchange |
| `AsyncProjector` | `EventStoreDatasource`, `ReadDBDatasource`, `RabbitMQ` | Subscribes to queue with DLQ, triggers projection catch-up with poison event handling |

System configurations:

```clojure
;; Minimal (synchronous, single DB, no RabbitMQ)
(bank.components/dev-system)

;; Full async (2× Postgres + RabbitMQ, outbox poller, 2 projectors)
(bank.components/dev-full-system)

;; Production (explicit connection details)
(bank.components/full-system
  {:event-store {:mode :jdbc-url :jdbc-url "..." :user "..." :password "..."}
   :read-db     {:mode :jdbc-url :jdbc-url "..." :user "..." :password "..."}
   :rabbitmq    {:mode :uri :uri "amqp://..."}})
```

## Project Structure

The codebase is split into two layers: the reusable **framework** (`es.*`) and the **domain** (`bank.*`).

```
src/
├── es/                          # Reusable event sourcing framework
│   ├── store.clj                #   Append-only event store (PostgreSQL)
│   ├── decider.clj              #   Command handler (Pull → Transform → Push)
│   ├── decider_kit.clj          #   Data-driven Decider factories (schemas → functions)
│   ├── projection.clj           #   Synchronous read model (single-DB catch-up + rebuild)
│   ├── projection_kit.clj       #   Data-driven projection handler factories
│   ├── async_projection.clj     #   Async read model (two-DB, batched, poison-event handling)
│   ├── outbox.clj               #   Transactional outbox (record + poll + publish)
│   ├── rabbitmq.clj             #   Thin Langohr wrapper (connect, publish, consume, DLQ, QoS)
│   ├── component.clj            #   Component records (Datasource, Migrator, RabbitMQ, etc.)
│   ├── saga.clj                 #   Reusable saga coordination helpers
│   ├── schema.clj               #   Shared Malli schemas
│   ├── migrations.clj           #   Migratus migration wrapper (configurable migration-dir)
│   ├── migrations_cli.clj       #   CLI for running migrations against external DB
│   └── infra.clj                #   Testcontainer lifecycle (Postgres + RabbitMQ)
│
├── bank/                        # Domain-specific code
│   ├── account.clj              #   Account Decider (decide/evolve) — pure, no I/O
│   ├── transfer.clj             #   Transfer Decider (decide/evolve) — pure, no I/O
│   ├── transfer_saga.clj        #   Saga coordinator for cross-account transfers
│   ├── account_projection.clj   #   Account projection handler specs + query
│   ├── transfer_projection.clj  #   Transfer projection handler specs + query
│   ├── system.clj               #   Synchronous composition root (single-DB wiring)
│   └── components.clj           #   Component system assembly (dev, full, production)

dev/
└── user.clj                     # Interactive REPL walkthrough (sync + async modes)

test/
├── bank/
│   ├── account_test.clj         #   Pure account domain unit tests (no DB)
│   ├── transfer_test.clj        #   Pure transfer domain unit tests (no DB)
│   ├── transfer_saga_test.clj   #   Transfer saga integration tests (DB)
│   ├── functional_test.clj      #   End-to-end lifecycle tests (DB)
│   ├── integration_test.clj     #   Concurrency, idempotency, projection tests (DB)
│   ├── async_integration_test.clj#  Full async pipeline end-to-end (3 testcontainers)
│   ├── fuzz_unit_test.clj       #   Property-based domain tests
│   ├── fuzz_integration_test.clj#   Property-based integration tests (DB)
│   ├── perf.clj                 #   Performance benchmarks
│   ├── perf_check.clj           #   Regression detection vs baseline
│   ├── perf_check_test.clj      #   Regression detection tests
│   ├── perf_baseline.clj        #   Baseline management
│   ├── perf_baseline_test.clj   #   Baseline management tests
│   └── test_support.clj         #   Test fixtures and utilities
├── es/
│   ├── decider_test.clj         #   Command handler tests (mocked store)
│   ├── decider_kit_test.clj     #   Decider-kit factory tests
│   ├── store_test.clj           #   Store utility unit tests
│   ├── store_integration_test.clj#  Event store integration tests (DB)
│   ├── projection_test.clj      #   Synchronous projection tests (DB)
│   ├── projection_kit_test.clj  #   Projection-kit factory tests
│   ├── async_projection_test.clj#   Async projection tests (2× Postgres, batching)
│   ├── outbox_test.clj          #   Outbox record, poll, and publish tests (DB)
│   ├── component_test.clj       #   Component lifecycle tests
│   ├── saga_test.clj            #   Saga helper tests
│   ├── migrations_test.clj      #   Migration wrapper tests
│   └── migrations_cli_test.clj  #   CLI interface tests

resources/
├── migrations/                  # Event store SQL migrations (Migratus)
│   ├── 20260315000000-create-events.{up,down}.sql
│   ├── 20260316000000-create-projections.{up,down}.sql
│   └── 20260318000000-create-event-outbox.{up,down}.sql
└── read-migrations/             # Read store SQL migrations (separate DB)
    └── 20260318100000-create-read-model-tables.{up,down}.sql

RUNBOOK.md                       # Operational procedures for async projections
```

## Database Schema

### Event Store (write database)

```sql
-- Append-only event log
events (id UUID PK, global_sequence BIGSERIAL UNIQUE,
        stream_id TEXT, stream_sequence BIGINT,
        event_type TEXT, event_version INTEGER,
        payload JSONB, created_at TIMESTAMPTZ)
  UNIQUE (stream_id, stream_sequence)

-- Command deduplication
idempotency_keys (idempotency_key TEXT PK,
                  stream_id TEXT, command_type TEXT,
                  command_payload JSONB, created_at TIMESTAMPTZ)

-- Synchronous projection read models (single-DB mode)
account_balances (account_id TEXT PK, balance BIGINT,
                  last_global_sequence BIGINT, updated_at TIMESTAMPTZ)

transfer_status (transfer_id TEXT PK, from_account TEXT,
                 to_account TEXT, amount BIGINT,
                 status TEXT, failure_reason TEXT,
                 last_global_sequence BIGINT, updated_at TIMESTAMPTZ)

-- Projection progress tracking
projection_checkpoints (projection_name TEXT PK,
                        last_global_sequence BIGINT)

-- Transactional outbox for reliable event publishing
event_outbox (id BIGSERIAL PK, global_sequence BIGINT UNIQUE,
              published_at TIMESTAMPTZ)
  INDEX idx_event_outbox_unpublished ON (id) WHERE published_at IS NULL
```

### Read Store (separate database, async mode)

```sql
-- Same schema as above, but in a separate database
projection_checkpoints (projection_name TEXT PK,
                        last_global_sequence BIGINT)

account_balances (account_id TEXT PK, balance BIGINT,
                  last_global_sequence BIGINT, updated_at TIMESTAMPTZ)

transfer_status (transfer_id TEXT PK, from_account TEXT,
                 to_account TEXT, amount BIGINT,
                 status TEXT, failure_reason TEXT,
                 last_global_sequence BIGINT, updated_at TIMESTAMPTZ)
```

## Prerequisites

- Java 21
- [Babashka](https://github.com/babashka/babashka) (task runner)
- Docker (for Testcontainers -- runs disposable PostgreSQL 16 and RabbitMQ instances)
- [clj-kondo](https://github.com/clj-kondo/clj-kondo) (linting, optional)
- [cljfmt](https://github.com/weavejester/cljfmt) (formatting, optional)

If using [mise](https://mise.jdx.dev/), `mise install` will set up the correct tool versions.

## Quick Start

### REPL Walkthrough

The fastest way to explore the system is the interactive walkthrough in `dev/user.clj`:

```bash
bb clj-repl
```

Then evaluate the commented forms in `user.clj` step by step.

**Synchronous walkthrough** (Steps 1-10):

1. Starting a disposable Postgres container
2. Creating the schema
3. Sending commands (open account, deposit, withdraw)
4. Inspecting the event stream
5. Building and querying the read model
6. Demonstrating optimistic concurrency conflicts
7. Demonstrating idempotency
8. Demonstrating retry-on-conflict
9. Fund transfer saga (cross-account transfers, compensation, crash recovery)
10. Tearing down

**Async walkthrough** (bottom of `user.clj`):

1. Start the full Component system (3 Testcontainers)
2. Send commands with the outbox hook
3. Watch projections appear in the separate read database
4. Transfer saga via async pipeline
5. Stop the system

### Running Tests

```bash
# All tests (175 tests, 477 assertions)
bb test

# Fuzz/property-based tests only (filters by namespace pattern)
bb fuzz

# Quick checks (lint + format + compile)
bb check
```

### Performance Benchmarks

```bash
# Run benchmarks
bb perf

# Check for regressions against baseline
bb perf-check

# Update baseline from latest results
bb perf-baseline
```

### Database Migrations (External DB)

For running migrations against a real database (not Testcontainers):

```bash
# Event store migrations
JDBC_URL=jdbc:postgresql://localhost:5432/eventstore \
DB_USER=myuser DB_PASSWORD=mypass \
bb migrate

bb rollback
bb migration-status
```

Read store migrations use a separate migration directory and must be run against the read database independently.

## Claude Code + REPL Integration

This project is set up for **REPL-driven development with Claude Code**. Claude can evaluate Clojure code directly against a running nREPL server, enabling an interactive workflow where it edits code, loads it into the REPL, tests it, and iterates — the same workflow a human Clojure developer uses.

### How it works

The integration uses two CLI tools configured in `CLAUDE.md`:

**`clj-nrepl-eval`** — Evaluates Clojure code against an nREPL server. Session state persists between evaluations, so Claude can require a namespace in one call and use it in subsequent calls.

```bash
# Discover running nREPL servers in the project
clj-nrepl-eval --discover-ports

# Evaluate code against a specific port
clj-nrepl-eval -p <port> "(require '[bank.account :as account] :reload)"
clj-nrepl-eval -p <port> "(account/decide {:command-type :deposit :data {:amount 50}}
                                           {:status :open :balance 100})"

# Multiline via heredoc
clj-nrepl-eval -p <port> <<'EOF'
(require '[bank.account :as account] :reload)
(account/evolve {:status :open :balance 100}
                {:event-type "money-deposited" :event-version 3
                 :payload {:amount 50 :origin "command" :currency "USD"}})
EOF
```

**`clj-paren-repair`** — Automatically fixes mismatched parentheses in Clojure files. Configured as a hook so that when Claude writes or edits a `.clj` file, any delimiter errors are repaired before the file is saved.

### Typical Claude Code workflow

1. **Start an nREPL server** — `clj -M:nrepl` (or use the `/start-nrepl` skill)
2. **Claude discovers the port** — `clj-nrepl-eval --discover-ports`
3. **Edit-eval-iterate loop:**
   - Claude edits a source file
   - Parenthesis repair hook runs automatically
   - Claude loads the namespace: `(require '[bank.account :as account] :reload)`
   - Claude evaluates expressions to verify the change works
   - If something fails, Claude reads the error, adjusts, and re-evaluates
4. **Run tests** — `bb test` to confirm everything passes

This gives Claude the same tight feedback loop that makes REPL-driven development effective for human developers: write code, load it, try it, fix it, repeat — all without restarting the JVM.

### Configuration

The REPL integration is configured via:

- **`CLAUDE.md`** — Instructions for Claude on how to use `clj-nrepl-eval` and `clj-paren-repair`
- **`deps.edn` `:nrepl` alias** — Starts an nREPL server with `clj -M:nrepl`
- **`.claude/settings.local.json`** — Permission rules for the CLI tools
- **`~/.claude/skills/clojure-eval/`** — Claude Code skill that teaches Claude the REPL evaluation workflow

## Test Strategy

| Layer | Files | What it tests | DB required |
|---|---|---|---|
| Unit | `account_test`, `transfer_test`, `decider_test`, `decider_kit_test`, `store_test`, `projection_kit_test`, `saga_test` | Pure domain logic, command handler wiring, store utilities, factory functions | No |
| Functional | `functional_test` | End-to-end lifecycle (open → deposit → withdraw → projection) | Yes |
| Integration | `integration_test`, `store_integration_test`, `transfer_saga_test`, `projection_test` | Concurrency, idempotency, migrations, projection correctness, cross-account sagas | Yes (1× Postgres) |
| Async | `async_projection_test`, `outbox_test`, `component_test` | Outbox record/poll/publish, two-database projection, batch processing, Component lifecycle | Yes (2× Postgres) |
| End-to-end | `async_integration_test` | Full async pipeline: command → outbox → RabbitMQ → projector → read DB | Yes (2× Postgres + RabbitMQ) |
| Property-based | `fuzz_unit_test`, `fuzz_integration_test` | Random command sequences never violate invariants; projection rebuild matches incremental | Mixed |
| Performance | `perf`, `perf_check` | Latency and throughput benchmarks with regression detection | Yes |

All DB-backed tests use Testcontainers (PostgreSQL 16 Alpine, RabbitMQ 3 Management Alpine) -- no external database setup required.

## Dependencies

| Dependency | Purpose |
|---|---|
| Clojure 1.12.0 | Language |
| next.jdbc 1.3.909 | Database access |
| PostgreSQL 42.7.1 | JDBC driver |
| Testcontainers/PostgreSQL 1.21.4 | Disposable test databases |
| Testcontainers/RabbitMQ 1.21.4 | Disposable test message broker |
| Malli 0.16.4 | Schema validation and generation |
| Migratus 1.6.4 | Database migrations |
| data.json 2.4.0 | JSON serialisation |
| Component 1.1.0 | Lifecycle management |
| Langohr 5.5.0 | RabbitMQ client (AMQP) |
| test.check 1.1.1 | Property-based testing |
| Cognitect test-runner 0.5.1 | Test autodiscovery |
| cloverage 1.2.4 | Code coverage |

## Key Design Decisions

**Pure domain, infrastructure boundary** -- The domain (`bank.account`, `bank.transfer`) contains no I/O, no timestamps, no sequence numbers. All infrastructure concerns (concurrency, idempotency, persistence) live in `es.decider` and `es.store`. This makes the domain trivially testable and portable.

**Data-driven over boilerplate** -- Both deciders and projections are built from data declarations. `es.decider-kit` takes schema maps and returns wired functions. `es.projection-kit` takes handler maps and returns dispatch functions. Adding a new aggregate requires writing data, not copying machinery.

**Idempotency separated from events** -- Command deduplication uses a dedicated `idempotency_keys` table rather than columns on the events table. This avoids cross-stream races and keeps event rows focused on domain facts.

**Advisory locks over row-level locks** -- `pg_advisory_xact_lock` serialises writers per stream without touching event rows. Combined with version checks, this gives deterministic conflict detection with minimal lock contention.

**Projections are disposable** -- The read model can be destroyed and rebuilt from the event stream at any time. The checkpoint tracks progress using `global_sequence`, not timestamps, ensuring exactly-once processing semantics.

**Event versioning as a domain concern** -- Upcasters live in the domain layer alongside the schemas they transform. The store is version-agnostic; it stores whatever version it receives and passes `event_version` through on read.

**Notifications over event-carrying messages** -- RabbitMQ messages signal "new events exist" rather than carrying event data. Projectors read directly from the event store, which means lost or duplicate messages are harmless. This avoids dual-write problems and keeps the event store as the single source of truth.

**Transactional outbox for reliable publishing** -- Outbox rows are written in the same transaction as events, guaranteeing at-least-once delivery. The poller uses `FOR UPDATE SKIP LOCKED` for safe concurrent polling.

**Serialised catch-ups** -- Concurrent catch-ups from RabbitMQ messages and the catch-up timer are serialised via `compare-and-set!`, preventing races where two threads try to process the same events. Channels use `prefetch=1` since each catch-up handles all pending events.

**Poison event resilience** -- After configurable consecutive failures (default 5), poison events are skipped and the projector resumes processing. The `on-poison` callback enables alerting. A full rebuild replays all events including previously skipped ones. This prevents a single bad event from permanently blocking a projector.

**Framework/domain separation** -- All infrastructure (`es.*`) is domain-agnostic. The bank domain is just one consumer. Adding a new domain means writing new deciders, projections, and a system assembly — the framework doesn't change.

## Operations

See [RUNBOOK.md](RUNBOOK.md) for operational procedures including:

- Poison event diagnosis and recovery
- Rebuilding projections
- Deploying new event types
- Dead letter queue inspection
- Outbox table maintenance
- Monitoring checklist
- Saga crash recovery

## References

- Jérôme Chassaing -- [Functional Event Sourcing: Decider](https://thinkbeforecoding.com/post/2021/12/17/functional-event-sourcing-decider) -- the pattern this project implements
- Zach Tellman -- *Elements of Clojure* (Pull, Transform, Push)
- Greg Young -- Event Sourcing and CQRS
- Stuart Sierra -- [Component](https://github.com/stuartsierra/component) -- lifecycle management
- Chris Richardson -- [Transactional Outbox Pattern](https://microservices.io/patterns/data/transactional-outbox.html)
