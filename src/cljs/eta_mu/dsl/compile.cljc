(ns eta-mu.dsl.compile
  "Compiles the canonical registry into a host-agnostic adapter:
   tools as flat descriptors, hooks composed per event in priority order,
   init fns carried through. Target boundaries render the adapter into
   whatever their host expects. Pure functions."
  (:require [eta-mu.dsl :as dsl]
            [eta-mu.dsl.schema :as schema]
            [promesa.core :as p]))

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
   Hooks receive [input ctx] and may return the effect algebra directly OR
   as a promise of it (e.g. a hook that awaits an HTTP call before deciding):
     nil | {:effect :reject :message ...} | {:effect :patch :output {...}}

   Always returns a promise of the first non-nil verdict (or nil once every
   hook has been asked), regardless of whether any individual hook was
   itself synchronous -- callers never need to branch on that; they just
   `.then`/await the result. A `reduce` cannot do this (it can't suspend
   between synchronous steps to await one), so this steps through `hooks`
   explicitly via a promise chain instead."
  [hooks]
  (fn [input ctx]
    (letfn [(step [i]
              (if (>= i (count hooks))
                nil
                (p/let [result ((:handler (nth hooks i)) input ctx)]
                  (cond
                    (nil? result) (step (inc i))
                    (= :reject (:effect result)) result
                    (= :patch (:effect result)) result
                    :else (step (inc i))))))]
      (step 0))))

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
