;; SPDX-License-Identifier: GPL-3.0-or-later
(ns eta-mu.shape.review-input
  "Pure fixed-descriptor projection from a validated transport manifest."
  (:require [clojure.string :as str]
            [eta-mu.law.review-invocation :as invocation]))

(defn input-descriptor
  "Return the original full_diff or full_input map only after law validation.
   This projection grants no native source authority or actionability admission."
  [source]
  (when (invocation/input-source-valid? source)
    (case (:schema source)
      "open-hax.review-input/v1" (:full_diff source)
      "open-hax.review-input/v2" (:view source)
      "open-hax.actionability-input/v1" (:full_input source)
      "open-hax.rejection-assessment-input/v1" (:full_input source)
      "open-hax.positive-cohort-assessment-input/v1" (:full_input source))))

(defn- page-end [text start]
  (let [size (count text)
        bound (min size (+ start 8192))
        bound (if (and (< bound size)
                       (<= 0xD800 #?(:clj (int (.charAt ^String text (dec bound)))
                                     :cljs (.charCodeAt text (dec bound))) 0xDBFF))
                (dec bound) bound)
        page (subs text start bound)]
    (loop [at 0 lines 0]
      (if-let [newline (str/index-of page "\n" at)]
        (if (= 127 lines) (+ start (inc newline)) (recur (inc newline) (inc lines)))
        bound))))

(defn diff-chunks
  "Canonical lossless pages, preserving the existing 8192 UTF-16/128-line
   geometry and surrogate boundaries. V1/native have no new resource ceiling."
  [text]
  (let [text (or text "") size (count text)]
    (loop [start 0 chunks []]
      (if (= start size) chunks
        (let [end (page-end text start)]
          (recur end (conj chunks {:id (inc (count chunks)) :start start :end end
                                  :text (subs text start end)})))))))

(defn bounded-page-count
  "Count using canonical geometry without collecting pages; stop at the first
   page beyond the fixed v2 ceiling. No complete collection precedes admission."
  [text]
  (loop [start 0 pages 0]
    (cond
      (= start (count text)) pages
      (= pages (:pages invocation/ordinary-v2-limits))
      (throw (ex-info "Ordinary-v2 exceeds the fixed page ceiling" {}))
      :else (recur (page-end text start) (inc pages)))))

(defn bounded-utf8-size
  "Count well-formed UTF-16 text's UTF-8 bytes before encoding/allocation. Stop
   immediately beyond the caller's fixed ceiling; never replace lone surrogates."
  [text ceiling]
  (loop [at 0 bytes 0]
    (when (> bytes ceiling) (throw (ex-info "Ordinary-v2 exceeds its byte ceiling" {})))
    (if (= at (count text)) bytes
      (let [c #?(:clj (int (.charAt ^String text at)) :cljs (.charCodeAt text at))]
        (cond
          (< c 128) (recur (inc at) (inc bytes))
          (< c 2048) (recur (inc at) (+ bytes 2))
          (<= 0xD800 c 0xDBFF)
          (if (and (< (inc at) (count text))
                   (<= 0xDC00 #?(:clj (int (.charAt ^String text (inc at)))
                                 :cljs (.charCodeAt text (inc at))) 0xDFFF))
            (recur (+ at 2) (+ bytes 4))
            (throw (ex-info "Unpaired ordinary-v2 surrogate" {})))
          (<= 0xDC00 c 0xDFFF) (throw (ex-info "Unpaired ordinary-v2 surrogate" {}))
          :else (recur (inc at) (+ bytes 3)))))))

(defn assemble-v2
  "Shape the exact ordered originals after an edge has verified UTF8 and their
   byte counts/digests. Does not compute or authenticate an edge's hash facts.
   Preserve empty roles, separate span units and raw full_diff identity."
  [source originals]
  (when-not (and (invocation/ordinary-v2-contract-valid? source)
                (vector? originals) (= 3 (count originals)))
    (throw (ex-info "Invalid ordinary-v2 components" {})))
  (let [components (:components source)
        total-bytes (reduce + (map :bytes originals))]
    (when-not (and (<= total-bytes (:view-bytes invocation/ordinary-v2-limits))
                  (= total-bytes (get-in source [:view :bytes]))
                  (every? true?
                          (map (fn [descriptor original]
                                 (and (string? (:text original))
                                      (= (:bytes descriptor) (:bytes original))
                                      (= (:sha256 descriptor) (:sha256 original))
                                      (= (- (second (:text_span descriptor))
                                            (first (:text_span descriptor)))
                                         (count (:text original))))) components originals)))
      (throw (ex-info "Ordinary-v2 original identity or geometry differs" {})))
    (let [text (apply str (map :text originals))
          pages (bounded-page-count text)]
      (when-not (and (= (count text) (get-in source [:view :text_units]))
                    (= pages (get-in source [:view :page_count])))
        (throw (ex-info "Ordinary-v2 view geometry differs" {})))
      {:text text :components components :view (:view source) :page-count pages})))

