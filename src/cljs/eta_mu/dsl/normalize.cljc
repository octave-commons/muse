;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.dsl.normalize
  "Normalizes DSL forms, merges resources, and links separated descriptors
   into the legacy flat tool projection. Pure and host-agnostic."
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
;; Descriptor linking
;; ---------------------------------------------------------------------------

(defn duplicate-ids
  "Return a sorted sequence of IDs that appear more than once."
  [definitions]
  (->> definitions
       (map :id)
       frequencies
       (keep (fn [[id n]] (when (< 1 n) id)))
       sort))

(defn- index-definitions!
  [kind definitions]
  (let [duplicates (vec (duplicate-ids definitions))]
    (when (seq duplicates)
      (throw (ex-info "Duplicate DSL descriptor IDs"
                      {:kind kind
                       :duplicates duplicates})))
    (into {} (map (juxt :id identity)) definitions)))

(defn- referenced!
  [kind reference definitions exposure]
  (or (get definitions reference)
      (throw (ex-info "Exposure references an unknown descriptor"
                      {:kind kind
                       :reference reference
                       :exposure (:id exposure)
                       :known (sort (keys definitions))}))))

(defn link-descriptors
  "Link registry exposures into legacy flat tools while retaining the source
   descriptor collections. Idempotent: generated tools carry an internal
   exposure provenance key and are not emitted twice."
  [registry]
  (let [capabilities   (vec (or (:capabilities registry) []))
        implementations (vec (or (:implementations registry) []))
        exposures      (vec (or (:exposures registry) []))]
    (if (empty? exposures)
      registry
      (let [capability-index    (index-definitions! :capability capabilities)
            implementation-index (index-definitions! :implementation implementations)
            linked-exposures    (into #{} (keep :ημ/exposure-id) (:tools registry))
            linked-tools
            (into []
                  (comp
                   (remove #(contains? linked-exposures (:id %)))
                   (map (fn [exposure]
                          (let [capability-definition
                                (referenced! :capability
                                             (:capability exposure)
                                             capability-index
                                             exposure)
                                implementation-definition
                                (referenced! :implementation
                                             (:implementation exposure)
                                             implementation-index
                                             exposure)]
                            (assoc (dsl/link-tool capability-definition
                                                  implementation-definition
                                                  exposure)
                                   :ημ/exposure-id (:id exposure))))))
                  exposures)]
        (update registry :tools (fnil into []) linked-tools)))))

;; ---------------------------------------------------------------------------
;; Registry operations
;; ---------------------------------------------------------------------------

(def empty-registry
  {:tools [] :hooks [] :inits [] :plugins []})

(defn- plugin-entries
  [plugin]
  (concat (:entries plugin)
          (:tools plugin)
          (:hooks plugin)
          (:capabilities plugin)
          (:implementations plugin)
          (:exposures plugin)))

(defn- add-entry
  [registry entry]
  (case (:ημ/kind entry)
    :tool          (update registry :tools conj entry)
    :hook          (update registry :hooks conj entry)
    :capability    (update registry :capabilities (fnil conj []) entry)
    :implementation (update registry :implementations (fnil conj []) entry)
    :exposure      (update registry :exposures (fnil conj []) entry)
    :plugin        (-> registry
                       (update :tools into (:tools entry))
                       (update :hooks into (:hooks entry))
                       (update :capabilities (fnil into []) (:capabilities entry))
                       (update :implementations (fnil into []) (:implementations entry))
                       (update :exposures (fnil into []) (:exposures entry))
                       (update :plugins conj entry))
    registry))

(defn merge-fragments
  "Merge plugin/tool/hook/descriptor fragments into one registry and link any
   complete capability → implementation → exposure triples into flat tools."
  [& fragments]
  (->
   (reduce
    (fn [out fragment]
      (let [plugin? (= :plugin (:ημ/kind fragment))
            entries (cond
                      (vector? fragment) fragment
                      plugin? (plugin-entries fragment)
                      :else [fragment])
            out (cond-> out
                  plugin? (update :plugins conj fragment)
                  (and plugin? (:init fragment))
                  (update :inits conj (:init fragment)))]
        (reduce add-entry out entries)))
    empty-registry
    fragments)
   link-descriptors))

(defn validate-registry!
  "Link descriptors and reject duplicate IDs within each definition kind."
  [registry]
  (let [{:keys [tools hooks capabilities implementations exposures] :as registry}
        (link-descriptors registry)
        duplicates (vec (concat (duplicate-ids tools)
                                (duplicate-ids hooks)
                                (duplicate-ids capabilities)
                                (duplicate-ids implementations)
                                (duplicate-ids exposures)))]
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
  "Resolve symbolic handlers in implementations, linked tools, and hooks."
  [handlers registry]
  (let [registry (cond-> registry
                   (:implementations registry)
                   (update :implementations
                           #(mapv (partial link-handler handlers) %)))]
    (-> registry
        link-descriptors
        (update :tools #(mapv (partial link-handler handlers) %))
        (update :hooks #(mapv (partial link-handler handlers) %)))))
