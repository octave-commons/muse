(ns plugins.actors
  "The Muse/Phase configuration of the actor system: a Muse influences
   and observes the phases she spawns — she is not an orchestrator. Her
   guidance is a ledger append the phase reads when it chooses; her only
   view of the world is what her phases record. Business logic only —
   authored with the host-agnostic eta-mu DSL."
  (:require [eta-mu.actor :as actor]
            [eta-mu.actor.backend :as backend]
            [eta-mu.actor.monitor :as monitor]
            [eta-mu.actor.muse :as muse]
            [eta-mu.actor.task :as task]
            [eta-mu.boundaries.node.fs :as bfs]
            [eta-mu.domain.agent :as dagent]
            [eta-mu.domain.mailbox :as dmail]
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

(deftool actor-spawn
  {:id          :actor/spawn
   :description "Register an actor with a mailbox ledger. Spawn one for
                 yourself before subscribing to a task's output."
   :args        [:map
                 [:actor_id :string]
                 [:kind {:optional true} :string]]
   :tags        #{:actor :actors}}
  [{:keys [actor_id kind]} _ctx]
  (p/let [_ (backend/ensure!)
          _ (actor/spawn! (keyword actor_id) (if kind {:kind kind} {}))]
    {:actor_id actor_id :status "spawned"}))

(deftool actor-mailbox
  {:id          :actor/mailbox
   :description "Read an actor's mailbox: everything after since_id,
                 optionally filtered by event type."
   :args        [:map
                 [:actor_id :string]
                 [:since_id {:optional true} :string]
                 [:limit {:optional true} :int]
                 [:filter_type {:optional true} :string]]
   :tags        #{:actor :actors}}
  [{:keys [actor_id since_id limit filter_type]} _ctx]
  (p/let [_      (backend/ensure!)
          events (actor/recv (keyword actor_id)
                             (cond-> {}
                               since_id    (assoc :since-id since_id)
                               limit       (assoc :limit limit)
                               filter_type (assoc :filter-type filter_type)))]
    {:actor_id actor_id
     :messages (event-view events)}))

(deftool actor-monitor
  {:id          :actor/monitor
   :description "Block until an actor's mailbox meets a condition or
                 the timeout passes — condition-triggered wake. All
                 constraint args AND together; with none given, wakes
                 on the next append. Returns {met, event?, count?}."
   :args        [:map
                 [:actor_id :string]
                 [:event_type {:optional true} :string]
                 [:from {:optional true} :string]
                 [:payload_contains {:optional true} :map]
                 [:min_count {:optional true} :int]
                 [:timeout_ms {:optional true} :int]]
   :tags        #{:actor :actors}}
  [{:keys [actor_id event_type from payload_contains min_count timeout_ms]} _ctx]
  (let [condition (cond-> {}
                    event_type       (assoc :event-type event_type)
                    from             (assoc :from from)
                    payload_contains (assoc :payload-contains payload_contains)
                    min_count        (assoc :min-count min_count))]
    (p/let [_      (backend/ensure!)
            result (monitor/monitor (keyword actor_id) condition
                                    (or timeout_ms 30000))]
      (cond-> {:actor_id  actor_id
               :condition (dmail/describe condition)
               :met       (:met result)}
        (:event result) (assoc :event (dissoc (:event result) :source))
        (:count result) (assoc :count (:count result))))))

;; ---------------------------------------------------------------------------
;; Task tools — background OS processes as actors
;; ---------------------------------------------------------------------------

(defn- task-view [snapshot]
  (-> snapshot
      (update :task-id name)
      (update :status name)
      (update :subscribers #(mapv name %))))

(defn- control!
  "Steer a task the actor way: a control envelope appended to its
   mailbox, picked up by the task's poll."
  [task_id event-type payload]
  (p/let [_   (backend/ensure!)
          eid (actor/tell! :opencode.session (keyword task_id)
                           event-type payload)]
    {:event_id eid :task_id task_id}))

(deftool task-spawn
  {:id          :task/spawn
   :description "Spawn a background OS process as a task actor. Its
                 ledger records task.started/task.exited; every
                 stdout/stderr line is fanned out to subscriber
                 mailboxes as task.stdout/task.stderr. cmd is argv —
                 never a shell string."
   :args        [:map
                 [:cmd [:vector :string]]
                 [:cwd {:optional true} :string]
                 [:subscribers {:optional true} [:vector :string]]]
   :tags        #{:task :actors}}
  [{:keys [cmd cwd subscribers]} _ctx]
  (p/let [_ (backend/ensure!)
          _ (p/all (for [sub subscribers]
                     (actor/spawn! (keyword sub) {})))
          {:keys [task-id pid]} (task/spawn-task!
                                 cmd {:cwd cwd :subscribers subscribers})]
    {:task_id (name task-id) :pid pid :status "running"}))

(deftool task-status
  {:id          :task/status
   :description "Live status of one task actor spawned by this process:
                 line counts, subscribers, exit state."
   :args        [:map [:task_id :string]]
   :tags        #{:task :actors}}
  [{:keys [task_id]} _ctx]
  (if-let [snapshot (task/status (keyword task_id))]
    (task-view snapshot)
    {:task_id task_id :status "unknown"
     :note "not spawned by this process; read its ledger via actor_mailbox"}))

(deftool task-list
  {:id          :task/list
   :description "List every task actor this process has spawned."
   :args        [:map]
   :tags        #{:task :actors}}
  [_args _ctx]
  {:tasks (mapv task-view (task/list-tasks))})

(deftool task-subscribe
  {:id          :task/subscribe
   :description "Subscribe an actor to a task's stdout/stderr stream.
                 Sent as a task.subscribe control message the task
                 reads from its own mailbox."
   :args        [:map [:task_id :string] [:subscriber :string]]
   :tags        #{:task :actors}}
  [{:keys [task_id subscriber]} _ctx]
  (p/let [_ (backend/ensure!)
          _ (actor/spawn! (keyword subscriber) {})]
    (control! task_id "task.subscribe" {:subscriber subscriber})))

(deftool task-unsubscribe
  {:id          :task/unsubscribe
   :description "Stop streaming a task's output to an actor's mailbox."
   :args        [:map [:task_id :string] [:subscriber :string]]
   :tags        #{:task :actors}}
  [{:keys [task_id subscriber]} _ctx]
  (control! task_id "task.unsubscribe" {:subscriber subscriber}))

(deftool task-kill
  {:id          :task/kill
   :description "Ask a task actor to terminate (SIGTERM) via a
                 task.kill control message."
   :args        [:map [:task_id :string]]
   :tags        #{:task :actors}}
  [{:keys [task_id]} _ctx]
  (control! task_id "task.kill" {}))

;; ---------------------------------------------------------------------------
;; Agent tools — background opencode sessions as actors: one more
;; configuration of the task machinery, as the muse and her phases
;; were one configuration of the mailbox semantics.
;; ---------------------------------------------------------------------------

(deftool agent-spawn
  {:id          :agent/spawn
   :description "Spawn a background opencode agent session as an actor.
                 Its output lines stream to subscriber mailboxes as
                 task.stdout; task.started/task.exited land on its own
                 ledger. Steer it like any task actor (task_kill,
                 task_subscribe) and watch it with actor_monitor.
                 Model resolution: model arg, else ETA_MU_AGENT_MODEL,
                 else opencode/big-pickle."
   :args        [:map
                 [:prompt :string]
                 [:agent {:optional true} :string]
                 [:model {:optional true} :string]
                 [:session {:optional true} :string]
                 [:cwd {:optional true} :string]
                 [:subscribers {:optional true} [:vector :string]]]
   :tags        #{:agent :actors}}
  [{:keys [prompt agent model session cwd subscribers]} _ctx]
  (p/let [_ (backend/ensure!)
          _ (p/all (for [sub subscribers]
                     (actor/spawn! (keyword sub) {})))
          {:keys [task-id pid]}
          (task/spawn-task!
           (dagent/run-cmd prompt {:agent   agent
                                   :model   (or model
                                                (bfs/env "ETA_MU_AGENT_MODEL")
                                                "opencode/big-pickle")
                                   :session session})
           {:cwd         cwd
            :subscribers subscribers
            :kind        "agent"
            :name-hint   (dagent/agent-slug {:agent agent})
            :meta        {:prompt prompt}})]
    {:agent_id (name task-id) :pid pid :status "running"}))

(deftool agent-list
  {:id          :agent/list
   :description "List background agent actors spawned by this process."
   :args        [:map]
   :tags        #{:agent :actors}}
  [_args _ctx]
  {:agents (into [] (comp (filter #(= "agent" (:kind %)))
                          (map task-view))
                 (task/list-tasks))})

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
  actor-list
  actor-spawn
  actor-mailbox
  actor-monitor
  agent-spawn
  agent-list
  task-spawn
  task-status
  task-list
  task-subscribe
  task-unsubscribe
  task-kill)
