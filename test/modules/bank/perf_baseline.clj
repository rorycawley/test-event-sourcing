(ns modules.bank.perf-baseline
  "Copies the latest target/perf/results.edn to perf/baseline.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private default-results-path "target/perf/results.edn")
(def ^:private default-baseline-path "perf/baseline.edn")

(defn- env-double
  [k default]
  (try
    (Double/parseDouble (or (System/getenv k) ""))
    (catch Throwable _
      default)))

(defn- ensure-parent-dir!
  [path]
  (let [f (io/file path)
        parent (.getParentFile f)]
    (when parent
      (.mkdirs parent))))

(defn run-baseline!
  "Writes baseline file from current perf results and returns the baseline map."
  [& {:keys [results-path baseline-path baseline-threshold]
      :or {results-path  default-results-path
           baseline-path default-baseline-path}}]
  (let [results-file  (io/file results-path)
        baseline-file (io/file baseline-path)]
    (when-not (.exists results-file)
      (throw (ex-info "Missing current performance results. Run bb perf first."
                      {:results-path results-path})))
    (let [results   (edn/read-string (slurp results-file))
          existing  (when (.exists baseline-file)
                      (edn/read-string (slurp baseline-file)))
          threshold (or baseline-threshold
                        (some-> (System/getenv "PERF_BASELINE_THRESHOLD") Double/parseDouble)
                        (:threshold existing)
                        (:threshold results)
                        (env-double "PERF_REGRESSION_THRESHOLD" 0.30))
          baseline  (assoc results :threshold threshold)]
      (ensure-parent-dir! baseline-path)
      (spit baseline-path (pr-str baseline))
      (println "Wrote baseline file:" baseline-path)
      baseline)))

(defn -main
  [& _]
  (run-baseline!))
