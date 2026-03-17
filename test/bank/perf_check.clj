(ns bank.perf-check
  "Compares target/perf/results.edn against perf/baseline.edn and fails on regressions."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]
            [clojure.string :as str]))

(def ^:private default-results-path "target/perf/results.edn")
(def ^:private default-baseline-path "perf/baseline.edn")
(def ^:private default-check-summary-path "target/perf/check-summary.md")

(def ^:private metrics-to-check
  [{:name      "decider/handle! p50 latency"
    :path      [:metrics :decider-handle-latency :p50-ms]
    :direction :lower-is-better}
   {:name      "decider/handle! p95 latency"
    :path      [:metrics :decider-handle-latency :p95-ms]
    :direction :lower-is-better}
   {:name      "idempotent replay p50 latency"
    :path      [:metrics :idempotent-replay-latency :p50-ms]
    :direction :lower-is-better}
   {:name      "idempotent replay p95 latency"
    :path      [:metrics :idempotent-replay-latency :p95-ms]
    :direction :lower-is-better}
   {:name      "store/append-events! throughput"
    :path      [:metrics :store-append-throughput :throughput-events-per-sec]
    :direction :higher-is-better}
   {:name      "projection/process-new-events! throughput"
    :path      [:metrics :projection-process-throughput :throughput-events-per-sec]
    :direction :higher-is-better}
   {:name      "projection/rebuild! throughput"
    :path      [:metrics :projection-rebuild-throughput :throughput-events-per-sec]
    :direction :higher-is-better}])

(defn- env-double
  [k default]
  (try
    (Double/parseDouble (or (System/getenv k) ""))
    (catch Throwable _
      default)))

(defn- env-bool
  [k default]
  (if-let [raw (System/getenv k)]
    (contains? #{"1" "true" "yes" "on"} (str/lower-case raw))
    default))

(defn- read-edn-file
  [path]
  (let [f (io/file path)]
    (when (.exists f)
      (edn/read-string (slurp f)))))

(defn- ensure-parent-dir!
  [path]
  (let [f (io/file path)
        parent (.getParentFile f)]
    (when parent
      (.mkdirs parent))))

(defn- pct-delta
  [baseline current]
  (if (zero? (double baseline))
    0.0
    (* 100.0 (/ (- (double current) (double baseline))
                (double baseline)))))

(defn- compare-metric
  [threshold baseline-results current-results {:keys [name path direction]}]
  (let [baseline (get-in baseline-results path)
        current  (get-in current-results path)]
    (if (or (nil? baseline) (nil? current))
      {:name      name
       :baseline  baseline
       :current   current
       :delta-pct nil
       :status    :missing
       :regressed? true}
      (let [delta      (pct-delta baseline current)
            regressed? (case direction
                         :lower-is-better (> current (* baseline (+ 1.0 threshold)))
                         :higher-is-better (< current (* baseline (- 1.0 threshold)))
                         false)]
        {:name       name
         :baseline   baseline
         :current    current
         :delta-pct  delta
         :status     (if regressed? :regressed :ok)
         :regressed? regressed?}))))

(defn- format-value
  [value]
  (if (number? value)
    (format "%.3f" (double value))
    (str value)))

(defn- format-delta
  [value]
  (if (number? value)
    (format "%+.2f%%" (double value))
    "n/a"))

(defn- render-check-summary-markdown
  [threshold rows]
  (str
   "# Performance Regression Check\n\n"
   "Threshold: " (format "%.0f%%" (* 100.0 threshold)) "\n\n"
   "| Metric | Baseline | Current | Delta | Status |\n"
   "| --- | ---: | ---: | ---: | --- |\n"
   (apply str
          (for [{:keys [name baseline current delta-pct status]} rows]
            (str "| " name
                 " | " (format-value baseline)
                 " | " (format-value current)
                 " | " (format-delta delta-pct)
                 " | " (clojure.core/name status) " |\n")))))

(defn- java-major
  [java-version]
  (try
    (some-> java-version
            (str/split #"\.")
            first
            Integer/parseInt)
    (catch Throwable _
      nil)))

(defn- runtime-fingerprint
  [results]
  (let [meta (:meta results)]
    {:os-name    (:os-name meta)
     :os-arch    (:os-arch meta)
     :java-major (java-major (:java-version meta))}))

(defn run-check!
  "Runs the perf regression check.
   Throws ex-info on missing inputs, environment mismatch, or regressions.
   Returns summary data when successful."
  [& {:keys [baseline-path
             results-path
             check-summary-path
             metrics
             threshold
             ignore-env-mismatch?]
      :or {baseline-path      default-baseline-path
           results-path       default-results-path
           check-summary-path default-check-summary-path
           metrics            metrics-to-check}}]
  (let [baseline-results (read-edn-file baseline-path)]
    (when-not baseline-results
      (throw (ex-info "Missing performance baseline file"
                      {:baseline-path baseline-path})))
    (let [current-results (read-edn-file results-path)]
      (when-not current-results
        (throw (ex-info "Missing current performance results. Run bb perf first."
                        {:results-path results-path})))
      (let [ignore-env-mismatch? (if (nil? ignore-env-mismatch?)
                                   (env-bool "PERF_IGNORE_ENV_MISMATCH" false)
                                   ignore-env-mismatch?)
            baseline-fingerprint (runtime-fingerprint baseline-results)
            current-fingerprint  (runtime-fingerprint current-results)
            _ (when (and (not ignore-env-mismatch?)
                         (not= baseline-fingerprint current-fingerprint))
                (throw (ex-info "Baseline/runtime environment mismatch"
                                {:baseline-fingerprint baseline-fingerprint
                                 :current-fingerprint  current-fingerprint
                                 :hint "Use an environment-matching baseline or set PERF_IGNORE_ENV_MISMATCH=true."})))
            threshold (or threshold
                          (some-> baseline-results :threshold double)
                          (env-double "PERF_REGRESSION_THRESHOLD" 0.30))
            rows (mapv #(compare-metric threshold baseline-results current-results %)
                       metrics)
            regressions (filterv :regressed? rows)
            summary (render-check-summary-markdown threshold rows)]
        (ensure-parent-dir! check-summary-path)
        (spit check-summary-path summary)
        (println "Wrote performance check summary:" check-summary-path)
        (if (seq regressions)
          (do
            (println "Performance regressions detected:")
            (doseq [{:keys [name baseline current delta-pct]} regressions]
              (println " -"
                       name
                       "baseline=" (format-value baseline)
                       "current=" (format-value current)
                       "delta=" (format-delta delta-pct)))
            (throw (ex-info "Performance regression check failed"
                            {:threshold   threshold
                             :regressions regressions})))
          (do
            (println "Performance regression check passed.")
            {:threshold         threshold
             :rows              rows
             :regressions       regressions
             :check-summary-path check-summary-path}))))))

(defn -main
  [& _]
  (run-check!))