(defn- append-span [spans start end]
  (if (= start (second (peek spans)))
    (assoc spans (dec (count spans)) [(first (peek spans)) end])
    (conj spans [start end])))

(defn- raw-hunk-spans
  "Retain unified-hunk membership before canonical pages split it. Counts end
   a hunk; file headers never become changed rows. Spans use original text units."
  [text]
  (loop [at 0 old-left 0 new-left 0 in-hunk? false hunks [] changed []]
    (if (= at (count text)) {:hunks hunks :changed changed}
      (let [newline (str/index-of text "\n" at)
            end (if newline (inc newline) (count text))
            row (subs text at (or newline end))
            header (re-find #"^@@ -[0-9]+(?:,([0-9]+))? \+[0-9]+(?:,([0-9]+))? @@" row)
            marker? (str/starts-with? row "\\ No newline at end of file")
            active? (and in-hunk? (or (pos? old-left) (pos? new-left) marker?))
            prefix (first row)
            body? (and active? (or marker? (contains? #{\space \+ \-} prefix)))]
        (cond
          header (recur end (if (second header) (parse-long (second header)) 1)
                        (if (nth header 2) (parse-long (nth header 2)) 1) true
                        (append-span hunks at end) changed)
          body? (recur end
                       (max 0 (- old-left (if (#{\space \-} prefix) 1 0)))
                       (max 0 (- new-left (if (#{\space \+} prefix) 1 0))) true
                       (append-span hunks at end)
                       (if (#{\+ \-} prefix) (append-span changed at end) changed))
          :else (recur end 0 0 false hunks changed))))))

(defn- local-spans [spans start end]
  (into [] (keep (fn [[low high]]
                   (when (< (max low start) (min high end))
                     [(- (max low start) start) (- (min high end) start)]))) spans))

(defn component-chunks
  "Reuse the canonical pager independently on each exact original. Global IDs
   identify delivered pages; local spans identify originals, view spans preserve
   transport identity. Zero-byte components remain descriptors with no pages."
  ([source text] (component-chunks source text nil))
  ([source text aggregate-pages]
  (when-not (and (invocation/ordinary-v2-contract-valid? source)
                (string? text) (= (count text) (get-in source [:view :text_units])))
    (throw (ex-info "Invalid complete component input" {})))
  ;; Role boundaries can add pages even when the aggregate view is within its
  ;; ceiling. Admit the complete count before collecting any component pages.
  (when (> (reduce + (map (fn [{[start end] :text_span}]
                           (bounded-page-count (subs text start end)))
                         (:components source)))
           (:pages invocation/ordinary-v2-limits))
    (throw (ex-info "Ordinary-v2 exceeds the combined component page ceiling" {})))
  (reduce
   (fn [pages {:keys [role bytes sha256 byte_span text_span]}]
     (let [[ts te] text_span original (subs text ts te)
           hunk-data (when (= role "raw-diff") (raw-hunk-spans original))]
       (:pages
        (reduce (fn [{:keys [pages byte-start]} {:keys [start end text]}]
                  (let [byte-end (+ byte-start (bounded-utf8-size text (:view-bytes invocation/ordinary-v2-limits)))]
                    {:byte-start byte-end
                     :pages (conj pages
                                  (cond-> {:id (inc (count pages)) :role role :component-sha256 sha256
                                   :component-byte-span [byte-start byte-end]
                                   :component-text-span [start end]
                                   :view-byte-span [(+ (first byte_span) byte-start)
                                                    (+ (first byte_span) byte-end)]
                                   :view-text-span [(+ ts start) (+ ts end)]
                                   :start (+ ts start) :end (+ ts end) :text text}
                                    hunk-data (assoc :hunk-spans (local-spans (:hunks hunk-data) start end)
                                                     :changed-spans (local-spans (:changed hunk-data) start end))))}))
                {:pages pages :byte-start 0}
                (cond
                  (zero? bytes) []
                  (and aggregate-pages (= 1 (count (filter #(pos? (:bytes %)) (:components source)))))
                  aggregate-pages
                  :else (diff-chunks original))))))
   [] (:components source))))
