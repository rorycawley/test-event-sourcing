# Event Modeling → Clojure Implementation

This document maps Event Modeling concepts to our Clojure implementation.
Event Modeling is a visual design method for event-driven systems. Each
visual element in an Event Modeling diagram has a direct counterpart in
our codebase.

## The Four Patterns

Event Modeling defines four patterns. Our system uses all four:

```
  Pattern              Visual         Clojure construct
  ─────────────────    ──────────     ─────────────────────────────────
  1. Command           Blue box       {:command-type :deposit :data {}}
  2. Event             Orange box     {:event-type "money-deposited" ...}
  3. Read Model        Green box      Projection (async or sync)
  4. Automation         Blue box       Reactor (event → command)
                       (between
                        swimlanes)
```

## Pattern 1: Command → Aggregate → Event

A user or system issues a command. The aggregate validates it against
the current state and emits events.

```
  ┌─────────────┐     ┌───────────┐     ┌──────────────────┐
  │   Command   │────>│ Aggregate │────>│      Event       │
  │  (intent)   │     │  (rules)  │     │     (fact)       │
  └─────────────┘     └───────────┘     └──────────────────┘

  {:command-type       bank.account/     {:event-type
     :deposit            decider            "money-deposited"
   :stream-id                             :payload
     "account-42"       {:initial-state     {:amount 100}}
   :data                 :decide
     {:amount 100}}      :evolve}
```

### Clojure mapping

| Event Modeling | Clojure | File pattern |
|----------------|---------|--------------|
| Command | Map with `:command-type`, `:stream-id`, `:data` | — |
| Aggregate | Decider map: `{:initial-state, :decide, :evolve}` | `<module>/<aggregate>.clj` |
| Event | Map with `:event-type`, `:event-version`, `:payload` | Stored in `events` table |

The Decider (Chassaing) is our aggregate pattern. It is a plain map
of three pure functions — no I/O, no database, no side effects:

```clojure
;; bank/account.clj — the Account Decider
(def decider
  {:initial-state {:status :not-found, :balance 0}
   :decide        decide    ;; Command → State → [Event]
   :evolve        evolve})  ;; State → Event → State
```

The infrastructure (`es.decider`) runs the Pull → Transform → Push
cycle against the event store:

```
  Pull:       load events from Postgres
  Transform:  (reduce evolve initial-state events) → state
              (decide command state) → new events
  Push:       append new events to Postgres
```

## Pattern 2: Event → Read Model (Projection)

Events are projected into a queryable read model. The projection
is a fold over the event stream, similar to evolve but targeting a
separate database table.

```
  ┌──────────────────┐     ┌──────────────┐     ┌──────────────────┐
  │      Event       │────>│  Projection  │────>│   Read Model     │
  │     (fact)       │     │  (fold)      │     │   (query)        │
  └──────────────────┘     └──────────────┘     └──────────────────┘

  Events table              handler-specs         account_balances
  (event store)             {event-type →         (read database)
                              (fn [tx event
                                   context])}
```

### Clojure mapping

| Event Modeling | Clojure | File pattern |
|----------------|---------|--------------|
| Read Model | Projection handler-specs + query fn | `<module>/<name>_projection.clj` |
| Green box (query) | `(get-balance ds "account-42")` | Same file |

Projections are data-driven maps:

```clojure
;; bank/account_projection.clj
(def handler-specs
  {"account-opened"  (fn [tx event _ctx] ...)
   "money-deposited" (fn [tx event ctx] ...)
   "money-withdrawn" (fn [tx event ctx] ...)})

(def get-balance
  (kit/make-query "account_balances" "account_id"))
```

### Sync vs Async projections

```
  Sync projection (single DB):
    Event Store ──── same tx ───> Read Model

  Async projection (separate DBs):
    Event Store ─> Outbox ─> RabbitMQ ─> Projector ─> Read Model
                                              │
                                    catch-up timer (DR)
```

Async projectors use checkpoint-based catch-up. They don't need an
inbox because the checkpoint IS the deduplication mechanism. If a
projector processes event 42 twice, the `last_global_sequence < 42`
guard in the UPDATE makes the second application a no-op.

## Pattern 3: Automation / Reactor (Event → Command)

