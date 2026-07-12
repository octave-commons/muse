(ns plugins.actors
  "The Muse/Phase configuration of the actor system: a Muse influences
   and observes the phases she spawns — she is not an orchestrator. Her
   guidance is a ledger append the phase reads when it chooses; her only
   view of the world is what her phases record. Business logic only —
   authored with the host-agnostic eta-mu DSL."
  (:require [eta-mu.actor :as actor]
            [eta-mu.actor.backend :as backend]
            [eta-mu.actor.muse :as muse]
            [eta-mu.dsl :as dsl :refer [deftool defplugin]]
            [promesa.core :as p]))

(defn- event-view [events]
  (mapv #(dissoc % :source) events))

;; ---------------------------------------------------------------------------
;; Muse tools
;; ---------------------------------------------------------------------------

(deftool muse-spawn
  {:id          :muse/spawn
   :description "Register a new Muse actor."
   :args        [:map [:muse_id {:optional true} :string]]
   :tags        #{:muse :actors}}
  [{:keys [muse_id]} _ctx]
  (let [id (or muse_id (str "muse." (random-uuid)))]
    (p/let [_ (backend/ensure!)
            _ (muse/spawn-muse! (keyword id))]
      {:muse_id id :status "spawned"})))

(deftool muse-phases
  {:id          :muse/phases
   :description "List all phases spawned by a Muse."
   :args        [:map [:muse_id :string]]
   :tags        #{:muse :actors}}
  [{:keys [muse_id]} _ctx]
  (p/let [_      (backend/ensure!)
          phases (muse/list-phases (keyword muse_id))]
    {:muse_id muse_id
     :phases  (mapv name phases)}))

(deftool muse-influence
  {:id          :muse/influence
   :description "The Muse appends guidance to a phase's ledger. The
                 phase reads it when it reads it — nothing is
                 interrupted, nothing is forced."
   :args        [:map
                 [:muse_id :string]
                 [:phase_id :string]
                 [:influence_type :string]
                 [:payload {:optional true} :map]]
   :tags        #{:muse :actors}}
  [{:keys [muse_id phase_id influence_type payload]} _ctx]
  (p/let [_   (backend/ensure!)
          eid (muse/influence! (keyword muse_id) (keyword phase_id)
                               (keyword influence_type) (or payload {}))]
    {:event_id eid :muse_id muse_id :phase_id phase_id}))

;; ---------------------------------------------------------------------------
;; Phase tools
;; ---------------------------------------------------------------------------

(deftool phase-spawn
  {:id          :phase/spawn
   :description "Spawn a new phase actor under a Muse. Returns the
                 phase's ledger id."
   :args        [:map [:muse_id :string] [:phase_type :string]]
   :tags        #{:phase :actors}}
  [{:keys [muse_id phase_type]} _ctx]
  (p/let [_        (backend/ensure!)
          phase-id (muse/spawn-phase! (keyword muse_id) (keyword phase_type))]
    {:phase_id   (name phase-id)
     :muse_id    muse_id
     :phase_type phase_type}))

(deftool phase-record
  {:id          :phase/record
   :description "A phase records an event to its OWN ledger: an
                 observation, evidence, a hypothesis, a claim, a
                 question, or a conclusion. This is how a phase's
                 understanding becomes visible to its Muse."
   :args        [:map
                 [:phase_id :string]
                 [:kind [:enum "observation" "evidence" "hypothesis"
                         "claim" "question" "conclusion"]]
                 [:payload :map]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id kind payload]} _ctx]
  (p/let [_   (backend/ensure!)
          eid (actor/tell! (keyword phase_id) (keyword phase_id)
                           (str "phase." kind) payload)]
    {:event_id eid :phase_id phase_id :kind kind}))

(deftool phase-list-active
  {:id          :phase/list_active
   :description "List phase actors whose ledgers hold events."
   :args        [:map [:muse_id :string]]
   :tags        #{:phase :actors}}
  [{:keys [muse_id]} _ctx]
  (p/let [_      (backend/ensure!)
          active (muse/list-active (keyword muse_id))]
    {:muse_id muse_id
     :active  (mapv name active)}))

(deftool phase-list-idle
  {:id          :phase/list_idle
   :description "List phase actors with empty ledgers."
   :args        [:map [:muse_id :string]]
   :tags        #{:phase :actors}}
  [{:keys [muse_id]} _ctx]
  (p/let [_    (backend/ensure!)
          idle (muse/list-idle (keyword muse_id))]
    {:muse_id muse_id
     :idle    (mapv name idle)}))

(deftool phase-tail
  {:id          :phase/tail
   :description "Return the last N events from a phase ledger."
   :args        [:map [:phase_id :string] [:n {:optional true} :int]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id n]} _ctx]
  (p/let [_      (backend/ensure!)
          events (muse/tail (keyword phase_id) (or n 10))]
    {:phase_id phase_id
     :messages (event-view events)}))

(deftool phase-head
  {:id          :phase/head
   :description "Return the first N events from a phase ledger."
   :args        [:map [:phase_id :string] [:n {:optional true} :int]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id n]} _ctx]
  (p/let [_      (backend/ensure!)
          events (muse/head (keyword phase_id) (or n 10))]
    {:phase_id phase_id
     :messages (event-view events)}))

(deftool phase-filter
  {:id          :phase/filter
   :description "Filter a phase ledger by event type."
   :args        [:map [:phase_id :string] [:event_type :string]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id event_type]} _ctx]
  (p/let [_      (backend/ensure!)
          events (muse/filter-events (keyword phase_id) event_type)]
    {:phase_id   phase_id
     :event_type event_type
     :messages   (event-view events)}))

(deftool phase-observations
  {:id          :phase/observations
   :description "Get all observations from a phase."
   :args        [:map [:phase_id :string]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id]} _ctx]
  (p/let [_      (backend/ensure!)
          events (muse/observations (keyword phase_id))]
    {:phase_id     phase_id
     :observations (event-view events)}))

(deftool phase-conclusions
  {:id          :phase/conclusions
   :description "Get all conclusions from a phase. A conclusion without
                 evidence in the same ledger is just a claim."
   :args        [:map [:phase_id :string]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id]} _ctx]
  (p/let [_      (backend/ensure!)
          events (muse/conclusions (keyword phase_id))]
    {:phase_id    phase_id
     :conclusions (event-view events)}))

;; ---------------------------------------------------------------------------
;; Actor tools
;; ---------------------------------------------------------------------------

(deftool actor-tell
  {:id          :actor/tell
   :description "Send a message between actors."
   :args        [:map
                 [:from :string]
                 [:to :string]
                 [:event_type :string]
                 [:payload {:optional true} :map]]
   :tags        #{:actor :actors}}
  [{:keys [from to event_type payload]} _ctx]
  (p/let [_        (backend/ensure!)
          event-id (actor/tell! (keyword from) (keyword to) event_type
                                (or payload {}))]
    {:event_id event-id :from from :to to}))

(deftool actor-list
  {:id          :actor/list
   :description "List all registered actors."
   :args        [:map]
   :tags        #{:actor :actors}}
  [_args _ctx]
  (p/let [_   (backend/ensure!)
          ids (actor/actors)]
    {:actors (mapv name ids)}))

;; ---------------------------------------------------------------------------
;; Plugin
;; ---------------------------------------------------------------------------

(defn init!
  "Runs once when a target activates this plugin. The backend (file
   ledgers by default, mongo when configured) is chosen from the env."
  []
  (backend/ensure!))

(defplugin plugin {:id :eta-mu/actors :init init!}
  muse-spawn
  muse-phases
  muse-influence
  phase-spawn
  phase-record
  phase-list-active
  phase-list-idle
  phase-tail
  phase-head
  phase-filter
  phase-observations
  phase-conclusions
  actor-tell
  actor-list)
