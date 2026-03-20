(ns modules.notification.infra.reactor
  "Reactor: translates integration events into notification commands.

   In Event Modeling terms, this is an automation — the blue box that
   sits between an event swimlane and a command. It observes integration
   events published by the bank module and issues commands to the
   notification aggregate.

   The reactor is the bridge between modules:
   - Depends on the shared contract (events.bank) for validation
   - Depends on the notification aggregate (notification.notification)
   - Does NOT depend on any bank.* internal namespace

   The reactor does not send emails. It creates notification aggregates
   in the event store. A separate delivery worker (not yet built) would
   listen for notification-requested events and perform the actual send,
   then issue :mark-sent or :mark-failed commands back to the aggregate."
  (:require [es.decider :as decider]
            [modules.notification.domain.delivery :as notification]
            [events.bank :as bank-events]))

(defn- notification-stream-id
  "Derives a deterministic stream-id for a notification.
   Uses the notification type and the integration event's global-sequence
   to ensure uniqueness and idempotency."
  [notification-type global-sequence]
  (str "notification-" notification-type "-" global-sequence))

(defn make-handler-specs
  "Returns handler-specs for the notification consumer group.

   Each handler is a reactor: validates the integration event, decides
   whether a notification is needed, and issues a :request-notification
   command to the notification aggregate.

   The handler receives a transaction (tx) on the consumer database but
   does not use it for aggregate writes — those go to the event store.
   The inbox claim (in the consumer tx) and the aggregate write (in the
   event store tx) are separate transactions. Safety:
   - If the aggregate write fails → exception propagates, consumer tx
     rolls back, event will be retried on next catch-up
   - If aggregate write succeeds but consumer tx fails → event is retried,
     but the idempotency key prevents duplicate aggregate writes

   event-store-ds: the shared event store datasource"
  [event-store-ds]
  {"bank.account-opened"
   (fn [_tx event]
     (let [event (bank-events/validate! event)]
       (decider/handle-with-retry!
        event-store-ds notification/decider
        {:command-type    :request-notification
         :stream-id       (notification-stream-id "welcome-email"
                                                  (:global-sequence event))
         :idempotency-key (str "notif-welcome-" (:global-sequence event))
         :data            {:notification-type "welcome-email"
                           :recipient-id      (:account-id event)
                           :payload           {:owner   (or (:owner event) "unknown")
                                               :message "Welcome to the bank!"}}})))

   "bank.money-deposited"
   (fn [_tx event]
     (let [event (bank-events/validate! event)]
       (when (>= (:amount event) 10000)
         (decider/handle-with-retry!
          event-store-ds notification/decider
          {:command-type    :request-notification
           :stream-id       (notification-stream-id "large-deposit-alert"
                                                    (:global-sequence event))
           :idempotency-key (str "notif-deposit-" (:global-sequence event))
           :data            {:notification-type "large-deposit-alert"
                             :recipient-id      (:account-id event)
                             :payload           {:amount  (:amount event)
                                                 :message "Large deposit received"}}}))))})
