(ns eta-mu.opencode.dsl
  "Macros for the OpenCode config DSL.
   Macros emit plain data conforming to eta-mu.opencode.schema shapes.
   No side effects, no global registration — just data."
  #?(:cljs (:require-macros eta-mu.opencode.dsl)))

;; ---------------------------------------------------------------------------
;; Tool macro
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro deftool
     "Define an OpenCode tool as a plain data map.
      Usage:
        (deftool search
          {:id          :research/search
           :description \"Search public sources.\"
           :args        [:map [:query :string] [:limit {:optional true} :int]]}
          [{:keys [query limit]} _ctx]
          {:content (run-search query (or limit 10))})"
     [sym {:keys [id description args] :as options} argv & body]
     `(def ~sym
        (merge
         {:opencode/kind :tool
          :id            ~id
          :name          ~(or (:name options)
                              (clojure.core/name id))
          :description   ~description
          :args          ~args
          :handler       (fn ~argv ~@body)
          :source        ~(select-keys (meta &form) [:file :line :column])}
         ~(dissoc options :id :name :description :args)))))

;; ---------------------------------------------------------------------------
;; Hook macro
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro defhook
     "Define an OpenCode hook as a plain data map.
      Usage:
        (defhook deny-env-access
          {:id       :policy/deny-env-access
           :event    :tool.execute.before
           :priority 100}
          [{:keys [tool args]} _ctx]
          (when (env-file? (:path args))
            {:opencode/action :reject
             :message \"Access to environment files is blocked\"}))"
     [sym {:keys [id event priority] :as options} argv & body]
     `(def ~sym
        (merge
         {:opencode/kind :hook
          :id            ~id
          :event         ~event
          :priority      ~(or priority 0)
          :handler       (fn ~argv ~@body)
          :source        ~(select-keys (meta &form) [:file :line :column])}
         ~(dissoc options :id :event :priority)))))

;; ---------------------------------------------------------------------------
;; Plugin macro
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro defplugin
     "Define an OpenCode plugin as a collection of tool/hook data maps.
      Usage:
        (defplugin research-tools
          search-tool
          deny-env-access)"
     [sym & entries]
     `(def ~sym
        {:opencode/kind :plugin
         :id            ~(keyword (str *ns*) (name sym))
         :entries       [~@entries]})))