An automation observes events and issues commands. No human involved.
In Event Modeling diagrams, this is the blue box that sits between
two swimlanes — connecting one aggregate's events to another
aggregate's commands.

```
  ┌──────────────────┐     ┌──────────────┐     ┌─────────────┐
  │      Event       │────>│   Reactor    │────>│   Command   │
  │  (integration)   │     │ (automation) │     │  (to new    │
  │                  │     │              │     │  aggregate)  │
  └──────────────────┘     └──────────────┘     └─────────────┘

  bank.account-opened       notification/         :request-notification
  (integration event)       reactor.clj           (to notification
                                                   aggregate)
```

### Clojure mapping

| Event Modeling | Clojure | File pattern |
|----------------|---------|--------------|
| Automation | Reactor — `make-handler-specs` returns handler map | `<module>/reactor.clj` |
| Cross-module event | Integration event (shared schema) | `events/<module>.clj` |

Reactors are the bridge between modules. They depend on the shared
integration event contract and the target aggregate, but NOT on the
source module's internals:

```clojure
;; notification/reactor.clj
(defn make-handler-specs [event-store-ds]
  {"bank.account-opened"
   (fn [_tx event]
     (let [event (bank-events/validate! event)]
       (decider/handle-with-retry!
        event-store-ds notification/decider
        {:command-type    :request-notification
         :stream-id       (notification-stream-id ...)
         :idempotency-key (str "notif-welcome-" ...)
         :data            {:notification-type "welcome-email"
                           :recipient-id      (:account-id event)
                           :payload           {...}}})))})
```

### Why aggregates don't perform side effects

The notification aggregate does NOT send emails. Its job is to track
the lifecycle — a pure state machine:

```
  :not-found ──request──> :pending ──mark-sent──> :sent (terminal)
                              │
                         mark-failed
                              │
                     retry-count < 3? ──yes──> :pending (retry)
                              │
                             no
                              │
                         :failed (terminal, abandoned)
```

The actual email delivery is the job of a separate worker (the
"imperative shell"). It would:

1. Listen for `notification-requested` events
2. Call SendGrid/SES/SMTP
3. Issue `:mark-sent` or `:mark-failed` commands back to the aggregate

This separation keeps the aggregate pure, testable, and free of I/O.

## Pattern 4: Integration Events (Between Modules)

In a modular monolith, modules communicate through integration events.
These are NOT raw domain events — they are curated, significant,
summary-level events that form the module's public API.

```
  Bank Module                        Notification Module
  ───────────                        ───────────────────

  Domain Events                      Integration Events
  (internal)                         (public contract)

  account-opened ─┐
  money-deposited ─┼─ domain->       bank.account-opened ──> Reactor
  debit-recorded ──┘  integration    bank.money-deposited ──> Reactor
  credit-recorded     (filter +      (debit-recorded: filtered out,
                       transform)     it's an internal saga step)
```

### Shared contract schema

Both producer and consumer validate against the same Malli schema:

```clojure
;; events/bank.clj — shared contract (single source of truth)
(def account-opened
  [:map
   [:event-type      [:= "bank.account-opened"]]
   [:global-sequence pos-int?]
   [:account-id      string?]
   [:owner           [:maybe string?]]])

(defn validate! [event] ...)
```

- Producer validates OUTGOING in `bank.integration-events/domain->integration`
- Consumer validates INCOMING in `notification.reactor` handler

If a schema changes, both sides fail at compile/test time — never
silently at runtime.

## Full Architecture: Event Modeling View

