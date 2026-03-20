(ns modules.bank.infra.integration-events
  "Maps domain events to integration events for external consumers.

   Integration events are the bank module's public API. They define
   a stable, curated contract that external modules (notification,
   fraud, reporting) can depend on without coupling to internal
   domain event structure.

   Not all domain events become integration events. Only significant,
   summary-level events are published. Internal saga steps (debit-recorded,
   credit-recorded) are not published — they are implementation details.

   Each integration event is:
   - Namespaced with 'bank.' to identify the source module
   - Self-contained (carries all the data a consumer needs)
   - Flat (no nested domain-specific structures)
   - Validated against the shared contract in events.bank"
  (:require [modules.bank.domain.transfer :as transfer]
            [events.bank :as contract]))

(defn domain->integration
  "Transforms a domain event into an integration event for publishing.

   Returns an integration event map, or nil if the event should not
   be published externally. Every returned event is validated against
   the shared contract schema — a schema mismatch throws at publish
   time, not silently at consume time.

   event: {:global-sequence, :stream-id, :event-type, :event-version, :payload}"
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

               "money-withdrawn"
               {:event-type      "bank.money-withdrawn"
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

               "transfer-completed"
               {:event-type      "bank.transfer-completed"
                :global-sequence global-sequence
                :transfer-id     (transfer/logical-transfer-id stream-id)}

               "transfer-failed"
               {:event-type      "bank.transfer-failed"
                :global-sequence global-sequence
                :transfer-id     (transfer/logical-transfer-id stream-id)
                :reason          (:reason payload)}

               ;; Internal saga steps and other domain events are not published.
               nil)]
    (contract/validate! integration-event)))
