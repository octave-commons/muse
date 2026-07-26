;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.domain.watch
  "Pure mailbox-watch decisions. A watch is a resumable cursor over an actor
   ledger, not a blocked host call. Runtime code owns registration, delivery,
   and persistence; this namespace only decides whether a mailbox fulfills a
   declarative condition."
  (:require [eta-mu.domain.mailbox :as mailbox]))

(def terminal-statuses
  #{"met" "cancelled" "failed"})

(defn terminal?
  [status]
  (contains? terminal-statuses status))

(defn cursor
  "Capture the ledger position from which a new watch should observe events.
   include-existing=true intentionally starts at the beginning."
  [events include-existing]
  (let [events (vec (or events []))]
    {:after-id        (when-not include-existing (:event/id (peek events)))
     :baseline-count (if include-existing 0 (count events))
     :include-existing (boolean include-existing)}))

(defn- event-index
  [events event-id]
  (first
   (keep-indexed
    (fn [idx event]
      (when (= event-id (:event/id event)) idx))
    events)))

(defn events-after
  "Return events visible to a watch cursor. If a referenced event is no longer
   available, fall back to the captured count rather than silently replaying
   the whole ledger."
  [events {:keys [after-id baseline-count include-existing]}]
  (let [events (vec (or events []))]
    (cond
      include-existing
      events

      after-id
      (if-let [idx (event-index events after-id)]
        (subvec events (inc idx))
        (vec (drop (or baseline-count 0) events)))

      :else
      (vec (drop (or baseline-count 0) events)))))

(defn evaluate
  "Evaluate a watch against the current mailbox.

   Event constraints only inspect events visible after the cursor unless the
   watch explicitly requested existing events. A count-only condition observes
   the whole mailbox. An empty condition means the first event after the
   cursor."
  [condition watch-cursor events]
  (let [events         (vec (or events []))
        visible        (events-after events watch-cursor)
        event-required (or (mailbox/event-condition? condition)
                           (nil? (:min-count condition)))
        event-hit      (first (filter (mailbox/event-pred condition) visible))
        count-met      (or (nil? (:min-count condition))
                           (>= (count events) (:min-count condition)))
        met            (and count-met
                            (or (not event-required) (some? event-hit)))]
    (cond-> {:status (if met "met" "pending")
             :count  (count events)}
      event-hit (assoc :event event-hit))))
