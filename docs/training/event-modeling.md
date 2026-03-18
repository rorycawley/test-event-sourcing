# Event Modeling -> Clojure Implementation

This document maps Event Modeling concepts to our Clojure implementation.
Event Modeling is a visual design method for event-driven systems. Each
visual element in an Event Modeling diagram has a direct counterpart in
our codebase.

## The Four Patterns

Event Modeling defines four patterns. Our system uses all four:

```
  Pattern              Visual         Clojure construct
  -----------------    ----------     --------------------------------
  1. Command           Blue box       {:command-type :deposit :data {}}
  2. Event             Orange box     {:event-type "money-deposited" ...}
  3. Read Model        Green box      Projection (async or sync)
  4. Automation        Blue box       Reactor or Saga (event -> command)
                       (between
                        swimlanes)
```

## Pattern 1: Command -> Aggregate -> Event

A user or system issues a command. The aggregate validates it against
the current state and emits events.

```
  +-------------+     +-----------+     +------------------+
  |   Command   |---->| Aggregate |---->|      Event       |
  |  (intent)   |     |  (rules)  |     |     (fact)       |
  +-------------+     +-----------+     +------------------+
```

The Decider (Chassaing) is our aggregate pattern. It is a plain map
of three pure functions -- no I/O, no database, no side effects.

### The Account Decider

```clojure
;; bank/account.clj

(def decider
  {:initial-state {:status :not-found, :balance 0}
   :decide        decide    ;; Command -> State -> [Event]
   :evolve        evolve})  ;; State -> Event -> State
```

**Evolve** -- given what happened, update the state:

```clojure
;; bank/account.clj — pure fold step, no I/O

(defn evolve [state event]
  (let [{:keys [event-type payload]} (validate-event! event)]
    (case event-type
      "account-opened"  (assoc state :status :open
                               :owner  (:owner payload))
      "money-deposited" (update state :balance + (:amount payload))
      "money-withdrawn" (update state :balance - (:amount payload))
      state)))
```

**Decide** -- given the intent and the truth, decide what should happen:

```clojure
;; bank/account.clj — business rules, returns events or throws

(defn- decide-withdraw [state {:keys [amount]}]
  (when-not (= :open (:status state))
    (throw (ex-info "Account not open"
                    {:error/type :domain/account-not-open})))
  (when (> amount (:balance state))
    (throw (ex-info "Insufficient funds"
                    {:error/type :domain/insufficient-funds
                     :balance    (:balance state)
                     :amount     amount})))
  [(mk-event "money-withdrawn" {:amount amount})])
```

The decide function is routed via a plain map:

```clojure
(def ^:private decisions
  {:open-account decide-open
   :deposit      decide-deposit
   :withdraw     decide-withdraw})
```

### The Transfer Decider

The transfer Decider models a multi-step workflow as a state machine:

```clojure
;; bank/transfer.clj

(def decider
  {:initial-state {:status :not-found}
   :decide        decide
   :evolve        evolve})
```

Its evolve function tracks the saga's progress:

```clojure
;; bank/transfer.clj

(defn evolve [state event]
  (let [{:keys [event-type payload]} (validate-event! event)]
    (case event-type
      "transfer-initiated" (assoc state
                                  :status       :initiated
                                  :from-account (:from-account payload)
                                  :to-account   (:to-account payload)
                                  :amount       (:amount payload))
      "debit-recorded"        (assoc state :status :debited)
      "credit-recorded"       (assoc state :status :credited)
      "transfer-completed"    (assoc state :status :completed)
      "compensation-recorded" (assoc state
                                     :status :compensating
                                     :compensation-reason (:reason payload))
      "transfer-failed"       (assoc state
                                     :status :failed
                                     :failure-reason (:reason payload))
      state)))
```

### The Notification Decider

Tracks notification lifecycle -- does NOT send emails:

```clojure
;; notification/notification.clj

(def decider
  {:initial-state {:status :not-found, :retry-count 0}
   :decide        decide
   :evolve        evolve})
```

```clojure
;; notification/notification.clj — retry logic lives in the domain

(defn- decide-mark-failed [state {:keys [reason]}]
  (when-not (= :pending (:status state))
    (throw (ex-info "Notification not pending"
                    {:error/type :domain/notification-not-pending
                     :status     (:status state)})))
  ;; After 3 failed attempts, abandon the notification
  (if (>= (inc (:retry-count state)) max-delivery-attempts)
    [(mk-event "notification-abandoned" {:reason reason})]
    [(mk-event "notification-failed" {:reason reason})]))
```

