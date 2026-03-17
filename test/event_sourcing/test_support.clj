(ns event-sourcing.test-support
  (:require [event-sourcing.infra :as infra]
            [event-sourcing.migrations :as migrations]
            [next.jdbc :as jdbc]
            [next.jdbc.result-set :as rs]))

(def ^:dynamic *ds* nil)

(defn with-system [f]
  (let [pg (infra/start-postgres!)
        ds (infra/->datasource pg)]
    (try
      (migrations/migrate! ds)
      (binding [*ds* ds]
        (f))
      (finally
        (infra/stop-postgres! pg)))))

(defn reset-db! []
  (jdbc/with-transaction [tx *ds*]
    ;; RESTART IDENTITY keeps test expectations deterministic for
    ;; global_sequence-based assertions.
    (jdbc/execute-one! tx
                       ["TRUNCATE TABLE account_balances,
                                        transfer_status,
                                        projection_checkpoints,
                                        events,
                                        idempotency_keys
                         RESTART IDENTITY"])))

(defn with-clean-db [f]
  (reset-db!)
  (f))

(defn checkpoint []
  (or (-> (jdbc/execute-one! *ds*
                             ["SELECT last_global_sequence
                               FROM projection_checkpoints
                              WHERE projection_name = ?"
                              "main"]
                             {:builder-fn rs/as-unqualified-kebab-maps})
          :last-global-sequence)
      0))
