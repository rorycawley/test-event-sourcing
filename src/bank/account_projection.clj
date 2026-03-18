(ns bank.account-projection
  "Account event handlers for the account_balances projection,
   plus query and search functions for the account read model.

   Handlers are declared as a data map keyed by event-type string,
   composed into the projection via bank.system."
  (:require [bank.account :as account]
            [es.projection-kit :as kit]
            [es.search :as search]
            [next.jdbc :as jdbc]))

;; ——— Handlers ———

(defn- apply-balance-delta!
  [tx {:keys [global-sequence stream-id] :as event} delta ensure-single-row-updated!]
  (let [result (jdbc/execute-one! tx
                                  ["UPDATE account_balances
        SET balance = balance + ?,
            last_global_sequence = ?,
            updated_at = NOW()
        WHERE account_id = ?
          AND last_global_sequence < ?"
                                   delta global-sequence stream-id global-sequence])]
    (ensure-single-row-updated! result
                                (select-keys event
                                             [:global-sequence
                                              :stream-id
                                              :event-type
                                              :event-version
                                              :payload]))))

(def handler-specs
  "Data-driven handler map: {event-type -> (fn [tx event context])}."
  {"account-opened"
   (fn [tx {:keys [global-sequence stream-id] :as event} _context]
     (let [{:keys [owner]} (:payload (account/validate-event! event))]
       (jdbc/execute-one! tx
                          ["INSERT INTO account_balances (account_id, owner, balance, last_global_sequence, updated_at)
        VALUES (?, ?, 0, ?, NOW())
        ON CONFLICT (account_id) DO UPDATE
          SET last_global_sequence = GREATEST(
                account_balances.last_global_sequence,
                EXCLUDED.last_global_sequence)"
                           stream-id (or owner "") global-sequence])))

   "money-deposited"
   (fn [tx event {:keys [ensure-single-row-updated!]}]
     (let [{amount :amount} (:payload (account/validate-event! event))]
       (apply-balance-delta! tx event amount ensure-single-row-updated!)))

   "money-withdrawn"
   (fn [tx event {:keys [ensure-single-row-updated!]}]
     (let [{amount :amount} (:payload (account/validate-event! event))]
       (apply-balance-delta! tx event (- amount) ensure-single-row-updated!)))})

;; ——— Query ———

(def get-balance
  "Reads the projected balance for one account."
  (kit/make-query "account_balances" "account_id"))

;; ——— Search ———

(def search-index-config
  "BM25 search index configuration for account_balances."
  {:table       "account_balances"
   :index-name  "idx_account_balances_search"
   :key-field   "account_id"
   :text-fields ["owner"]})

(def search-accounts
  "BM25 full-text search over account owners.

   Usage:
     (search-accounts ds \"Alice\")
     (search-accounts ds \"owner:Alice\" :limit 5)
     (search-accounts ds \"owner:\\\"Alice Smith\\\"\" :offset 10)

   Works with either the sync datasource (event store) or
   the async read database — just pass the appropriate ds."
  (search/make-searcher {:table "account_balances"}))
