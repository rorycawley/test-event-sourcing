(ns modules.notification.domain.delivery
  "Notification aggregate — tracks notification lifecycle.

   The aggregate enforces business rules and state transitions. It does
   NOT send emails — that is the job of a separate worker (imperative
   shell). The aggregate is the functional core: pure, testable, no I/O.

   Commands:  :request-notification, :mark-sent, :mark-failed
   Events:    notification-requested, notification-sent,
              notification-failed, notification-abandoned

   State:     {:status :not-found|:pending|:sent|:failed
               :notification-type, :recipient-id, :payload, :retry-count}

   Lifecycle (state machine):
     :not-found → :request-notification → :pending
     :pending   → :mark-sent            → :sent       (terminal)
     :pending   → :mark-failed          → :pending    (retry, attempts < 3)
     :pending   → :mark-failed          → :failed     (abandoned, attempts >= 3)

   Once :sent or :failed, the notification is immutable — no further
   commands are accepted."
  (:require [es.decider-kit :as kit]
            [es.schema :as schema]))

(def ^:private max-delivery-attempts
  "Maximum delivery attempts before abandoning a notification."
  3)

;; ═══════════════════════════════════════════════════
;; Command schemas
;; ═══════════════════════════════════════════════════

(def ^:private request-data-schema
  [:map
   [:notification-type schema/non-empty-string]
   [:recipient-id      schema/non-empty-string]
   [:payload           map?]])

(def ^:private mark-sent-data-schema
  [:map])

(def ^:private mark-failed-data-schema
  [:map
   [:reason schema/non-empty-string]])

(def ^:private command-data-specs
  {:request-notification request-data-schema
   :mark-sent            mark-sent-data-schema
   :mark-failed          mark-failed-data-schema})

;; ═══════════════════════════════════════════════════
;; Event schemas (versioned)
;; ═══════════════════════════════════════════════════

(def ^:private latest-event-version
  {"notification-requested" 1
   "notification-sent"      1
   "notification-failed"    1
   "notification-abandoned" 1})

(def ^:private event-schemas
  {["notification-requested" 1] request-data-schema
   ["notification-sent" 1]      mark-sent-data-schema
   ["notification-failed" 1]    mark-failed-data-schema
   ["notification-abandoned" 1] mark-failed-data-schema})

(def ^:private event-upcasters {})

;; ═══════════════════════════════════════════════════
;; Kit-derived functions
;; ═══════════════════════════════════════════════════

(def ^:private validate-command! (kit/make-command-validator command-data-specs))

(def upcast-event
  "Normalises a possibly-legacy event to the latest known schema version."
  (kit/make-event-upcaster latest-event-version event-upcasters))

(def validate-event!
  "Upcasts + validates one notification domain event map.
   Returns the validated/upcasted event or throws ex-info."
  (kit/make-event-validator event-schemas upcast-event))

(def ^:private mk-event (kit/make-event-factory latest-event-version validate-event!))

;; ═══════════════════════════════════════════════════
;; Evolve — State → Event → State
;; ═══════════════════════════════════════════════════

(defn evolve
  "Applies a single event to the current state.
   Pure fold step — no I/O."
  [state event]
  (let [{:keys [event-type payload]} (validate-event! event)]
    (case event-type
      "notification-requested"
      (assoc state
             :status            :pending
             :notification-type (:notification-type payload)
             :recipient-id      (:recipient-id payload)
             :payload           (:payload payload))

      "notification-sent"
      (assoc state :status :sent)

      "notification-failed"
      (update state :retry-count inc)

      "notification-abandoned"
      (-> state
          (assoc :status :failed)
          (update :retry-count inc))

      state)))

;; ═══════════════════════════════════════════════════
;; Decide — Command → State → Event list
;; ═══════════════════════════════════════════════════

(defn- decide-request
  [state {:keys [notification-type recipient-id payload]}]
  (case (:status state)
    :not-found [(mk-event "notification-requested"
                          {:notification-type notification-type
                           :recipient-id      recipient-id
                           :payload           payload})]
    :pending   []  ;; Already requested — idempotent, emit nothing
    (:sent :failed)
    (throw (ex-info "Notification is terminal — no further commands accepted"
                    {:error/type :domain/notification-terminal
                     :status     (:status state)}))))

(defn- decide-mark-sent [state _data]
  (when-not (= :pending (:status state))
    (throw (ex-info "Notification not pending"
                    {:error/type :domain/notification-not-pending
                     :status     (:status state)})))
  [(mk-event "notification-sent" {})])

(defn- decide-mark-failed [state {:keys [reason]}]
  (when-not (= :pending (:status state))
    (throw (ex-info "Notification not pending"
                    {:error/type :domain/notification-not-pending
                     :status     (:status state)})))
  (if (>= (inc (:retry-count state)) max-delivery-attempts)
    [(mk-event "notification-abandoned" {:reason reason})]
    [(mk-event "notification-failed" {:reason reason})]))

(def ^:private decisions
  {:request-notification decide-request
   :mark-sent            decide-mark-sent
   :mark-failed          decide-mark-failed})

(def decide
  "Command → State → Event list.
   Dispatches on :command-type, delegates to the
   appropriate decision function."
  (kit/make-decide validate-command! validate-event! decisions))

;; ═══════════════════════════════════════════════════
;; The Decider — a plain map
;; ═══════════════════════════════════════════════════

(def decider
  {:initial-state {:status :not-found, :retry-count 0}
   :decide        decide
   :evolve        evolve})
