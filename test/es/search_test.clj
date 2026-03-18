(ns es.search-test
  "Tests for es.search — BM25 full-text search via ParadeDB pg_search.

   Uses a single ParadeDB testcontainer. Creates a test table,
   populates it, creates a BM25 index, and verifies search results."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [es.search :as search]
            [es.infra :as infra]
            [next.jdbc :as jdbc]))

;; ═══════════════════════════════════════════════════
;; Test infrastructure
;; ═══════════════════════════════════════════════════

(def ^:dynamic *ds* nil)

(defn- with-system [f]
  (let [pg (infra/start-postgres!)
        ds (infra/->datasource pg)]
    (try
      (binding [*ds* ds]
        (f))
      (finally
        (infra/stop-postgres! pg)))))

(defn- with-test-table [f]
  (jdbc/execute-one! *ds*
                     ["CREATE TABLE IF NOT EXISTS search_articles (
                         article_id   TEXT PRIMARY KEY,
                         title        TEXT NOT NULL,
                         body         TEXT NOT NULL,
                         author       TEXT NOT NULL)"])
  ;; Populate test data
  (jdbc/execute-one! *ds*
                     ["INSERT INTO search_articles (article_id, title, body, author) VALUES
                       ('a1', 'Introduction to Clojure', 'Clojure is a functional programming language on the JVM', 'Alice Johnson'),
                       ('a2', 'Event Sourcing Patterns', 'Event sourcing stores every state change as an immutable event', 'Bob Smith'),
                       ('a3', 'Advanced Clojure Macros', 'Macros allow compile-time code transformation in Clojure', 'Alice Johnson'),
                       ('a4', 'PostgreSQL Full Text Search', 'PostgreSQL provides built-in full text search capabilities', 'Carol Davis'),
                       ('a5', 'Building CQRS Systems', 'CQRS separates command and query responsibilities', 'Bob Smith'),
                       ('a6', 'Functional Programming in Java', 'Java has embraced functional programming with lambdas', 'Dave Wilson')
                      ON CONFLICT DO NOTHING"])
  ;; Create BM25 index
  (search/ensure-search! *ds*
                         {:table       "search_articles"
                          :index-name  "idx_search_articles_bm25"
                          :key-field   "article_id"
                          :text-fields ["title" "body" "author"]})
  (f))

(use-fixtures :once with-system)
(use-fixtures :each with-test-table)

;; ═══════════════════════════════════════════════════
;; Search function under test
;; ═══════════════════════════════════════════════════

(def ^:private search-articles
  (search/make-searcher {:table "search_articles"}))

;; ═══════════════════════════════════════════════════
;; Tests
;; ═══════════════════════════════════════════════════

(deftest search-returns-relevant-results
  (let [results (search-articles *ds* "Clojure")]
    (is (pos? (count results)))
    (is (every? #(contains? % :score) results))
    (is (every? #(pos? (:score %)) results))
    ;; Both Clojure articles should appear
    (let [ids (set (map :article-id results))]
      (is (contains? ids "a1"))
      (is (contains? ids "a3")))))

(deftest search-by-specific-field
  (let [results (search-articles *ds* "author:Alice")]
    (is (= 2 (count results)))
    (let [ids (set (map :article-id results))]
      (is (= #{"a1" "a3"} ids)))))

(deftest search-results-ordered-by-relevance
  (let [results (search-articles *ds* "Clojure")]
    ;; Scores should be in descending order
    (is (apply >= (map :score results)))))

(deftest search-respects-limit
  (let [results (search-articles *ds* "programming" :limit 2)]
    (is (<= (count results) 2))))

(deftest search-respects-offset
  (let [all-results (search-articles *ds* "Clojure")
        offset-results (search-articles *ds* "Clojure" :offset 1)]
    (when (> (count all-results) 1)
      (is (= (count offset-results) (dec (count all-results)))))))

(deftest search-no-matches-returns-empty
  (let [results (search-articles *ds* "xyznonexistent")]
    (is (empty? results))))

(deftest ensure-search-is-idempotent
  ;; Calling ensure-search! again should not throw
  (search/ensure-search! *ds*
                         {:table       "search_articles"
                          :index-name  "idx_search_articles_bm25"
                          :key-field   "article_id"
                          :text-fields ["title" "body" "author"]})
  ;; Search still works
  (let [results (search-articles *ds* "Clojure")]
    (is (pos? (count results)))))

(deftest drop-and-recreate-search-index
  (search/drop-search! *ds* "idx_search_articles_bm25")
  ;; Recreate
  (search/ensure-search! *ds*
                         {:table       "search_articles"
                          :index-name  "idx_search_articles_bm25"
                          :key-field   "article_id"
                          :text-fields ["title" "body" "author"]})
  ;; Search works after recreation
  (let [results (search-articles *ds* "event sourcing")]
    (is (pos? (count results)))))

(deftest make-searcher-with-custom-columns
  (let [search-fn (search/make-searcher {:table "search_articles"}
                                        :columns "article_id, title")
        results   (search-fn *ds* "Clojure")]
    (is (pos? (count results)))
    ;; Should have article-id, title, and score but not body or author
    (is (contains? (first results) :article-id))
    (is (contains? (first results) :title))
    (is (contains? (first results) :score))))

;; ═══════════════════════════════════════════════════
;; SQL injection prevention
;; ═══════════════════════════════════════════════════

(deftest ensure-search-rejects-malicious-identifiers
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Invalid SQL identifier"
       (search/ensure-search! *ds* {:table       "search_articles"
                                    :index-name  "idx; DROP TABLE events"
                                    :key-field   "article_id"
                                    :text-fields ["title"]})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Invalid SQL identifier"
       (search/ensure-search! *ds* {:table       "search_articles"
                                    :index-name  "idx_test"
                                    :key-field   "'; DROP TABLE events; --"
                                    :text-fields ["title"]})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Invalid SQL identifier"
       (search/ensure-search! *ds* {:table       "search_articles"
                                    :index-name  "idx_test"
                                    :key-field   "article_id"
                                    :text-fields ["title; DROP TABLE events"]}))))

(deftest drop-search-rejects-malicious-identifiers
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Invalid SQL identifier"
       (search/drop-search! *ds* "idx; DROP TABLE events"))))

(deftest make-searcher-rejects-malicious-identifiers
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Invalid SQL identifier"
       (search/make-searcher {:table "search_articles; DROP TABLE events"})))
  (is (thrown-with-msg?
       clojure.lang.ExceptionInfo #"Invalid SQL identifier"
       (search/make-searcher {:table "search_articles"}
                             :columns "*, 1; DROP TABLE events"))))
