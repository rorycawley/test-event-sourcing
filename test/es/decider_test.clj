(ns es.decider-test
  (:require [clojure.test :refer [deftest is]]
            [es.decider :as decider]))

(deftest retries-on-structured-optimistic-conflict
  (let [attempts (atom 0)]
    (with-redefs [es.decider/handle!
                  (fn [_ _ _ & _]
                    (if (< (swap! attempts inc) 3)
                      (throw (ex-info "message intentionally unrelated"
                                      {:error/type       :concurrency/optimistic-conflict
                                       :error/retryable? true}))
                      :ok))]
      (is (= :ok (decider/handle-with-retry! nil nil {} :max-retries 5)))
      (is (= 3 @attempts)))))

(deftest does-not-retry-when-conflict-not-marked-retryable
  (let [attempts (atom 0)
        e (try
            (with-redefs [es.decider/handle!
                          (fn [_ _ _ & _]
                            (swap! attempts inc)
                            (throw (ex-info "optimistic conflict but no retry flag"
                                            {:error/type :concurrency/optimistic-conflict})))]
              (decider/handle-with-retry! nil nil {} :max-retries 5)
              nil)
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= 1 @attempts))))

(deftest retries-stop-at-max-retries
  (let [attempts (atom 0)
        e (try
            (with-redefs [es.decider/handle!
                          (fn [_ _ _ & _]
                            (swap! attempts inc)
                            (throw (ex-info "always conflict"
                                            {:error/type       :concurrency/optimistic-conflict
                                             :error/retryable? true})))]
              (decider/handle-with-retry! nil nil {} :max-retries 3)
              nil)
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= 3 @attempts))))

(deftest retries-apply-backoff-between-attempts
  (let [attempts (atom 0)
        sleeps   (atom [])]
    (with-redefs [es.decider/handle!
                  (fn [_ _ _ & _]
                    (if (< (swap! attempts inc) 3)
                      (throw (ex-info "conflict"
                                      {:error/type       :concurrency/optimistic-conflict
                                       :error/retryable? true}))
                      :ok))]
      (is (= :ok
             (decider/handle-with-retry! nil nil {}
                                         :max-retries 5
                                         :base-backoff-ms 2
                                         :max-backoff-ms 100
                                         :jitter-ms 0
                                         :sleep-fn #(swap! sleeps conj %))))
      (is (= 3 @attempts))
      (is (= [2 4] @sleeps)))))

(deftest invalid-command-envelope-is-structured
  (let [e (try
            (decider/handle! nil nil {:command-type :deposit})
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Invalid command envelope" (.getMessage e)))
    (is (= :command/invalid-envelope (:error/type (ex-data e))))))
