(ns bank.system
  "Composition root: wires the es framework with bank domain projections.

   Architecture overview:

   ┌─────────┐    ┌──────────────────────┐    ┌───────────────┐
   │ Command │───>│       Decider        │───>│     Store     │
   └─────────┘    │  decide() -> [Event] │    │ append-events!│
                  │  evolve()  -> State  │    │  (append-only │
                  │    (pure functions)   │    │   event log)  │
                  └──────────────────────┘    └───────┬───────┘
                                                      │
                                                      v
   ┌────────────┐    ┌──────────────────────┐    ┌──────────┐
   │ Read Model │<───│     Projection       │<───│  Events  │
   │            │    │  process-new-events!  │    │  table   │
   │ - balances │    │  (handler per event   │    └──────────┘
   │ - transfers│    │   type, fail-fast)    │
   └────────────┘    └──────────────────────┘

   Saga (cross-stream coordination):

   ┌──────┐    ┌───────────┐    ┌───────────┐    ┌───────────┐
   │ Saga │───>│ Decider A │───>│ Decider B │───>│ Decider C │
   │      │    │  (debit)  │    │  (credit) │    │ (complete)│
   └──────┘    └───────────┘    └───────────┘    └───────────┘

   This namespace is the single place where framework infrastructure
   meets domain-specific projection handlers. It:
   1. Merges all domain handler-specs maps into one combined handler
   2. Defines the projection config (name, read model tables, handler)
   3. Provides convenience wrappers for projection operations"
  (:require [es.projection :as projection]
            [es.projection-kit :as kit]
            [bank.account-projection :as account-projection]
            [bank.transfer-projection :as transfer-projection]))

(defn- merge-handler-specs
  "Merges handler-specs maps, failing fast on duplicate event-type keys.
   Silent merge-overwrites would cause one projection handler to vanish."
  [& specs-maps]
  (let [all-keys (mapcat keys specs-maps)
        dupes    (for [[k freq] (frequencies all-keys) :when (> freq 1)] k)]
    (when (seq dupes)
      (throw (ex-info "Duplicate event-type keys in projection handler-specs"
                      {:duplicate-keys (vec dupes)}))))
  (apply merge specs-maps))

(def ^:private combined-handler
  "Merged handler built from all domain projection handler-specs."
  (kit/make-handler
   (merge-handler-specs account-projection/handler-specs
                        transfer-projection/handler-specs)))

(def projection-config
  "Configuration for the bank domain's main projection."
  {:projection-name   "main"
   :read-model-tables ["account_balances" "transfer_status"]
   :handler           combined-handler})

(defn process-new-events!
  "Reads events the projection hasn't seen yet and applies them."
  [ds]
  (projection/process-new-events! ds projection-config))

(defn rebuild!
  "Destroys and rebuilds the read model from the complete event stream."
  [ds]
  (projection/rebuild! ds projection-config))
