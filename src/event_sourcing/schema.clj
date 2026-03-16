(ns event-sourcing.schema
  "Shared Malli schemas used across namespaces.")

(def non-empty-string
  [:and string? [:fn not-empty]])
