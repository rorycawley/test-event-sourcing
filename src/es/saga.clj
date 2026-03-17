(ns es.saga
  "Generic saga infrastructure — reusable machinery for coordinating
   multi-aggregate workflows.

   Provides:
   • domain-error? — predicate for business-rule rejections
   • try-command!  — attempts a command, catching domain rejections
   • run-loop      — drives a step-handler map to completion"
  (:require [es.decider :as decider]))

(defn domain-error?
  "Returns true when an exception is a domain-level rejection
   (business rule violation), not an infrastructure failure."
  [e]
  (let [error-type (:error/type (ex-data e))]
    (and (keyword? error-type)
         (= "domain" (namespace error-type)))))

(defn try-command!
  "Attempts a command via a decider with retry, returning :ok/:idempotent
   on success or {:error true :reason \"...\"} on domain rejection.
   Infrastructure errors (DB down, connection lost) propagate — they
   should not be confused with domain rejections."
  [ds decider command]
  (try
    (decider/handle-with-retry! ds decider command)
    (catch clojure.lang.ExceptionInfo e
      (if (domain-error? e)
        {:error  true
         :reason (name (:error/type (ex-data e)))}
        (throw e)))))

(defn run-loop
  "Drives a saga state machine to completion.

   step-handlers is a map of {status-keyword -> (fn [context] result)}
   where result is either:
     {:status :completed/:failed ...}  — terminal, loop ends
     {:next-status :some-status}       — advance the loop

   terminal-statuses is a set of status keywords that end the loop
   immediately (e.g. #{:completed :failed}).

   state is the current saga state (must have :status key).
   context is passed through to each step handler."
  [step-handlers terminal-statuses state context]
  (loop [status (:status state)
         state  state]
    (if (contains? terminal-statuses status)
      (cond
        (= :completed status) {:status :completed}
        (= :failed status)    {:status :failed :reason (:failure-reason state)}
        :else                 {:status status})
      (if-let [handler (get step-handlers status)]
        (let [result (handler context)]
          (if (:next-status result)
            (recur (:next-status result) state)
            result))
        (throw (ex-info "No handler for saga status"
                        {:status status
                         :known-statuses (set (keys step-handlers))}))))))