State machine:

```
  :not-found --request--> :pending --mark-sent--> :sent (terminal)
                              |
                         mark-failed
                              |
                     retry-count < 3? --yes--> :pending (retry)
                              |
                             no
                              |
                         :failed (terminal, abandoned)
```

### Pull -> Transform -> Push

The infrastructure (`es.decider`) runs the Decider against the event
store. The domain knows nothing about Postgres:

```clojure
;; es/decider.clj — the imperative shell

(defn handle! [ds decider {:keys [stream-id idempotency-key] :as command}
               & {:keys [on-events-appended]}]
  (validate-command-envelope! command)
  (if (= :idempotent
         (store/check-idempotency! ds stream-id idempotency-key command))
    :idempotent
    (let [;; — Pull —
          events     (store/load-stream ds stream-id)
          ;; — Transform —
          state      (evolve-state decider events)
          version    (stream-version events)
          new-events ((:decide decider) command state)]
      ;; — Push —
      (store/append-events! ds stream-id version
                            idempotency-key command new-events
                            :on-events-appended on-events-appended))))
```

Concurrency conflicts trigger a full retry of the Pull -> Transform -> Push
cycle via `handle-with-retry!`, which uses exponential backoff + jitter.

### All commands and events

**bank.account** (3 commands, 3 events):

| Command | Event | Payload |
|---------|-------|---------|
| `:open-account` | `account-opened` (v1) | `{:owner}` |
| `:deposit` | `money-deposited` (v3) | `{:amount, :origin, :currency}` |
| `:withdraw` | `money-withdrawn` (v1) | `{:amount}` |

**bank.transfer** (6 commands, 6 events):

| Command | Event | Purpose |
|---------|-------|---------|
| `:initiate-transfer` | `transfer-initiated` | Start saga |
| `:record-debit` | `debit-recorded` | Saga: source debited |
| `:record-credit` | `credit-recorded` | Saga: dest credited |
| `:complete-transfer` | `transfer-completed` | Saga: done |
| `:record-compensation` | `compensation-recorded` | Saga: refund needed |
| `:fail-transfer` | `transfer-failed` | Saga: failed |

**notification.notification** (3 commands, 4 events):

| Command | Event(s) | Purpose |
|---------|----------|---------|
| `:request-notification` | `notification-requested` | Create notification |
| `:mark-sent` | `notification-sent` | Delivery confirmed |
| `:mark-failed` | `notification-failed` or `notification-abandoned` | Delivery failed (retry or give up after 3) |

### Event versioning and upcasting

Events can evolve over time. The account module shows the full pattern
-- `money-deposited` has been through three versions:

```clojure
;; bank/account.clj — version history

(def ^:private latest-event-version
  {"account-opened" 1
   "money-deposited" 3      ;; <-- evolved twice
   "money-withdrawn" 1})

(def ^:private event-schemas
  {["money-deposited" 1] [:map [:amount pos-int?]]
   ["money-deposited" 2] [:map [:amount pos-int?]
                                [:origin schema/non-empty-string]]
   ["money-deposited" 3] [:map [:amount pos-int?]
                                [:origin schema/non-empty-string]
                                [:currency [:= "USD"]]]})
```

Upcasters chain one version at a time (v1 -> v2 -> v3):

```clojure
;; bank/account.clj — upcasters add missing fields with sensible defaults

(def ^:private event-upcasters
  {["money-deposited" 1]
   (fn [{:keys [payload] :as event}]
     (assoc event
            :event-version 2
            :payload (assoc payload :origin "legacy")))

   ["money-deposited" 2]
   (fn [{:keys [payload] :as event}]
     (assoc event
            :event-version 3
            :payload (assoc payload :currency "USD")))})
```

Old events stored as v1 are transparently upcasted when read. The evolve
function always sees the latest schema. No data migration needed.

### Command shape

A command flows into the system as a plain map:

```clojure
;; The command envelope — same shape for all aggregates
{:command-type    :deposit
 :stream-id       "account-42"
 :idempotency-key "cmd-abc-123"
 :data            {:amount 100}}
```

The envelope is validated by `es.decider`, the `:data` is validated by
the aggregate's command schema:

