(ns es.component-test
  "Tests for es.component — Component lifecycle management."
  (:require [clojure.test :refer [deftest is]]
            [com.stuartsierra.component :as component]
            [bank.components :as bank-components]
            [next.jdbc :as jdbc]))

(deftest dev-system-starts-and-stops
  (let [system (component/start (bank-components/dev-system))]
    (try
      (let [ds (get-in system [:datasource :datasource])]
        (is (some? ds) "Datasource should be available after start")
        (is (true? (get-in system [:migrator :migrated]))
            "Migrator should have run")
        (let [result (jdbc/execute-one! ds ["SELECT 1 AS n"])]
          (is (= 1 (:n result)) "Should be able to query the database")))
      (finally
        (component/stop system)))))

(deftest system-stop-cleans-up
  (let [system  (component/start (bank-components/dev-system))
        stopped (component/stop system)]
    (is (nil? (get-in stopped [:datasource :datasource]))
        "Datasource should be nil after stop")
    (is (nil? (get-in stopped [:datasource :container]))
        "Container should be nil after stop")))
