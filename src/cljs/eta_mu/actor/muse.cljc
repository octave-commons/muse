(ns eta-mu.actor.muse
  "The Muse: she bestows what she does not possess. A Muse has no tools
   of her own creativity — she can only influence and observe the phases
   she spawns. She cannot inspect truth directly: she reads phase
   ledgers, and a phase's claim is worth nothing to her without evidence
   recorded there. Influence is a ledger append the phase reads when it
   gets around to it — guidance lands only if the phase chooses to look.

   She is not an orchestrator. All functions resolve promises."
  (:require [eta-mu.actor :as actor]
            [promesa.core :as p]))

;; ---------------------------------------------------------------------------
;; Muse lifecycle
;; ---------------------------------------------------------------------------

(defn spawn-muse!
  "Register a new Muse actor. Resolves to the muse-id."
  [muse-id]
  (p/let [_ (actor/spawn! muse-id {:kind "muse"})]
    muse-id))

(defn spawn-phase!
  "Spawn a phase actor under this Muse. Resolves to the phase-id
   (muse-id + phase-type + sequence number, so the first Discover
   phase's ledger is findable by the 99th)."
  [muse-id phase-type]
  (p/let [registry (actor/registry)]
    (let [n (->> (vals registry)
                 (filter #(and (= muse-id (:muse-id %))
                               (= phase-type (:phase-type %))))
                 count)
          phase-id (keyword (str (name muse-id) "." (name phase-type) "." (inc n)))]
      (p/let [_ (actor/spawn! phase-id {:kind       "phase"
                                        :muse-id    muse-id
                                        :phase-type phase-type
                                        :sequence   (inc n)})]
        phase-id))))

(defn list-phases
  "Resolves to all phase actor-ids spawned by this Muse."
  [muse-id]
  (p/let [registry (actor/registry)]
    (into [] (keep (fn [[id meta]]
                     (when (= muse-id (:muse-id meta)) id)))
          registry)))

(defn- phases-with-mailboxes [muse-id]
  (p/let [ids       (list-phases muse-id)
          mailboxes (p/all (map actor/mailbox ids))]
    (map vector ids mailboxes)))

(defn list-active
  "Resolves to phase-ids whose ledgers hold messages."
  [muse-id]
  (p/let [pairs (phases-with-mailboxes muse-id)]
    (into [] (keep (fn [[id mb]] (when (seq mb) id))) pairs)))

(defn list-idle
  "Resolves to phase-ids with empty ledgers."
  [muse-id]
  (p/let [pairs (phases-with-mailboxes muse-id)]
    (into [] (keep (fn [[id mb]] (when (empty? mb) id))) pairs)))

;; ---------------------------------------------------------------------------
;; Influence — not command. A record in the phase's ledger, nothing more.
;; ---------------------------------------------------------------------------

(defn influence!
  "The Muse appends guidance to a phase's ledger. The phase reads it
   when it reads it; nothing is interrupted, nothing is forced."
  [muse-id phase-id influence-type payload]
  (actor/tell! muse-id phase-id
               (str "muse.influence." (name influence-type)) payload))

;; ---------------------------------------------------------------------------
;; Observation — how the Muse sees the world: through her phases' eyes.
;; ---------------------------------------------------------------------------

(defn tail
  "Resolves to the last n events of a phase ledger."
  [phase-id n]
  (p/let [mb (actor/mailbox phase-id)]
    (vec (take-last n mb))))

(defn head
  "Resolves to the first n events of a phase ledger."
  [phase-id n]
  (p/let [mb (actor/mailbox phase-id)]
    (vec (take n mb))))

(defn filter-events
  "Resolves to a phase's ledger events of one type."
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
  "All evidence events from a phase. A conclusion without evidence
   recorded here is just a claim."
  [phase-id]
  (filter-events phase-id "phase.evidence"))
