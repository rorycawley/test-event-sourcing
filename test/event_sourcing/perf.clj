(ns event-sourcing.perf
  "Deterministic performance benchmarks for core write/projection paths.
   Results are written to target/perf/results.edn and target/perf/summary.md."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [event-sourcing.account :as account]
            [event-sourcing.decider :as decider]
            [event-sourcing.infra :as infra]
            [event-sourcing.projection :as projection]
            [event-sourcing.store :as store]
            [next.jdbc :as jdbc])
  (:import [java.lang.management ManagementFactory]
           [java.time Instant]))

(def ^:private results-path "target/perf/results.edn")
(def ^:private summary-path "target/perf/summary.md")

(defn- env-int
  [k default]
  (try
    (Integer/parseInt (or (System/getenv k) ""))
    (catch Throwable _
      default)))

(defn- git-sha []
  (or (System/getenv "GITHUB_SHA")
      (try
        (let [proc (.exec (Runtime/getRuntime)
                          (into-array String ["git" "rev-parse" "--short" "HEAD"]))]
          (.waitFor proc)
          (when (zero? (.exitValue proc))
            (str/trim (slurp (.getInputStream proc)))))
        (catch Throwable _
          nil))))

(defn- ensure-parent-dir!
  [path]
  (let [f (io/file path)
        parent (.getParentFile f)]
    (when parent
      (.mkdirs parent))))

(defn- reset-db!
  [ds]
  (jdbc/with-transaction [tx ds]
    (jdbc/execute-one! tx ["DELETE FROM account_balances"])
    (jdbc/execute-one! tx ["DELETE FROM projection_checkpoints"])
    (jdbc/execute-one! tx ["DELETE FROM events"])
    (jdbc/execute-one! tx ["DELETE FROM idempotency_keys"])))

(defn- ns->ms [value]
  (/ (double value) 1000000.0))

(defn- ns->sec [value]
  (/ (double value) 1000000000.0))

(defn- percentile-ns
  [sorted-values p]
  (let [n (count sorted-values)]
    (when (pos? n)
      (let [idx (-> (* p n)
                    Math/ceil
                    long
                    dec
                    (max 0)
                    (min (dec n)))]
        (nth sorted-values idx)))))

(defn- latency-stats
  [durations]
  (let [sorted-values (vec (sort durations))
        sample-count  (count sorted-values)
        total-ns      (reduce + 0 sorted-values)]
    {:samples sample-count
     :p50-ms  (ns->ms (percentile-ns sorted-values 0.50))
     :p95-ms  (ns->ms (percentile-ns sorted-values 0.95))
     :avg-ms  (ns->ms (/ (double total-ns) (max 1 sample-count)))}))

(defn- measure-latencies
  [iterations f]
  (mapv (fn [idx]
          (let [start (System/nanoTime)]
            (f idx)
            (- (System/nanoTime) start)))
        (range iterations)))

(defn- expect!
  [expected actual context]
  (when-not (= expected actual)
    (throw (ex-info "Unexpected benchmark result"
                    {:expected expected
                     :actual   actual
                     :context  context}))))

(defn- benchmark-handle-latency!
  [ds {:keys [handle-warmup handle-samples]}]
  (reset-db! ds)
  (let [stream-id "perf-handle-stream"]
    (expect! :ok
             (decider/handle! ds account/decider
                              {:command-type    :open-account
                               :stream-id       stream-id
                               :idempotency-key "perf-handle-open"
                               :data            {:owner "Perf"}})
             :open-account)
    (doseq [i (range handle-warmup)]
      (expect! :ok
               (decider/handle! ds account/decider
                                {:command-type    :deposit
                                 :stream-id       stream-id
                                 :idempotency-key (str "perf-handle-warm-" i)
                                 :data            {:amount 1}})
               :warmup-deposit))
    (latency-stats
     (measure-latencies
      handle-samples
      (fn [i]
        (expect! :ok
                 (decider/handle! ds account/decider
                                  {:command-type    :deposit
                                   :stream-id       stream-id
                                   :idempotency-key (str "perf-handle-sample-" i)
                                   :data            {:amount 1}})
                 :sample-deposit))))))