```clojure
;; bank/account.clj — command data schemas

(def ^:private command-data-specs
  {:open-account [:map [:owner schema/non-empty-string]]
   :deposit      [:map [:amount pos-int?]]
   :withdraw     [:map [:amount pos-int?]]})
```

## Pattern 2: Event -> Read Model (Projection)

Events are projected into a queryable read model. The projection
is a fold over the event stream, similar to evolve but targeting a
separate database table.

```
  +------------------+     +--------------+     +------------------+
  |      Event       |---->|  Projection  |---->|   Read Model     |
  |     (fact)       |     |  (fold)      |     |   (query)        |
  +------------------+     +--------------+     +------------------+
```

### Account balance projection

Transforms account events into a queryable `account_balances` table:

```clojure
;; bank/account_projection.clj

(def handler-specs
  {"account-opened"
   (fn [tx {:keys [global-sequence stream-id] :as event} _context]
     (let [{:keys [owner]} (:payload (account/validate-event! event))]
       (jdbc/execute-one! tx
         ["INSERT INTO account_balances
             (account_id, owner, balance, last_global_sequence, updated_at)
           VALUES (?, ?, 0, ?, NOW())
           ON CONFLICT (account_id) DO UPDATE
             SET last_global_sequence = GREATEST(
                   account_balances.last_global_sequence,
                   EXCLUDED.last_global_sequence)"
          stream-id (or owner "") global-sequence])))

   "money-deposited"
   (fn [tx event {:keys [ensure-single-row-updated!]}]
     (let [{amount :amount} (:payload (account/validate-event! event))]
       (apply-balance-delta! tx event amount ensure-single-row-updated!)))

   "money-withdrawn"
   (fn [tx event {:keys [ensure-single-row-updated!]}]
     (let [{amount :amount} (:payload (account/validate-event! event))]
       (apply-balance-delta! tx event (- amount) ensure-single-row-updated!)))})
```

Key patterns in this code:

- **Idempotency guard**: `ON CONFLICT DO UPDATE SET last_global_sequence = GREATEST(...)` -- replaying the same event is a no-op
- **Monotonic guard**: `WHERE last_global_sequence < ?` in updates -- out-of-order events are ignored
- **Validation at the boundary**: `account/validate-event!` upcasts and validates before use

The query is a simple factory:

```clojure
(def get-balance
  (kit/make-query "account_balances" "account_id"))

;; Usage: (get-balance ds "account-42")
;; => {:account-id "account-42", :owner "Alice", :balance 1500, ...}
```

### Transfer status projection

Same pattern, different event types:

```clojure
;; bank/transfer_projection.clj

(def handler-specs
  {"transfer-initiated"
   (fn [tx event _context]
     (let [{:keys [global-sequence stream-id payload]} (transfer/validate-event! event)
           {:keys [from-account to-account amount]} payload
           transfer-id (transfer/logical-transfer-id stream-id)]
       (jdbc/execute-one! tx
         ["INSERT INTO transfer_status
             (transfer_id, from_account, to_account, amount,
              status, last_global_sequence, updated_at)
           VALUES (?, ?, ?, ?, 'initiated', ?, NOW())
           ON CONFLICT (transfer_id) DO UPDATE
             SET last_global_sequence = GREATEST(
                   transfer_status.last_global_sequence,
                   EXCLUDED.last_global_sequence)"
          transfer-id from-account to-account amount global-sequence])))

   "debit-recorded"
   (fn [tx event context]
     (update-transfer-status! tx event context "debited"))

   ;; ... credit-recorded, compensation-recorded, transfer-completed ...

   "transfer-failed"
   (fn [tx event context]
     (let [validated (transfer/validate-event! event)]
       (update-transfer-status! tx validated context "failed"
                                :failure-reason (get-in validated [:payload :reason]))))})
```

### Notification projection

Projects notification aggregate events into the notification DB:

