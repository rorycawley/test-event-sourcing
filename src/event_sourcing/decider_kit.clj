(ns event-sourcing.decider-kit
  "Data-driven toolkit for building Deciders.

   Provide your schemas and upcasters as data; get back validated
   command/event functions. No macros — just functions that return
   functions.

   Usage:
     (def validate-command! (kit/make-command-validator command-data-specs))
     (def upcast-event      (kit/make-event-upcaster latest-versions upcasters))
     (def validate-event!   (kit/make-event-validator event-schemas upcast-event))
     (def mk-event          (kit/make-event-factory latest-versions validate-event!))
     (def decide            (kit/make-decide validate-command! validate-event! decisions))"
  (:require [malli.core :as m]))

;; ═══════════════════════════════════════════════════
;; Private helpers
;; ═══════════════════════════════════════════════════

(def ^:private command-schema
  [:map
   [:command-type keyword?]
   [:data map?]])

(defn- invalid-command!
  [command reason explain]
  (throw (ex-info "Invalid command"
                  {:error/type :domain/invalid-command
                   :reason     reason
                   :command    command
                   :explain    explain})))

(defn- invalid-event!
  [event reason explain]
  (throw (ex-info "Invalid event"
                  {:error/type :domain/invalid-event
                   :reason     reason
                   :event      event
                   :explain    explain})))

;; ═══════════════════════════════════════════════════
;; Factory functions
;; ═══════════════════════════════════════════════════

(defn make-command-validator
  "Returns a function that validates a command envelope and its data payload.
   command-data-specs: {keyword? -> malli-schema}"
  [command-data-specs]
  (fn [{:keys [command-type data] :as command}]
    (when-not (m/validate command-schema command)
      (invalid-command! command :invalid-shape (m/explain command-schema command)))
    (let [data-spec (or (get command-data-specs command-type)
                        (throw (ex-info "Unknown command"
                                        {:error/type   :domain/unknown-command
                                         :command-type command-type})))]
      (when-not (m/validate data-spec data)
        (invalid-command! command :invalid-data (m/explain data-spec data))))))

(defn make-event-upcaster
  "Returns a function that normalises a possibly-legacy event to the
   latest known schema version.
   latest-event-version: {\"event-type\" -> int}
   event-upcasters:      {[\"event-type\" from-version] -> (fn [event] event')}"
  [latest-event-version event-upcasters]
  (fn [event]
    (let [event       (update event :event-version #(or % 1))
          event-type  (:event-type event)
          version     (:event-version event)
          latest-ver  (get latest-event-version event-type)]
      (when-not latest-ver
        (invalid-event! event :unknown-event-type nil))
      (when-not (pos-int? version)
        (invalid-event! event :invalid-version nil))
      (when (> version latest-ver)
        (invalid-event! event :unsupported-future-version nil))
      (loop [{:keys [event-type event-version] :as current} event]
        (if (= event-version latest-ver)
          current
          (let [upcaster (get event-upcasters [event-type event-version])]
            (when-not upcaster
              (invalid-event! current :missing-upcaster nil))
            (recur (upcaster current))))))))

(defn make-event-validator
  "Returns a function that upcasts + validates one domain event map.
   event-schemas: {[\"event-type\" version] -> malli-schema}
   upcast-fn:     output of make-event-upcaster"
  [event-schemas upcast-fn]
  (fn [event]
    (let [event  (upcast-fn event)
          schema (get event-schemas [(:event-type event) (:event-version event)])]
      (when-not schema
        (invalid-event! event :missing-schema nil))
      (when-not (m/validate schema event)
        (invalid-event! event :invalid-shape (m/explain schema event)))
      event)))

(defn make-event-factory
  "Returns a function (event-type, payload) -> validated event map.
   latest-event-version: {\"event-type\" -> int}
   validate-fn:          output of make-event-validator"
  [latest-event-version validate-fn]
  (fn [event-type payload]
    (validate-fn {:event-type    event-type
                  :event-version (get latest-event-version event-type)
                  :payload       payload})))

(defn make-decide
  "Returns a decide function: Command -> State -> [Event].
   validate-command-fn: output of make-command-validator
   validate-event-fn:   output of make-event-validator
   decisions:           {keyword? -> (fn [state data] [event ...])}"
  [validate-command-fn validate-event-fn decisions]
  (fn [{:keys [command-type data] :as command} state]
    (validate-command-fn command)
    (let [decision (or (get decisions command-type)
                       (throw (ex-info "Unknown command"
                                       {:error/type   :domain/unknown-command
                                        :command-type command-type})))]
      (mapv validate-event-fn (decision state data)))))
