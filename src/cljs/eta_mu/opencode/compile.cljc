(ns eta-mu.opencode.compile
  "Compiles the canonical registry into OpenCode-compatible adapters.
   Pure functions — the output is data that can be serialized to JSON or JS."
  (:require [eta-mu.opencode.schema :as schema]
            [malli.core :as m]))

;; ---------------------------------------------------------------------------
;; Tool compilation
;; ---------------------------------------------------------------------------

(defn compile-tool
  "Compile a DSL tool definition into an OpenCode-facing tool map."
  [{:keys [id name description args handler requires tags effects source]}]
  {::schema/kind :tool
   :name         (or name (clojure.core/name id))
   :description  description
   :args         args
   :handler      handler
   :permissions  (or tags #{})
   :source       source
   :effects      effects})

;; ---------------------------------------------------------------------------
;; Hook compilation
;; ---------------------------------------------------------------------------

(defn compile-hooks-by-event
  "Group hooks by event, sort by priority (descending), return a map of
   event-keyword → sorted vector of hook maps."
  [hooks]
  (->> hooks
       (map (fn [{:keys [event priority] :as hook}]
              {:priority (or priority 0)
               :handler  (:handler hook)
               :id       (:id hook)
               :event    event}))
       (group-by :event)
        (into {} (map (fn [[event hooks]]
                         [event (sort-by :priority > hooks)])))))

(defn compose-event-handler
  "Create a single callback for an event that runs hooks in priority order.
   Each hook receives [input ctx] and may return an action map:
     {:effect :continue}
     {:effect :reject :message \"...\"}
     {:effect :patch :output {...}}"
  [hooks]
  (fn [input ctx]
    (reduce
     (fn [_ hook]
       (let [result ((:handler hook) input ctx)]
         (cond
           (nil? result) nil
           (= :reject (:effect result)) (reduced result)
           (= :patch (:effect result)) (reduced result)
           :else nil)))
     nil
     hooks)))

(defn compile-hooks
  "Compile hooks into an OpenCode-compatible hooks map."
  [hooks]
  (let [grouped (compile-hooks-by-event hooks)]
    (into {}
          (map (fn [[event sorted-hooks]]
                 [event (compose-event-handler sorted-hooks)]))
          grouped)))

;; ---------------------------------------------------------------------------
;; Full adapter compilation
;; ---------------------------------------------------------------------------

(defn compile-adapter
  "Compile a full registry into an OpenCode adapter.
   Returns a map with :tools, :hooks, and :permissions."
  [{:keys [tools hooks]}]
  {::schema/kind :adapter
   :tools        (mapv compile-tool tools)
   :hooks        (compile-hooks hooks)
   :permissions  (into #{} (mapcat :tags) tools)})

;; ---------------------------------------------------------------------------
;; JSON config generation
;; ---------------------------------------------------------------------------

(defn generate-opencode-json
  "Generate an opencode.json-compatible config from a compiled adapter.
   Returns a data map ready for JSON serialization."
  [{:keys [tools permissions]} & [{:keys [plugin-path] :or {plugin-path "./plugins/eta-mu.mjs"}}]]
  {"$schema"   "https://opencode.ai/config.json"
   "plugin"    [(str "file://" plugin-path)]
   "permission" (into {}
                       (map (fn [perm]
                              [(clojure.core/name perm) "allow"]))
                       permissions)})