```clojure
;; notification/notification_projection.clj

(def handler-specs
  {"notification-requested"
   (fn [tx {:keys [global-sequence stream-id] :as event} _context]
     (let [{:keys [notification-type recipient-id payload]}
           (:payload (notification/validate-event! event))]
       (jdbc/execute-one! tx
         ["INSERT INTO notifications
             (stream_id, notification_type, recipient_id,
              payload, status, last_global_sequence)
           VALUES (?, ?, ?, ?::jsonb, 'pending', ?)
           ON CONFLICT (stream_id) DO UPDATE
             SET last_global_sequence = GREATEST(
                   notifications.last_global_sequence,
                   EXCLUDED.last_global_sequence)"
          stream-id notification-type recipient-id
          (json/write-str payload) global-sequence])))

   "notification-failed"
   (fn [tx {:keys [global-sequence stream-id] :as event}
        {:keys [ensure-single-row-updated!]}]
     (let [{:keys [reason]} (:payload (notification/validate-event! event))
           result (jdbc/execute-one! tx
                    ["UPDATE notifications
                        SET retry_count = retry_count + 1,
                            failure_reason = ?,
                            last_global_sequence = ?,
                            updated_at = NOW()
                        WHERE stream_id = ?
                          AND last_global_sequence < ?"
                     reason global-sequence stream-id global-sequence])]
       (ensure-single-row-updated! result
                                   (select-keys event
                                                [:global-sequence :stream-id
                                                 :event-type]))))

   ;; ... notification-sent, notification-abandoned ...
   })
```

### BM25 full-text search

ParadeDB search indexes are managed alongside projections but outside
SQL migrations (they have different lifecycle concerns):

```clojure
;; bank/account_projection.clj

(def search-index-config
  {:table       "account_balances"
   :index-name  "idx_account_balances_search"
   :key-field   "account_id"
   :text-fields ["owner"]})

(def search-accounts
  (search/make-searcher {:table "account_balances"}))

;; Usage:
;; (search-accounts ds "Alice")
;; (search-accounts ds "owner:Alice" :limit 5)
;; (search-accounts ds "owner:\"Alice Smith\"" :offset 10)
```

### Sync vs async projections

```
  Sync projection (single DB):
    Event Store ---- same tx ---> Read Model

  Async projection (separate DBs):
    Event Store -> Outbox -> RabbitMQ -> Projector -> Read Model
                                              |
                                    catch-up timer (30s)
```

Async projectors use checkpoint-based catch-up. They don't need an
inbox because the checkpoint IS the deduplication mechanism. If a
projector processes event 42 twice, the `last_global_sequence < 42`
guard in the UPDATE makes the second application a no-op.

The framework handles all the machinery:

```clojure
;; system.clj — wiring an async projector

:account-projector
(component/using
  (apply async-projector-component
         "projector.account-balances"         ;; RabbitMQ queue name
         {:projection-name   "account-balances"
          :read-model-tables ["account_balances"]
          :handler           (kit/make-handler account-projection/handler-specs
                                               :skip-unknown? true)}
         :exchange-name "events"
         catch-up-opts)
  {:event-store-ds :event-store-ds
   :read-db-ds     :read-db-ds
   :rabbitmq       :rabbitmq
   :event-migrator :event-migrator
   :read-migrator  :read-migrator})
```

## Pattern 3: Automation / Reactor (Event -> Command)

An automation observes events and issues commands. No human involved.
In Event Modeling diagrams, this is the blue box that sits between
two swimlanes -- connecting one aggregate's events to another
aggregate's commands.

Our system has two kinds of automation:

1. **Reactor** -- subscribes to integration events, issues commands to a different aggregate
2. **Saga** -- orchestrates a multi-step workflow across multiple aggregates

### Reactor: notification.reactor

```
  Bank Module                           Notification Module
  ────────────                          ──────────────────

  account-opened  ──> domain->integration ──> bank.account-opened
                                                     |
                                              (outbox -> RabbitMQ)
                                                     |
                                                     v
                                              +-----------+
                                              |  Reactor  |──> :request-notification
                                              +-----------+    (to notification aggregate)
```

The reactor translates integration events into commands:

