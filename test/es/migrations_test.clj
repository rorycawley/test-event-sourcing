(ns es.migrations-test
  (:require [clojure.test :refer [deftest is testing]]
            [es.migrations :as migrations]
            [migratus.core]))

(deftest migratus-wrapper-functions-pass-consistent-config
  (let [ds (Object.)]
    (testing "migrate!"
      (let [captured (atom nil)]
        (with-redefs [migratus.core/migrate (fn [config]
                                              (reset! captured config)
                                              :migrated)]
          (is (= :migrated (migrations/migrate! ds)))
          (is (= :database (:store @captured)))
          (is (= "migrations" (:migration-dir @captured)))
          (is (= "schema_migrations" (:migration-table-name @captured)))
          (is (= ds (get-in @captured [:db :datasource]))))))

    (testing "rollback!"
      (let [captured (atom nil)]
        (with-redefs [migratus.core/rollback (fn [config]
                                               (reset! captured config)
                                               :rolled-back)]
          (is (= :rolled-back (migrations/rollback! ds)))
          (is (= ds (get-in @captured [:db :datasource]))))))

    (testing "pending"
      (let [captured (atom nil)]
        (with-redefs [migratus.core/pending-list (fn [config]
                                                   (reset! captured config)
                                                   [{:id 1}])]
          (is (= [{:id 1}] (migrations/pending ds)))
          (is (= ds (get-in @captured [:db :datasource]))))))

    (testing "completed"
      (let [captured (atom nil)]
        (with-redefs [migratus.core/completed-list (fn [config]
                                                     (reset! captured config)
                                                     [{:id 2}])]
          (is (= [{:id 2}] (migrations/completed ds)))
          (is (= ds (get-in @captured [:db :datasource]))))))))
