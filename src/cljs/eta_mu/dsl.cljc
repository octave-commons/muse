(ns eta-mu.dsl
  "Host-agnostic authoring macros for the eta-mu agent-system DSL.

   Everything here emits plain data tagged with :ημ/kind — no opencode, no
   claude, no MCP. Target adapters (eta-mu.opencode.*, later eta-mu.mcp.*,
   eta-mu.claude.*) interpret this data; they never define it.

   Handlers consume and return plain CLJC data. Host boundaries own every
   translation to JS/JSON/wire shapes."
  #?(:cljs (:require-macros eta-mu.dsl)))

#?(:clj
   (defn- current-ns
     "The namespace a macro is expanding in, for both CLJ and CLJS."
     [env]
     (or (some-> env :ns :name) (ns-name *ns*))))

(defn default-name
  "Default host-facing name for an entry id:
   :muse/spawn → \"muse_spawn\", :phase/list_active → \"phase_list_active\"."
  [id]
  (if (namespace id)
    (str (namespace id) "_" (name id))
    (name id)))

;; ---------------------------------------------------------------------------
;; deftool — a capability exposed as an agent tool
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro deftool
     "Define a tool as a plain data map with an inline handler.
      Usage:
        (deftool search
          {:id          :research/search
           :description \"Search public sources.\"
           :args        [:map [:query :string] [:limit {:optional true} :int]]}
          [{:keys [query limit]} _ctx]
          {:findings (run-search query (or limit 10))})"
     [sym {:keys [id description args] :as options} argv & body]
     `(def ~sym
        (merge
         {:ημ/kind     :tool
          :id          ~id
          :name        ~(or (:name options) (default-name id))
          :description ~description
          :args        ~args
          :handler     (fn ~argv ~@body)
          :source      ~(select-keys (meta &form) [:file :line :column])}
         ~(dissoc options :id :name :description :args)))))

;; ---------------------------------------------------------------------------
;; defhook — a lifecycle interceptor
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro defhook
     "Define a hook as a plain data map with an inline handler.
      Handlers return the effect algebra understood by target boundaries:
        nil | {:effect :reject :message ...} | {:effect :patch :output {...}}
      Usage:
        (defhook deny-env-access
          {:id       :policy/deny-env-access
           :event    :tool.execute.before
           :priority 100}
          [{:keys [tool args]} _ctx]
          (when (env-file? (:path args))
            {:effect :reject :message \"Access to environment files is blocked\"}))"
     [sym {:keys [id event priority] :as options} argv & body]
     `(def ~sym
        (merge
         {:ημ/kind  :hook
          :id       ~id
          :event    ~event
          :priority ~(or priority 0)
          :handler  (fn ~argv ~@body)
          :source   ~(select-keys (meta &form) [:file :line :column])}
         ~(dissoc options :id :event :priority)))))

;; ---------------------------------------------------------------------------
;; defplugin — a named, loadable bundle of tools and hooks
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro defplugin
     "Define a plugin: the unit of registration that config EDN references
      as a :resource. Takes an optional options map first (:id, :init —
      a zero-arg side-effecting fn run once at target activation).
      Usage:
        (defplugin plugin {:init init-store!}
          search audit-hook)"
     [sym & entries]
     (let [[options entries] (if (map? (first entries))
                               [(first entries) (rest entries)]
                               [{} entries])]
       `(def ~sym
          (merge
           {:ημ/kind :plugin
            :id      ~(or (:id options)
                          (keyword (str (current-ns &env)) (name sym)))
            :entries [~@entries]}
           ~(dissoc options :id))))))
