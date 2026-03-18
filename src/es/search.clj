(ns es.search
  "Generic BM25 full-text search for projected read models.

   Uses ParadeDB pg_search extension for relevance-ranked search.
   Requires a ParadeDB-compatible PostgreSQL instance.

   Three factory/lifecycle functions:

     ensure-search!    ds, config → creates pg_search extension + BM25 index
     drop-search!      ds, index  → drops a BM25 index
     make-searcher     config     → (fn [ds query & opts] results)

   BM25 indexes are created via ensure-search!, not in SQL migrations,
   because the Tantivy-backed index has different lifecycle concerns
   than schema DDL (e.g. rebuild after TRUNCATE). Migrations handle
   the schema (columns); this namespace handles the search index."
  (:require [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]
            [clojure.string :as str]))

;; ——— Identifier validation ———

(def ^:private identifier-pattern
  "SQL identifiers must be alphanumeric + underscores only."
  #"^[a-zA-Z_][a-zA-Z0-9_]*$")

(defn- validate-identifier!
  "Validates that a string is a safe SQL identifier.
   DDL statements (CREATE INDEX, etc.) cannot use parameterized queries,
   so we validate identifiers against an allowlist pattern instead."
  [s label]
  (when-not (re-matches identifier-pattern s)
    (throw (ex-info (str "Invalid SQL identifier for " label)
                    {:label label :value s})))
  s)

;; ——— Index lifecycle ———

(defn ensure-search!
  "Ensures the pg_search extension is available and a BM25 index exists.
   Idempotent — safe to call on every startup.

   config:
     :table       — table name
     :index-name  — BM25 index name (must be unique per database)
     :key-field   — primary key column name
     :text-fields — vector of text column names to index for search

   All identifiers are validated against [a-zA-Z_][a-zA-Z0-9_]* to
   prevent SQL injection (DDL cannot use parameterized queries)."
  [ds {:keys [table index-name key-field text-fields]}]
  (validate-identifier! table "table")
  (validate-identifier! index-name "index-name")
  (validate-identifier! key-field "key-field")
  (doseq [f text-fields]
    (validate-identifier! f "text-field"))
  (jdbc/execute-one! ds ["CREATE EXTENSION IF NOT EXISTS pg_search"])
  (let [all-cols (distinct (cons key-field text-fields))
        cols     (str/join ", " all-cols)]
    (jdbc/execute-one! ds
                       [(str "CREATE INDEX IF NOT EXISTS " index-name
                             " ON " table
                             " USING bm25 (" cols ")"
                             " WITH (key_field = '" key-field "')")])))

(defn drop-search!
  "Drops a BM25 index. Useful before rebuilds or TRUNCATE operations."
  [ds index-name]
  (validate-identifier! index-name "index-name")
  (jdbc/execute-one! ds [(str "DROP INDEX IF EXISTS " index-name)]))

;; ——— Search factory ———

(defn make-searcher
  "Returns a search function for a table with a BM25 index.

   The returned fn: (fn [ds query & {:keys [limit offset]}])
   Returns results ordered by BM25 relevance score (descending).

   query is a Tantivy query string:
     \"Alice\"              — search all indexed fields
     \"owner:Alice\"        — search specific field
     \"owner:Alice Smith\"  — multi-term search
     \"owner:\\\"Alice Smith\\\"\" — exact phrase search

   config:
     :table — table name (used in the @@@ operator and score function)
   opts:
     :default-limit — max results when :limit not specified (default 20)
     :columns       — SELECT columns (default \"*\")"
  [{:keys [table]} & {:keys [default-limit columns]
                      :or   {default-limit 20 columns "*"}}]
  (validate-identifier! table "table")
  (when-not (= "*" columns)
    (doseq [col (str/split columns #",\s*")]
      (validate-identifier! (str/trim col) "column")))
  (let [select-clause (str "SELECT " columns
                           ", paradedb.score(" table ".ctid) AS score")
        from-clause   (str " FROM " table)
        where-clause  (str " WHERE " table " @@@ paradedb.parse(?)")
        order-clause  " ORDER BY score DESC"
        limit-clause  " LIMIT ? OFFSET ?"]
    (fn [ds query & {:keys [limit offset] :or {offset 0}}]
      (let [lim (or limit default-limit)]
        (jdbc/execute! ds
                       [(str select-clause from-clause where-clause
                             order-clause limit-clause)
                        query (long lim) (long offset)]
                       {:builder-fn rs/as-unqualified-kebab-maps})))))
