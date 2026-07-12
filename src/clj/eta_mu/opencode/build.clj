(ns eta-mu.opencode.build
  "Shadow-cljs build hooks for the OpenCode target. Nothing here is specific
   to any particular plugin — every name is derived from the EDN config tree
   and the shadow build config.

   :configure  generate-entrypoint — reads .ημ/config/opencode/root.edn,
               collects the :resource symbols from exposure fragments, and
               generates the entrypoint namespace that requires them and
               runs the DSL pipeline. This is how EDN \"loads\" cljs/cljc.
   :flush      emit-host-config — writes the .opencode/ host artifacts:
               a plugins/ shim re-exporting only the plugin functions
               (OpenCode requires every export of a discovered plugin file
               to be a function, but shadow's :esm target also exports
               $APP/shadow$provide objects), opencode.json permissions
               projected from permissions/*.edn, and package.json."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [eta-mu.opencode.config :as config])
  (:import [java.nio.file Paths]))

(def ^:private root-path ".ημ/config/opencode/root.edn")

(def ^:private gen-ns 'eta-mu.gen.opencode-plugin)

(def ^:private gen-path
  (str "src/gen/"
       (-> (str gen-ns) (str/replace "-" "_") (str/replace "." "/"))
       ".cljs"))

;; ---------------------------------------------------------------------------
;; Entrypoint generation
;; ---------------------------------------------------------------------------

(defn- entrypoint-source
  "The EDN config is embedded as a literal (not loaded via macro) so that
   any config change changes this file's content and invalidates shadow's
   compilation cache for it."
  [cfg resource-syms]
  (let [requires (->> resource-syms (map namespace) distinct sort)]
    (str
     "(ns " gen-ns "\n"
     "  \"GENERATED from " root-path " by eta-mu.opencode.build — do not edit.\"\n"
     "  (:require\n"
     (str/join "\n" (map #(str "   [" % "]") requires))
     "\n"
     "   [eta-mu.boundaries.opencode :as host]\n"
     "   [eta-mu.dsl.compile :as dsl.compile]\n"
     "   [eta-mu.dsl.normalize :as dsl.normalize]\n"
     "   [eta-mu.dsl.profile :as dsl.profile]\n"
     "   [eta-mu.opencode.config :as config]))\n"
     "\n"
     "(def config* (quote " (pr-str cfg) "))\n"
     "\n"
     "(def resources\n"
     "  {" (str/join "\n   "
                     (map #(str "'" % " " %) resource-syms))
     "})\n"
     "\n"
     "(def adapter\n"
     "  (->> (config/apply-exposure config* resources)\n"
     "       (dsl.profile/apply-profile (config/active-profile config*))\n"
     "       dsl.normalize/validate-registry!\n"
     "       dsl.compile/compile-adapter))\n"
     "\n"
     "(defn plugin\n"
     "  [_input]\n"
     "  (host/activate! adapter))\n")))

(defn- write-if-changed! [path content]
  (let [f (io/file path)]
    (when-not (and (.exists f) (= content (slurp f)))
      (io/make-parents f)
      (spit f content))))

(defn generate-entrypoint
  "Build hook (:configure stage)."
  {:shadow.build/stage :configure}
  [build-state & _]
  (let [cfg  (config/read-config root-path)
        syms (config/resources cfg)]
    (when (empty? syms)
      (throw (ex-info "No :resource symbols found in config exposures"
                      {:root root-path})))
    (write-if-changed! gen-path (entrypoint-source cfg syms)))
  build-state)

;; ---------------------------------------------------------------------------
;; Host artifact emission
;; ---------------------------------------------------------------------------

(defn- json-str [s]
  (str "\"" (str/replace s "\"" "\\\"") "\""))

(defn- json-obj
  "Minimal JSON emitter for the flat string→value maps we generate."
  [m indent]
  (let [pad (apply str (repeat indent " "))]
    (str "{\n"
         (str/join ",\n"
                   (for [[k v] m]
                     (str pad "  " (json-str k) ": "
                          (cond
                            (map? v)    (json-obj v (+ indent 2))
                            (string? v) (json-str v)
                            :else       (str v)))))
         "\n" pad "}")))

(def ^:private policy->permission
  {:allow                "allow"
   :deny                 "deny"
   :require-confirmation "ask"})

(defn- permission-map [cfg]
  (into (sorted-map)
        (for [{:keys [applies policy]} (config/permissions cfg)
              applied applies]
          [(name applied) (policy->permission policy "ask")])))

(defn- emit! [path content]
  (let [f (io/file path)]
    (io/make-parents f)
    (spit f (str content "\n"))))

(defn- relative-path [from-dir to-file]
  (str (.relativize (Paths/get from-dir (make-array String 0))
                    (Paths/get to-file (make-array String 0)))))

(defn- shim-source
  "One shim line per module: re-export only the declared (function) exports."
  [output-dir module-name export-names]
  (let [target (relative-path ".opencode/plugins"
                              (str output-dir "/" module-name ".js"))
        spec   (if (str/starts-with? target ".") target (str "./" target))]
    (str "// Generated by eta-mu.opencode.build — do not edit.\n"
         "export { " (str/join ", " export-names) " } from \"" spec "\";")))

(defn emit-host-config
  "Build hook (:flush stage)."
  {:shadow.build/stage :flush}
  [build-state & _]
  (let [{:keys [output-dir modules]} (:shadow.build/config build-state)
        cfg (config/read-config root-path)]
    (doseq [[module-key {:keys [exports]}] modules]
      (emit! (str ".opencode/plugins/" (name module-key) ".js")
             (shim-source output-dir (name module-key)
                          (map name (keys exports)))))
    (emit! ".opencode/opencode.json"
           (json-obj {"$schema"    "https://opencode.ai/config.json"
                      "permission" (permission-map cfg)}
                     0))
    (emit! ".opencode/package.json"
           (json-obj {"type" "module"
                      "dependencies" {"@opencode-ai/plugin" "1.17.18"
                                      "zod"                 "4.1.8"}}
                     0)))
  build-state)
