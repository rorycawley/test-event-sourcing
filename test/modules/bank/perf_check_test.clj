(ns modules.bank.perf-check-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [modules.bank.perf-check :as perf-check]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "perf-check-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-edn!
  [path value]
  (io/make-parents path)
  (spit path (pr-str value)))

(def ^:private single-metric
  [{:name      "latency p50"
    :path      [:metrics :latency :p50-ms]
    :direction :lower-is-better}])

(def ^:private base-meta
  {:os-name "TestOS"
   :os-arch "x86_64"
   :java-version "21.0.2"})

(deftest missing-metric-fails-check
  (let [dir (temp-dir)
        baseline-path (str (io/file dir "baseline.edn"))
        results-path (str (io/file dir "results.edn"))
        summary-path (str (io/file dir "check-summary.md"))]
    (write-edn! baseline-path {:meta base-meta
                               :threshold 0.30
                               :metrics {:latency {:p50-ms 10.0}}})
    (write-edn! results-path {:meta base-meta
                              :metrics {}})
    (let [e (try
              (perf-check/run-check! :baseline-path baseline-path
                                     :results-path results-path
                                     :check-summary-path summary-path
                                     :metrics single-metric)
              nil
              (catch clojure.lang.ExceptionInfo ex
                ex))
          regressions (:regressions (ex-data e))]
      (is (some? e))
      (is (= "Performance regression check failed" (.getMessage e)))
      (is (= :missing (-> regressions first :status)))
      (is (true? (-> regressions first :regressed?)))
      (is (.exists (io/file summary-path))))))

(deftest env-mismatch-fails-by-default
  (let [dir (temp-dir)
        baseline-path (str (io/file dir "baseline.edn"))
        results-path (str (io/file dir "results.edn"))
        summary-path (str (io/file dir "check-summary.md"))]
    (write-edn! baseline-path {:meta (assoc base-meta :os-name "OS-A")
                               :metrics {:latency {:p50-ms 10.0}}})
    (write-edn! results-path {:meta (assoc base-meta :os-name "OS-B")
                              :metrics {:latency {:p50-ms 10.0}}})
    (let [e (try
              (perf-check/run-check! :baseline-path baseline-path
                                     :results-path results-path
                                     :check-summary-path summary-path
                                     :metrics single-metric)
              nil
              (catch clojure.lang.ExceptionInfo ex
                ex))]
      (is (some? e))
      (is (= "Baseline/runtime environment mismatch" (.getMessage e)))
      (is (= "OS-A" (get-in (ex-data e) [:baseline-fingerprint :os-name])))
      (is (= "OS-B" (get-in (ex-data e) [:current-fingerprint :os-name]))))))

(deftest env-mismatch-can-be-ignored-explicitly
  (let [dir (temp-dir)
        baseline-path (str (io/file dir "baseline.edn"))
        results-path (str (io/file dir "results.edn"))
        summary-path (str (io/file dir "check-summary.md"))]
    (write-edn! baseline-path {:meta (assoc base-meta :os-name "OS-A")
                               :threshold 0.30
                               :metrics {:latency {:p50-ms 10.0}}})
    (write-edn! results-path {:meta (assoc base-meta :os-name "OS-B")
                              :metrics {:latency {:p50-ms 9.0}}})
    (let [result (perf-check/run-check! :baseline-path baseline-path
                                        :results-path results-path
                                        :check-summary-path summary-path
                                        :metrics single-metric
                                        :ignore-env-mismatch? true)]
      (is (= [] (:regressions result)))
      (is (.exists (io/file summary-path))))))
