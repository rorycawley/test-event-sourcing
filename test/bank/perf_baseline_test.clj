(ns bank.perf-baseline-test
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]
            [bank.perf-baseline :as perf-baseline]))

(defn- temp-dir []
  (.toFile (java.nio.file.Files/createTempDirectory
            "perf-baseline-test-"
            (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- write-edn!
  [path value]
  (io/make-parents path)
  (spit path (pr-str value)))

(defn- read-edn!
  [path]
  (edn/read-string (slurp path)))

(deftest preserves-existing-threshold-by-default
  (let [dir (temp-dir)
        results-path (str (io/file dir "results.edn"))
        baseline-path (str (io/file dir "baseline.edn"))]
    (write-edn! results-path {:meta {:generated-at "now"}
                              :metrics {:latency {:p50-ms 10.0}}
                              :threshold 0.90})
    (write-edn! baseline-path {:meta {:generated-at "before"}
                               :metrics {:latency {:p50-ms 12.0}}
                               :threshold 0.50})
    (let [baseline (perf-baseline/run-baseline! :results-path results-path
                                                :baseline-path baseline-path)]
      (is (= 0.50 (:threshold baseline)))
      (is (= 0.50 (:threshold (read-edn! baseline-path)))))))

(deftest explicit-threshold-override-wins
  (let [dir (temp-dir)
        results-path (str (io/file dir "results.edn"))
        baseline-path (str (io/file dir "baseline.edn"))]
    (write-edn! results-path {:meta {:generated-at "now"}
                              :metrics {:latency {:p50-ms 10.0}}
                              :threshold 0.90})
    (let [baseline (perf-baseline/run-baseline! :results-path results-path
                                                :baseline-path baseline-path
                                                :baseline-threshold 0.42)]
      (is (= 0.42 (:threshold baseline)))
      (is (= 0.42 (:threshold (read-edn! baseline-path)))))))

(deftest missing-results-file-throws
  (let [dir (temp-dir)
        results-path (str (io/file dir "missing-results.edn"))
        baseline-path (str (io/file dir "baseline.edn"))
        e (try
            (perf-baseline/run-baseline! :results-path results-path
                                         :baseline-path baseline-path)
            nil
            (catch clojure.lang.ExceptionInfo ex
              ex))]
    (is (some? e))
    (is (= "Missing current performance results. Run bb perf first."
           (.getMessage e)))
    (is (= results-path (:results-path (ex-data e))))))