```
  Bank Module (Producer)
  ══════════════════════════════════════════════════════════════

  ┌──────────┐    ┌───────────┐    ┌──────────────┐
  │ :deposit │───>│  Account  │───>│ money-       │──┐
  │ command  │    │  Decider  │    │ deposited    │  │
  └──────────┘    └───────────┘    └──────────────┘  │
                                                      │
                                        domain->integration
                                                      │
                                   ┌──────────────────┘
                                   v
                         ┌───────────────────┐
                         │ bank.money-       │
                         │ deposited         │    Integration
                         │ (integration      │    Event
                         │  event in outbox) │
                         └────────┬──────────┘
                                  │
                    ┌─────────────┼──────────────┐
                    v             v               v
              ┌──────────┐ ┌──────────┐   ┌───────────┐
              │ Account  │ │ Transfer │   │  Outbox   │
              │ Balance  │ │ Status   │   │  Poller   │
              │ Projector│ │ Projector│   │           │
              └────┬─────┘ └────┬─────┘   └─────┬─────┘
                   v            v               v
              ┌─────────────────┐        ┌──────────┐
              │   Read Store    │        │ RabbitMQ │
              │  (Postgres)     │        │ (fanout) │
              └─────────────────┘        └─────┬────┘
                                               │
  ═════════════════════════════════════════════════════════════
  Notification Module (Consumer)                │
  ═════════════════════════════════════════════════════════════
                                               │
                                    ┌──────────┘
                                    v
                         ┌────────────────────┐
                         │ Consumer Group     │
                         │ (inbox + workers)  │
                         └────────┬───────────┘
                                  │
                                  v
                         ┌────────────────────┐
                         │     Reactor        │    Automation
                         │ (event → command)  │    (Event Modeling)
                         └────────┬───────────┘
                                  │
                                  v
                         ┌────────────────────┐
                         │  Notification      │
                         │  Aggregate         │    Command + Aggregate
                         │  (Decider)         │    (Event Modeling)
                         └────────┬───────────┘
                                  │
                         notification-requested
                                  │
                    ┌─────────────┼──────────────┐
                    v                             v
           ┌───────────────┐            ┌─────────────────┐
           │ Notification  │            │ Delivery Worker  │
           │ Projector     │            │ (future: send    │
           │               │            │  email, issue    │
           │               │            │  :mark-sent or   │
           │               │            │  :mark-failed)   │
           └───────┬───────┘            └─────────────────┘
                   v
           ┌───────────────┐
           │ Notification  │    Read Model
           │ Read Model    │    (Event Modeling)
           │ (notifications│
           │  table)       │
           └───────────────┘
```

## Mapping Summary

| Event Modeling | Clojure construct | File pattern | Example |
|----------------|-------------------|--------------|---------|
| Command | `{:command-type :k :data {...}}` | — | `:request-notification` |
| Aggregate | Decider `{:initial-state :decide :evolve}` | `<mod>/<agg>.clj` | `notification/notification.clj` |
| Event | `{:event-type "..." :payload {...}}` | stored in `events` table | `notification-requested` |
| Read Model | Projection handler-specs + query fn | `<mod>/<name>_projection.clj` | `notification/notification_projection.clj` |
| Automation | Reactor `make-handler-specs` | `<mod>/reactor.clj` | `notification/reactor.clj` |
| Integration Event | Shared Malli schema | `events/<mod>.clj` | `events/bank.clj` |
| Swimlane | Module boundary | `<mod>/*` namespace | `notification.*`, `bank.*` |

## Creating a New Module

To add a new module (e.g., `fraud`):

1. **Define the aggregate** — `fraud/detection.clj` with Decider map
2. **Define the reactor** — `fraud/reactor.clj` with `make-handler-specs`
3. **Define the projection** — `fraud/detection_projection.clj`
4. **Add shared schema** — `events/fraud.clj` (if publishing integration events)
5. **Add migrations** — `resources/fraud-migrations/001-schema.up.sql`
6. **Wire in system.clj** — datasource, migrator, consumer group, projector

No changes needed to existing modules. The only coupling point is
the shared integration event schema in `events/bank.clj`.

## Functional Core / Imperative Shell

Our architecture follows this principle strictly:

```
  Functional Core (pure, testable, no I/O):
    - Aggregates (Deciders): decide + evolve
    - Reactors: event → command translation logic
    - Integration event schemas

  Imperative Shell (I/O, infrastructure):
    - es.decider: Pull → Transform → Push
    - es.async-projection: checkpoint-based catch-up
    - es.consumer: inbox claims, RabbitMQ bridge
    - Delivery workers: external API calls (email, SMS)
```

The aggregate is always pure. Side effects (sending emails, calling
APIs) happen in separate workers that read events and issue commands
back. This makes the domain logic fast, predictable, and testable
without mocking external services.
