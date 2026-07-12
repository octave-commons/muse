(ns eta-mu.opencode.schema
  "Malli schemas for the OpenCode config DSL.
   All shapes are data-first, platform-agnostic, and composable.
   Designed for CLJC: works on both JVM Clojure and ClojureScript."
  (:require [malli.core :as m]
            [malli.error :as me]))

;; ---------------------------------------------------------------------------
;; Schema expressions (used for :args, :input, :output)
;; ---------------------------------------------------------------------------

(def schema-expr
  "A Malli schema expression: keyword or vector.
   Used for :args, :input/:output fields.
   Validates the *shape* of a schema reference, not the schema itself."
  [:or :keyword [:vector :any]])

;; ---------------------------------------------------------------------------
;; Capability schema (host-agnostic)
;; ---------------------------------------------------------------------------

(def capability
  "A host-agnostic capability definition.
   Lives in .eta-mu/contracts/, not in target-specific config."
  [:map
   [:id :keyword]
   [:input schema-expr]
   [:output schema-expr]
   [:effects {:optional true} [:set :keyword]]
   [:handler :qualified-symbol]
   [:docs {:optional true}
    [:map
     [:summary :string]
     [:description {:optional true} :string]]]])

(m/=> ::capability [:schema capability])

;; ---------------------------------------------------------------------------
;; Tool schema
;; ---------------------------------------------------------------------------

(def tool
  "An OpenCode tool definition.
   May reference a capability or define its own contract inline."
  [:map
   [:id :keyword]
   [:name {:optional true} :string]
   [:description :string]
   [:args schema-expr]
   [:handler :qualified-symbol]
   [:capability {:optional true} :keyword]
   [:requires {:optional true} [:set :keyword]]
   [:tags {:optional true} [:set :keyword]]
   [:effects {:optional true} [:set :keyword]]
   [:source {:optional true}
    [:map
     [:file :string]
     [:line :int]
     [:column :int]]]])

(m/=> ::tool [:schema tool])

;; ---------------------------------------------------------------------------
;; Hook schema
;; ---------------------------------------------------------------------------

(def hook-event
  "Supported OpenCode lifecycle events."
  [:enum
   :tool.execute.before
   :tool.execute.after
   :tool.execute.error
   :chat.system.messages.transform
   :chat.completion.before
   :chat.completion.after
   :text.complete
   :session.start
   :session.end
   :session.error
   :file.changed
   :permission.request
   :command.executed])

(def hook
  "An OpenCode hook definition."
  [:map
   [:id :keyword]
   [:event hook-event]
   [:handler :qualified-symbol]
   [:priority {:optional true} :int]
   [:applies-to {:optional true} [:set :keyword]]
   [:tags {:optional true} [:set :keyword]]
   [:source {:optional true}
    [:map
     [:file :string]
     [:line :int]
     [:column :int]]]])

(m/=> ::hook [:schema hook])

;; ---------------------------------------------------------------------------
;; Plugin schema
;; ---------------------------------------------------------------------------

(def plugin-entry
  "A single entry within a plugin: tool or hook."
  [:or tool hook])

(def plugin
  "A plugin groups tools and hooks under a namespace."
  [:map
   [:id :keyword]
   [:description {:optional true} :string]
   [:tools {:optional true} [:vector tool]]
   [:hooks {:optional true} [:vector hook]]
   [:entries {:optional true} [:vector plugin-entry]]])

(m/=> ::plugin [:schema plugin])

;; ---------------------------------------------------------------------------
;; Profile schema
;; ---------------------------------------------------------------------------

(def profile-rule
  "A single profile's allow/deny rules."
  [:map
   [:allow {:optional true} [:set :keyword]]
   [:deny {:optional true} [:set :keyword]]
   [:deny-effects {:optional true} [:set :keyword]]
   [:audit {:optional true} [:enum :verbose :strict :full :none :off]]])

(def profiles
  "Named profile collection."
  [:map-of :keyword profile-rule])

(m/=> ::profiles [:schema profiles])

;; ---------------------------------------------------------------------------
;; Registry (the fully merged, validated form)
;; ---------------------------------------------------------------------------

(def registry
  "The fully merged, validated registry.
   This is the canonical intermediate representation before compilation."
  [:map
   [:id :keyword]
   [:version {:optional true} :int]
   [:tools [:vector tool]]
   [:hooks [:vector hook]]
   [:plugins {:optional true} [:vector plugin]]
   [:capabilities {:optional true} [:vector capability]]
   [:profile {:optional true} :keyword]])

(m/=> ::registry [:schema registry])

;; ---------------------------------------------------------------------------
;; OpenCode adapter output (generated)
;; ---------------------------------------------------------------------------

(def opencode-tool
  "OpenCode-facing tool descriptor (post-compilation)."
  [:map
   [::kind [:= :tool]]
   [:name :string]
   [:description :string]
   [:args :map]
   [:handler fn?]
   [:permissions {:optional true} [:set :keyword]]])

(def opencode-hook
  "OpenCode-facing hook descriptor (post-compilation)."
  [:map
   [::kind [:= :hook]]
   [:event hook-event]
   [:priority :int]
   [:handler fn?]])

(def opencode-adapter
  "The final OpenCode plugin shape."
  [:map
   [::kind [:= :adapter]]
   [:tools [:vector opencode-tool]]
   [:hooks [:map-of hook-event fn?]]
   [:permissions {:optional true} [:set :keyword]]])

(m/=> ::opencode-adapter [:schema opencode-adapter])

;; ---------------------------------------------------------------------------
;; Hiccup DSL forms
;; ---------------------------------------------------------------------------

(def hiccup-tool
  "Hiccup form: [:tool {attrs} handler-fn]"
  [:vector
   [:= :tool]
   [:map-of :keyword :any]
   :any])

(def hiccup-hook
  "Hiccup form: [:hook {attrs} handler-fn]"
  [:vector
   [:= :hook]
   [:map-of :keyword :any]
   :any])

(def hiccup-plugin
  "Hiccup form: [:plugin {attrs} ...entries]"
  [:vector
   [:= :plugin]
   [:map-of :keyword :any]
   [:* [:or hiccup-tool hiccup-plugin]]])

(m/=> ::hiccup-plugin [:schema hiccup-plugin])

;; ---------------------------------------------------------------------------
;; Validation helpers
;; ---------------------------------------------------------------------------

(defn validate
  "Validate a value against a schema.
   Returns nil on success, malli explanation map on failure."
  [schema value]
  (when-not (m/validate schema value)
    (m/explain schema value)))

(defn valid?
  "True if value conforms to schema."
  [schema value]
  (m/validate schema value))

(defn explain
  "Human-readable explanation of validation failure."
  [schema value]
  (me/humanize (m/explain schema value)))
