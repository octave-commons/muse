(ns eta-mu.opencode.plugin
  "OpenCode plugin: registers actor system tools and Muse orchestration hooks.
   This is the entry point that OpenCode loads."
  (:require [eta-mu.actor :as actor]
            [eta-mu.actor.muse :as muse]
            [eta-mu.opencode.compile :as compile]))

;; ---------------------------------------------------------------------------
;; Tool handlers
;; ---------------------------------------------------------------------------

(defn ^:async handle-phase-spawn
  "Spawn a new phase sub-actor under a Muse."
  [{:keys [muse_id phase_type]} _ctx]
  (let [phase-id (muse/spawn-phase! (keyword muse_id) (keyword phase_type))]
    {:content (pr-str {:phase_id (name phase-id)
                       :muse_id  muse_id
                       :phase_type phase_type})}))

(defn ^:async handle-phase-list-active
  "List phase actors that have received messages."
  [{:keys [muse_id]} _ctx]
  (let [active (muse/list-active (keyword muse_id))]
    {:content (pr-str (mapv name active))}))

(defn ^:async handle-phase-list-idle
  "List phase actors with empty mailboxes."
  [{:keys [muse_id]} _ctx]
  (let [idle (muse/list-idle (keyword muse_id))]
    {:content (pr-str (mapv name idle))}))

(defn ^:async handle-phase-tail
  "Return the last N messages from a phase mailbox."
  [{:keys [phase_id n]} _ctx]
  (let [events (muse/tail (keyword phase_id) (or n 10))]
    {:content (pr-str (mapv #(dissoc % :source) events))}))

(defn ^:async handle-phase-head
  "Return the first N messages from a phase mailbox."
  [{:keys [phase_id n]} _ctx]
  (let [events (muse/head (keyword phase_id) (or n 10))]
    {:content (pr-str (mapv #(dissoc % :source) events))}))

(defn ^:async handle-phase-filter
  "Filter phase mailbox by event type."
  [{:keys [phase_id event_type]} _ctx]
  (let [events (muse/filter-events (keyword phase_id) event_type)]
    {:content (pr-str (mapv #(dissoc % :source) events))}))

(defn ^:async handle-phase-observations
  "Get all observations from a phase."
  [{:keys [phase_id]} _ctx]
  (let [events (muse/observations (keyword phase_id))]
    {:content (pr-str (mapv #(dissoc % :source) events))}))

(defn ^:async handle-phase-conclusions
  "Get all conclusions from a phase."
  [{:keys [phase_id]} _ctx]
  (let [events (muse/conclusions (keyword phase_id))]
    {:content (pr-str (mapv #(dissoc % :source) events))}))

(defn ^:async handle-tell
  "Send a message between actors."
  [{:keys [from to event_type payload]} _ctx]
  (let [event-id (actor/tell! (keyword from) (keyword to) event_type payload)]
    {:content (pr-str {:event_id event-id
                       :from from
                       :to to})}))

(defn ^:async handle-actor-list
  "List all registered actors."
  [_args _ctx]
  {:content (pr-str (mapv name (actor/actors)))})

(defn ^:async handle-muse-spawn
  "Register a new Muse actor."
  [{:keys [muse_id]} _ctx]
  (muse/spawn-muse! (keyword muse_id))
  {:content (pr-str {:muse_id muse_id :status "spawned"})})

(defn ^:async handle-muse-phases
  "List all phases spawned by a Muse."
  [{:keys [muse_id]} _ctx]
  (let [phases (muse/list-phases (keyword muse_id))]
    {:content (pr-str (mapv name phases))}))

;; ---------------------------------------------------------------------------
;; Tool definitions (data conforming to schema)
;; ---------------------------------------------------------------------------

(def tools
  [{:opencode/kind :tool
    :id            :muse/spawn
    :name          "muse_spawn"
    :description   "Register a new Muse actor."
    :args          [:map [:muse_id :string]]
    :handler       handle-muse-spawn}

   {:opencode/kind :tool
    :id            :muse/phases
    :name          "muse_phases"
    :description   "List all phases spawned by a Muse."
    :args          [:map [:muse_id :string]]
    :handler       handle-muse-phases}

   {:opencode/kind :tool
    :id            :phase/spawn
    :name          "phase_spawn"
    :description   "Spawn a new phase sub-actor under a Muse."
    :args          [:map [:muse_id :string] [:phase_type :string]]
    :handler       handle-phase-spawn}

   {:opencode/kind :tool
    :id            :phase/list_active
    :name          "phase_list_active"
    :description   "List phase actors that have received messages."
    :args          [:map [:muse_id :string]]
    :handler       handle-phase-list-active}

   {:opencode/kind :tool
    :id            :phase/list_idle
    :name          "phase_list_idle"
    :description   "List phase actors with empty mailboxes."
    :args          [:map [:muse_id :string]]
    :handler       handle-phase-list-idle}

   {:opencode/kind :tool
    :id            :phase/tail
    :name          "phase_tail"
    :description   "Return the last N messages from a phase mailbox."
    :args          [:map [:phase_id :string] [:n {:optional true} :int]]
    :handler       handle-phase-tail}

   {:opencode/kind :tool
    :id            :phase/head
    :name          "phase_head"
    :description   "Return the first N messages from a phase mailbox."
    :args          [:map [:phase_id :string] [:n {:optional true} :int]]
    :handler       handle-phase-head}

   {:opencode/kind :tool
    :id            :phase/filter
    :name          "phase_filter"
    :description   "Filter phase mailbox by event type."
    :args          [:map [:phase_id :string] [:event_type :string]]
    :handler       handle-phase-filter}

   {:opencode/kind :tool
    :id            :phase/observations
    :name          "phase_observations"
    :description   "Get all observations from a phase."
    :args          [:map [:phase_id :string]]
    :handler       handle-phase-observations}

   {:opencode/kind :tool
    :id            :phase/conclusions
    :name          "phase_conclusions"
    :description   "Get all conclusions from a phase."
    :args          [:map [:phase_id :string]]
    :handler       handle-phase-conclusions}

   {:opencode/kind :tool
    :id            :actor/tell
    :name          "actor_tell"
    :description   "Send a message between actors."
    :args          [:map
                    [:from :string]
                    [:to :string]
                    [:event_type :string]
                    [:payload {:optional true} :map]]
    :handler       handle-tell}

   {:opencode/kind :tool
    :id            :actor/list
    :name          "actor_list"
    :description   "List all registered actors."
    :args          [:map]
    :handler       handle-actor-list}])

;; ---------------------------------------------------------------------------
;; Plugin assembly
;; ---------------------------------------------------------------------------

(def plugin
  {:opencode/kind :plugin
   :id            :eta-mu/actors
   :tools         tools
   :hooks         []})
