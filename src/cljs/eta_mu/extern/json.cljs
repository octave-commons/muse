(ns eta-mu.extern.json
  "Shared strict JSON/text decoding boundary; no filesystem or review policy.
   GPL-3.0-or-later."
  (:require [clojure.string :as str]))

(defn- invalid! [] (throw (ex-info "Unestablished structured input" {})))

(defn- well-formed-text? [text]
  (loop [i 0]
    (if (>= i (count text)) true
      (let [c (.charCodeAt text i)]
        (cond
          (<= 55296 c 56319) (and (< (inc i) (count text))
                                 (<= 56320 (.charCodeAt text (inc i)) 57343)
                                 (recur (+ i 2)))
          (<= 56320 c 57343) false
          :else (recur (inc i)))))))

(defn decode-text
  "Fatal UTF8 decoding with well-formed strings and lossless leading BOM."
  [raw]
  (let [text (cond (string? raw) raw
                   (instance? js/Uint8Array raw) (.decode (js/TextDecoder. "utf-8" #js {:fatal true :ignoreBOM true}) raw)
                   :else (invalid!))]
    (when-not (well-formed-text? text) (invalid!))
    text))

(defn strict-json
  "JSON grammar with duplicate member/nonfinite rejection. Parsing is only at
   the runtime boundary. Never recursively interpret decoded text as events."
  [text]
  (let [at (volatile! 0) size (count text)]
    (letfn [(peek-char [] (when (< @at size) (subs text @at (inc @at))))
            (space! [] (while (and (< @at size) (re-matches #"[\t\r\n ]" (peek-char))) (vswap! at inc)))
            (eat! [c] (when-not (= c (peek-char)) (invalid!)) (vswap! at inc))
            (string! []
              (let [start @at]
                (eat! "\"")
                (loop []
                  (when (>= @at size) (invalid!))
                  (case (peek-char)
                    "\"" (do (vswap! at inc)
                               (let [s (js/JSON.parse (subs text start @at))]
                                 (when-not (well-formed-text? s) (invalid!)) s))
                    "\\" (do (vswap! at + 2) (recur))
                    (do (vswap! at inc) (recur))))))
            (number! []
              (let [token (re-find #"^-?(?:0|[1-9][0-9]*)(?:\.[0-9]+)?(?:[eE][+-]?[0-9]+)?" (subs text @at))]
                (when-not token (invalid!))
                (vswap! at + (count token))
                (let [n (js/Number token)] (when-not (js/Number.isFinite n) (invalid!)) n)))
            (literal! [s v] (when-not (str/starts-with? (subs text @at) s) (invalid!))
              (vswap! at + (count s)) v)
            (array! [depth]
              (eat! "[") (space!)
              (if (= "]" (peek-char)) (do (eat! "]") [])
                (loop [items []]
                  (let [items (conj items (value! (inc depth)))]
                    (space!)
                    (case (peek-char)
                      "]" (do (eat! "]") items)
                      "," (do (eat! ",") (space!) (recur items))
                      (invalid!))))))
            (object! [depth]
              (eat! "{") (space!)
              (if (= "}" (peek-char)) (do (eat! "}") {})
                (loop [items {} names #{}]
                  (when-not (= "\"" (peek-char)) (invalid!))
                  (let [k (string!)]
                    (when (contains? names k) (invalid!))
                    (space!) (eat! ":")
                    (let [items (assoc items (keyword k) (value! (inc depth)))]
                      (space!)
                      (case (peek-char)
                        "}" (do (eat! "}") items)
                        "," (do (eat! ",") (space!) (recur items (conj names k)))
                        (invalid!)))))))
            (value! [depth]
              (when (> depth 128) (invalid!)) (space!)
              (case (peek-char)
                "{" (object! depth) "[" (array! depth) "\"" (string!)
                "t" (literal! "true" true) "f" (literal! "false" false)
                "n" (literal! "null" nil) (number!)))]
      (let [v (value! 0)] (space!) (when-not (= @at size) (invalid!)) v))))
