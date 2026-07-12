(ns plugins.actors
  "The actor-system plugin: Muse orchestration and phase-ledger tools.
   Business logic only — authored with the host-agnostic eta-mu DSL.
   Config EDN references this as :resource plugins.actors/plugin;
   target boundaries decide how it's exposed."
  (:require [eta-mu.actor :as actor]
            [eta-mu.actor.memory :as mem]
            [eta-mu.actor.muse :as muse]
            [eta-mu.dsl :as dsl :refer [deftool defplugin]]))

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
    (muse/spawn-muse! (keyword id))
    {:muse_id id :status "spawned"}))

(deftool muse-phases
  {:id          :muse/phases
   :description "List all phases spawned by a Muse."
   :args        [:map [:muse_id :string]]
   :tags        #{:muse :actors}}
  [{:keys [muse_id]} _ctx]
  {:muse_id muse_id
   :phases  (mapv name (muse/list-phases (keyword muse_id)))})

;; ---------------------------------------------------------------------------
;; Phase tools
;; ---------------------------------------------------------------------------

(deftool phase-spawn
  {:id          :phase/spawn
   :description "Spawn a new phase sub-actor under a Muse."
   :args        [:map [:muse_id :string] [:phase_type :string]]
   :tags        #{:phase :actors}}
  [{:keys [muse_id phase_type]} _ctx]
  (let [phase-id (muse/spawn-phase! (keyword muse_id) (keyword phase_type))]
    {:phase_id   (name phase-id)
     :muse_id    muse_id
     :phase_type phase_type}))

(deftool phase-list-active
  {:id          :phase/list_active
   :description "List phase actors that have received messages."
   :args        [:map [:muse_id :string]]
   :tags        #{:phase :actors}}
  [{:keys [muse_id]} _ctx]
  {:muse_id muse_id
   :active  (mapv name (muse/list-active (keyword muse_id)))})

(deftool phase-list-idle
  {:id          :phase/list_idle
   :description "List phase actors with empty mailboxes."
   :args        [:map [:muse_id :string]]
   :tags        #{:phase :actors}}
  [{:keys [muse_id]} _ctx]
  {:muse_id muse_id
   :idle    (mapv name (muse/list-idle (keyword muse_id)))})

(deftool phase-tail
  {:id          :phase/tail
   :description "Return the last N messages from a phase mailbox."
   :args        [:map [:phase_id :string] [:n {:optional true} :int]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id n]} _ctx]
  {:phase_id phase_id
   :messages (event-view (muse/tail (keyword phase_id) (or n 10)))})

(deftool phase-head
  {:id          :phase/head
   :description "Return the first N messages from a phase mailbox."
   :args        [:map [:phase_id :string] [:n {:optional true} :int]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id n]} _ctx]
  {:phase_id phase_id
   :messages (event-view (muse/head (keyword phase_id) (or n 10)))})

(deftool phase-filter
  {:id          :phase/filter
   :description "Filter phase mailbox by event type."
   :args        [:map [:phase_id :string] [:event_type :string]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id event_type]} _ctx]
  {:phase_id   phase_id
   :event_type event_type
   :messages   (event-view (muse/filter-events (keyword phase_id) event_type))})

(deftool phase-observations
  {:id          :phase/observations
   :description "Get all observations from a phase."
   :args        [:map [:phase_id :string]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id]} _ctx]
  {:phase_id     phase_id
   :observations (event-view (muse/observations (keyword phase_id)))})

(deftool phase-conclusions
  {:id          :phase/conclusions
   :description "Get all conclusions from a phase."
   :args        [:map [:phase_id :string]]
   :tags        #{:phase :actors}}
  [{:keys [phase_id]} _ctx]
  {:phase_id    phase_id
   :conclusions (event-view (muse/conclusions (keyword phase_id)))})

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
  (let [event-id (actor/tell! (keyword from) (keyword to) event_type
                              (or payload {}))]
    {:event_id event-id :from from :to to}))

(deftool actor-list
  {:id          :actor/list
   :description "List all registered actors."
   :args        [:map]
   :tags        #{:actor :actors}}
  [_args _ctx]
  {:actors (mapv name (actor/actors))})

;; ---------------------------------------------------------------------------
;; Plugin
;; ---------------------------------------------------------------------------

(defn init!
  "Runs once when a target activates this plugin."
  []
  (actor/init-store! (mem/make-memory-store)))

(defplugin plugin {:id :eta-mu/actors :init init!}
  muse-spawn
  muse-phases
  phase-spawn
  phase-list-active
  phase-list-idle
  phase-tail
  phase-head
  phase-filter
  phase-observations
  phase-conclusions
  actor-tell
  actor-list)
