(ns bank.account.withdraw
  "Vertical slice: Withdraw Money.

   Command:  :withdraw {:amount 50}
   Event:    money-withdrawn {:amount 50}
   Rules:    Account must be open. Cannot withdraw more than balance.")

;; ═══════════════════════════════════════════════════
;; Schemas
;; ═══════════════════════════════════════════════════

(def command-data-specs
  {:withdraw [:map [:amount pos-int?]]})

(def latest-event-version
  {"money-withdrawn" 1})

(def event-schemas
  {["money-withdrawn" 1] [:map [:amount pos-int?]]})

(def event-upcasters {})

;; ═══════════════════════════════════════════════════
;; Decide
;; ═══════════════════════════════════════════════════

(defn make-decide
  "Returns the decide function for this slice.
   Takes mk-event from the aggregate core."
  [mk-event]
  (fn [state {:keys [amount]}]
    (when-not (= :open (:status state))
      (throw (ex-info "Account not open"
                      {:error/type :domain/account-not-open})))
    (when (> amount (:balance state))
      (throw (ex-info "Insufficient funds"
                      {:error/type :domain/insufficient-funds
                       :balance    (:balance state)
                       :amount     amount})))
    [(mk-event "money-withdrawn" {:amount amount})]))
