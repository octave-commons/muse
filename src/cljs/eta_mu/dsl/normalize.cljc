(ns eta-mu.dsl.normalize
  "Converts hiccup-style DSL forms into the canonical registry shape and
   merges fragments into a registry. Pure functions, host-agnostic."
  (:require [eta-mu.dsl :as dsl]))

;; ---------------------------------------------------------------------------
;; Hiccup → canonical form
;; ---------------------------------------------------------------------------

(defn normalize-entry
  "Convert a single hiccup entry [:tag {attrs} handler] into a canonical map."
  [[tag attrs & children :as form]]
  (case tag
    :tool
    (let [[handler] children]
      (cond-> (assoc attrs
                     :ημ/kind :tool
                     :name (or (:name attrs)
                               (dsl/default-name (:id attrs)))
                     :handler handler)
        (nil? (:description attrs))
        (assoc :description "")))

    :hook
    (let [[handler] children]
      (assoc attrs
             :ημ/kind :hook
             :priority (or (:priority attrs) 0)
             :handler handler))

    :plugin
    (let [entries (mapv normalize-entry children)]
      (assoc attrs
             :ημ/kind :plugin
             :entries entries))

    (throw (ex-info "Unknown DSL tag"
                    {:tag tag
                     :form form}))))

(defn normalize
  "Normalize a hiccup plugin form into a canonical plugin map."
  [form]
  (normalize-entry form))

;; ---------------------------------------------------------------------------
;; Registry operations
;; ---------------------------------------------------------------------------

(def empty-registry
  {:tools [] :hooks [] :inits [] :plugins []})

(defn merge-fragments
  "Merge plugin/tool/hook fragments into a single registry.
   Collections are concatenated, not merged-by-key. Plugin :init fns are
   gathered into :inits."
  [& fragments]
  (reduce
   (fn [out fragment]
     (let [plugin?  (= :plugin (:ημ/kind fragment))
           entries  (cond
                      (vector? fragment) fragment
                      plugin? (concat (:entries fragment)
                                      (:tools fragment)
                                      (:hooks fragment))
                      :else [fragment])
           out      (cond-> out
                      plugin? (update :plugins conj fragment)
                      (and plugin? (:init fragment))
                      (update :inits conj (:init fragment)))]
       (reduce
        (fn [acc entry]
          (case (:ημ/kind entry)
            :tool   (update acc :tools conj entry)
            :hook   (update acc :hooks conj entry)
            :plugin (-> acc
                        (update :tools into (:tools entry))
                        (update :hooks into (:hooks entry))
                        (update :plugins conj entry))
            acc))
        out
        entries)))
   empty-registry
   fragments))

(defn duplicate-ids
  "Return a sorted sequence of IDs that appear more than once."
  [definitions]
  (->> definitions
       (map :id)
       frequencies
       (keep (fn [[id n]] (when (< 1 n) id)))
       sort))

(defn validate-registry!
  "Validate a merged registry. Throws on duplicate IDs."
  [{:keys [tools hooks] :as registry}]
  (let [duplicates (vec (concat (duplicate-ids tools)
                                (duplicate-ids hooks)))]
    (when (seq duplicates)
      (throw (ex-info "Duplicate DSL IDs in registry"
                      {:duplicates duplicates})))
    registry))

(defn link-handler
  "Resolve a :qualified-symbol :handler to a function from a handler table.
   Handlers that are already functions pass through."
  [handlers {:keys [id handler] :as definition}]
  (cond
    (fn? handler) definition
    (contains? handlers handler) (assoc definition :handler (get handlers handler))
    :else (throw (ex-info "Unknown handler symbol"
                          {:definition-id id
                           :handler-symbol handler
                           :known-handlers (sort (keys handlers))}))))

(defn link-registry
  "Resolve all symbolic handlers in a registry against a handler table."
  [handlers registry]
  (-> registry
      (update :tools #(mapv (partial link-handler handlers) %))
      (update :hooks #(mapv (partial link-handler handlers) %))))
