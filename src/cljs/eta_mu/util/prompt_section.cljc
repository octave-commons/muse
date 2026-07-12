(ns eta-mu.util.prompt-section
  "Idempotent prompt-section injection.

   Plugins that augment a system prompt across turns must replace their
   section in place instead of appending forever — hosts often feed the
   previously augmented prompt back in. Pure string functions, cljc."
  (:require [clojure.string :as str]))

(defn strip-section
  "Remove a [start-marker … end-marker] block from text. An unterminated
   block (start without end) is stripped to the end of the text."
  [text start-marker end-marker]
  (let [text (or text "")]
    (if-let [start-idx (str/index-of text start-marker)]
      (let [end-search-start (+ start-idx (count start-marker))
            end-idx   (str/index-of text end-marker end-search-start)
            after-end (if (nil? end-idx)
                        ""
                        (subs text (+ end-idx (count end-marker))))
            before    (subs text 0 start-idx)]
        (str before (str/replace after-end #"^\s*" "")))
      text)))

(defn upsert-section
  "Replace-or-append a marked section. A blank body removes the section."
  [text start-marker end-marker body]
  (let [base (str/trimr (strip-section text start-marker end-marker))]
    (if (str/blank? body)
      base
      (str base
           (when-not (str/blank? base) "\n\n")
           start-marker "\n"
           body "\n"
           end-marker))))
