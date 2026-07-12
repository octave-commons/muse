(ns eta-mu.opencode.config
  "The OpenCode target's config layer: loads the .ημ/config/opencode EDN
   tree and links it against DSL resources.

   Config EDN never restates what code already declares. An exposure
   fragment selects a resource and filters/overrides what it exposes:

     {:resource  plugins.actors/plugin
      :expose    [:muse/* :phase/* :actor/*]   ; optional, default all
      :overrides {:muse/spawn {:name \"muse_spawn\"}}} ; optional

   :resource is a qualified symbol naming a defplugin value. The build
   generates the entrypoint namespace from these symbols (that is how EDN
   \"loads\" cljs/cljc resources into a compiled artifact), and the
   entrypoint passes the resolved {sym plugin-value} table to
   apply-exposure. Other targets (mcp, claude, codex) get their own
   .ημ/config/<target>/ tree — typically a subset — linked against the
   same resources by their own config layer."
  (:require [eta-mu.dsl.normalize :as normalize]
            [eta-mu.dsl.profile :as profile]
            #?@(:clj [[clojure.edn :as edn]
                      [clojure.java.io :as io]]))
  #?(:cljs (:require-macros eta-mu.opencode.config)))

;; ---------------------------------------------------------------------------
;; Compile-time loading (clj side of the macro)
;; ---------------------------------------------------------------------------

#?(:clj
   (defn read-config
     "Read a root EDN config and its :imports (paths relative to the root
      file). Returns {:root {...} :fragments [{:path ... :data ...}]}.
      Plain function so JVM build tasks can reuse it."
     [root-path]
     (let [root-file (io/file root-path)
           dir       (.getParentFile root-file)
           root      (edn/read-string (slurp root-file))]
       {:root root
        :fragments
        (mapv (fn [rel]
                {:path rel
                 :data (edn/read-string (slurp (io/file dir rel)))})
              (:imports root))})))

#?(:clj
   (defmacro load-config
     "Embed the EDN config tree as literal data at compile time."
     [root-path]
     (list 'quote (read-config root-path))))

;; ---------------------------------------------------------------------------
;; Fragment classification (pure)
;; ---------------------------------------------------------------------------

(defn fragment-kind [{:keys [data]}]
  (cond
    (:resource data)    :exposure
    (:permissions data) :permissions
    (:settings data)    :settings
    (and (map? data)
         (every? map? (vals data))
         (some #(or (:allow %) (:deny %)) (vals data))) :profiles
    :else :unknown))

(defn- fragments-of [config kind]
  (map :data (filter #(= kind (fragment-kind %)) (:fragments config))))

(defn exposures
  "Exposure fragments in import order."
  [config]
  (vec (fragments-of config :exposure)))

(defn resources
  "The distinct resource symbols the config wants loaded, in import order.
   The build's entrypoint generator turns these into :require forms."
  [config]
  (into [] (distinct) (map :resource (exposures config))))

(defn permissions
  [config]
  (into [] (mapcat :permissions) (fragments-of config :permissions)))

(defn profiles
  [config]
  (apply merge {} (fragments-of config :profiles)))

(defn settings-fragments
  "The :settings payloads in import order. A settings fragment carries host
   configuration verbatim (model, providers, skills, …) rather than plugin
   exposure — eta-mu.opencode.settings deep-merges and renders them."
  [config]
  (into [] (map :settings) (fragments-of config :settings)))

(defn emit-target
  "The root's :emit declaration ({:path \"…\"} or nil): where the daemon
   renders this tree's merged settings."
  [config]
  (get-in config [:root :emit]))

(defn build-command
  "The root's :build command vector (or nil): what the daemon runs in the
   repo when this tree (or its plugin sources) change."
  [config]
  (get-in config [:root :build]))

(defn active-profile
  "The profile rule selected by the root's :profile key. {} when none."
  [config]
  (get (profiles config) (get-in config [:root :profile]) {}))

;; ---------------------------------------------------------------------------
;; Exposure linking
;; ---------------------------------------------------------------------------

(defn- expose-entry? [patterns {:keys [id]}]
  (or (empty? patterns)
      (boolean (some #(profile/matches-pattern? % id) patterns))))

(defn- link-exposure
  "Resolve one exposure fragment against the resource table, returning a
   registry fragment of the exposed (and possibly overridden) entries."
  [resource-table {:keys [resource expose overrides]}]
  (let [plugin (get resource-table resource)]
    (when-not plugin
      (throw (ex-info "Config exposes a resource that is not loaded"
                      {:resource resource
                       :known (sort (keys resource-table))})))
    (-> (normalize/merge-fragments plugin)
        (update :tools #(filterv (partial expose-entry? expose) %))
        (update :hooks #(filterv (partial expose-entry? expose) %))
        (update :tools #(mapv (fn [{:keys [id] :as tool}]
                                (merge tool (get overrides id)))
                              %))
        (update :hooks #(mapv (fn [{:keys [id] :as hook}]
                                (merge hook (get overrides id)))
                              %)))))

(defn apply-exposure
  "Build the registry the config exposes: every exposure fragment linked
   against the {resource-symbol plugin-value} table and merged."
  [config resource-table]
  (->> (exposures config)
       (map (partial link-exposure resource-table))
       (reduce (fn [out fragment]
                 (-> out
                     (update :tools into (:tools fragment))
                     (update :hooks into (:hooks fragment))
                     (update :inits into (:inits fragment))
                     (update :plugins into (:plugins fragment))))
               normalize/empty-registry)))
