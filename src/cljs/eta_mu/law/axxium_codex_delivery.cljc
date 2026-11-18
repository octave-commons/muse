;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.law.axxium-codex-delivery
  "Candidate admission contract only. No receiver or runtime consumes it yet."
  (:require [malli.core :as m]))

(def ^:private nonblank
  [:and :string [:re #"\S"]])

(def ^:private exact-binding
  [:map {:closed true}
   [:origin nonblank]
   [:actor-did nonblank]
   [:mailbox-did nonblank]
   [:host-id nonblank]
   [:thread-id nonblank]
   [:generation pos-int?]])

(def ^:private queue-proposal
  [:map {:closed true}
   [:approved-binding exact-binding]
   [:observed-binding exact-binding]
   [:binding-current? :boolean]
   [:target-state [:enum :idle :active :pending :approval :compacting :unknown]]
   [:submission-id nonblank]
   [:attempt-state [:enum :initial :rejected :accepted :uncertain]]
   [:reconciled? :boolean]
   [:capability
    [:map {:closed true}
     [:mode [:enum :atomic-queue :send-now :cli-dispatch :unverified]]
     [:verified? :boolean]
     [:no-steer? :boolean]
     [:no-start? :boolean]
     [:idempotent? :boolean]
     [:reconcilable? :boolean]]]])

(defn queue-admissible?
  "Validate trusted evidence for queue-only admission, never wake or steer.
   The caller must authenticate these assertions outside this pure law.
   Equality includes binding generation; uncertain/accepted attempts reconcile
   before redispatch. This function creates neither receipts nor authority."
  [proposal]
  (boolean
   (and (m/validate queue-proposal proposal)
        (let [{:keys [approved-binding observed-binding binding-current? target-state
                      attempt-state reconciled? capability]} proposal]
          (and (= approved-binding observed-binding)
               binding-current?
               (#{:idle :active} target-state)
               (or (= :initial attempt-state)
                   (and (= :rejected attempt-state) reconciled?))
               (= {:mode :atomic-queue
                   :verified? true :no-steer? true :no-start? true
                   :idempotent? true :reconcilable? true}
                  capability))))))