(defn- benchmark-idempotent-replay-latency!
  [ds {:keys [idempotent-samples]}]
  (reset-db! ds)
  (let [stream-id "perf-idempotent-stream"
        command   {:command-type    :deposit
                   :stream-id       stream-id
                   :idempotency-key "perf-idempotent-key"
                   :data            {:amount 10}}]
    (expect! :ok
             (decider/handle! ds account/decider
                              {:command-type    :open-account
                               :stream-id       stream-id
                               :idempotency-key "perf-idempotent-open"
                               :data            {:owner "Perf"}})
             :open-account)
    (expect! :ok (decider/handle! ds account/decider command) :first-call)
    (latency-stats
     (measure-latencies
      idempotent-samples
      (fn [_]
        (expect! :idempotent
                 (decider/handle! ds account/decider command)
                 :idempotent-replay))))))

(defn- benchmark-store-append-throughput!
  [ds {:keys [store-events]}]
  (reset-db! ds)
  (let [start (System/nanoTime)]
    (doseq [i (range store-events)]
      (expect! :ok
               (store/append-events! ds
                                     (str "perf-store-stream-" i)
                                     0
                                     nil
                                     {:command-type :seed
                                      :data         {:value i}}
                                     [{:event-type "seeded"
                                       :payload    {:value i}}])
               :store-append))
    (let [duration-ns (- (System/nanoTime) start)]
      {:events                    store-events
       :duration-ms               (ns->ms duration-ns)
       :throughput-events-per-sec (/ store-events (ns->sec duration-ns))})))

(defn- seed-projection-workload!
  [ds account-count deposits-per-account]
  (doseq [acct (range account-count)]
    (let [stream-id (str "perf-projection-stream-" acct)]
      (expect! :ok
               (store/append-events! ds
                                     stream-id
                                     0
                                     nil
                                     {:command-type :open-account
                                      :data         {:owner "Perf"}}
                                     [{:event-type    "account-opened"
                                       :event-version 1
                                       :payload       {:owner "Perf"}}])
               :projection-open)
      (loop [version 1
             deposit-idx 0]
        (when (< deposit-idx deposits-per-account)
          (expect! :ok
                   (store/append-events! ds
                                         stream-id
                                         version
                                         nil
                                         {:command-type :deposit
                                          :data         {:amount 1}}
                                         [{:event-type    "money-deposited"
                                           :event-version 3
                                           :payload       {:amount   1
                                                           :origin   "perf"
                                                           :currency "USD"}}])
                   :projection-deposit)
          (recur (inc version) (inc deposit-idx))))))
  (* account-count (inc deposits-per-account)))

(defn- benchmark-projection-process-throughput!
  [ds {:keys [projection-accounts projection-deposits-per-account]}]
  (reset-db! ds)
  (let [total-events (seed-projection-workload! ds
                                                projection-accounts
                                                projection-deposits-per-account)
        start        (System/nanoTime)
        processed    (projection/process-new-events! ds)
        duration-ns  (- (System/nanoTime) start)]
    (expect! total-events processed :projection-process)
    {:events                    processed
     :duration-ms               (ns->ms duration-ns)
     :throughput-events-per-sec (/ processed (ns->sec duration-ns))}))

(defn- benchmark-projection-rebuild-throughput!
  [ds {:keys [projection-accounts projection-deposits-per-account]}]
  (reset-db! ds)
  (let [total-events (seed-projection-workload! ds
                                                projection-accounts
                                                projection-deposits-per-account)]
    (projection/process-new-events! ds)
    (let [start       (System/nanoTime)
          rebuilt     (projection/rebuild! ds)
          duration-ns (- (System/nanoTime) start)]
      (expect! total-events rebuilt :projection-rebuild)
      {:events                    rebuilt
       :duration-ms               (ns->ms duration-ns)
       :throughput-events-per-sec (/ rebuilt (ns->sec duration-ns))})))

