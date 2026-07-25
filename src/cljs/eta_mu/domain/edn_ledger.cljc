(ns eta-mu.domain.edn-ledger
  "Pure logic for generic append-only EDN event ledgers: one event map per
   line. No I/O — the plugin injects lines, a clock, and a uuid fn."
  (:require [clojure.string :as str]
            #?(:clj [clojure.edn :as edn]
               :cljs [cljs.reader :as reader])))

(defn parse-line
  "Parse one ledger line as EDN. Returns the map, or nil for blank/invalid."
  [line]
  (when-not (str/blank? (str line))
    (let [v (#?(:clj edn/read-string :cljs reader/read-string) line)]
      (when (map? v) v))))

(defn parse-lines
  "Parse ledger lines into event maps, dropping blanks and invalid lines."
  [lines]
  (into [] (keep parse-line) lines))

(defn- collapse-newlines [s]
  (-> (str s) (str/replace #"\r?\n+" " ")))

(defn build-event
  "Build the event map to append. `fields` is a map of caller-supplied data;
   injects :event/id and :ts defaults via the given fns. Any :event/type
   string is coerced to a keyword. Newlines inside string values are
   collapsed so one event always fits on one line."
  [fields now-iso uuid]
  (let [event (-> (or fields {})
                  (update :event/type #(if (string? %) (keyword %) %))
                  (cond-> (nil? (:event/id fields)) (assoc :event/id (uuid))
                          (nil? (:ts fields)) (assoc :ts (now-iso))))]
    (into {} (map (fn [[k v]] [k (if (string? v) (collapse-newlines v) v)])) event)))

(defn edn-line
  "Serialize an event map to a single-line EDN string."
  [event]
  (collapse-newlines (pr-str event)))

(defn count-by-type
  "Frequencies of :event/type across events (nil grouped under :unknown)."
  [events]
  (frequencies (map #(or (:event/type %) :unknown) events)))

(defn query-events
  "Filter events by exact :event/type (keyword or string) and/or a substring
   match against the event's pr-str. Returns at most `limit` matches,
   most-recent-last."
  [events {:keys [type contains limit]}]
  (let [type-kw (cond (keyword? type) type
                      (string? type) (keyword type)
                      :else nil)
        pred (fn [e]
               (and (or (nil? type-kw) (= type-kw (:event/type e)))
                    (or (str/blank? contains)
                        (str/includes? (pr-str e) contains))))]
    (cond->> (filter pred events)
      (number? limit) (take limit)
      :always vec)))