```clojure
;; notification/reactor.clj

(defn make-handler-specs [event-store-ds]
  {"bank.account-opened"
   (fn [_tx event]
     (let [event (bank-events/validate! event)]   ;; validate against shared contract
       (decider/handle-with-retry!
        event-store-ds notification/decider
        {:command-type    :request-notification
         :stream-id       (notification-stream-id "welcome-email"
                                                  (:global-sequence event))
         :idempotency-key (str "notif-welcome-" (:global-sequence event))
         :data            {:notification-type "welcome-email"
                           :recipient-id      (:account-id event)
                           :payload           {:owner   (or (:owner event) "unknown")
                                               :message "Welcome to the bank!"}}})))

   "bank.money-deposited"
   (fn [_tx event]
     (let [event (bank-events/validate! event)]
       (when (>= (:amount event) 10000)          ;; conditional automation
         (decider/handle-with-retry!
          event-store-ds notification/decider
          {:command-type    :request-notification
           :stream-id       (notification-stream-id "large-deposit-alert"
                                                    (:global-sequence event))
           :idempotency-key (str "notif-deposit-" (:global-sequence event))
           :data            {:notification-type "large-deposit-alert"
                             :recipient-id      (:account-id event)
                             :payload           {:amount  (:amount event)
                                                 :message "Large deposit received"}}}))))})
```

Key design decisions visible in this code:

- **Deterministic stream-id**: `(str "notification-" type "-" global-sequence)` -- ensures one notification per source event
- **Idempotency key**: `(str "notif-welcome-" global-sequence)` -- safe to retry
- **`handle-with-retry!`**: retries on optimistic concurrency conflicts
- **Conditional automation**: large deposit alerts only fire for amounts >= 10000
- **Validates incoming**: `bank-events/validate!` checks the shared contract

| Integration Event | Condition | Command Issued |
|-------------------|-----------|---------------|
| `bank.account-opened` | Always | `:request-notification` (type: "welcome-email") |
| `bank.money-deposited` | amount >= 10000 | `:request-notification` (type: "large-deposit-alert") |
| `bank.money-deposited` | amount < 10000 | No action |

### Consumer framework wiring

The reactor runs inside a consumer group -- multiple workers competing
on a RabbitMQ queue with inbox-based deduplication:

```clojure
;; system.clj — wiring the notification consumer group

:notification-consumers
(component/using
  (notification/consumer-group-component
   {:consumer-group         "account-notifications"
    :queue-name             "notifications.account-events"
    :exchange-name          "events"
    :worker-count           3
    :make-handler-specs-fn  reactor/make-handler-specs   ;; receives event-store-ds at start
    :catch-up-interval-ms   30000})
  {:event-store-ds :event-store-ds
   :consumer-db-ds :notification-db        ;; inbox lives here
   :rabbitmq       :rabbitmq
   :event-migrator :event-migrator
   :notification-migrator :notification-migrator})
```

The consumer framework (`es.consumer`) provides:

```
  RabbitMQ delivery -> bounded queue -> worker thread -> inbox claim -> handler
                                                              |
                                              INSERT ON CONFLICT DO NOTHING
                                              (first worker wins, dupes skip)
```

- Multiple workers compete on the same queue (horizontal scaling)
- Inbox table (`consumer_inbox`) provides exactly-once per consumer group
- Catch-up timer (every 30s) reads directly from `event_outbox` via JDBC
- Unknown event types halt checkpoint advancement (prevents data loss)

### Saga: bank.transfer-saga

The transfer saga orchestrates across the account and transfer aggregates.
Unlike a reactor (one event -> one command), a saga drives a multi-step
state machine:

```
  :not-found -> :initiated -> :debited -> :credited -> :completed
                     |             |
                  :failed    :compensating -> :failed (with refund)
```

Each step issues a command and records progress:

```clojure
;; bank/transfer_saga.clj

(defn- execute-initiated
  "State is :initiated — debit the source account."
  [ds transfer-id stream {:keys [from-account amount]} on-events-appended]
  (let [result (saga/try-command!
                ds account/decider
                {:command-type    :withdraw
                 :stream-id       from-account
                 :idempotency-key (step-key transfer-id :debit)
                 :data            {:amount amount}}
                :on-events-appended on-events-appended)]
    (if (:error result)
      ;; Debit failed (e.g. insufficient funds) — fail the transfer
      (do (fail-transfer! ds stream transfer-id (:reason result)
                          :on-events-appended on-events-appended)
          {:status :failed :reason (:reason result)})
      ;; Debit succeeded — record progress on the transfer stream
      (do (record-transfer-command! ds stream transfer-id
                                    :record-debit :record-debit
                                    {:account-id from-account :amount amount}
                                    :on-events-appended on-events-appended)
          {:next-status :debited}))))
```

