(ns eta-mu.boundaries.node.fs
  "Node filesystem boundary. The only namespace allowed to touch node:fs,
   node:path, node:os. Every function takes and returns plain CLJS data."
  (:refer-clojure :exclude [exists?])
  (:require ["node:fs" :as fs]
            ["node:crypto" :as crypto]
            ["node:os" :as os]
            ["node:path" :as path]
            [clojure.string :as str]
            [eta-mu.law.review-invocation :as invocation]
            [eta-mu.shape.review-input :as input-shape]
            [eta-mu.extern.review-invocation :as review-input]
            [eta-mu.extern.json :as json]))

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

(defn remove-file!
  "Remove one owned output file if present; never recurse into a directory."
  [p]
  (when (exists? p) (.unlinkSync fs p)))

(defn read-bounded-file
  "Read one fixed regular file with stat/fstat limits before allocation, an
   exact-size bounded read and one EOF probe. Refuse symlink/identity changes,
   short reads and growth; always close the owned descriptor."
  [file ceiling expected-size]
  (let [stat (.lstatSync fs file)]
    (when-not (and (.isFile stat) (js/Number.isSafeInteger (.-size stat))
                  (<= 0 (.-size stat) ceiling)
                  (or (nil? expected-size) (= expected-size (.-size stat))))
      (throw (js/Error. "Full-review input file exceeds its bound or differs from its descriptor.")))
    (let [fd (.openSync fs file (bit-or (.-O_RDONLY (.-constants fs))
                                      (.-O_NOFOLLOW (.-constants fs))
                                      (.-O_NONBLOCK (.-constants fs))))]
      (try
        (let [opened (.fstatSync fs fd) size (.-size opened)]
          (when-not (and (.isFile opened) (= (.-dev stat) (.-dev opened))
                        (= (.-ino stat) (.-ino opened)) (= (.-size stat) size))
            (throw (js/Error. "Full-review input changed while opening.")))
          (let [bytes (js/Buffer.alloc size)]
            (loop [offset 0]
              (when (< offset size)
                (let [read (.readSync fs fd bytes offset (- size offset) offset)]
                  (when (zero? read) (throw (js/Error. "Full-review input has a short read.")))
                  (recur (+ offset read)))))
            (let [probe (js/Buffer.alloc 1) extra (.readSync fs fd probe 0 1 size)
                  after (.fstatSync fs fd) path-after (.lstatSync fs file)]
              (when-not (and (zero? extra) (= size (.-size after))
                            (= (.-dev opened) (.-dev path-after))
                            (= (.-ino opened) (.-ino path-after)) (.isFile path-after))
                (throw (js/Error. "Full-review input changed during its bounded read."))))
            bytes))
        (finally (.closeSync fs fd))))))

(defn read-review-input
  "Legacy one-argument input preserves its metadata/content policy and refuses
   v2. The explicit context-supplied entry selects the bounded v2 profile before
   I/O; candidate JSON cannot downgrade it or raise its fixed metadata ceiling."
  ([dir]
   (let [manifest (json/strict-json (json/decode-text (.readFileSync fs (join dir "input-manifest.json"))))
         full (when-not (= "open-hax.review-input/v2" (:schema manifest))
                (input-shape/input-descriptor manifest))]
     (when-not full (throw (js/Error. "Invalid full-review input manifest.")))
     (let [bytes (.readFileSync fs (join dir (:path full)))
           digest (-> (.createHash crypto "sha256") (.update bytes) (.digest "hex"))]
       (when-not (and (= (:bytes full) (.-length bytes)) (= (:sha256 full) digest))
         (throw (js/Error. "Full-review input is missing bytes or differs from its immutable manifest.")))
       {:manifest manifest :text (json/decode-text bytes)})))
  ([dir trusted-context]
   (when-not (invocation/ordinary-v2-trusted-context-valid? trusted-context)
     (throw (js/Error. "Ordinary-v2 requires independently trusted producer/content context.")))
   (let [manifest (json/strict-json
                   (json/decode-text
                    (read-bounded-file (join dir "input-manifest.json")
                                       (:view-bytes invocation/ordinary-v2-limits) nil)))]
     (when-not (and (= "open-hax.review-input/v2" (:schema manifest))
                    (invocation/ordinary-v2-origin-bound? manifest trusted-context))
       (throw (js/Error. "Ordinary-v2 producer/content context or selected profile differs.")))
     (let [ceilings [(:raw-diff-bytes invocation/ordinary-v2-limits)
                     (:summary-bytes invocation/ordinary-v2-limits)
                     (:log-bytes invocation/ordinary-v2-limits)]
           originals (mapv (fn [descriptor ceiling]
                             (read-bounded-file (join dir (:path descriptor)) ceiling (:bytes descriptor)))
                           (:components manifest) ceilings)
           shaped (review-input/prepare-ordinary-v2-originals manifest originals trusted-context)]
       {:manifest manifest :text (:text shaped) :components (:components shaped)
        :view (:view shaped) :raw-diff (:raw-diff shaped)}))))

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

(defn write-text-if-changed!
  "Write only when content differs; returns true when it wrote."
  [p text]
  (if (= text (read-text p))
    false
    (do (write-text! p text) true)))

(defn find-dirs
  "Breadth-first search under `root` for real directories named `target`,
   descending at most `max-depth` levels and skipping directories where
   (skip? name) is true. Symlinked directories are never followed, so a
   .eta-mu → .ημ symlink is not double-counted."
  [root target {:keys [max-depth skip?] :or {max-depth 4 skip? (constantly false)}}]
  (loop [frontier [[root 0]]
         found    []]
    (if-let [[dir depth] (first frontier)]
      (let [entries (try
                      (.readdirSync fs dir #js {:withFileTypes true})
                      (catch :default _ #js []))
            dirs    (into []
                          (comp (filter #(.isDirectory ^js %))
                                (map #(.-name ^js %))
                                (remove skip?))
                          entries)
            hits    (into [] (comp (filter #(= target %))
                                   (map #(join dir %)))
                          dirs)
            next-fr (when (< depth max-depth)
                      (into []
                            (comp (remove #(= target %))
                                  (map (fn [n] [(join dir n) (inc depth)])))
                            dirs))]
        (recur (into (vec (rest frontier)) next-fr)
               (into found hits)))
      found)))

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
