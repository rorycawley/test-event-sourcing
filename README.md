# Event Sourcing in Clojure

A reference implementation of event sourcing using the **Decider pattern** (Chassaing) and **Pull-Transform-Push** (Tellman), backed by PostgreSQL.

Implements a bank account domain to demonstrate: append-only event storage, optimistic concurrency with retry, command idempotency, event versioning with upcasting, projections (derived read models), and cross-aggregate saga coordination (fund transfers).

## Architecture

```
┌─────────────────────────────────────────────────────────────────┐
│                    Command Handler (decider.clj)                │
│                                                                 │
│   Pull           Transform              Push                    │
│   ┌──────┐       ┌────────────────┐     ┌──────────────────┐   │
│   │ Load │       │ Evolve state   │     │ Append events    │   │
│   │ event│──────▶│ (left fold)    │────▶│ with optimistic  │   │
│   │stream│       │ Decide new     │     │ concurrency +    │   │
│   │      │       │ events (pure)  │     │ idempotency      │   │
│   └──────┘       └────────────────┘     └──────────────────┘   │
│       │                                         │               │
│       ▼                                         ▼               │
│  ┌──────────────────────────────────────────────────┐           │
│  │              PostgreSQL (store.clj)              │           │
│  │                                                  │           │
│  │  events table          idempotency_keys table    │           │
│  │  (append-only log)     (command deduplication)   │           │
│  └──────────────────────────────────────────────────┘           │
│                         │                                       │
│                         ▼                                       │
│            ┌──────────────────────┐                             │
│            │  Projection          │                             │
│            │  (projection.clj)    │                             │
│            │                      │                             │
│            │  account_balances    │                             │
│            │  (derived read model)│                             │
│            └──────────────────────┘                             │
└─────────────────────────────────────────────────────────────────┘
```

### The Decider Pattern

The domain is defined as a plain Clojure map with three elements:

```clojure
{:initial-state {:status :not-found, :balance 0}
 :decide        decide   ;; Command -> State -> [Event]
 :evolve        evolve}  ;; State -> Event -> State
```

- **`evolve`** is a pure fold step: given current state and an event, produce next state
- **`decide`** is a pure decision: given a command and current state, produce new events (or throw on business rule violation)
- No I/O, no database, no side effects in the domain

The command handler (`decider.clj`) provides the infrastructure wiring:

1. **Pull** -- load events from the store (I/O)
2. **Transform** -- `reduce evolve initial-state events`, then `decide command state` (pure)
3. **Push** -- append new events to the store (I/O)

### Event Store

The event store (`store.clj`) is an append-only log in PostgreSQL with:

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

The transfer saga (`transfer_saga.clj`) demonstrates cross-aggregate coordination:

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

The transfer is its own Decider (`transfer.clj`) with a state machine: `not-found → initiated → debited → credited → completed` (or `→ failed` from any non-terminal state). The saga coordinator uses idempotency keys derived from the transfer-id, so every step is safe to retry.

**Crash recovery**: if the process dies mid-transfer, `resume!` reads the transfer stream, evolves to the current state, and picks up from the last completed step. Because every step is idempotent, resumption is always safe — money is never lost or duplicated.

### Projections

The read model (`projection.clj`) is a derived, disposable view built from the event stream:

- **Catch-up processing**: reads events after last checkpoint, applies them, advances checkpoint
- **Full rebuild**: destroys and rebuilds from complete event stream
- **Correctness**: advisory lock serialises workers; all work in one transaction; failure rolls back without advancing checkpoint
- **Dispatch**: multimethod on `:event-type` (`projection_dispatch.clj`, `account_projection.clj`)

## Project Structure

```
src/event_sourcing/
├── account.clj               # Account domain model (the Decider) -- pure, no I/O
├── transfer.clj              # Transfer domain model (the Decider) -- pure, no I/O
├── transfer_saga.clj         # Saga coordinator for cross-account transfers
├── decider.clj               # Command handler (Pull -> Transform -> Push)
├── store.clj                 # Append-only event store (PostgreSQL)
├── projection.clj            # Read model catch-up and rebuild
├── projection_dispatch.clj   # Multimethod dispatch for projection handlers
├── account_projection.clj    # Account event handlers for the projection
├── transfer_projection.clj   # Transfer event handlers for the projection
├── schema.clj                # Shared Malli schemas
├── migrations.clj            # Migratus migration wrapper
├── migrations_cli.clj        # CLI for running migrations against external DB
└── infra.clj                 # Testcontainer lifecycle (disposable Postgres)

dev/
└── user.clj                  # Interactive REPL walkthrough (10 steps)

test/event_sourcing/
├── account_test.clj          # Pure account domain unit tests (no DB)
├── transfer_test.clj         # Pure transfer domain unit tests (no DB)
├── transfer_saga_test.clj    # Transfer saga integration tests (DB)
├── decider_test.clj          # Command handler tests (mocked store)
├── store_test.clj            # Store utility unit tests
├── store_integration_test.clj # Event store integration tests (DB)
├── functional_test.clj       # End-to-end lifecycle tests (DB)
├── integration_test.clj      # Comprehensive integration tests (DB)
├── fuzz_unit_test.clj        # Property-based domain tests
├── fuzz_integration_test.clj # Property-based integration tests (DB)
├── migrations_test.clj       # Migration wrapper tests
├── migrations_cli_test.clj   # CLI interface tests
├── perf.clj                  # Performance benchmarks
├── perf_check.clj            # Regression detection vs baseline
├── perf_baseline.clj         # Baseline management
└── test_support.clj          # Test fixtures and utilities

resources/migrations/          # SQL migrations (Migratus)
```

