(ns eta-mu.dsl.schema
  "Malli schemas for the eta-mu DSL's canonical data shapes.
   Host-agnostic: hook events are open keywords here — each target adapter
   validates against the event vocabulary its host actually supports."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Schema expressions (used for :args, :input, :output)
;; ---------------------------------------------------------------------------

(def schema-expr
  "A Malli schema expression: keyword or vector.
   Validates the *shape* of a schema reference, not the schema itself."
  [:or :keyword [:vector :any]])

(def handler
  "A handler: an actual function, or a qualified symbol naming one
   (resolved by a link step against a handler table)."
  [:or fn? :qualified-symbol])

;; ---------------------------------------------------------------------------
;; Capability schema (host-agnostic contract)
;; ---------------------------------------------------------------------------

(def capability
  "A host-agnostic capability definition."
  [:map
   [:id :keyword]
   [:input schema-expr]
   [:output schema-expr]
   [:effects {:optional true} [:set :keyword]]
   [:handler handler]
   [:docs {:optional true}
    [:map
     [:summary :string]
     [:description {:optional true} :string]]]])

;; ---------------------------------------------------------------------------
;; Tool / hook / plugin
;; ---------------------------------------------------------------------------

(def source-location
  [:map
   [:file :string]
   [:line :int]
   [:column :int]])

(def tool
  "A tool definition. May reference a capability or carry its own contract."
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
  [:or tool hook])

(def plugin
  "A plugin: the loadable unit of registration."
  [:map
   [:id :keyword]
   [:description {:optional true} :string]
   [:init {:optional true} fn?]
   [:tools {:optional true} [:vector tool]]
   [:hooks {:optional true} [:vector hook]]
   [:entries {:optional true} [:vector plugin-entry]]])

;; ---------------------------------------------------------------------------
;; Profiles
;; ---------------------------------------------------------------------------

(def profile-rule
  [:map
   [:allow {:optional true} [:set :keyword]]
   [:deny {:optional true} [:set :keyword]]
   [:deny-effects {:optional true} [:set :keyword]]
   [:audit {:optional true} [:enum :verbose :strict :full :none :off]]])

(def profiles
  [:map-of :keyword profile-rule])

;; ---------------------------------------------------------------------------
;; Registry (the fully merged, validated intermediate representation)
;; ---------------------------------------------------------------------------

(def registry
  [:map
   [:tools [:vector tool]]
   [:hooks [:vector hook]]
   [:inits {:optional true} [:vector fn?]]
   [:plugins {:optional true} [:vector plugin]]
   [:capabilities {:optional true} [:vector capability]]])

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
  "Hiccup form: [:tool {attrs} handler-fn]"
  [:vector [:= :tool] [:map-of :keyword :any] :any])

(def hiccup-hook
  "Hiccup form: [:hook {attrs} handler-fn]"
  [:vector [:= :hook] [:map-of :keyword :any] :any])

(def hiccup-plugin
  "Hiccup form: [:plugin {attrs} ...entries]"
  [:vector
   [:= :plugin]
   [:map-of :keyword :any]
   [:* [:or hiccup-tool hiccup-plugin]]])

;; ---------------------------------------------------------------------------
;; Validation helpers
;; ---------------------------------------------------------------------------

(defn validate
  "Returns nil on success, malli explanation map on failure."
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
