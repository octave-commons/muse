(ns eta-mu.opencode.entrypoint
  "OpenCode plugin entry point.
   The :opencode-plugin shadow-cljs target compiles this to .opencode/plugins/eta-mu-actors.mjs.
   Default export is eta-mu-plugin: (ctx) => hooks-map."
  (:require [eta-mu.actor :as actor]
            [eta-mu.actor.muse :as muse]
            [eta-mu.actor.memory :as mem]))

(actor/init-store! (mem/make-memory-store))

;; ---------------------------------------------------------------------------
;; Helpers
;; ---------------------------------------------------------------------------

(defn- json-str [x]
  (js/JSON.stringify (clj->js x)))

(defn- ok [data]
  #js {:content (json-str data)})

(defn- as-vec [f coll]
  (json-str (mapv f coll)))

;; ---------------------------------------------------------------------------
;; Tool handlers — pure CLJS calling actor/muse APIs
;; ---------------------------------------------------------------------------

(defn ^:async handle-muse-spawn
  [{:keys [muse_id]} _ctx]
  (let [id (or muse_id (str "muse." (random-uuid)))]
    (muse/spawn-muse! (keyword id))
    (ok {:muse_id id :status "spawned"})))

(defn ^:async handle-muse-phases
  [{:keys [muse_id]} _ctx]
  (ok {:muse_id muse_id
       :phases (as-vec name (muse/list-phases (keyword muse_id)))}))

(defn ^:async handle-phase-spawn
  [{:keys [muse_id phase_type]} _ctx]
  (let [pid (muse/spawn-phase! (keyword muse_id) (keyword phase_type))]
    (ok {:phase_id (name pid) :muse_id muse_id :phase_type phase_type})))

(defn ^:async handle-phase-list-active
  [{:keys [muse_id]} _ctx]
  (ok {:muse_id muse_id
       :active (as-vec name (muse/list-active (keyword muse_id)))}))

(defn ^:async handle-phase-list-idle
  [{:keys [muse_id]} _ctx]
  (ok {:muse_id muse_id
       :idle (as-vec name (muse/list-idle (keyword muse_id)))}))

(defn ^:async handle-phase-tail
  [{:keys [phase_id n]} _ctx]
  (let [events (muse/tail (keyword phase_id) (or n 10))]
    (ok {:phase_id phase_id :messages (as-vec #(dissoc % :source) events)})))

(defn ^:async handle-phase-head
  [{:keys [phase_id n]} _ctx]
  (let [events (muse/head (keyword phase_id) (or n 10))]
    (ok {:phase_id phase_id :messages (as-vec #(dissoc % :source) events)})))

(defn ^:async handle-phase-filter
  [{:keys [phase_id event_type]} _ctx]
  (let [events (muse/filter-events (keyword phase_id) event_type)]
    (ok {:phase_id phase_id :event_type event_type
         :messages (as-vec #(dissoc % :source) events)})))

(defn ^:async handle-phase-observations
  [{:keys [phase_id]} _ctx]
  (ok {:phase_id phase_id
       :observations (as-vec #(dissoc % :source) (muse/observations (keyword phase_id)))}))

(defn ^:async handle-phase-conclusions
  [{:keys [phase_id]} _ctx]
  (ok {:phase_id phase_id
       :conclusions (as-vec #(dissoc % :source) (muse/conclusions (keyword phase_id)))}))

(defn ^:async handle-tell
  [{:keys [from to event_type payload]} _ctx]
  (let [eid (actor/tell! (keyword from) (keyword to) event_type
                         (js->clj (or payload #js {}) :keywordize-keys true))]
    (ok {:event_id eid :from from :to to})))

(defn ^:async handle-actor-list
  [_args _ctx]
  (ok {:actors (as-vec name (actor/actors))}))

;; ---------------------------------------------------------------------------
;; The export shadow-cljs builds into .opencode/plugins/eta-mu-actors.mjs
;; ---------------------------------------------------------------------------

(defn ^:export ^:async eta-mu-plugin
  "Default export. OpenCode calls: eta-muPlugin(ctx) => hooks-map."
  [_ctx]
  #js
  {:tool
   #js
   {:muse_spawn
    #js {:description "Register a new Muse actor."
         :args #js {:muse_id #js {:type "string" :description "Muse actor ID (auto-generated if omitted)"}}
         :execute handle-muse-spawn}

    :muse_phases
    #js {:description "List all phases spawned by a Muse."
         :args #js {:muse_id #js {:type "string"}}
         :execute handle-muse-phases}

    :phase_spawn
    #js {:description "Spawn a new phase sub-actor under a Muse."
         :args #js {:muse_id   #js {:type "string"}
                    :phase_type #js {:type "string"}}
         :execute handle-phase-spawn}

    :phase_list_active
    #js {:description "List phase actors that have received messages."
         :args #js {:muse_id #js {:type "string"}}
         :execute handle-phase-list-active}

    :phase_list_idle
    #js {:description "List phase actors with empty mailboxes."
         :args #js {:muse_id #js {:type "string"}}
         :execute handle-phase-list-idle}

    :phase_tail
    #js {:description "Return the last N messages from a phase mailbox."
         :args #js {:phase_id #js {:type "string"}
                    :n        #js {:type "number" :optional true :description "Max messages (default 10)"}}
         :execute handle-phase-tail}

    :phase_head
    #js {:description "Return the first N messages from a phase mailbox."
         :args #js {:phase_id #js {:type "string"}
                    :n        #js {:type "number" :optional true}}
         :execute handle-phase-head}

    :phase_filter
    #js {:description "Filter phase mailbox by event type."
         :args #js {:phase_id  #js {:type "string"}
                    :event_type #js {:type "string"}}
         :execute handle-phase-filter}

    :phase_observations
    #js {:description "Get all observations from a phase."
         :args #js {:phase_id #js {:type "string"}}
         :execute handle-phase-observations}

    :phase_conclusions
    #js {:description "Get all conclusions from a phase."
         :args #js {:phase_id #js {:type "string"}}
         :execute handle-phase-conclusions}

    :actor_tell
    #js {:description "Send a message between actors."
         :args #js {:from       #js {:type "string"}
                    :to         #js {:type "string"}
                    :event_type #js {:type "string"}
                    :payload    #js {:type "object" :optional true}}
         :execute handle-tell}

    :actor_list
    #js {:description "List all registered actors."
         :args #js {}
         :execute handle-actor-list}}})