```clojure
;; bank/transfer_saga.clj — compensation when credit fails

(defn- execute-debited
  "State is :debited — credit the destination account."
  [ds transfer-id stream {:keys [to-account amount]} on-events-appended]
  (let [result (saga/try-command!
                ds account/decider
                {:command-type    :deposit
                 :stream-id       to-account
                 :idempotency-key (step-key transfer-id :credit)
                 :data            {:amount amount}}
                :on-events-appended on-events-appended)]
    (if (:error result)
      ;; Credit failed — record compensation intent BEFORE refunding.
      ;; This transitions to :compensating, preventing resume from
      ;; re-attempting the credit after a crash.
      (do (record-transfer-command! ds stream transfer-id
                                    :record-compensation :record-compensation
                                    {:reason (:reason result)}
                                    :on-events-appended on-events-appended)
          {:next-status :compensating})
      ;; Credit succeeded
      (do (record-transfer-command! ds stream transfer-id
                                    :record-credit :record-credit
                                    {:account-id to-account :amount amount}
                                    :on-events-appended on-events-appended)
          {:next-status :credited}))))
```

**Saga steps:**

| Current State | Action | On Success | On Failure |
|---------------|--------|------------|------------|
| `:initiated` | Withdraw from source account | -> `:debited` | -> `:failed` |
| `:debited` | Deposit to destination account | -> `:credited` | -> `:compensating` |
| `:compensating` | Refund source account | -> `:failed` | (must succeed) |
| `:credited` | Record transfer complete | -> `:completed` | -- |

Each step uses an idempotency key derived from `transfer-id + step-name`,
so the entire saga is safe to retry or resume after a crash.

**Crash recovery** uses Pull -> Transform: load the transfer stream,
evolve to current state, and enter the state machine loop:

```clojure
;; bank/transfer_saga.clj

(defn resume! [ds transfer-id & {:keys [on-events-appended]}]
  (let [stream (transfer-stream-id transfer-id)
        events (store/load-stream ds stream)
        state  (decider/evolve-state transfer/decider events)]
    (case (:status state)
      :not-found  (throw (ex-info "Transfer not found" {...}))
      :completed  {:status :already-completed}
      :failed     {:status :already-failed :reason (:failure-reason state)}
      ;; Non-terminal: re-enter the state machine loop
      (run-from ds transfer-id stream state on-events-appended))))
```

## Pattern 4: Integration Events (Between Modules)

In a modular monolith, modules communicate through integration events.
These are NOT raw domain events -- they are curated, significant,
summary-level events that form the module's public API.

### Domain -> integration mapping

```clojure
;; bank/integration_events.clj

(defn domain->integration
  [{:keys [global-sequence stream-id event-type payload]}]
  (when-let [integration-event
             (case event-type
               "account-opened"
               {:event-type      "bank.account-opened"
                :global-sequence global-sequence
                :account-id      stream-id
                :owner           (:owner payload)}

               "money-deposited"
               {:event-type      "bank.money-deposited"
                :global-sequence global-sequence
                :account-id      stream-id
                :amount          (:amount payload)}

               "transfer-initiated"
               {:event-type      "bank.transfer-initiated"
                :global-sequence global-sequence
                :transfer-id     (transfer/logical-transfer-id stream-id)
                :from-account    (:from-account payload)
                :to-account      (:to-account payload)
                :amount          (:amount payload)}

               ;; Internal saga steps are NOT published
               nil)]
    (contract/validate! integration-event)))
```

Key design decisions:

- **Namespaced**: `bank.account-opened` not `account-opened` -- identifies the source module
- **Self-contained**: carries all the data a consumer needs (flat, no nested domain structures)
- **Validated**: `contract/validate!` ensures the event matches the shared schema before publishing
- **Filtered**: returns `nil` for internal saga steps (`debit-recorded`, `credit-recorded`, `compensation-recorded`) -- consumers never see these

**Published** (6 event types):

| Domain Event | Integration Event | Key Fields |
|-------------|-------------------|------------|
| `account-opened` | `bank.account-opened` | account-id, owner |
| `money-deposited` | `bank.money-deposited` | account-id, amount |
| `money-withdrawn` | `bank.money-withdrawn` | account-id, amount |
| `transfer-initiated` | `bank.transfer-initiated` | transfer-id, from-account, to-account, amount |
| `transfer-completed` | `bank.transfer-completed` | transfer-id |
| `transfer-failed` | `bank.transfer-failed` | transfer-id, reason |

