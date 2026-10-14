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
