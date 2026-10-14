(ns eta-mu.extern.review-input
  "Bounded original-byte preparation below review-domain behavior. Native
   artifact observations and caller authentication remain outside this adapter.
   GPL-3.0-or-later."
  (:require [eta-mu.extern.json :as json]
            [eta-mu.law.review-invocation :as law]
            [eta-mu.shape.review-input :as input-shape]
            ["node:crypto" :as crypto]))

(defn- invalid! [] (throw (ex-info "Unestablished structured input" {})))

(defn bounded-bytes [raw ceiling]
  (cond
    (string? raw)
    (do (input-shape/bounded-utf8-size raw ceiling)
        (.encode (js/TextEncoder.) raw))
    (instance? js/Uint8Array raw)
    (do (when (> (.-length raw) ceiling) (invalid!)) raw)
    :else (invalid!)))

(defn prepare-ordinary-v2-originals
  "Verify each original against independently held complete input metadata,
   then reuse the owning pure component/view shaping. No tool/session/domain
   behavior, filesystem access or invocation admission is performed."
  [source raw-originals trusted-context]
  (when-not (and (law/ordinary-v2-origin-bound? source trusted-context)
                (vector? raw-originals) (= 3 (count raw-originals)))
    (invalid!))
  (let [ceilings [(:raw-diff-bytes law/ordinary-v2-limits)
                  (:summary-bytes law/ordinary-v2-limits)
                  (:log-bytes law/ordinary-v2-limits)]
        originals (mapv (fn [raw descriptor ceiling]
                          (let [bytes (bounded-bytes raw ceiling)
                                digest (-> (js-invoke crypto "createHash" "sha256")
                                           (js-invoke "update" bytes) (js-invoke "digest" "hex"))]
                            (when-not (and (= (.-length bytes) (:bytes descriptor))
                                            (= digest (:sha256 descriptor))) (invalid!))
                            {:text (json/decode-text bytes) :bytes (.-length bytes) :sha256 digest}))
                        raw-originals (:components source) ceilings)
        shaped (input-shape/assemble-v2 source originals)
        bytes (bounded-bytes (:text shaped) (:view-bytes law/ordinary-v2-limits))
        digest (-> (js-invoke crypto "createHash" "sha256")
                   (js-invoke "update" bytes) (js-invoke "digest" "hex"))]
    (when-not (= digest (get-in source [:view :sha256])) (invalid!))
    (assoc shaped :raw-diff (:text (first originals)))))

(defn prepare-v2-carrier [raw source trusted-context]
  (when-not (law/ordinary-v2-origin-bound? source trusted-context) (invalid!))
  (let [bytes (bounded-bytes raw (:view-bytes law/ordinary-v2-limits))]
    (when-not (= (.-length bytes) (get-in source [:view :bytes])) (invalid!))
    (prepare-ordinary-v2-originals
     source (mapv (fn [{[start end] :byte_span}] (.subarray bytes start end)) (:components source))
     trusted-context)))