**Filtered out** (3 event types): `debit-recorded`, `credit-recorded`,
`compensation-recorded` -- internal saga implementation details.

### Shared contract schema

Both producer and consumer validate against the same Malli schema:

```clojure
;; events/bank.clj — single source of truth

(def account-opened
  [:map
   [:event-type      [:= "bank.account-opened"]]
   [:global-sequence pos-int?]
   [:account-id      string?]
   [:owner           [:maybe string?]]])

(def money-deposited
  [:map
   [:event-type      [:= "bank.money-deposited"]]
   [:global-sequence pos-int?]
   [:account-id      string?]
   [:amount          pos-int?]])
```

```clojure
;; events/bank.clj — dispatch-based validation

(def schemas
  {"bank.account-opened"     account-opened
   "bank.money-deposited"    money-deposited
   "bank.money-withdrawn"    money-withdrawn
   "bank.transfer-initiated" transfer-initiated
   "bank.transfer-completed" transfer-completed
   "bank.transfer-failed"    transfer-failed})

(defn validate! [event]
  (let [schema (get schemas (:event-type event))]
    (when-not schema
      (throw (ex-info "Unknown integration event type"
                      {:event-type (:event-type event)})))
    (when-not (m/validate schema event)
      (throw (ex-info "Integration event does not match contract"
                      {:event-type (:event-type event)
                       :explain    (m/explain schema event)})))
    event))
```

- Producer validates OUTGOING in `bank.integration-events/domain->integration`
- Consumer validates INCOMING in `notification.reactor` handler
- If a schema changes, both sides fail at compile/test time -- never silently at runtime

### Transactional outbox flow

Integration events flow through the transactional outbox:

```
  1. Command handler appends events to event store
  2. In the SAME transaction, outbox hook calls domain->integration
  3. Integration event (or nil) is stored as JSONB in event_outbox table
  4. Outbox poller reads unpublished rows (FOR UPDATE SKIP LOCKED)
  5. Publishes JSON to RabbitMQ "events" exchange (fanout)
  6. Marks row with published_at timestamp
```

The outbox hook is created from the mapper:

```clojure
;; system.clj

(defn make-outbox-hook []
  (outbox/make-outbox-hook integration-events/domain->integration))
```

```clojure
;; es/outbox.clj — the hook runs inside the event-store transaction

(defn make-outbox-hook [event->message]
  (fn [tx global-sequences]
    (doseq [gs global-sequences]
      (let [event   (jdbc/execute-one! tx
                      ["SELECT global_sequence, stream_id,
                              event_type, event_version, payload
                       FROM events WHERE global_sequence = ?" gs]
                      {:builder-fn rs/as-unqualified-kebab-maps})
            event   (update event :payload store/<-pgobject)
            message (event->message event)]     ;; domain->integration
        (when message                            ;; nil = don't publish
          (record! tx gs message))))))
```

An outbox message looks like:

```clojure
;; Stored in event_outbox.message (JSONB)
{:event-type      "bank.account-opened"
 :global-sequence 42
 :account-id      "account-123"
 :owner           "Alice"}
```

## Full Architecture: Event Modeling View

