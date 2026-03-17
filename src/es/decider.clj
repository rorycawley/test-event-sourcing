(ns es.decider
  "Generic Decider infrastructure — the bridge between pure domain and I/O.

   A Decider (Chassaing) is a plain map:

     {:initial-state  State                      ;; before any events
      :decide         Command → State → [Event]  ;; intent + truth → facts
      :evolve         State → Event → State}     ;; truth + fact → new truth

   The Decider is pure — no I/O, no database. This namespace provides
   the infrastructure wiring to run any Decider against the event store.
   The domain (account, transfer) supplies the Decider map. This
   namespace supplies Pull → Transform → Push (Tellman):

   ┌──────────────────────────────────────────────────────────────┐
   │  Pull:      load events from the store (I/O)                │
   │  Transform: reduce evolve initial-state events → state      │
   │             decide command state → new events (pure)         │
   │  Push:      append new events to the store (I/O)            │
   └──────────────────────────────────────────────────────────────┘

   The domain namespace knows nothing about the store, the database,
   or this namespace. It's pure data and pure functions."
  (:require [es.schema :as schema]
            [es.store :as store]
            [malli.core :as m]))

;; ═══════════════════════════════════════════════════
;; Transform
;; ═══════════════════════════════════════════════════

(defn evolve-state
  "Folds a sequence of events through a decider's evolve function,
   starting from its initial state. Pure — no I/O.

   This is the 'left fold' at the heart of event sourcing:
     (reduce evolve initial-state events)"
  [{:keys [initial-state evolve]} events]
  (reduce evolve initial-state events))

;; ═══════════════════════════════════════════════════
;; Pull → Transform → Push
;; ═══════════════════════════════════════════════════

(def ^:private command-envelope-schema
  [:map
   [:command-type keyword?]
   [:stream-id schema/non-empty-string]
   [:data map?]
   [:idempotency-key {:optional true}
    [:or nil? schema/non-empty-string]]])

(defn- validate-command-envelope!
  [command]
  (when-not (m/validate command-envelope-schema command)
    (throw (ex-info "Invalid command envelope"
                    {:error/type :command/invalid-envelope
                     :command    command
                     :explain    (m/explain command-envelope-schema command)}))))

(defn- stream-version
  "Extracts the stream-sequence of the last event, or 0 if empty.
   This keeps version tracking out of the domain — the decider's
   evolve function never needs to know about stream-sequence."
  [events]
  (if (seq events)
    (:stream-sequence (peek events))
    0))

(defn handle!
  "Processes a command through a decider.

   Pull:      reads the event stream for the given stream-id
   Transform: evolves current state, decides new events
   Push:      appends new events to the store

   Command shape:
     {:command-type    :deposit
      :stream-id       \"account-42\"
      :idempotency-key \"cmd-abc-123\"
      :data            {:amount 100}}

   Returns :ok or :idempotent.
   Throws on business-rule violation, idempotency collision,
   or concurrency conflict."
  [ds decider {:keys [stream-id idempotency-key] :as command}]
  (validate-command-envelope! command)
  ;; Fast path: if this command key has already been claimed for the
  ;; same command, return :idempotent before any domain checks.
  (if (= :idempotent
         (store/check-idempotency! ds stream-id idempotency-key command))
    :idempotent
    (let [;; — Pull —
          ;; Load the complete event stream for this aggregate.
          ;; This is I/O: data flows from Postgres into our process.
          events     (store/load-stream ds stream-id)

          ;; — Transform —
          ;; Pure computation, no side effects.
          ;; First: fold all past events into current state.
          state      (evolve-state decider events)
          ;; Extract the version for optimistic concurrency.
          ;; This is infrastructure bookkeeping, not domain logic —
          ;; that's why it lives here and not in the decider's evolve.
          version    (stream-version events)
          ;; Then: let the decider decide what should happen.
          new-events ((:decide decider) command state)]

      ;; — Push —
      ;; Write the new events to the store. This is I/O:
      ;; data flows from our process back to Postgres.
      ;; Optimistic concurrency and idempotency are enforced
      ;; by the store inside a single transaction.
      (store/append-events! ds stream-id version
                            idempotency-key command new-events))))

(defn handle-with-retry!
  "Like handle!, but retries on optimistic concurrency conflicts.

   On conflict the entire Pull → Transform → Push cycle re-executes.
   Each retry pulls fresh events, so the transform sees the latest
   state. This is the standard optimistic-concurrency retry loop.
   Retryability is determined from structured ex-data, not messages.
   Uses exponential backoff + jitter between retries to reduce
   thundering-herd contention."
  [ds decider command & {:keys [max-retries
                                base-backoff-ms
                                max-backoff-ms
                                jitter-ms
                                sleep-fn]
                         :or {max-retries     3
                              base-backoff-ms 5
                              max-backoff-ms  200
                              jitter-ms       10
                              sleep-fn        #(Thread/sleep (long %))}}]
  (letfn [(backoff-ms [attempt]
            (let [exp-factor (bit-shift-left 1 (dec (max 1 attempt)))
                  capped     (min (long max-backoff-ms)
                                  (* (long base-backoff-ms) exp-factor))
                  jitter     (if (pos? (long jitter-ms))
                               (rand-int (inc (long jitter-ms)))
                               0)]
              (+ capped jitter)))]
    (loop [attempt 1]
      (let [result (try
                     (handle! ds decider command)
                     (catch clojure.lang.ExceptionInfo e
                       (if (and (store/optimistic-concurrency-conflict? e)
                                (< attempt max-retries))
                         ::retry
                         (throw e))))]
        (if (= ::retry result)
          (do
            (sleep-fn (backoff-ms attempt))
            (recur (inc attempt)))
          result)))))