## Database Schema

Five tables, created via Migratus migrations:

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

-- Projection read model
account_balances (account_id TEXT PK, balance BIGINT,
                  last_global_sequence BIGINT, updated_at TIMESTAMPTZ)

-- Transfer saga read model
transfer_status (transfer_id TEXT PK, from_account TEXT,
                 to_account TEXT, amount BIGINT,
                 status TEXT, failure_reason TEXT,
                 last_global_sequence BIGINT, updated_at TIMESTAMPTZ)

-- Projection progress tracking
projection_checkpoints (projection_name TEXT PK,
                        last_global_sequence BIGINT)
```

## Prerequisites

- Java 21
- [Babashka](https://github.com/babashka/babashka) (task runner)
- Docker (for Testcontainers -- runs a disposable PostgreSQL 16 instance)
- [clj-kondo](https://github.com/clj-kondo/clj-kondo) (linting, optional)
- [cljfmt](https://github.com/weavejester/cljfmt) (formatting, optional)

If using [mise](https://mise.jdx.dev/), `mise install` will set up the correct tool versions.

## Quick Start

### REPL Walkthrough

The fastest way to explore the system is the interactive walkthrough in `dev/user.clj`:

```bash
bb clj-repl
```

Then evaluate the commented forms in `user.clj` step by step. The walkthrough covers:

1. Starting a disposable Postgres container
2. Creating the schema
3. Sending commands (open account, deposit, withdraw)
4. Inspecting the event stream
5. Building and querying the read model
6. Demonstrating optimistic concurrency conflicts
7. Demonstrating idempotency
8. Demonstrating retry-on-conflict
9. Tearing down

### Running Tests

```bash
# All tests (unit + functional + integration + fuzz)
bb test

# Fuzz/property-based tests only
bb fuzz

# Quick checks (lint + format + compile)
bb check

# Code coverage (88% threshold)
bb coverage
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
JDBC_URL=jdbc:postgresql://localhost:5432/mydb \
DB_USER=myuser DB_PASSWORD=mypass \
bb migrate

bb rollback
bb migration-status
```

## Test Strategy

| Layer | Files | What it tests | DB required |
|---|---|---|---|
| Unit | `account_test`, `transfer_test`, `decider_test`, `store_test` | Pure domain logic, command handler wiring, store utilities | No |
| Functional | `functional_test` | End-to-end lifecycle (open -> deposit -> withdraw -> projection) | Yes |
| Integration | `integration_test`, `store_integration_test`, `transfer_saga_test` | Concurrency, idempotency, migrations, projection correctness, cross-account sagas | Yes |
| Property-based | `fuzz_unit_test`, `fuzz_integration_test` | Random command sequences never violate invariants; projection rebuild matches incremental | Mixed |
| Performance | `perf`, `perf_check` | Latency and throughput benchmarks with regression detection | Yes |

All DB-backed tests use Testcontainers (PostgreSQL 16 Alpine) -- no external database setup required.

## Dependencies

| Dependency | Purpose |
|---|---|
| Clojure 1.12.0 | Language |
| next.jdbc 1.3.909 | Database access |
| PostgreSQL 42.7.1 | JDBC driver |
| Testcontainers 1.21.4 | Disposable test databases |
| Malli 0.16.4 | Schema validation and generation |
| Migratus 1.6.4 | Database migrations |
| data.json 2.4.0 | JSON serialisation |
| test.check 1.1.1 | Property-based testing |
| cloverage 1.2.4 | Code coverage |

## Key Design Decisions

**Pure domain, infrastructure boundary** -- The domain (`account.clj`) contains no I/O, no timestamps, no sequence numbers. All infrastructure concerns (concurrency, idempotency, persistence) live in `decider.clj` and `store.clj`. This makes the domain trivially testable and portable.

**Idempotency separated from events** -- Command deduplication uses a dedicated `idempotency_keys` table rather than columns on the events table. This avoids cross-stream races and keeps event rows focused on domain facts.

**Advisory locks over row-level locks** -- `pg_advisory_xact_lock` serialises writers per stream without touching event rows. Combined with version checks, this gives deterministic conflict detection with minimal lock contention.

**Projections are disposable** -- The read model can be destroyed and rebuilt from the event stream at any time. The checkpoint tracks progress using `global_sequence`, not timestamps, ensuring exactly-once processing semantics.

**Event versioning as a domain concern** -- Upcasters live in the domain layer alongside the schemas they transform. The store is version-agnostic; it stores whatever version it receives and passes `event_version` through on read.

## References

- Jrme Chassaing -- [The Decider Pattern](https://thinkbeforecoding.com/post/2021/12/17/functional-event-sourcing-decider)
- Zach Tellman -- *Elements of Clojure* (Pull, Transform, Push)
- Greg Young -- Event Sourcing and CQRS
