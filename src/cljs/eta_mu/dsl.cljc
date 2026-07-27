;; SPDX-License-Identifier: LGPL-3.0-or-later
(ns eta-mu.dsl
  "Host-agnostic authoring macros and descriptor linking for Muse.

   Capabilities describe meaning and contracts. Implementations bind runtime
   handlers. Exposures describe target-facing presentation. `link-tool` projects
   those records into the legacy flat :tool shape consumed by current target
   adapters. `deftool` remains a compatibility macro over that linker.

   Everything here emits plain data tagged with :ημ/kind — no OpenCode, Claude,
   or MCP objects. Target adapters interpret the linked data; they never define
   canonical semantics."
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

(defn- compact
  "Remove nil-valued optional descriptor fields without dropping false."
  [m]
  (into {} (remove (comp nil? val)) m))

(defn capability
  "Construct a semantic capability descriptor. It contains no executable
   handler and no host-facing name."
  [options]
  (assoc (compact options) :ημ/kind :capability))

(defn implementation
  "Construct a runtime implementation descriptor for one capability."
  [options]
  (assoc (compact options) :ημ/kind :implementation))

(defn exposure
  "Construct a target-facing exposure descriptor linking a capability to one
   selected implementation."
  [options]
  (assoc (compact options) :ημ/kind :exposure))

(defn- require-kind!
  [expected descriptor]
  (when-not (= expected (:ημ/kind descriptor))
    (throw (ex-info "Descriptor kind mismatch"
                    {:expected expected
                     :actual (:ημ/kind descriptor)
                     :descriptor descriptor})))
  descriptor)

(defn- require-reference!
  [reference expected actual descriptor]
  (when-not (= expected actual)
    (throw (ex-info "Descriptor reference mismatch"
                    {:reference reference
                     :expected expected
                     :actual actual
                     :descriptor descriptor}))))

(defn link-tool
  "Link capability, implementation, and exposure descriptors into the legacy
   flat :tool projection consumed by current adapters.

   The optional overrides map exists for `deftool` compatibility: it is merged
   last, matching the historical macro's option precedence."
  ([capability-definition implementation-definition exposure-definition]
   (link-tool capability-definition implementation-definition exposure-definition {}))
  ([capability-definition implementation-definition exposure-definition overrides]
   (require-kind! :capability capability-definition)
   (require-kind! :implementation implementation-definition)
   (require-kind! :exposure exposure-definition)
   (require-reference! :implementation/capability
                       (:id capability-definition)
                       (:capability implementation-definition)
                       implementation-definition)
   (require-reference! :exposure/capability
                       (:id capability-definition)
                       (:capability exposure-definition)
                       exposure-definition)
   (require-reference! :exposure/implementation
                       (:id implementation-definition)
                       (:implementation exposure-definition)
                       exposure-definition)
   (when-not (:handler implementation-definition)
     (throw (ex-info "Implementation has no handler"
                     {:implementation (:id implementation-definition)})))
   (let [source (or (:source exposure-definition)
                    (:source implementation-definition)
                    (:source capability-definition))]
     (merge
      (cond->
       {:ημ/kind     :tool
        :id          (:id exposure-definition)
        :name        (or (:name exposure-definition)
                         (default-name (:id exposure-definition)))
        :description (or (:description exposure-definition)
                         (:description capability-definition)
                         "")
        :args        (or (:args exposure-definition)
                         (:input capability-definition))
        :handler     (:handler implementation-definition)}
        (:effects capability-definition)
        (assoc :effects (:effects capability-definition))

        (:dependencies implementation-definition)
        (assoc :requires (:dependencies implementation-definition))

        (:tags exposure-definition)
        (assoc :tags (:tags exposure-definition))

        source
        (assoc :source source))
      overrides))))

;; ---------------------------------------------------------------------------
;; Explicit separated descriptors
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro defcapability
     "Define a semantic capability descriptor as plain data."
     [sym options]
     `(def ~sym
        (capability
         (merge {:source ~(select-keys (meta &form) [:file :line :column])}
                ~options)))))

#?(:clj
   (defmacro defimplementation
     "Define an implementation descriptor with an inline handler."
     [sym options argv & body]
     `(def ~sym
        (implementation
         (merge {:runtime :cljs
                 :source ~(select-keys (meta &form) [:file :line :column])}
                ~options
                {:handler (fn ~argv ~@body)})))))

#?(:clj
   (defmacro defexposure
     "Define a target-facing exposure descriptor as plain data."
     [sym options]
     `(def ~sym
        (exposure
         (merge {:target :tool
                 :source ~(select-keys (meta &form) [:file :line :column])}
                ~options)))))

;; ---------------------------------------------------------------------------
;; deftool — compatibility projection over separated descriptors
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro deftool
     "Define a legacy flat tool by constructing and linking separated
      capability, implementation, and exposure descriptors.

      Existing output shape and option precedence are preserved while callers
      migrate to defcapability/defimplementation/defexposure."
     [sym {:keys [id description args] :as options} argv & body]
     (let [source (select-keys (meta &form) [:file :line :column])]
       `(def ~sym
          (link-tool
           (capability
            (merge {:id          ~id
                    :description ~description
                    :input       ~args
                    :source      ~source}
                   ~(select-keys options [:output :effects :errors])))
           (implementation
            (merge {:id         ~id
                    :capability ~id
                    :runtime    ~(or (:runtime options) :cljs)
                    :handler    (fn ~argv ~@body)
                    :source     ~source}
                   ~(cond-> {}
                      (:requires options) (assoc :dependencies (:requires options))
                      (:version options) (assoc :version (:version options)))))
           (exposure
            (merge {:id             ~id
                    :capability     ~id
                    :implementation ~id
                    :target         ~(or (:target options) :tool)
                    :name           ~(or (:name options) (default-name id))
                    :description    ~description
                    :args           ~args
                    :source         ~source}
                   ~(select-keys options [:tags :presentation])))
           ~(dissoc options :id :name :description :args))))))

;; ---------------------------------------------------------------------------
;; defhook — a lifecycle interceptor
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro defhook
     "Define a hook as a plain data map with an inline handler.
      Handlers return the effect algebra understood by target boundaries:
        nil | {:effect :reject :message ...} | {:effect :patch :output {...}}"
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
;; defplugin — a named, loadable bundle
;; ---------------------------------------------------------------------------

#?(:clj
   (defmacro defplugin
     "Define a plugin: the unit of registration that config EDN references as
      a :resource. Entries may be linked tools, hooks, or separated descriptors."
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
