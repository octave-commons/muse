(ns eta-mu.boundaries.node.fs
  "Node filesystem boundary. The only namespace allowed to touch node:fs,
   node:path, node:os. Every function takes and returns plain CLJS data."
  (:refer-clojure :exclude [exists?])
  (:require ["node:fs" :as fs]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]))

(defn home-dir []
  (.homedir os))

(defn env
  "Read an environment variable (first non-blank of the given names)."
  [& names]
  (some #(let [v (unchecked-get (.-env js/process) %)]
           (when (and v (not (str/blank? v))) v))
        names))

(defn join [& segments]
  (apply path/join segments))

(defn dirname [p]
  (let [d (path/dirname p)]
    (when (and d (not= d p)) d)))

(defn exists? [p]
  (.existsSync fs p))

(defn ensure-dir! [dir]
  (.mkdirSync fs dir #js {:recursive true})
  dir)

(defn read-text [p]
  (when (exists? p)
    (.readFileSync fs p "utf8")))

(defn write-text! [p text]
  (ensure-dir! (path/dirname p))
  (.writeFileSync fs p text "utf8")
  p)

(defn append-line! [p line]
  (ensure-dir! (path/dirname p))
  (.appendFileSync fs p (str line "\n") "utf8")
  p)

(defn read-lines
  "Non-blank lines of a file; [] when missing."
  [p]
  (if-let [text (read-text p)]
    (into [] (remove str/blank?) (str/split text #"\r?\n"))
    []))

(defn tail-lines [p n]
  (vec (take-last n (read-lines p))))

;; ---------------------------------------------------------------------------
;; JSONL (wire format owned by the boundary)
;; ---------------------------------------------------------------------------

(defn append-jsonl!
  "Append one value as a JSON line."
  [p value]
  (append-line! p (js/JSON.stringify (clj->js value))))

(defn read-jsonl
  "Last `limit` parseable JSON lines as keywordized CLJS data."
  [p limit]
  (into []
        (keep (fn [line]
                (try
                  (js->clj (js/JSON.parse line) :keywordize-keys true)
                  (catch :default _ nil))))
        (take-last limit (read-lines p))))

;; ---------------------------------------------------------------------------
;; Shared eta-mu state root
;; ---------------------------------------------------------------------------

(defn state-dir
  "~/.eta-mu/state/<name>. Falls back to the pi-era layout
   (~/.eta-mu/agent/state/<name>) only when it alone exists — eta-mu is a
   hard fork of pi and old state may still live there."
  [name]
  (let [current (join (home-dir) ".eta-mu" "state" name)
        legacy  (join (home-dir) ".eta-mu" "agent" "state" name)]
    (cond
      (exists? current) current
      (exists? legacy)  legacy
      :else current)))

(defn now-iso []
  (.toISOString (js/Date.)))
