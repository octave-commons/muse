(ns eta-mu.domain.mailbox
  "Declarative mailbox conditions — the monitor_mailbox cell of the
   epiphany meta-workflow phase-0 toolset: wake when the mailbox meets
   some condition, expressed as data so any host can ship it over a
   tool boundary.

   A condition is a map; absent keys are unconstrained:
     :event-type       exact match on :event/type
     :from             exact match on the sender's actor-id
     :payload-contains submap match against :payload (nested maps
                       match recursively)
     :min-count        mailbox-level: total events >= n

   Event-level keys AND together. :min-count is a condition on the
   whole mailbox rather than any single event, so it is checked
   against the ledger, not per envelope."
  (:require [clojure.string :as str]))

(defn submap?
  "True when every key in expected equals the actual value, recursing
   into nested maps."
  [expected actual]
  (every? (fn [[k v]]
            (let [actual-v (get actual k)]
              (if (and (map? v) (map? actual-v))
                (submap? v actual-v)
                (= v actual-v))))
          expected))

(defn event-condition?
  "True when the condition constrains individual events."
  [condition]
  (boolean (some condition [:event-type :from :payload-contains])))

(defn event-pred
  "Compile the event-level constraints into a predicate of one
   envelope. Unconstrained conditions match everything."
  [{:keys [event-type from payload-contains]}]
  (fn [envelope]
    (and (or (nil? event-type)
             (= event-type (:event/type envelope)))
         (or (nil? from)
             (= from (get-in envelope [:event/from :actor-id])))
         (or (nil? payload-contains)
             (submap? payload-contains (:payload envelope))))))

(defn mailbox-met?
  "Evaluate a condition against a whole mailbox: min-count against its
   size, event-level constraints as ∃ one matching event."
  [condition mailbox]
  (and (or (nil? (:min-count condition))
           (>= (count mailbox) (:min-count condition)))
       (or (not (event-condition? condition))
           (boolean (some (event-pred condition) mailbox)))))

(defn describe
  "Human-readable rendering of a condition for tool output."
  [condition]
  (if (seq condition)
    (str/join ", "
              (for [[k v] condition]
                (str (name k) "=" (pr-str v))))
    "any event"))
