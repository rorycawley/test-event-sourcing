(ns bank.account.deposit
  "Vertical slice: Deposit Money.

   Command:  :deposit {:amount 50}
   Event:    money-deposited {:amount 50, :origin \"command\", :currency \"USD\"}
   Rule:     Account must be open.

   Event versioning:
     v1: {:amount 25}
     v2: {:amount 25, :origin \"legacy\"}
     v3: {:amount 25, :origin \"legacy\", :currency \"USD\"}"
  (:require [es.schema :as schema]))

;; ═══════════════════════════════════════════════════
;; Schemas
;; ═══════════════════════════════════════════════════

(def command-data-specs
  {:deposit [:map [:amount pos-int?]]})

(def latest-event-version
  {"money-deposited" 3})

(def event-schemas
  {["money-deposited" 1] [:map [:amount pos-int?]]
   ["money-deposited" 2] [:map
                          [:amount pos-int?]
                          [:origin schema/non-empty-string]]
   ["money-deposited" 3] [:map
                          [:amount pos-int?]
                          [:origin schema/non-empty-string]
                          [:currency [:= "USD"]]]})

(def event-upcasters
  {["money-deposited" 1]
   (fn [{:keys [payload] :as event}]
     (assoc event
            :event-version 2
            :payload (if (map? payload)
                       (assoc payload :origin "legacy")
                       payload)))
   ["money-deposited" 2]
   (fn [{:keys [payload] :as event}]
     (assoc event
            :event-version 3
            :payload (if (map? payload)
                       (assoc payload :currency "USD")
                       payload)))})

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
    [(mk-event "money-deposited" {:amount amount
                                  :origin "command"
                                  :currency "USD"})]))
