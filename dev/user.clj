(ns user
  "REPL walkthrough — evaluate each form one at a time.

   This walkthrough uses the Decider pattern:
     :initial-state — state before any events
     :decide        — Command → State → Event list
     :evolve        — State → Event → State

   The generic handler in es.decider implements
   Tellman's Pull → Transform → Push:
     Pull:      load events from the store
     Transform: evolve state, decide new events (pure)
     Push:      append new events to the store

   Workflow
   ────────
   Step 1: Start Postgres testcontainer
   Step 2: Create schema
   Step 3: Send commands (with idempotency keys)
   Step 4: Inspect the event stream
   Step 5: Build the read model
   Step 6: Demonstrate optimistic concurrency conflict
   Step 7: Demonstrate idempotency
   Step 8: Demonstrate retry on conflict
   Step 9: Fund transfer saga (cross-account)
   Step 10: Tear down")

;; ═══════════════════════════════════════════════════
;; Step 0 — Require namespaces
;; ═══════════════════════════════════════════════════
;;
;; account   — pure account domain (the Decider map, no I/O)
;; transfer  — pure transfer domain (the Decider map, no I/O)
;; saga      — saga coordinator for cross-account transfers
;; decider   — generic Pull → Transform → Push handler
;; store     — event store (Pull and Push)
;; system    — composition root (wires projections)

(require '[es.infra                   :as infra]
         '[es.migrations              :as migrations]
         '[es.store                   :as store]
         '[es.decider                 :as decider]
         '[bank.account               :as account]
         '[bank.system                :as system]
         '[bank.account-projection    :as account-projection]
         '[bank.transfer-projection   :as transfer-projection]
         '[bank.transfer              :as transfer]
         '[bank.transfer-saga         :as saga])

;; ═══════════════════════════════════════════════════
;; Step 1 — Start a throwaway Postgres
;; ═══════════════════════════════════════════════════
;;
;; This pulls paradedb/paradedb:latest via Testcontainers.
;; Takes ~5-10 s on first run (Docker image pull).

(comment

  (def pg (infra/start-postgres!))
  ;; => {:container #object[...], :jdbc-url "jdbc:postgresql://...", ...}

  (def ds (infra/->datasource pg))

  ;; ═══════════════════════════════════════════════════
  ;; Step 2 — Create the schema
  ;; ═══════════════════════════════════════════════════

  (migrations/migrate! ds)

  ;; ═══════════════════════════════════════════════════
  ;; Step 3 — Send commands
  ;; ═══════════════════════════════════════════════════
  ;;
  ;; Notice: we pass account/decider to decider/handle!
  ;; The handler is generic — it doesn't know about
  ;; accounts. It only knows Pull → Transform → Push.
  ;;
  ;; Every command carries an idempotency-key.
  ;; If you evaluate the same form twice, the second
  ;; call returns :idempotent instead of :ok.

  ;; 3a. Open an account
  (decider/handle! ds account/decider
                   {:command-type    :open-account
                    :stream-id       "account-42"
                    :idempotency-key "cmd-open-42"
                    :data            {:owner "Alice"}})
  ;; => :ok

  ;; 3b. Deposit money
  (decider/handle! ds account/decider
                   {:command-type    :deposit
                    :stream-id       "account-42"
                    :idempotency-key "cmd-dep-100"
                    :data            {:amount 100}})
  ;; => :ok

  ;; 3c. Deposit more
  (decider/handle! ds account/decider
                   {:command-type    :deposit
                    :stream-id       "account-42"
                    :idempotency-key "cmd-dep-50"
                    :data            {:amount 50}})
  ;; => :ok

  ;; 3d. Withdraw
  (decider/handle! ds account/decider
                   {:command-type    :withdraw
                    :stream-id       "account-42"
                    :idempotency-key "cmd-wd-30"
                    :data            {:amount 30}})
  ;; => :ok

  ;; ═══════════════════════════════════════════════════
  ;; Step 4 — Inspect the event stream
  ;; ═══════════════════════════════════════════════════
  ;;
  ;; This is THE event stream for account-42:
  ;; an ordered, immutable log of everything that happened.
  ;;
  ;; Each event has three identity/ordering fields:
  ;;   :id               — UUIDv7 (application-generated, globally unique)
  ;;   :global-sequence   — DB-assigned position across ALL streams
  ;;   :stream-sequence   — per-stream version (used for optimistic concurrency)
  ;;   :event-version    — schema version for payload validation/upcasting

  (store/load-stream ds "account-42")
  ;; => [{:id #uuid "...", :global-sequence 1, :stream-sequence 1,
  ;;      :event-type "account-opened", :event-version 1,
  ;;      :payload {:owner "Alice"}, ...}
  ;;     ...]

  ;; Reconstitute aggregate state from the stream (pure Transform):
  (decider/evolve-state account/decider
                        (store/load-stream ds "account-42"))
  ;; => {:status :open, :owner "Alice", :balance 120}

  ;; Same thing without the database — pure data in, pure data out:
  (decider/evolve-state account/decider
                        [{:event-type "account-opened"  :event-version 1 :payload {:owner "Alice"}}
                         {:event-type "money-deposited" :event-version 3 :payload {:amount 100 :origin "command" :currency "USD"}}
                         {:event-type "money-deposited" :event-version 3 :payload {:amount 50 :origin "command" :currency "USD"}}
                         {:event-type "money-withdrawn" :event-version 1 :payload {:amount 30}}])
  ;; => {:status :open, :owner "Alice", :balance 120}

  ;; ═══════════════════════════════════════════════════
  ;; Step 5 — Build the read model (projection)
  ;; ═══════════════════════════════════════════════════
  ;;
  ;; The read model is a DERIVED view, not the source of truth.
  ;; It can be rebuilt from scratch at any time.
  ;; It uses global-sequence (not stream-sequence) as its cursor
  ;; because it processes events from ALL streams in one pass.

  (system/process-new-events! ds)
  ;; => 4  (four events processed)

  (account-projection/get-balance ds "account-42")
  ;; => {:account-id "account-42", :balance 120, :last-global-sequence 4, ...}

  ;; Call again — should return 0 (checkpoint remembers where we left off):
  (system/process-new-events! ds)
  ;; => 0

  ;; ═══════════════════════════════════════════════════
  ;; Step 6 — Optimistic concurrency conflict
  ;; ═══════════════════════════════════════════════════
  ;;
  ;; Simulate: we read at stream-sequence 4, but someone else writes first.

  ;; First, a normal deposit bumps stream-sequence to 5:
  (decider/handle! ds account/decider
                   {:command-type    :deposit
                    :stream-id       "account-42"
                    :idempotency-key "cmd-dep-sneaky"
                    :data            {:amount 10}})
  ;; => :ok

  ;; Now try to append with stale expected stream-sequence 4:
  (try
    (store/append-events! ds "account-42" 4 "cmd-stale"
                          {:command-type :deposit
                           :data         {:amount 999}}
                          [{:event-type    "money-deposited"
                            :event-version 3
                            :payload       {:amount 999
                                            :origin "command"
                                            :currency "USD"}}])
    (catch clojure.lang.ExceptionInfo e
      (ex-data e)))
  ;; => {:stream-id "account-42", :expected-version 4, :actual-version 5}

  ;; ═══════════════════════════════════════════════════
  ;; Step 7 — Idempotency
  ;; ═══════════════════════════════════════════════════
  ;;
  ;; Re-send the same command (same idempotency-key).
  ;; The store detects it and short-circuits.

  (decider/handle! ds account/decider
                   {:command-type    :deposit
                    :stream-id       "account-42"
                    :idempotency-key "cmd-dep-sneaky"   ;; already used above
                    :data            {:amount 10}})
  ;; => :idempotent  (no duplicate event created)

  ;; ═══════════════════════════════════════════════════
  ;; Step 8 — Retry on conflict
  ;; ═══════════════════════════════════════════════════
  ;;
  ;; handle-with-retry! automatically retries on
  ;; concurrency conflicts. Each retry re-pulls fresh
  ;; events and re-transforms (evolve + decide).

  (decider/handle-with-retry! ds account/decider
                              {:command-type    :withdraw
                               :stream-id       "account-42"
                               :idempotency-key "cmd-wd-final"
                               :data            {:amount 20}})
  ;; => :ok

  ;; Update read model and check final balance:
  (system/process-new-events! ds)
  (account-projection/get-balance ds "account-42")

  ;; Full rebuild of read model (proves it's disposable):
  (system/rebuild! ds)
  (account-projection/get-balance ds "account-42")

  ;; ═══════════════════════════════════════════════════
  ;; Step 9 — Fund transfer saga (cross-account)
  ;; ═══════════════════════════════════════════════════
  ;;
  ;; The transfer saga coordinates a cross-account transfer
  ;; using three streams:
  ;;   1. Source account stream (debit)
  ;;   2. Destination account stream (credit)
  ;;   3. Transfer stream (saga progress tracking)
  ;;
  ;; First, open a second account:

  (decider/handle! ds account/decider
                   {:command-type    :open-account
                    :stream-id       "account-99"
                    :idempotency-key "cmd-open-99"
                    :data            {:owner "Bob"}})

  (decider/handle! ds account/decider
                   {:command-type    :deposit
                    :stream-id       "account-99"
                    :idempotency-key "cmd-dep-99-200"
                    :data            {:amount 200}})

  ;; 9a. Execute a transfer from Alice to Bob:
  (saga/execute! ds "tx-001" "account-42" "account-99" 40)
  ;; => {:status :completed}

  ;; Check balances after transfer:
  (system/process-new-events! ds)
  (account-projection/get-balance ds "account-42")
  ;; => {:balance 70, ...}  (was 110, minus 40)
  (account-projection/get-balance ds "account-99")
  ;; => {:balance 240, ...}  (was 200, plus 40)

  ;; Inspect the transfer stream — tracks saga progress:
  (mapv :event-type (store/load-stream ds "transfer-tx-001"))
  ;; => ["transfer-initiated" "debit-recorded"
  ;;     "credit-recorded" "transfer-completed"]

  ;; View projected transfer status:
  (transfer-projection/get-transfer ds "transfer-tx-001")
  ;; => {:transfer-id "transfer-tx-001", :status "completed", ...}

  ;; 9b. Transfer with insufficient funds fails gracefully:
  (saga/execute! ds "tx-002" "account-42" "account-99" 9999)
  ;; => {:status :failed, :reason "insufficient-funds"}

  ;; Alice's balance unchanged:
  (system/process-new-events! ds)
  (account-projection/get-balance ds "account-42")

  ;; 9c. Resume a completed transfer (no-op):
  (saga/resume! ds "tx-001")
  ;; => {:status :already-completed}

  ;; 9d. Resume a failed transfer (no-op):
  (saga/resume! ds "tx-002")
  ;; => {:status :already-failed, :reason "insufficient-funds"}

  ;; 9e. Simulate crash recovery: write only the initiate event
  ;;     (as if the process died right after step 1), then resume.
  (store/append-events! ds "transfer-tx-crash" 0
                        "seed-crash"
                        {:command-type :seed :data {}}
                        [{:event-type    "transfer-initiated"
                          :event-version 1
                          :payload       {:from-account "account-42"
                                          :to-account   "account-99"
                                          :amount       15}}])
  (saga/resume! ds "tx-crash")
  ;; => {:status :completed}
  ;; The saga read the transfer stream, saw :initiated, and
  ;; completed the remaining steps (debit, credit, complete).

  (system/process-new-events! ds)
  (account-projection/get-balance ds "account-42")
  ;; => Alice's balance decreased by 15
  (account-projection/get-balance ds "account-99")
  ;; => Bob's balance increased by 15

  ;; Transfer domain is also pure — test without a database:
  (transfer/decide {:command-type :initiate-transfer
                    :data         {:from-account "a" :to-account "b" :amount 50}}
                   {:status :not-found})
  ;; => [{:event-type "transfer-initiated", :payload {...}}]

  (transfer/evolve {:status :not-found}
                   {:event-type "transfer-initiated" :event-version 1
                    :payload {:from-account "a" :to-account "b" :amount 50}})
  ;; => {:status :initiated, :from-account "a", :to-account "b", :amount 50}

  ;; ═══════════════════════════════════════════════════
  ;; Step 10 — Tear down
  ;; ═══════════════════════════════════════════════════

  (infra/stop-postgres! pg)

  ;; ═══════════════════════════════════════════════════
  ;; Exploring the Decider
  ;; ═══════════════════════════════════════════════════
  ;;
  ;; The decider is a plain map — inspect it:

  account/decider
  ;; => {:initial-state {:status :not-found, :balance 0}
  ;;     :decide        #function[...]
  ;;     :evolve        #function[...]}

  ;; The decide function is pure — test it without a database:
  (account/decide {:command-type :deposit :data {:amount 50}}
                  {:status :open :balance 100})
  ;; => [{:event-type "money-deposited", :payload {:amount 50}}]

  ;; The evolve function is pure — test it without a database:
  (account/evolve {:status :open :balance 100}
                  {:event-type "money-deposited" :payload {:amount 50}})
  ;; => {:status :open, :balance 150}

  ;; Business rule enforcement — no database needed:
  (try
    (account/decide {:command-type :withdraw :data {:amount 999}}
                    {:status :open :balance 100})
    (catch clojure.lang.ExceptionInfo e
      (ex-data e)))
  ;; => {:balance 100, :amount 999}

  ;; ═══════════════════════════════════════════════════
  ;; Exploring the Transfer Decider
  ;; ═══════════════════════════════════════════════════
  ;;
  ;; The transfer decider is also a plain map:

  transfer/decider
  ;; => {:initial-state {:status :not-found}
  ;;     :decide        #function[...]
  ;;     :evolve        #function[...]}

  ;; Evolve through a full transfer lifecycle — pure data:
  (decider/evolve-state transfer/decider
                        [{:event-type "transfer-initiated" :event-version 1
                          :payload {:from-account "a" :to-account "b" :amount 50}}
                         {:event-type "debit-recorded" :event-version 1
                          :payload {:account-id "a" :amount 50}}
                         {:event-type "credit-recorded" :event-version 1
                          :payload {:account-id "b" :amount 50}}
                         {:event-type "transfer-completed" :event-version 1
                          :payload {}}])
  ;; => {:status :completed, :from-account "a", :to-account "b", :amount 50}

  ;; Business rule: can't transfer to the same account:
  (try
    (transfer/decide {:command-type :initiate-transfer
                      :data {:from-account "a" :to-account "a" :amount 10}}
                     {:status :not-found})
    (catch clojure.lang.ExceptionInfo e
      (ex-data e)))
  ;; => {:error/type :domain/same-account-transfer, :account "a"}
  )

;; ═══════════════════════════════════════════════════
;; Full Async System (Component)
;; ═══════════════════════════════════════════════════
;;
;; The full system includes:
;;   - Event store (Postgres)        — write side
;;   - Read store (separate Postgres) — read side
;;   - RabbitMQ                       — event broker
;;   - Outbox poller                  — reliable publishing
;;   - Async projectors               — account + transfer
;;
;; RabbitMQ messages are "wake up" notifications.
;; Each projector reads from the event store using checkpoints.
;; Missed messages are recovered by periodic catch-up.

(comment

  (require '[com.stuartsierra.component :as component]
           '[bank.components :as bank-components]
           '[es.outbox :as outbox])

  ;; ── Start the full system ──────────────────────

  (def system (component/start (bank-components/dev-full-system)))
  ;; Starts 3 Testcontainers (2× Postgres, 1× RabbitMQ),
  ;; runs migrations, starts outbox poller + projectors.

  ;; ── Grab datasources ──────────────────────────

  (def event-ds (get-in system [:event-store-ds :datasource]))
  (def read-ds  (get-in system [:read-db-ds :datasource]))

  ;; ── Send commands (events flow async to read DB) ──

  (def outbox-hook (outbox/make-outbox-hook))

  (decider/handle-with-retry! event-ds account/decider
                              {:command-type    :open-account
                               :stream-id       "account-1"
                               :idempotency-key "open-1"
                               :data            {:owner "Alice"}}
                              :on-events-appended outbox-hook)

  (decider/handle-with-retry! event-ds account/decider
                              {:command-type    :deposit
                               :stream-id       "account-1"
                               :idempotency-key "dep-100"
                               :data            {:amount 100}}
                              :on-events-appended outbox-hook)

  ;; Wait a moment, then query the read DB:
  (account-projection/get-balance read-ds "account-1")
  ;; => {:account-id "account-1", :balance 100, ...}

  ;; ── Transfer (saga) ────────────────────────────

  (decider/handle-with-retry! event-ds account/decider
                              {:command-type    :open-account
                               :stream-id       "account-2"
                               :idempotency-key "open-2"
                               :data            {:owner "Bob"}}
                              :on-events-appended outbox-hook)

  (decider/handle-with-retry! event-ds account/decider
                              {:command-type    :deposit
                               :stream-id       "account-2"
                               :idempotency-key "dep-2-200"
                               :data            {:amount 200}}
                              :on-events-appended outbox-hook)

  (saga/execute! event-ds "tx-001" "account-1" "account-2" 30)
  ;; Saga events are picked up by catch-up timer (no outbox hook).

  ;; After a few seconds:
  (account-projection/get-balance read-ds "account-1")
  ;; => {:balance 70, ...}
  (account-projection/get-balance read-ds "account-2")
  ;; => {:balance 230, ...}
  (transfer-projection/get-transfer read-ds "transfer-tx-001")
  ;; => {:status "completed", ...}

  ;; ── Stop the system ────────────────────────────

  (component/stop system))
