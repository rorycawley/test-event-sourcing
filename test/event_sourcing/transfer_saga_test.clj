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

;; ═══════════════════════════════════════════════════
;; Resume — crash recovery
;; ═══════════════════════════════════════════════════
;;
;; These tests simulate mid-saga crashes by writing transfer
;; events directly to the store (bypassing the saga), then
;; calling resume! to continue from the last persisted step.

(defn- write-transfer-events!
  "Writes raw transfer events to a stream, simulating partial saga progress."
  [stream-id events]
  (store/append-events! support/*ds*
                        stream-id
                        0
                        (str "seed-" stream-id)
                        {:command-type :seed :data {:case :resume-test}}
                        events))

(deftest resume-from-initiated-completes-transfer
  ;; Simulate: saga wrote transfer-initiated, then process crashed
  ;; before debiting the source account.
  (open-and-fund! "alice" "Alice" 200)
  (open-and-fund! "bob" "Bob" 50)

  (write-transfer-events!
   "transfer-tx-resume-1"
   [{:event-type    "transfer-initiated"
     :event-version 1
     :payload       {:from-account "alice"
                     :to-account   "bob"
                     :amount       60}}])

  ;; Resume picks up from :initiated and finishes the transfer
  (let [result (saga/resume! support/*ds* "tx-resume-1")]
    (is (= :completed (:status result))))

  ;; Balances correct
  (let [alice (decider/evolve-state account/decider
                                    (store/load-stream support/*ds* "alice"))
        bob   (decider/evolve-state account/decider
                                    (store/load-stream support/*ds* "bob"))]
    (is (= 140 (:balance alice)))
    (is (= 110 (:balance bob))))

  ;; Transfer stream has all 4 events
  (is (= ["transfer-initiated" "debit-recorded"
          "credit-recorded" "transfer-completed"]
         (mapv :event-type
               (store/load-stream support/*ds* "transfer-tx-resume-1")))))

(deftest resume-from-debited-completes-transfer
  ;; Simulate: debit succeeded and was recorded on transfer stream,
  ;; but process crashed before crediting the destination.
  ;; This is the dangerous case — money has left Alice's account.
  (open-and-fund! "alice" "Alice" 200)
  (open-and-fund! "bob" "Bob" 50)

  ;; Manually debit Alice (as the saga would have)
  (decider/handle! support/*ds* account/decider
                   {:command-type    :withdraw
                    :stream-id       "alice"
                    :idempotency-key "transfer-tx-resume-2-debit"
                    :data            {:amount 80}})

  (write-transfer-events!
   "transfer-tx-resume-2"
   [{:event-type    "transfer-initiated"
     :event-version 1
     :payload       {:from-account "alice"
                     :to-account   "bob"
                     :amount       80}}])
  ;; Advance stream to include debit-recorded
  (store/append-events! support/*ds*
                        "transfer-tx-resume-2"
                        1
                        "seed-transfer-tx-resume-2-debit"
                        {:command-type :seed :data {:case :resume-debit}}
                        [{:event-type    "debit-recorded"
                          :event-version 1
                          :payload       {:account-id "alice"
                                          :amount     80}}])

  ;; Resume picks up from :debited — credits Bob and completes
  (let [result (saga/resume! support/*ds* "tx-resume-2")]
    (is (= :completed (:status result))))

  (let [alice (decider/evolve-state account/decider
                                    (store/load-stream support/*ds* "alice"))
        bob   (decider/evolve-state account/decider
                                    (store/load-stream support/*ds* "bob"))]
    (is (= 120 (:balance alice)))
    (is (= 130 (:balance bob)))))

(deftest resume-from-credited-completes-transfer
  ;; Simulate: both accounts updated, credit-recorded written,
  ;; but process crashed before writing transfer-completed.
  (open-and-fund! "alice" "Alice" 200)
  (open-and-fund! "bob" "Bob" 50)

  ;; Manually perform the account operations
  (decider/handle! support/*ds* account/decider
                   {:command-type    :withdraw
                    :stream-id       "alice"
                    :idempotency-key "transfer-tx-resume-3-debit"
                    :data            {:amount 30}})
  (decider/handle! support/*ds* account/decider
                   {:command-type    :deposit
                    :stream-id       "bob"
                    :idempotency-key "transfer-tx-resume-3-credit"
                    :data            {:amount 30}})

  (write-transfer-events!
   "transfer-tx-resume-3"
   [{:event-type    "transfer-initiated"
     :event-version 1
     :payload       {:from-account "alice"
                     :to-account   "bob"
                     :amount       30}}])
  (store/append-events! support/*ds* "transfer-tx-resume-3" 1
                        "seed-tx-resume-3-debit"
                        {:command-type :seed :data {}}
                        [{:event-type    "debit-recorded"
                          :event-version 1
                          :payload       {:account-id "alice" :amount 30}}])
  (store/append-events! support/*ds* "transfer-tx-resume-3" 2
                        "seed-tx-resume-3-credit"
                        {:command-type :seed :data {}}
                        [{:event-type    "credit-recorded"
                          :event-version 1
                          :payload       {:account-id "bob" :amount 30}}])

  ;; Resume picks up from :credited — just marks complete
  (let [result (saga/resume! support/*ds* "tx-resume-3")]
    (is (= :completed (:status result))))

  (is (= ["transfer-initiated" "debit-recorded"
          "credit-recorded" "transfer-completed"]
         (mapv :event-type
               (store/load-stream support/*ds* "transfer-tx-resume-3")))))

(deftest resume-completed-transfer-returns-already-completed
  (open-and-fund! "alice" "Alice" 200)
  (open-and-fund! "bob" "Bob" 50)

  (saga/execute! support/*ds* "tx-resume-done" "alice" "bob" 25)

  (let [result (saga/resume! support/*ds* "tx-resume-done")]
    (is (= :already-completed (:status result)))))

(deftest resume-failed-transfer-returns-already-failed
  (open-and-fund! "alice" "Alice" 10)
  (open-and-fund! "bob" "Bob" 0)

  (saga/execute! support/*ds* "tx-resume-fail" "alice" "bob" 999)

  (let [result (saga/resume! support/*ds* "tx-resume-fail")]
    (is (= :already-failed (:status result)))
    (is (= "insufficient-funds" (:reason result)))))

(deftest resume-nonexistent-transfer-throws
  (let [e (try
            (saga/resume! support/*ds* "no-such-transfer")
            nil
            (catch clojure.lang.ExceptionInfo ex ex))]
    (is (some? e))
    (is (= :saga/transfer-not-found (:error/type (ex-data e))))))
