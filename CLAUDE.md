# Project: Event Sourcing in Clojure

## REPL Workflow

### nREPL Evaluation

The command `clj-nrepl-eval` is installed on your path.

**Discover nREPL servers:**
`clj-nrepl-eval --discover-ports`

**Evaluate code:**
`clj-nrepl-eval -p <port> "<clojure-code>"`

**With timeout:**
`clj-nrepl-eval -p <port> --timeout 5000 "<clojure-code>"`

The REPL session persists between evaluations — namespaces and state are maintained.
Always use `:reload` when requiring namespaces to pick up file changes.

### Parenthesis Repair

The command `clj-paren-repair` is installed on your path.
**IMPORTANT:** Do NOT try to manually repair parenthesis errors.
Run `clj-paren-repair <file>` instead.

## Testing

Run all tests: `bb test`
Run fuzz tests only: `bb fuzz`
Run quick checks (lint + format + smoke): `bb check`

## Project Structure

- `es.*` — Reusable event sourcing framework (store, decider, projection, async-projection, outbox, consumer, saga, rabbitmq, search, component, migrations)
- `modules.bank.domain.*` — Bank domain (account aggregate, transfer aggregate)
- `modules.bank.use-cases.*` — Bank use cases / vertical slices (open-account, deposit, withdraw)
- `modules.bank.infra.*` — Bank infrastructure (projections, saga, integration-events, system, components)
- `modules.notification.domain.*` — Notification domain (delivery aggregate)
- `modules.notification.infra.*` — Notification infrastructure (delivery-projection, reactor, components)
- `events.bank` — Shared integration event schemas (contract between modules)
- `system` — Full async system composition root (all modules, all infrastructure)

## Key Commands

- `bb test` — Run all tests (requires Docker for Testcontainers)
- `bb check` — Lint + format check + smoke compile
- `bb fmt` — Auto-format all Clojure files
- `bb perf` — Run performance benchmarks
- `bb perf-check` — Check for performance regressions
- `bb migrate` — Apply DB migrations (requires JDBC_URL)
