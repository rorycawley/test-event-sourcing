(ns user
  "REPL walkthrough — evaluate each form one at a time.

   This walkthrough uses the Decider pattern:
     :initial-state — state before any events
     :decide        — Command → State → Event list
     :evolve        — State → Event → State

   The generic handler in event-sourcing.decider implements
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
   Step 9: Tear down")

;; ═══════════════════════════════════════════════════
;; Step 0 — Require namespaces
;; ═══════════════════════════════════════════════════
;;
;; account  — pure domain (the Decider map, no I/O)
;; decider  — generic Pull → Transform → Push handler
;; store    — event store (Pull and Push)
;; projection — read model

(require '[event-sourcing.infra       :as infra]
         '[event-sourcing.store       :as store]
         '[event-sourcing.decider     :as decider]
         '[event-sourcing.account     :as account]
         '[event-sourcing.projection  :as projection])

;; ═══════════════════════════════════════════════════
;; Step 1 — Start a throwaway Postgres
;; ═══════════════════════════════════════════════════
;;
;; This pulls postgres:16-alpine via Testcontainers.
;; Takes ~5-10 s on first run (Docker image pull).

(comment

  (def pg (infra/start-postgres!))
  ;; => {:container #object[...], :jdbc-url "jdbc:postgresql://...", ...}

  (def ds (infra/->datasource pg))

  ;; ═══════════════════════════════════════════════════
  ;; Step 2 — Create the schema
  ;; ═══════════════════════════════════════════════════

  (store/create-schema! ds)

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

  (projection/process-new-events! ds)
  ;; => 4  (four events processed)

  (projection/get-balance ds "account-42")
  ;; => {:account-id "account-42", :balance 120, :last-global-sequence 4, ...}

  ;; Call again — should return 0 (checkpoint remembers where we left off):
  (projection/process-new-events! ds)
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
  (projection/process-new-events! ds)
  (projection/get-balance ds "account-42")

  ;; Full rebuild of read model (proves it's disposable):
  (projection/rebuild! ds)
  (projection/get-balance ds "account-42")

  ;; ═══════════════════════════════════════════════════
  ;; Step 9 — Tear down
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
  )
