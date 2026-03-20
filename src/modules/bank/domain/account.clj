(ns modules.bank.domain.account
  "Bank-account aggregate — composes vertical slices into one Decider.

   Slices (each in its own namespace):
     bank.account.open-account  — open a new account
     bank.account.deposit       — deposit money
     bank.account.withdraw      — withdraw money (with balance check)

   This namespace is the aggregate core. It:
     1. Merges schemas and upcasters from all slice files
     2. Creates shared kit functions (validators, factories)
     3. Defines evolve (shared — handles all event types)
     4. Composes the decisions map from slice make-decide functions
     5. Exports the Decider map

   The public API is unchanged: decider, decide, evolve, validate-event!,
   upcast-event. External consumers require bank.account and see the
   same interface regardless of the internal slice organisation."
  (:require [modules.bank.use-cases.open-account :as open-account]
            [modules.bank.use-cases.deposit :as deposit]
            [modules.bank.use-cases.withdraw :as withdraw]
            [es.decider-kit :as kit]))

;; ═══════════════════════════════════════════════════
;; Compose schemas from slices
;; ═══════════════════════════════════════════════════

(def ^:private command-data-specs
  (merge open-account/command-data-specs
         deposit/command-data-specs
         withdraw/command-data-specs))

(def ^:private latest-event-version
  (merge open-account/latest-event-version
         deposit/latest-event-version
         withdraw/latest-event-version))

(def ^:private event-schemas
  (merge open-account/event-schemas
         deposit/event-schemas
         withdraw/event-schemas))

(def ^:private event-upcasters
  (merge open-account/event-upcasters
         deposit/event-upcasters
         withdraw/event-upcasters))

;; ═══════════════════════════════════════════════════
;; Kit-derived functions (shared across all slices)
;; ═══════════════════════════════════════════════════

(def ^:private validate-command! (kit/make-command-validator command-data-specs))

(def upcast-event
  "Normalises a possibly-legacy event to the latest known schema version.
   Missing :event-version is treated as version 1 for backward compatibility."
  (kit/make-event-upcaster latest-event-version event-upcasters))

(def validate-event!
  "Upcasts + validates one domain event map.
   Returns the validated/upcasted event or throws ex-info."
  (kit/make-event-validator event-schemas upcast-event))

(def ^:private mk-event (kit/make-event-factory latest-event-version validate-event!))

;; ═══════════════════════════════════════════════════
;; Evolve — State → Event → State (shared)
;; ═══════════════════════════════════════════════════
;;
;; Handles all event types across all slices. Cannot be
;; split by slice because state reconstruction requires
;; all events in the stream.

(defn evolve
  "Applies a single event to the current state.
   No infrastructure concerns — no stream-sequence,
   no timestamps. Just domain state transitions."
  [state event]
  (let [{:keys [event-type payload]} (validate-event! event)]
    (case event-type
      "account-opened"  (assoc state :status :open
                               :owner  (:owner payload))
      "money-deposited" (update state :balance + (:amount payload))
      "money-withdrawn" (update state :balance - (:amount payload))
      state)))

;; ═══════════════════════════════════════════════════
;; Decisions — composed from slice make-decide functions
;; ═══════════════════════════════════════════════════

(def ^:private decisions
  {:open-account (open-account/make-decide mk-event)
   :deposit      (deposit/make-decide mk-event)
   :withdraw     (withdraw/make-decide mk-event)})

(def decide
  "Command → State → Event list.
   Dispatches on :command-type, delegates to the
   appropriate slice's decision function."
  (kit/make-decide validate-command! validate-event! decisions))

;; ═══════════════════════════════════════════════════
;; The Decider — a plain map
;; ═══════════════════════════════════════════════════

(def decider
  {:initial-state {:status :not-found, :balance 0}
   :decide        decide
   :evolve        evolve})