```
  Bank Module (Producer)
  ================================================================

  +----------+    +-----------+    +--------------+
  | :deposit |----| Account   |--->| money-       |--+
  | command  |    | Decider   |    | deposited    |  |
  +----------+    +-----------+    +--------------+  |
                                                     |
  +----------+    +-----------+    +--------------+  |
  | :initiate|----| Transfer  |--->| transfer-    |--+
  | -transfer|    | Decider   |    | initiated    |  |
  +----------+    +-----------+    +--------------+  |
       ^                                             |
       |          +-----------+                      |
       +----------| Transfer  |   Saga               |
       (saga      | Saga      |   (automation)       |
        steps)    +-----------+                      |
                                       domain->integration
                                                     |
                                  +------------------+
                                  v
                        +-------------------+
                        | bank.money-       |
                        | deposited         |    Integration
                        | (integration      |    Event
                        |  event in outbox) |
                        +--------+----------+
                                 |
                   +-------------+-------------+
                   v             v              v
             +----------+ +----------+   +-----------+
             | Account  | | Transfer |   |  Outbox   |
             | Balance  | | Status   |   |  Poller   |
             | Projector| | Projector|   |           |
             +----+-----+ +----+-----+   +-----+-----+
                  v            v                v
             +-----------------+         +----------+
             |   Read Store    |         | RabbitMQ |
             |  (Postgres)     |         | (fanout) |
             +-----------------+         +-----+----+
                                               |
  =============================================================
  Notification Module (Consumer)               |
  =============================================================
                                               |
                                    +----------+
                                    v
                        +--------------------+
                        | Consumer Group     |
                        | (inbox + 3 workers)|
                        +--------+-----------+
                                 |
                                 v
                        +--------------------+
                        |     Reactor        |    Automation
                        | (event -> command) |    (Event Modeling)
                        +--------+-----------+
                                 |
                                 v
                        +--------------------+
                        |  Notification      |
                        |  Aggregate         |    Command + Aggregate
                        |  (Decider)         |    (Event Modeling)
                        +--------+-----------+
                                 |
                        notification-requested
                                 |
                   +-------------+-------------+
                   v                            v
          +---------------+           +-----------------+
          | Notification  |           | Delivery Worker  |
          | Projector     |           | (future: send    |
          |               |           |  email, issue    |
          |               |           |  :mark-sent or   |
          |               |           |  :mark-failed)   |
          +-------+-------+           +-----------------+
                  v
          +---------------+
          | Notification  |    Read Model
          | Read Model    |    (Event Modeling)
          | (notifications|
          |  table)       |
          +---------------+
```

## Mapping Summary

| Event Modeling | Clojure construct | File pattern | Example |
|----------------|-------------------|--------------|---------|
| Command | `{:command-type :k :data {...}}` | -- | `:request-notification` |
| Aggregate | Decider `{:initial-state :decide :evolve}` | `<mod>/<agg>.clj` | `notification/notification.clj` |
| Event (domain) | `{:event-type "..." :payload {...}}` | stored in `events` table | `notification-requested` |
| Event (integration) | `{:event-type "bank...." ...}` | stored in `event_outbox` | `bank.account-opened` |
| Read Model | Projection handler-specs + query fn | `<mod>/<name>_projection.clj` | `notification/notification_projection.clj` |
| Automation (reactor) | Reactor `make-handler-specs` | `<mod>/reactor.clj` | `notification/reactor.clj` |
| Automation (saga) | Saga step handlers + state machine | `<mod>/<name>_saga.clj` | `bank/transfer_saga.clj` |
| Integration Event | Shared Malli schema + mapper | `events/<mod>.clj` | `events/bank.clj` |
| Swimlane | Module boundary | `<mod>/*` namespace | `notification.*`, `bank.*` |

## Creating a New Module

To add a new module (e.g., `fraud`):

1. **Define the aggregate** -- `fraud/detection.clj` with Decider map
2. **Define the reactor** -- `fraud/reactor.clj` with `make-handler-specs`
3. **Define the projection** -- `fraud/detection_projection.clj`
4. **Add shared schema** -- `events/fraud.clj` (if publishing integration events)
5. **Add migrations** -- `resources/fraud-migrations/001-schema.up.sql`
6. **Wire in system.clj** -- datasource, migrator, consumer group, projector

No changes needed to existing modules. The only coupling point is
the shared integration event schema in `events/bank.clj`.

## Functional Core / Imperative Shell

```
  Functional Core (pure, testable, no I/O):
    - Aggregates (Deciders): decide + evolve
    - Reactors: event -> command translation logic
    - Integration event schemas and validation
    - Saga step logic (which command to issue next)

  Imperative Shell (I/O, infrastructure):
    - es.decider: Pull -> Transform -> Push
    - es.store: append events, advisory locks, idempotency
    - es.async-projection: checkpoint-based catch-up
    - es.consumer: inbox claims, RabbitMQ bridge
    - es.outbox: transactional outbox, polling, publish
    - Delivery workers: external API calls (email, SMS)
```

The aggregate is always pure. Side effects (sending emails, calling
APIs) happen in separate workers that read events and issue commands
back. This makes the domain logic fast, predictable, and testable
without mocking external services.

The notification aggregate illustrates this clearly -- it tracks
lifecycle state (pending, sent, failed) but never sends an email.
The actual delivery would be a separate imperative-shell worker:

1. Listen for `notification-requested` events
2. Call SendGrid/SES/SMTP
3. Issue `:mark-sent` or `:mark-failed` commands back to the aggregate
