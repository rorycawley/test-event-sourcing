(ns event-sourcing.projection-dispatch
  "Dispatch for projection event handlers.")

(defmulti project-event!
  "Applies one event to a projection read model. Dispatches on :event-type."
  (fn [_tx {:keys [event-type]} _context] event-type))

(defmethod project-event! :default
  [_tx {:keys [event-type event-version global-sequence stream-id]}
   {:keys [projection-name]}]
  (throw (ex-info "Unknown event type for projection"
                  {:projection-name projection-name
                   :event-type      event-type
                   :event-version   event-version
                   :global-sequence global-sequence
                   :stream-id       stream-id})))
