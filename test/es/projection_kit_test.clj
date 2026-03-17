(ns es.projection-kit-test
  "Unit tests for es.projection-kit — data-driven projection handler dispatch."
  (:require [clojure.test :refer [deftest is]]
            [es.projection-kit :as kit]))

;; ═══════════════════════════════════════════════════
;; make-handler
;; ═══════════════════════════════════════════════════

(deftest handler-dispatches-to-correct-handler
  (let [calls   (atom [])
        handler (kit/make-handler
                 {"event-a" (fn [tx event ctx]
                              (swap! calls conj {:type "event-a" :tx tx :event event :ctx ctx}))
                  "event-b" (fn [tx event ctx]
                              (swap! calls conj {:type "event-b" :tx tx :event event :ctx ctx}))})]
    (handler :tx-1 {:event-type "event-a" :payload {}} {:projection-name "test"})
    (handler :tx-2 {:event-type "event-b" :payload {}} {:projection-name "test"})
    (is (= 2 (count @calls)))
    (is (= "event-a" (:type (first @calls))))
    (is (= :tx-1 (:tx (first @calls))))
    (is (= "event-b" (:type (second @calls))))
    (is (= :tx-2 (:tx (second @calls))))))

(deftest handler-throws-on-unknown-event-type
  (let [handler (kit/make-handler {"known" (fn [_ _ _] :ok)})
        e (try
            (handler :tx
                     {:event-type      "unknown"
                      :event-version   1
                      :global-sequence 42
                      :stream-id       "s-1"}
                     {:projection-name "test-proj"})
            nil
            (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= "Unknown event type for projection" (.getMessage e)))
    (is (= "test-proj" (:projection-name (ex-data e))))
    (is (= "unknown" (:event-type (ex-data e))))
    (is (= 1 (:event-version (ex-data e))))
    (is (= 42 (:global-sequence (ex-data e))))
    (is (= "s-1" (:stream-id (ex-data e))))))

(deftest handler-passes-context-through-to-handler-fn
  (let [received-ctx (atom nil)
        handler (kit/make-handler
                 {"test" (fn [_tx _event ctx]
                           (reset! received-ctx ctx))})]
    (handler :tx {:event-type "test"} {:projection-name "p" :extra "data"})
    (is (= {:projection-name "p" :extra "data"} @received-ctx))))

(deftest handler-works-with-empty-handler-map
  (let [handler (kit/make-handler {})
        e (try
            (handler :tx {:event-type "anything"} {:projection-name "p"})
            nil
            (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= "Unknown event type for projection" (.getMessage e)))))

(deftest handler-returns-handler-fn-result
  (let [handler (kit/make-handler
                 {"test" (fn [_ _ _] :handler-result)})]
    (is (= :handler-result
           (handler :tx {:event-type "test"} {})))))

;; ═══════════════════════════════════════════════════
;; make-query — integration tests in projection_test.clj
;; Unit-level: verify it returns a function with right arity
;; ═══════════════════════════════════════════════════

(deftest make-query-returns-a-function
  (let [query-fn (kit/make-query "some_table" "id_column")]
    (is (fn? query-fn))))

(deftest make-query-with-custom-columns-returns-a-function
  (let [query-fn (kit/make-query "some_table" "id_column" :columns "id, name")]
    (is (fn? query-fn))))