(defn- format-ms [value]
  (format "%.3f" (double value)))

(defn- format-eps [value]
  (format "%.1f" (double value)))

(defn- render-summary-markdown
  [{:keys [meta config metrics]}]
  (str
   "# Performance Summary\n\n"
   "Generated: " (:generated-at meta) "\n\n"
   (when-let [sha (:git-sha meta)]
     (str "Git SHA: `" sha "`\n\n"))
   "## Config\n\n"
   "| Key | Value |\n"
   "| --- | --- |\n"
   "| handle-samples | " (:handle-samples config) " |\n"
   "| idempotent-samples | " (:idempotent-samples config) " |\n"
   "| store-events | " (:store-events config) " |\n"
   "| projection-accounts | " (:projection-accounts config) " |\n"
   "| projection-deposits-per-account | " (:projection-deposits-per-account config) " |\n\n"
   "## Metrics\n\n"
   "| Metric | Value |\n"
   "| --- | --- |\n"
   "| decider/handle! p50 latency | " (format-ms (get-in metrics [:decider-handle-latency :p50-ms])) " ms |\n"
   "| decider/handle! p95 latency | " (format-ms (get-in metrics [:decider-handle-latency :p95-ms])) " ms |\n"
   "| idempotent replay p50 latency | " (format-ms (get-in metrics [:idempotent-replay-latency :p50-ms])) " ms |\n"
   "| idempotent replay p95 latency | " (format-ms (get-in metrics [:idempotent-replay-latency :p95-ms])) " ms |\n"
   "| store/append-events! throughput | " (format-eps (get-in metrics [:store-append-throughput :throughput-events-per-sec])) " events/s |\n"
   "| projection/process-new-events! throughput | " (format-eps (get-in metrics [:projection-process-throughput :throughput-events-per-sec])) " events/s |\n"
   "| projection/rebuild! throughput | " (format-eps (get-in metrics [:projection-rebuild-throughput :throughput-events-per-sec])) " events/s |\n"))

(defn- build-config []
  {:handle-warmup                  (env-int "PERF_HANDLE_WARMUP" 30)
   :handle-samples                 (env-int "PERF_HANDLE_SAMPLES" 200)
   :idempotent-samples             (env-int "PERF_IDEMPOTENT_SAMPLES" 200)
   :store-events                   (env-int "PERF_STORE_EVENTS" 1500)
   :projection-accounts            (env-int "PERF_PROJECTION_ACCOUNTS" 150)
   :projection-deposits-per-account (env-int "PERF_PROJECTION_DEPOSITS_PER_ACCOUNT" 10)})

(defn run-benchmarks!
  []
  (let [config (build-config)
        pg     (infra/start-postgres!)
        ds     (infra/->datasource pg)]
    (try
      (store/create-schema! ds)
      {:meta
       {:generated-at (str (Instant/now))
        :git-sha      (git-sha)
        :java-version (System/getProperty "java.version")
        :os-name      (System/getProperty "os.name")
        :os-arch      (System/getProperty "os.arch")
        :jvm-name     (.getName (ManagementFactory/getRuntimeMXBean))}
       :config config
       :metrics
       {:decider-handle-latency      (benchmark-handle-latency! ds config)
        :idempotent-replay-latency   (benchmark-idempotent-replay-latency! ds config)
        :store-append-throughput     (benchmark-store-append-throughput! ds config)
        :projection-process-throughput (benchmark-projection-process-throughput! ds config)
        :projection-rebuild-throughput (benchmark-projection-rebuild-throughput! ds config)}}
      (finally
        (infra/stop-postgres! pg)))))

(defn -main
  [& _]
  (let [results (run-benchmarks!)
        summary (render-summary-markdown results)]
    (ensure-parent-dir! results-path)
    (ensure-parent-dir! summary-path)
    (spit results-path (pr-str results))
    (spit summary-path summary)
    (println "Wrote performance results:" results-path)
    (println "Wrote performance summary:" summary-path)))
