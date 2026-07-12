(ns eta-mu.dsl.compile
  "Compiles the canonical registry into a host-agnostic adapter:
   tools as flat descriptors, hooks composed per event in priority order,
   init fns carried through. Target boundaries render the adapter into
   whatever their host expects. Pure functions."
  (:require [eta-mu.dsl :as dsl]
            [eta-mu.dsl.schema :as schema]))

;; ---------------------------------------------------------------------------
;; Tool compilation
;; ---------------------------------------------------------------------------

(defn compile-tool
  [{:keys [id name description args handler tags effects source]}]
  {::schema/kind :tool
   :name         (or name (dsl/default-name id))
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
  "Group hooks by event, sorted by priority (descending)."
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
  "One callback per event, running hooks in priority order.
   Hooks receive [input ctx] and may return the effect algebra:
     nil | {:effect :reject :message ...} | {:effect :patch :output {...}}"
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
  "Compile a registry into an adapter: {:tools [...] :hooks {event fn}
   :inits [...] :permissions #{...}}."
  [{:keys [tools hooks inits]}]
  {::schema/kind :adapter
   :tools        (mapv compile-tool tools)
   :hooks        (compile-hooks hooks)
   :inits        (vec inits)
   :permissions  (into #{} (mapcat :tags) tools)})
