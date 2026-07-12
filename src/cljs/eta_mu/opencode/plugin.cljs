(ns eta-mu.opencode.plugin
  "OpenCode plugin: actor system tools and Muse orchestration.
   All logic in CLJS. Uses @opencode-ai/plugin's tool() helper for definitions."
  (:require [eta-mu.actor :as actor]
            [eta-mu.actor.muse :as muse]
            [eta-mu.actor.memory :as mem]
            ["@opencode-ai/plugin" :refer [tool]]))

;; Initialize store
(actor/init-store! (mem/make-memory-store))

;; ---------------------------------------------------------------------------
;; Tool definitions via tool() helper
;; ---------------------------------------------------------------------------

(def muse-spawn
  (tool
   #js {:description "Register a new Muse actor."
        :args #js {}
        :execute
        (fn [args _ctx]
          (let [muse-id (or (.-muse_id args) (str "muse." (random-uuid)))]
            (muse/spawn-muse! (keyword muse-id))
            #js {:content (js/JSON.stringify #js {:muse_id muse-id :status "spawned"})}))}))

(def muse-phases
  (tool
   #js {:description "List all phases spawned by a Muse."
        :args #js {:muse_id (.. tool -schema (string))}
        :execute
        (fn [args _ctx]
          (let [phases (muse/list-phases (keyword (.-muse_id args)))]
            #js {:content (js/JSON.stringify (clj->js (mapv name phases)))}))}))

(def phase-spawn
  (tool
   #js {:description "Spawn a new phase sub-actor under a Muse."
        :args #js {:muse_id   (.. tool -schema (string))
                   :phase_type (.. tool -schema (string))}
        :execute
        (fn [args _ctx]
          (let [pid (muse/spawn-phase! (keyword (.-muse_id args))
                                       (keyword (.-phase_type args)))]
            #js {:content (js/JSON.stringify
                           #js {:phase_id (name pid)
                                :muse_id (.-muse_id args)
                                :phase_type (.-phase_type args)})}))}))

(def phase-list-active
  (tool
   #js {:description "List phase actors that have received messages."
        :args #js {:muse_id (.. tool -schema (string))}
        :execute
        (fn [args _ctx]
          (let [active (muse/list-active (keyword (.-muse_id args)))]
            #js {:content (js/JSON.stringify (clj->js (mapv name active)))}))}))

(def phase-list-idle
  (tool
   #js {:description "List phase actors with empty mailboxes."
        :args #js {:muse_id (.. tool -schema (string))}
        :execute
        (fn [args _ctx]
          (let [idle (muse/list-idle (keyword (.-muse_id args)))]
            #js {:content (js/JSON.stringify (clj->js (mapv name idle)))}))}))

(def phase-tail
  (tool
   #js {:description "Return the last N messages from a phase mailbox."
        :args #js {:phase_id (.. tool -schema (string))
                   :n        (.. tool -schema (number) (optional))}
        :execute
        (fn [args _ctx]
          (let [n      (or (.-n args) 10)
                events (muse/tail (keyword (.-phase_id args)) n)]
            #js {:content (js/JSON.stringify
                           (clj->js (mapv #(dissoc % :source) events)))}))}))

(def phase-head
  (tool
   #js {:description "Return the first N messages from a phase mailbox."
        :args #js {:phase_id (.. tool -schema (string))
                   :n        (.. tool -schema (number) (optional))}
        :execute
        (fn [args _ctx]
          (let [n      (or (.-n args) 10)
                events (muse/head (keyword (.-phase_id args)) n)]
            #js {:content (js/JSON.stringify
                           (clj->js (mapv #(dissoc % :source) events)))}))}))

(def phase-filter
  (tool
   #js {:description "Filter phase mailbox by event type."
        :args #js {:phase_id  (.. tool -schema (string))
                   :event_type (.. tool -schema (string))}
        :execute
        (fn [args _ctx]
          (let [events (muse/filter-events (keyword (.-phase_id args))
                                           (.-event_type args))]
            #js {:content (js/JSON.stringify
                           (clj->js (mapv #(dissoc % :source) events)))}))}))

(def phase-observations
  (tool
   #js {:description "Get all observations from a phase."
        :args #js {:phase_id (.. tool -schema (string))}
        :execute
        (fn [args _ctx]
          (let [events (muse/observations (keyword (.-phase_id args)))]
            #js {:content (js/JSON.stringify
                           (clj->js (mapv #(dissoc % :source) events)))}))}))

(def phase-conclusions
  (tool
   #js {:description "Get all conclusions from a phase."
        :args #js {:phase_id (.. tool -schema (string))}
        :execute
        (fn [args _ctx]
          (let [events (muse/conclusions (keyword (.-phase_id args)))]
            #js {:content (js/JSON.stringify
                           (clj->js (mapv #(dissoc % :source) events)))}))}))

(def actor-tell
  (tool
   #js {:description "Send a message between actors."
        :args #js {:from       (.. tool -schema (string))
                   :to         (.. tool -schema (string))
                   :event_type (.. tool -schema (string))
                   :payload    (.. tool -schema (object) (optional))}
        :execute
        (fn [args _ctx]
          (let [eid (actor/tell! (keyword (.-from args))
                                 (keyword (.-to args))
                                 (.-event_type args)
                                 (js->clj (or (.-payload args) #js {})
                                          :keywordize-keys true))]
            #js {:content (js/JSON.stringify #js {:event_id eid
                                                  :from (.-from args)
                                                  :to   (.-to args)})}))}))

(def actor-list
  (tool
   #js {:description "List all registered actors."
        :args #js {}
        :execute
        (fn [_args _ctx]
          #js {:content (js/JSON.stringify (clj->js (mapv name (actor/actors))))})}))

;; ---------------------------------------------------------------------------
;; Plugin export
;; ---------------------------------------------------------------------------

(defn ^:export default
  "OpenCode plugin entry point. Returns hooks + tool map."
  [_ctx]
  #js {:tool
       #js {:muse_spawn         muse-spawn
            :muse_phases        muse-phases
            :phase_spawn        phase-spawn
            :phase_list_active  phase-list-active
            :phase_list_idle    phase-list-idle
            :phase_tail         phase-tail
            :phase_head         phase-head
            :phase_filter       phase-filter
            :phase_observations phase-observations
            :phase_conclusions  phase-conclusions
            :actor_tell         actor-tell
            :actor_list         actor-list}

       "tool.execute.before"
       (fn [_input _output] nil)

       "tool.execute.after"
       (fn [_input _output] nil)})
