(ns modules.bank.use-cases.open-account
  "Vertical slice: Open Account.

   Command:  :open-account {:owner \"Alice\"}
   Event:    account-opened {:owner \"Alice\"}
   Rule:     Cannot open an already-open account."
  (:require [es.schema :as schema]))

;; ═══════════════════════════════════════════════════
;; Schemas
;; ═══════════════════════════════════════════════════

(def command-data-specs
  {:open-account [:map [:owner schema/non-empty-string]]})

(def latest-event-version
  {"account-opened" 1})

(def event-schemas
  {["account-opened" 1] [:map [:owner schema/non-empty-string]]})

(def event-upcasters {})

;; ═══════════════════════════════════════════════════
;; Decide
;; ═══════════════════════════════════════════════════

(defn make-decide
  "Returns the decide function for this slice.
   Takes mk-event from the aggregate core."
  [mk-event]
  (fn [state {:keys [owner]}]
    (when (= :open (:status state))
      (throw (ex-info "Account already open"
                      {:error/type :domain/account-already-open
                       :owner      (:owner state)})))
    [(mk-event "account-opened" {:owner owner})]))
