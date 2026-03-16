(ns event-sourcing.migrations-cli-test
  (:require [clojure.test :refer [deftest is]]
            [event-sourcing.migrations]
            [event-sourcing.migrations-cli :as migrations-cli]
            [next.jdbc]))

(deftest migrate-command-runs-migrate-and-status
  (let [calls (atom [])]
    (with-redefs [event-sourcing.migrations-cli/datasource-from-env! (fn []
                                                                       :ds)
                  event-sourcing.migrations/migrate! (fn [ds]
                                                       (swap! calls conj [:migrate ds]))
                  event-sourcing.migrations-cli/status! (fn [ds]
                                                          (swap! calls conj [:status ds])
                                                          :status)]
      (is (= :status (migrations-cli/-main "migrate")))
      (is (= [[:migrate :ds] [:status :ds]] @calls)))))

(deftest rollback-command-runs-rollback-and-status
  (let [calls (atom [])]
    (with-redefs [event-sourcing.migrations-cli/datasource-from-env! (fn []
                                                                       :ds)
                  event-sourcing.migrations/rollback! (fn [ds]
                                                        (swap! calls conj [:rollback ds]))
                  event-sourcing.migrations-cli/status! (fn [ds]
                                                          (swap! calls conj [:status ds])
                                                          :status)]
      (is (= :status (migrations-cli/-main "rollback")))
      (is (= [[:rollback :ds] [:status :ds]] @calls)))))

(deftest status-command-runs-status
  (let [calls (atom [])]
    (with-redefs [event-sourcing.migrations-cli/datasource-from-env! (fn []
                                                                       :ds)
                  event-sourcing.migrations-cli/status! (fn [ds]
                                                          (swap! calls conj [:status ds])
                                                          :status)]
      (is (= :status (migrations-cli/-main "status")))
      (is (= [[:status :ds]] @calls)))))

(deftest missing-jdbc-url-throws-structured-error
  (let [e (try
            (#'event-sourcing.migrations-cli/datasource-from-env!)
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Missing JDBC_URL environment variable" (.getMessage e)))
    (is (= ["JDBC_URL"] (:required-env (ex-data e))))))

(deftest datasource-from-env-builds-datasource-with-optional-creds
  (let [captured (atom nil)]
    (with-redefs [event-sourcing.migrations-cli/env-non-empty
                  (fn [k]
                    (case k
                      "JDBC_URL" "jdbc:postgresql://example/db"
                      "DB_USER" "alice"
                      "DB_PASSWORD" "secret"
                      nil))
                  next.jdbc/get-datasource
                  (fn [opts]
                    (reset! captured opts)
                    :ds)]
      (is (= :ds (#'event-sourcing.migrations-cli/datasource-from-env!)))
      (is (= {:jdbcUrl "jdbc:postgresql://example/db"
              :user "alice"
              :password "secret"}
             @captured)))))

(deftest datasource-from-env-omits-empty-optional-creds
  (let [captured (atom nil)]
    (with-redefs [event-sourcing.migrations-cli/env-non-empty
                  (fn [k]
                    (case k
                      "JDBC_URL" "jdbc:postgresql://example/db"
                      nil))
                  next.jdbc/get-datasource
                  (fn [opts]
                    (reset! captured opts)
                    :ds)]
      (is (= :ds (#'event-sourcing.migrations-cli/datasource-from-env!)))
      (is (= {:jdbcUrl "jdbc:postgresql://example/db"}
             @captured)))))

(deftest default-main-command-is-status
  (let [calls (atom [])]
    (with-redefs [event-sourcing.migrations-cli/datasource-from-env! (fn []
                                                                       :ds)
                  event-sourcing.migrations-cli/status! (fn [ds]
                                                          (swap! calls conj [:status ds])
                                                          :status)]
      (is (= :status (migrations-cli/-main)))
      (is (= [[:status :ds]] @calls)))))

(deftest unknown-command-prints-usage-and-exits-nonzero
  (let [exit-code (atom nil)]
    (with-redefs [event-sourcing.migrations-cli/datasource-from-env! (fn []
                                                                       (throw (ex-info "datasource should not be created" {})))
                  event-sourcing.migrations-cli/status! (fn [_]
                                                          (throw (ex-info "status should not run" {})))
                  event-sourcing.migrations-cli/*exit-fn* (fn [code]
                                                            (reset! exit-code code)
                                                            :exited)]
      (is (= :exited (migrations-cli/-main "wat")))
      (is (= 1 @exit-code)))))
