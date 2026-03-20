(ns modules.bank.infra.search-test
  "End-to-end BM25 search tests: command → projection → search.

   Uses a ParadeDB container for pg_search BM25 support.
   The BM25 index is created once after migration; DELETE (not TRUNCATE)
   is used between tests to preserve the Tantivy index."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [modules.bank.domain.account :as account]
            [modules.bank.infra.account-projection :as account-projection]
            [modules.bank.infra.system :as system]
            [es.decider :as decider]
            [es.infra :as infra]
            [es.migrations :as migrations]
            [es.search :as search]
            [next.jdbc :as jdbc]))

;; ═══════════════════════════════════════════════════
;; Test infrastructure — ParadeDB container
;; ═══════════════════════════════════════════════════

(def ^:dynamic *ds* nil)

(defn- with-paradedb-system [f]
  (let [pg (infra/start-postgres!)
        ds (infra/->datasource pg)]
    (try
      (migrations/migrate! ds)
      ;; Create BM25 index immediately after migration.
      ;; ParadeDB preloads pg_search; INSERTs on tables with text columns
      ;; need a configured BM25 index to avoid "No key field defined".
      (search/ensure-search! ds account-projection/search-index-config)
      (binding [*ds* ds]
        (f))
      (finally
        (infra/stop-postgres! pg)))))

(defn- reset-db! []
  ;; Use DELETE (not TRUNCATE) to preserve the BM25 index.
  ;; TRUNCATE invalidates the Tantivy index backing pg_search.
  (jdbc/execute-one! *ds* ["DELETE FROM account_balances"])
  (jdbc/execute-one! *ds* ["DELETE FROM transfer_status"])
  (jdbc/execute-one! *ds* ["DELETE FROM projection_checkpoints"])
  (jdbc/execute-one! *ds* ["DELETE FROM events"])
  (jdbc/execute-one! *ds* ["DELETE FROM idempotency_keys"])
  (jdbc/execute-one! *ds* ["DELETE FROM event_outbox"])
  ;; Reset sequences for deterministic global_sequence values
  (jdbc/execute-one! *ds* ["ALTER SEQUENCE events_global_sequence_seq RESTART WITH 1"])
  (jdbc/execute-one! *ds* ["ALTER SEQUENCE event_outbox_id_seq RESTART WITH 1"]))

(defn- with-clean-db [f]
  (reset-db!)
  (f))

(use-fixtures :once with-paradedb-system)
(use-fixtures :each with-clean-db)

;; ═══════════════════════════════════════════════════
;; Helpers
;; ═══════════════════════════════════════════════════

(defn- open-account! [id owner]
  (decider/handle! *ds* account/decider
                   {:command-type    :open-account
                    :stream-id       id
                    :idempotency-key (str "open-" id)
                    :data            {:owner owner}}))

;; ═══════════════════════════════════════════════════
;; Tests
;; ═══════════════════════════════════════════════════

(deftest search-accounts-by-owner
  (doseq [[id owner] [["acct-s1" "Alice Johnson"]
                      ["acct-s2" "Bob Smith"]
                      ["acct-s3" "Alice Williams"]
                      ["acct-s4" "Carol Davis"]]]
    (open-account! id owner))
  (system/process-new-events! *ds*)

  ;; Search for "Alice" — should find both Alice accounts
  (let [results (account-projection/search-accounts *ds* "owner:Alice")]
    (is (= 2 (count results)))
    (is (= #{"acct-s1" "acct-s3"} (set (map :account-id results))))
    (is (every? #(pos? (:score %)) results)))

  ;; Search for "Bob" — should find one
  (let [results (account-projection/search-accounts *ds* "owner:Bob")]
    (is (= 1 (count results)))
    (is (= "acct-s2" (:account-id (first results)))))

  ;; No results for unknown owner
  (is (empty? (account-projection/search-accounts *ds* "owner:Zara"))))

(deftest owner-field-stored-in-projection
  (open-account! "acct-owner-1" "Alice Johnson")
  (system/process-new-events! *ds*)

  (let [balance (account-projection/get-balance *ds* "acct-owner-1")]
    (is (= "Alice Johnson" (:owner balance)))
    (is (= 0 (:balance balance)))))

(deftest search-respects-limit-and-offset
  (doseq [i (range 5)]
    (open-account! (str "acct-page-" i) (str "Pagination User " i)))
  (system/process-new-events! *ds*)

  ;; Limit to 2
  (let [results (account-projection/search-accounts *ds* "owner:Pagination"
                                                    :limit 2)]
    (is (= 2 (count results))))

  ;; Offset past first results
  (let [all-results (account-projection/search-accounts *ds* "owner:Pagination")
        offset-results (account-projection/search-accounts *ds* "owner:Pagination"
                                                           :offset 2)]
    (is (= (- (count all-results) 2) (count offset-results)))))

(deftest search-results-ordered-by-bm25-score
  (doseq [[id owner] [["acct-r1" "Alice"]
                      ["acct-r2" "Bob"]
                      ["acct-r3" "Alice Alice"]]]
    (open-account! id owner))
  (system/process-new-events! *ds*)

  (let [results (account-projection/search-accounts *ds* "owner:Alice")]
    (is (pos? (count results)))
    ;; Scores should be in descending order
    (is (apply >= (map :score results)))))

(deftest search-empty-table-returns-empty
  (is (empty? (account-projection/search-accounts *ds* "owner:Alice"))))

(deftest search-after-rebuild
  (doseq [[id owner] [["acct-rb1" "Alice"]
                      ["acct-rb2" "Bob"]]]
    (open-account! id owner))
  (system/process-new-events! *ds*)

  ;; Verify initial search works
  (is (= 1 (count (account-projection/search-accounts *ds* "owner:Alice"))))

  ;; Rebuild projection — drop index first (DELETE in rebuild + BM25 index)
  (search/drop-search! *ds* "idx_account_balances_search")
  (system/rebuild! *ds*)

  ;; Recreate index and search again
  (search/ensure-search! *ds* account-projection/search-index-config)
  (is (= 1 (count (account-projection/search-accounts *ds* "owner:Alice"))))
  (is (= 1 (count (account-projection/search-accounts *ds* "owner:Bob")))))
