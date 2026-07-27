;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.dsl.schema
  "Malli schemas for Muse's host-agnostic descriptor and adapter shapes.

   Capability, implementation, and exposure are separate records. The legacy
   flat tool schema remains because current target adapters consume the linked
   projection produced by eta-mu.dsl/link-tool."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Schema expressions and handlers
;; ---------------------------------------------------------------------------

(def schema-expr
  "A Malli schema expression: keyword or vector."
  [:or :keyword [:vector :any]])

(def handler
  "An executable function or a qualified symbol resolved during linking."
  [:or fn? :qualified-symbol])

(def source-location
  [:map
   [:file :string]
   [:line :int]
   [:column :int]])

;; ---------------------------------------------------------------------------
;; Separated descriptors
;; ---------------------------------------------------------------------------

(def capability
  "Semantic meaning and contract. Never carries an executable handler or a
   host-facing name."
  [:map
   [:ημ/kind {:optional true} [:= :capability]]
   [:id :keyword]
   [:description :string]
   [:input schema-expr]
   [:output {:optional true} schema-expr]
   [:effects {:optional true} [:set :keyword]]
   [:errors {:optional true} [:vector :any]]
   [:docs {:optional true}
    [:map
     [:summary :string]
     [:description {:optional true} :string]]]
   [:source {:optional true} source-location]])

(def implementation
  "Executable binding for one capability."
  [:map
   [:ημ/kind {:optional true} [:= :implementation]]
   [:id :keyword]
   [:capability :keyword]
   [:runtime :keyword]
   [:handler handler]
   [:dependencies {:optional true} [:set :keyword]]
   [:version {:optional true} :string]
   [:source {:optional true} source-location]])

(def exposure
  "Target-facing presentation selecting one implementation of one capability."
  [:map
   [:ημ/kind {:optional true} [:= :exposure]]
   [:id :keyword]
   [:capability :keyword]
   [:implementation :keyword]
   [:target :keyword]
   [:name {:optional true} :string]
   [:description {:optional true} :string]
   [:args {:optional true} schema-expr]
   [:tags {:optional true} [:set :keyword]]
   [:presentation {:optional true} [:map-of :keyword :any]]
   [:source {:optional true} source-location]])

;; ---------------------------------------------------------------------------
;; Linked tool / hook / plugin
;; ---------------------------------------------------------------------------

(def tool
  "Legacy flat tool projection consumed by current target adapters."
  [:map
   [:id :keyword]
   [:name {:optional true} :string]
   [:description :string]
   [:args schema-expr]
   [:handler handler]
   [:capability {:optional true} :keyword]
   [:requires {:optional true} [:set :keyword]]
   [:tags {:optional true} [:set :keyword]]
   [:effects {:optional true} [:set :keyword]]
   [:source {:optional true} source-location]])

(def hook
  "A lifecycle hook definition. :event vocabulary is target-specific."
  [:map
   [:id :keyword]
   [:event :keyword]
   [:handler handler]
   [:priority {:optional true} :int]
   [:applies-to {:optional true} [:set :keyword]]
   [:tags {:optional true} [:set :keyword]]
   [:source {:optional true} source-location]])

(def plugin-entry
  [:or tool hook capability implementation exposure])

(def plugin
  "A plugin: the loadable unit of registration."
  [:map
   [:id :keyword]
   [:description {:optional true} :string]
   [:init {:optional true} fn?]
   [:tools {:optional true} [:vector tool]]
   [:hooks {:optional true} [:vector hook]]
   [:capabilities {:optional true} [:vector capability]]
   [:implementations {:optional true} [:vector implementation]]
   [:exposures {:optional true} [:vector exposure]]
   [:entries {:optional true} [:vector plugin-entry]]])

;; ---------------------------------------------------------------------------
;; Profiles and registry
;; ---------------------------------------------------------------------------

(def profile-rule
  [:map
   [:allow {:optional true} [:set :keyword]]
   [:deny {:optional true} [:set :keyword]]
   [:deny-effects {:optional true} [:set :keyword]]
   [:audit {:optional true} [:enum :verbose :strict :full :none :off]]])

(def profiles
  [:map-of :keyword profile-rule])

(def registry
  [:map
   [:tools [:vector tool]]
   [:hooks [:vector hook]]
   [:inits {:optional true} [:vector fn?]]
   [:plugins {:optional true} [:vector plugin]]
   [:capabilities {:optional true} [:vector capability]]
   [:implementations {:optional true} [:vector implementation]]
   [:exposures {:optional true} [:vector exposure]]])

;; ---------------------------------------------------------------------------
;; Adapter (post-compilation, still host-agnostic data)
;; ---------------------------------------------------------------------------

(def adapter-tool
  [:map
   [::kind [:= :tool]]
   [:name :string]
   [:description :string]
   [:args schema-expr]
   [:handler fn?]])

(def adapter
  [:map
   [::kind [:= :adapter]]
   [:tools [:vector adapter-tool]]
   [:hooks [:map-of :keyword fn?]]
   [:inits {:optional true} [:vector fn?]]
   [:permissions {:optional true} [:set :keyword]]])

;; ---------------------------------------------------------------------------
;; Hiccup DSL forms
;; ---------------------------------------------------------------------------

(def hiccup-tool
  [:vector [:= :tool] [:map-of :keyword :any] :any])

(def hiccup-hook
  [:vector [:= :hook] [:map-of :keyword :any] :any])

(def hiccup-plugin
  [:vector
   [:= :plugin]
   [:map-of :keyword :any]
   [:* [:or hiccup-tool hiccup-plugin]]])

;; ---------------------------------------------------------------------------
;; Validation helpers
;; ---------------------------------------------------------------------------

(defn validate
  "Returns nil on success, a Malli explanation map on failure."
  [schema value]
  (when-not (m/validate schema value)
    (m/explain schema value)))

(defn valid?
  [schema value]
  (m/validate schema value))

(defn explain
  "Human-readable explanation of validation failure."
  [schema value]
  (me/humanize (m/explain schema value)))
