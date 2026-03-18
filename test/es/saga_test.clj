(ns es.saga-test
  "Unit tests for es.saga — generic saga infrastructure.

   Tests use mock deciders and step handlers, not the bank domain."
  (:require [clojure.test :refer [deftest is]]
            [es.decider]
            [es.saga :as saga]))

;; ═══════════════════════════════════════════════════
;; domain-error?
;; ═══════════════════════════════════════════════════

(deftest domain-error-recognises-domain-namespace
  (is (true? (saga/domain-error?
              (ex-info "test" {:error/type :domain/insufficient-funds}))))
  (is (true? (saga/domain-error?
              (ex-info "test" {:error/type :domain/account-not-open}))))
  (is (true? (saga/domain-error?
              (ex-info "test" {:error/type :domain/anything})))))

(deftest domain-error-rejects-non-domain-errors
  (is (false? (saga/domain-error?
               (ex-info "test" {:error/type :concurrency/optimistic-conflict}))))
  (is (false? (saga/domain-error?
               (ex-info "test" {:error/type :store/invalid-event-envelope}))))
  (is (false? (saga/domain-error?
               (ex-info "test" {:error/type :command/invalid-envelope}))))
  (is (false? (saga/domain-error?
               (ex-info "test" {})))))

(deftest domain-error-rejects-non-keyword-error-type
  (is (false? (saga/domain-error?
               (ex-info "test" {:error/type "domain/foo"}))))
  (is (false? (saga/domain-error?
               (ex-info "test" {:error/type nil})))))

;; ═══════════════════════════════════════════════════
;; try-command!
;; ═══════════════════════════════════════════════════

(deftest try-command-returns-ok-on-success
  (with-redefs [es.decider/handle-with-retry! (fn [_ _ _ & _] :ok)]
    (is (= :ok (saga/try-command! :ds :decider :command)))))

(deftest try-command-returns-idempotent-on-replay
  (with-redefs [es.decider/handle-with-retry! (fn [_ _ _ & _] :idempotent)]
    (is (= :idempotent (saga/try-command! :ds :decider :command)))))

(deftest try-command-catches-domain-error-and-returns-error-map
  (with-redefs [es.decider/handle-with-retry!
                (fn [_ _ _ & _]
                  (throw (ex-info "Insufficient funds"
                                  {:error/type :domain/insufficient-funds})))]
    (let [result (saga/try-command! :ds :decider :command)]
      (is (= true (:error result)))
      (is (= "insufficient-funds" (:reason result))))))

(deftest try-command-propagates-infrastructure-errors
  (with-redefs [es.decider/handle-with-retry!
                (fn [_ _ _ & _]
                  (throw (ex-info "DB down"
                                  {:error/type :infra/connection-lost})))]
    (let [e (try (saga/try-command! :ds :decider :command) nil
                 (catch clojure.lang.ExceptionInfo ex ex))]
      (is (some? e))
      (is (= "DB down" (.getMessage e))))))

(deftest try-command-passes-on-events-appended-hook
  (let [captured (atom nil)]
    (with-redefs [es.decider/handle-with-retry!
                  (fn [_ _ _ & {:keys [on-events-appended]}]
                    (reset! captured on-events-appended)
                    :ok)]
      (let [hook (fn [_ _] :hook-called)]
        (saga/try-command! :ds :decider :command
                           :on-events-appended hook)
        (is (= hook @captured))))))

;; ═══════════════════════════════════════════════════
;; run-loop
;; ═══════════════════════════════════════════════════

(deftest run-loop-returns-completed-for-terminal-completed
  (let [result (saga/run-loop {} #{:completed :failed}
                              {:status :completed} {})]
    (is (= {:status :completed} result))))

(deftest run-loop-returns-failed-with-reason-for-terminal-failed
  (let [result (saga/run-loop {} #{:completed :failed}
                              {:status :failed :failure-reason "broke"} {})]
    (is (= {:status :failed :reason "broke"} result))))

(deftest run-loop-returns-generic-terminal-for-custom-status
  (let [result (saga/run-loop {} #{:completed :failed :cancelled}
                              {:status :cancelled} {})]
    (is (= {:status :cancelled} result))))

(deftest run-loop-executes-steps-until-terminal
  (let [steps (atom [])
        step-handlers
        {:initiated (fn [_state _ctx]
                      (swap! steps conj :initiated)
                      {:next-status :debited})
         :debited   (fn [_state _ctx]
                      (swap! steps conj :debited)
                      {:next-status :completed})
         :completed (fn [_ _] (throw (ex-info "should not be called" {})))}
        result (saga/run-loop step-handlers #{:completed :failed}
                              {:status :initiated} {:ds :test})]
    (is (= {:status :completed} result))
    (is (= [:initiated :debited] @steps))))

(deftest run-loop-stops-on-direct-result
  (let [result (saga/run-loop
                {:initiated (fn [_state _]
                              {:status :failed :reason "nope"})}
                #{:completed :failed}
                {:status :initiated} {})]
    (is (= {:status :failed :reason "nope"} result))))

(deftest run-loop-throws-on-missing-handler
  (let [e (try
            (saga/run-loop {} #{:completed :failed}
                           {:status :processing} {})
            nil
            (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= "No handler for saga status" (.getMessage e)))
    (is (= :processing (:status (ex-data e))))))

(deftest run-loop-passes-state-and-context-to-handlers
  (let [received-state (atom nil)
        received-ctx   (atom nil)
        step-handlers
        {:active (fn [state ctx]
                   (reset! received-state state)
                   (reset! received-ctx ctx)
                   {:next-status :completed})}]
    (saga/run-loop step-handlers #{:completed}
                   {:status :active :data 42} {:ds :my-ds :extra "info"})
    (is (= {:status :active :data 42} @received-state))
    (is (= {:ds :my-ds :extra "info"} @received-ctx))))

(deftest run-loop-threads-state-through-steps
  (let [step-handlers
        {:step-a (fn [state _ctx]
                   {:next-status :step-b :counter (inc (:counter state))})
         :step-b (fn [state _ctx]
                   {:next-status :completed :counter (inc (:counter state))})}
        result (saga/run-loop step-handlers #{:completed}
                              {:status :step-a :counter 0} {})]
    (is (= {:status :completed} result))))
