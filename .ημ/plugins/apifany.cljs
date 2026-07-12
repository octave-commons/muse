(ns plugins.apifany
  "apifany: the agent-actor message-passing toolset. Sending a message
   appends a record to a ledger the recipient owns and reads when they
   get around to it; immediate notification exists only for actors that
   set up a watcher. The humble actor model, hiding in plain sight.

   These are the phase-0 tools from the epiphany meta-workflow notes:
   spawn_agent_actor, list_known_agents, send_agent_actor_message,
   read_mailbox, monitor_mailbox. (manage_mailbox_policies is deferred
   to the policy slice.)"
  (:require [eta-mu.actor :as actor]
            [eta-mu.actor.backend :as backend]
            [eta-mu.dsl :as dsl :refer [deftool defplugin]]
            [promesa.core :as p]))

(deftool spawn-agent-actor
  {:id          :apifany/spawn_agent_actor
   :description "Register an agent actor with its own append-only event ledger."
   :args        [:map
                 [:actor_id :string]
                 [:kind {:optional true} :string]]
   :tags        #{:apifany :actors}}
  [{:keys [actor_id kind]} _ctx]
  (p/let [_ (backend/ensure!)
          _ (actor/spawn! (keyword actor_id) {:kind (or kind "actor")})]
    {:actor_id actor_id :status "spawned"}))

(deftool list-known-agents
  {:id          :apifany/list_known_agents
   :description "List every registered agent actor with its metadata."
   :args        [:map]
   :tags        #{:apifany :actors}}
  [_args _ctx]
  (p/let [_        (backend/ensure!)
          registry (actor/registry)]
    {:agents (mapv (fn [[id meta]] (assoc meta :actor_id (name id)))
                   registry)}))

(deftool send-message
  {:id          :apifany/send_agent_actor_message
   :description "Append a message to another actor's ledger. They will
                 read it when they get around to reading it — this does
                 not interrupt or notify unless they set up a watcher."
   :args        [:map
                 [:from :string]
                 [:to :string]
                 [:event_type :string]
                 [:payload {:optional true} :map]
                 [:mode {:optional true} [:enum "tell" "ask"]]]
   :tags        #{:apifany :actors}}
  [{:keys [from to event_type payload mode]} _ctx]
  (p/let [_   (backend/ensure!)
          eid (if (= mode "ask")
                (actor/ask! (keyword from) (keyword to) event_type (or payload {}))
                (actor/tell! (keyword from) (keyword to) event_type (or payload {})))]
    {:event_id eid :from from :to to}))

(deftool read-mailbox
  {:id          :apifany/read_mailbox
   :description "Read an actor's ledger: newest-last, optionally only
                 events after since_id or of one event_type."
   :args        [:map
                 [:actor_id :string]
                 [:limit {:optional true} :int]
                 [:since_id {:optional true} :string]
                 [:event_type {:optional true} :string]]
   :tags        #{:apifany :actors}}
  [{:keys [actor_id limit since_id event_type]} _ctx]
  (p/let [_      (backend/ensure!)
          events (actor/recv (keyword actor_id)
                             (cond-> {:limit (or limit 50)}
                               since_id   (assoc :since-id since_id)
                               event_type (assoc :filter-type event_type)))]
    {:actor_id actor_id
     :count    (count events)
     :events   events}))

(deftool monitor-mailbox
  {:id          :apifany/monitor_mailbox
   :description "Block until the next message arrives in an actor's
                 ledger (optionally of one event_type), or until
                 timeout_ms passes. Returns the event or null."
   :args        [:map
                 [:actor_id :string]
                 [:event_type {:optional true} :string]
                 [:timeout_ms {:optional true} :int]]
   :tags        #{:apifany :actors}}
  [{:keys [actor_id event_type timeout_ms]} _ctx]
  (p/let [hit (backend/watch-once
               (keyword actor_id)
               (if event_type
                 #(= event_type (:event/type %))
                 (constantly true))
               (min (or timeout_ms 60000) 300000))]
    {:actor_id actor_id
     :event    hit
     :timed_out (nil? hit)}))

(defn init! []
  (backend/ensure!))

(defplugin plugin {:id :eta-mu/apifany :init init!}
  spawn-agent-actor
  list-known-agents
  send-message
  read-mailbox
  monitor-mailbox)
