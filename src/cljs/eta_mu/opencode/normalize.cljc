(ns eta-mu.opencode.normalize
  "Converts hiccup-style DSL forms into the canonical registry shape.
   Pure functions, no side effects."
  (:require [eta-mu.opencode.schema :as schema]
            [malli.core :as m]))

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
                     :opencode/kind :tool
                     :name (or (:name attrs)
                               (clojure.core/name (:id attrs)))
                     :handler handler)
        (nil? (:description attrs))
        (assoc :description "")))

    :hook
    (let [[handler] children]
      (assoc attrs
             :opencode/kind :hook
             :priority (or (:priority attrs) 0)
             :handler handler))

    :plugin
    (let [entries (mapv normalize-entry children)]
      (assoc attrs
             :opencode/kind :plugin
             :entries entries))

    (throw (ex-info "Unknown DSL tag"
                    {:tag tag
                     :form form}))))

(defn normalize
  "Normalize a hiccup plugin form into a canonical plugin map.
   Input:  [:plugin {:id :my/plugin} [:tool {...} handler] ...]
   Output: {:opencode/kind :plugin :id :my/plugin :entries [...]}"
  [form]
  (normalize-entry form))

;; ---------------------------------------------------------------------------
;; Registry operations
;; ---------------------------------------------------------------------------

(defn merge-fragments
  "Merge multiple plugin/registry fragments into a single registry.
   Collections are concatenated, not merged-by-key."
  [& fragments]
  (reduce
   (fn [out fragment]
     (let [entries (cond
                     (vector? fragment) fragment
                     (= :plugin (:opencode/kind fragment)) (:entries fragment)
                     :else [fragment])]
       (reduce
        (fn [acc entry]
          (case (:opencode/kind entry)
            :tool   (update acc :tools conj entry)
            :hook   (update acc :hooks conj entry)
            :plugin (-> acc
                        (update :tools into (:tools entry))
                        (update :hooks into (:hooks entry))
                        (update :plugins conj entry))
            acc))
        out
        entries)))
   {:tools []
    :hooks []
    :plugins []}
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
  "Resolve a :qualified-symbol :handler to an actual function from a handler map.
   Throws if the symbol is not found."
  [handlers {:keys [id handler] :as definition}]
  (if-let [resolved (get handlers handler)]
    (assoc definition :handler resolved)
    (throw (ex-info "Unknown handler symbol"
                    {:definition-id id
                     :handler-symbol handler
                     :known-handlers (sort (keys handlers))}))))

(defn link-registry
  "Resolve all handler symbols in a registry against a handler map.
   Returns the registry with :handler values replaced by functions."
  [handlers registry]
  (-> registry
      (update :tools #(mapv (partial link-handler handlers) %))
      (update :hooks #(mapv (partial link-handler handlers) %))))

;; ---------------------------------------------------------------------------
;; Full pipeline
;; ---------------------------------------------------------------------------

(defn build-registry
  "Build a validated, linked registry from fragments and a handler map.
   Steps: merge → validate-duplicates → link-handlers."
  [handler-map & fragments]
  (-> (apply merge-fragments fragments)
      validate-registry!
      (link-registry handler-map)))
