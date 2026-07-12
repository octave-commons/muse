(ns eta-mu.dsl.profile
  "Profile-based filtering of registry entries.
   A profile selects which tools/hooks are active based on allow/deny rules.
   Host-agnostic: targets choose which profile applies."
  (:require [clojure.set :as set]))

;; ---------------------------------------------------------------------------
;; Pattern matching
;; ---------------------------------------------------------------------------

(defn matches-pattern?
  "Check if a keyword ID matches a pattern.
   Patterns may use * as a wildcard for the name segment:
     :research/* matches :research/search, :research/index, etc.
     :research/search matches only :research/search"
  [pattern id]
  (cond
    (= pattern id) true
    (and (keyword? pattern)
         (= "*" (name pattern))
         (= (namespace pattern) (namespace id)))
    true
    :else false))

(defn selected?
  "True if an entry passes the profile's allow/deny filters."
  [{:keys [allow deny deny-effects]}
   {:keys [id effects]}]
  (and
   ;; Must be in allow set (or allow is empty = allow all)
   (or (empty? allow)
       (some #(matches-pattern? % id) allow))
   ;; Must not be in deny set
   (not (some #(matches-pattern? % id) (or deny #{})))
   ;; Must not have denied effects
   (not (some #(contains? (or effects #{}) %) (or deny-effects #{})))))

(defn apply-profile
  "Filter a registry's tools and hooks by a profile rule."
  [profile registry]
  (-> registry
      (update :tools #(filterv (partial selected? profile) %))
      (update :hooks #(filterv (partial selected? profile) %))))

(defn available-profiles
  [profiles]
  (set (keys profiles)))

(defn validate-profile
  "Check that a profile doesn't deny effects required by its own allowed tools.
   Returns nil on success, seq of violation maps on failure."
  [profile registry]
  (let [allowed-tools (filterv (partial selected? profile) (:tools registry))
        denied-effects (:deny-effects profile)]
    (when (and denied-effects (seq denied-effects))
      (->> allowed-tools
           (filter (fn [{:keys [effects]}]
                     (some denied-effects (or effects #{}))))
           (mapv (fn [{:keys [id effects]}]
                   {:tool id
                    :denied-effects (set/intersection
                                     (or effects #{})
                                     denied-effects)}))))))
