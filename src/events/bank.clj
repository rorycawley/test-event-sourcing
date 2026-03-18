(ns events.bank
  "Shared integration event contracts for the bank module.

   This namespace defines the schema for every integration event the
   bank module publishes. It is the single source of truth for the
   contract between the bank module (producer) and any consuming
   module (notification, fraud, reporting, etc.).

   Both sides validate against these schemas:
   - The bank module validates OUTGOING events in domain->integration
   - Consuming modules validate INCOMING events in their handlers

   If a schema changes here, both the producer and any affected
   consumers will fail at compile time (missing keys) or test time
   (schema validation), never silently at runtime.

   These schemas are Malli maps. They define the exact shape of each
   integration event — no more, no less."
  (:require [malli.core :as m]))

;; ──── Account events ────

(def account-opened
  "Schema for bank.account-opened integration event."
  [:map
   [:event-type      [:= "bank.account-opened"]]
   [:global-sequence pos-int?]
   [:account-id      string?]
   [:owner           [:maybe string?]]])

(def money-deposited
  "Schema for bank.money-deposited integration event."
  [:map
   [:event-type      [:= "bank.money-deposited"]]
   [:global-sequence pos-int?]
   [:account-id      string?]
   [:amount          pos-int?]])

(def money-withdrawn
  "Schema for bank.money-withdrawn integration event."
  [:map
   [:event-type      [:= "bank.money-withdrawn"]]
   [:global-sequence pos-int?]
   [:account-id      string?]
   [:amount          pos-int?]])

;; ──── Transfer events ────

(def transfer-initiated
  "Schema for bank.transfer-initiated integration event."
  [:map
   [:event-type      [:= "bank.transfer-initiated"]]
   [:global-sequence pos-int?]
   [:transfer-id     string?]
   [:from-account    string?]
   [:to-account      string?]
   [:amount          pos-int?]])

(def transfer-completed
  "Schema for bank.transfer-completed integration event."
  [:map
   [:event-type      [:= "bank.transfer-completed"]]
   [:global-sequence pos-int?]
   [:transfer-id     string?]])

(def transfer-failed
  "Schema for bank.transfer-failed integration event."
  [:map
   [:event-type      [:= "bank.transfer-failed"]]
   [:global-sequence pos-int?]
   [:transfer-id     string?]
   [:reason          string?]])

;; ──── Registry ────

(def schemas
  "Map of event-type → Malli schema. Used for dispatch-based validation."
  {"bank.account-opened"     account-opened
   "bank.money-deposited"    money-deposited
   "bank.money-withdrawn"    money-withdrawn
   "bank.transfer-initiated" transfer-initiated
   "bank.transfer-completed" transfer-completed
   "bank.transfer-failed"    transfer-failed})

(defn validate!
  "Validates an integration event against its schema.
   Throws ex-info if the event doesn't match the expected shape.
   Returns the event unchanged on success."
  [event]
  (let [event-type (:event-type event)
        schema     (get schemas event-type)]
    (when-not schema
      (throw (ex-info "Unknown integration event type"
                      {:event-type event-type
                       :event      event})))
    (when-not (m/validate schema event)
      (throw (ex-info "Integration event does not match contract"
                      {:event-type event-type
                       :event      event
                       :explain    (m/explain schema event)})))
    event))
