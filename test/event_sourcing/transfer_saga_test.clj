(ns event-sourcing.transfer-saga-test
  "Integration tests for fund transfers — requires a running database."
  (:require [clojure.test :refer [deftest is use-fixtures]]
            [event-sourcing.account :as account]
            [event-sourcing.decider :as decider]
            [event-sourcing.projection :as projection]
            [event-sourcing.store :as store]
            [event-sourcing.transfer-saga :as saga]
            [event-sourcing.test-support :as support]))

(use-fixtures :once support/with-system)
(use-fixtures :each support/with-clean-db)

(defn- open-and-fund!
  "Helper: opens an account and optionally deposits the given amount."
  [account-id owner amount]
  (decider/handle! support/*ds* account/decider
                   {:command-type    :open-account
                    :stream-id       account-id
                    :idempotency-key (str "setup-open-" account-id)
                    :data            {:owner owner}})
  (when (pos? amount)
    (decider/handle! support/*ds* account/decider
                     {:command-type    :deposit
                      :stream-id       account-id
                      :idempotency-key (str "setup-fund-" account-id)
                      :data            {:amount amount}})))

;; ——— Happy path ———

(deftest successful-transfer-moves-funds
  (open-and-fund! "alice" "Alice" 200)
  (open-and-fund! "bob" "Bob" 50)

  (let [result (saga/execute! support/*ds* "tx-1" "alice" "bob" 75)]
    (is (= :completed (:status result))))

  ;; Verify balances via event streams
  (let [alice-state (decider/evolve-state account/decider
                                          (store/load-stream support/*ds* "alice"))
        bob-state   (decider/evolve-state account/decider
                                          (store/load-stream support/*ds* "bob"))]
    (is (= 125 (:balance alice-state)))
    (is (= 125 (:balance bob-state))))

  ;; Verify transfer stream has all 4 events
  (let [transfer-events (store/load-stream support/*ds* "transfer-tx-1")]
    (is (= 4 (count transfer-events)))
    (is (= ["transfer-initiated" "debit-recorded"
            "credit-recorded" "transfer-completed"]
           (mapv :event-type transfer-events)))))

(deftest successful-transfer-projection
  (open-and-fund! "alice" "Alice" 200)
  (open-and-fund! "bob" "Bob" 50)

  (saga/execute! support/*ds* "tx-proj" "alice" "bob" 30)
  (projection/process-new-events! support/*ds*)

  ;; Account balances updated
  (is (= 170 (:balance (projection/get-balance support/*ds* "alice"))))
  (is (= 80 (:balance (projection/get-balance support/*ds* "bob"))))

  ;; Transfer status projected
  (let [t (projection/get-transfer support/*ds* "transfer-tx-proj")]
    (is (= "completed" (:status t)))
    (is (= "alice" (:from-account t)))
    (is (= "bob" (:to-account t)))
    (is (= 30 (:amount t)))))

;; ——— Failure: insufficient funds ———

(deftest transfer-fails-on-insufficient-funds
  (open-and-fund! "alice" "Alice" 50)
  (open-and-fund! "bob" "Bob" 0)

  (let [result (saga/execute! support/*ds* "tx-fail-1" "alice" "bob" 100)]
    (is (= :failed (:status result)))
    (is (= "insufficient-funds" (:reason result))))

  ;; Alice's balance unchanged
  (let [alice-state (decider/evolve-state account/decider
                                          (store/load-stream support/*ds* "alice"))]
    (is (= 50 (:balance alice-state))))

  ;; Transfer stream shows initiated + failed
  (let [events (store/load-stream support/*ds* "transfer-tx-fail-1")]
    (is (= ["transfer-initiated" "transfer-failed"]
           (mapv :event-type events)))))

(deftest failed-transfer-projection
  (open-and-fund! "alice" "Alice" 50)
  (open-and-fund! "bob" "Bob" 0)

  (saga/execute! support/*ds* "tx-fail-proj" "alice" "bob" 100)
  (projection/process-new-events! support/*ds*)

  (let [t (projection/get-transfer support/*ds* "transfer-tx-fail-proj")]
    (is (= "failed" (:status t)))
    (is (= "insufficient-funds" (:failure-reason t)))))

;; ——— Failure: source account not open ———

(deftest transfer-fails-when-source-not-open
  (open-and-fund! "bob" "Bob" 100)
  ;; "ghost" account was never opened

  (let [result (saga/execute! support/*ds* "tx-fail-2" "ghost" "bob" 10)]
    (is (= :failed (:status result)))
    (is (= "account-not-open" (:reason result)))))

;; ——— Failure: destination not open → compensation ———

(deftest transfer-compensates-when-destination-not-open
  (open-and-fund! "alice" "Alice" 200)
  ;; "ghost" account was never opened

  (let [result (saga/execute! support/*ds* "tx-comp-1" "alice" "ghost" 50)]
    (is (= :failed (:status result)))
    (is (= "account-not-open" (:reason result))))

  ;; Alice's balance restored after compensation
  (let [alice-state (decider/evolve-state account/decider
                                          (store/load-stream support/*ds* "alice"))]
    (is (= 200 (:balance alice-state))))

  ;; Transfer stream shows: initiated, debit-recorded, failed
  (let [events (store/load-stream support/*ds* "transfer-tx-comp-1")]
    (is (= ["transfer-initiated" "debit-recorded" "transfer-failed"]
           (mapv :event-type events)))))

(deftest compensation-projection-balances-correct
  (open-and-fund! "alice" "Alice" 200)

  (saga/execute! support/*ds* "tx-comp-proj" "alice" "ghost" 50)
  (projection/process-new-events! support/*ds*)

  ;; Alice refunded
  (is (= 200 (:balance (projection/get-balance support/*ds* "alice"))))

  (let [t (projection/get-transfer support/*ds* "transfer-tx-comp-proj")]
    (is (= "failed" (:status t)))))

;; ——— Projection rebuild includes transfers ———

(deftest projection-rebuild-includes-transfers
  (open-and-fund! "alice" "Alice" 300)
  (open-and-fund! "bob" "Bob" 100)

  (saga/execute! support/*ds* "tx-rebuild" "alice" "bob" 50)
  (projection/process-new-events! support/*ds*)

  ;; Rebuild from scratch
  (projection/rebuild! support/*ds*)

  (is (= 250 (:balance (projection/get-balance support/*ds* "alice"))))
  (is (= 150 (:balance (projection/get-balance support/*ds* "bob"))))
  (is (= "completed" (:status (projection/get-transfer support/*ds*
                                                        "transfer-tx-rebuild")))))

;; ——— Idempotency: re-executing the same transfer is safe ———

(deftest transfer-saga-is-idempotent
  (open-and-fund! "alice" "Alice" 200)
  (open-and-fund! "bob" "Bob" 50)

  ;; Execute twice with same transfer-id
  (saga/execute! support/*ds* "tx-idem" "alice" "bob" 40)
  ;; Second execution — all steps hit idempotency short-circuit
  (saga/execute! support/*ds* "tx-idem" "alice" "bob" 40)

  ;; Funds moved exactly once
  (let [alice-state (decider/evolve-state account/decider
                                          (store/load-stream support/*ds* "alice"))
        bob-state   (decider/evolve-state account/decider
                                          (store/load-stream support/*ds* "bob"))]
    (is (= 160 (:balance alice-state)))
    (is (= 90 (:balance bob-state)))))
