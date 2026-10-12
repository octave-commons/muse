;; SPDX-License-Identifier: GPL-3.0-or-later
(ns eta-mu.shape.review-input
  "Pure fixed-descriptor projection from a validated transport manifest."
  (:require [eta-mu.law.review-invocation :as invocation]))

(defn input-descriptor
  "Return the original full_diff or full_input map only after law validation.
   This projection grants no native source authority or actionability admission."
  [source]
  (when (invocation/input-source-valid? source)
    (case (:schema source)
      "open-hax.review-input/v1" (:full_diff source)
      "open-hax.actionability-input/v1" (:full_input source)
      "open-hax.rejection-assessment-input/v1" (:full_input source))))
