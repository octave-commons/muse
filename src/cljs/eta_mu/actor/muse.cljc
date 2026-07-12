(ns eta-mu.actor.muse
  "The Muse: primary actor that orchestrates sub-actors (phases).
   A Muse cannot inspect truth directly — she reads phase mailboxes
   and requires evidence in ledgers to verify claims."
  (:require [eta-mu.actor :as actor]))

;; ---------------------------------------------------------------------------
;; Muse lifecycle
;; ---------------------------------------------------------------------------

(defn spawn-muse!
  "Register a new Muse actor. Returns the muse-id."
  [muse-id]
  (actor/spawn! muse-id {:kind "muse"})
  muse-id)

(defn spawn-phase!
  "Spawn a phase sub-actor under this Muse.
   Returns the phase-id (muse-id + phase-type + sequence number)."
  [muse-id phase-type]
  (let [n (->> (actor/actors)
               (filter #(let [m (actor/actor-meta %)]
                          (and (= muse-id (:muse-id m))
                               (= phase-type (:phase-type m)))))
               count)
        phase-id (keyword (str muse-id "." (name phase-type) "." (inc n)))]
    (actor/spawn! phase-id {:kind    "phase"
                            :muse-id muse-id
                            :phase-type phase-type
                            :sequence (inc n)})
    phase-id))

(defn list-phases
  "Return all phase actor-ids spawned by this Muse."
  [muse-id]
  (->> (actor/actors)
       (filter #(let [m (actor/actor-meta %)]
                  (= muse-id (:muse-id m))))))

(defn list-active
  "Return phase-ids that have received messages since last checked."
  [muse-id]
  (->> (list-phases muse-id)
       (filter #(seq (actor/mailbox %)))))

(defn list-idle
  "Return phase-ids with empty mailboxes."
  [muse-id]
  (->> (list-phases muse-id)
       (filter #(empty? (actor/mailbox %)))))

;; ---------------------------------------------------------------------------
;; Message operations
;; ---------------------------------------------------------------------------

(defn command!
  "Muse sends a command to a phase."
  [muse-id phase-id command-type payload]
  (actor/tell! muse-id phase-id (str "muse.command." (name command-type)) payload))

(defn tail
  "Return the last n messages from a phase mailbox."
  [phase-id n]
  (let [mb (actor/mailbox phase-id)]
    (vec (take-last n mb))))

(defn head
  "Return the first n messages from a phase mailbox."
  [phase-id n]
  (let [mb (actor/mailbox phase-id)]
    (vec (take n mb))))

(defn filter-events
  "Filter a phase mailbox by event type."
  [phase-id event-type]
  (actor/recv phase-id {:filter-type event-type}))

(defn observations
  "All observation events from a phase."
  [phase-id]
  (filter-events phase-id "phase.observation"))

(defn conclusions
  "All conclusion events from a phase."
  [phase-id]
  (filter-events phase-id "phase.conclusion"))

(defn evidence
  "All evidence events from a phase."
  [phase-id]
  (filter-events phase-id "phase.evidence"))
