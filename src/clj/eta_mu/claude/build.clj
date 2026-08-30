(ns eta-mu.claude.build
  "Shadow-cljs build hooks for the Claude Code target. Mirrors
   eta-mu.opencode.build and eta-mu.mcp.build: nothing here is
   plugin-specific, every name comes from the EDN config tree.

   :configure  generate-entrypoint — reads .ημ/config/claude/root.edn,
               collects :resource symbols, generates the entrypoint that
               requires them, runs the DSL pipeline, and dispatches on argv
               at init: --emit-hook-config writes .claude/settings.json +
               .claude/hooks/*.sh from the real adapter (see
               eta-mu.boundaries.claude/emit-hook-config!), --hook <event>
               runs one hook invocation, and no flag starts the MCP server
               (Claude connects to that via .mcp.json).
   :flush      emit-host-config — writes only the static artifacts
               (package.json, .mcp.json); see scripts/build-host-targets.sh
               for why hook/settings generation is not done here."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [eta-mu.opencode.config :as config]))

(def ^:private root-path ".ημ/config/claude/root.edn")

(def ^:private gen-ns 'eta-mu.gen.claude-server)

(def ^:private gen-path
  (str "src/gen/"
       (-> (str gen-ns) (str/replace "-" "_") (str/replace "." "/"))
       ".cljs"))

(defn mcp-config-path
  "Configured public MCP registry destination for the supported build wrapper."
  []
  (get-in (config/read-config root-path) [:root :publish :mcp-config]))

;; ---------------------------------------------------------------------------
;; Entrypoint generation
;; ---------------------------------------------------------------------------

(defn- entrypoint-source
  [cfg resource-syms]
  (let [requires (->> resource-syms (map namespace) distinct sort)]
    (str
     "(ns " gen-ns "\n"
     "  \"GENERATED from " root-path " by eta-mu.claude.build — do not edit.\"\n"
     "  (:require\n"
     (str/join "\n" (map #(str "   [" % "]") requires))
     "\n"
     "   [eta-mu.boundaries.claude :as host]\n"
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
     "(defn init\n"
     "  []\n"
     "  (let [argv (.-argv js/process)]\n"
     "    (cond\n"
     "      (host/emit-config-requested? argv) (host/emit-hook-config! adapter (aget argv 1))\n"
     "      (host/hook-arg argv) (host/run-hook-cli! adapter (host/hook-arg argv))\n"
     "      :else (host/serve! adapter (get-in config* [:root :info])))))\n")))

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

(defn- emit! [path content]
  (let [f (io/file path)]
    (io/make-parents f)
    (spit f (str content "\n"))))

(defn- json-str [s]
  (str "\"" (str/replace s "\"" "\\\"") "\""))

(declare json-obj)

(defn- json-key [k]
  (if (keyword? k) (name k) (str k)))

(defn- json-value [v indent]
  (let [pad (apply str (repeat indent " "))]
    (cond
      (map? v)    (json-obj v indent)
      (string? v) (json-str v)
      (vector? v) (str "[\n"
                       (str/join ",\n"
                                 (map #(str pad "  " (json-value % (+ indent 2))) v))
                       "\n" pad "]")
      (keyword? v) (json-str (name v))
      (boolean? v) (str v)
      (nil? v)     "null"
      :else        (str v))))

(defn- json-obj [m indent]
  (let [pad (apply str (repeat indent " "))]
    (str "{\n"
         (str/join ",\n"
                   (for [[k v] m]
                     (str pad "  " (json-str (json-key k)) ": "
                          (json-value v (+ indent 2)))))
         "\n" pad "}")))

(defn- mcp-config-json [server-name entry-path]
  (str "{\n"
       "  \"mcpServers\": {\n"
       "    " (json-str server-name) ": {\n"
       "      \"command\": \"node\",\n"
       "      \"args\": [" (json-str entry-path) "]\n"
       "    }\n"
       "  }\n"
       "}\n"))

(defn emit-host-config
  "Build hook (:flush stage). Only the static artifacts (package.json,
   .mcp.json) get written here. .claude/settings.json and .claude/hooks/*.sh
   are generated separately, by the compiled artifact itself running
   `--emit-hook-config` (see eta-mu.boundaries.claude/emit-hook-config! and
   scripts/build-host-targets.sh) -- not from this JVM-side :flush hook.
   A :flush-stage hook that shelled out to inspect the just-compiled JS
   observed a stale/empty adapter, so hook discovery/settings generation
   needs a process that starts strictly after `shadow-cljs release` exits,
   which this stage cannot guarantee itself."
  {:shadow.build/stage :flush}
  [build-state & _]
  (let [{:keys [output-dir modules]} (:shadow.build/config build-state)
        cfg (config/read-config root-path)
        module-key  (name (ffirst modules))
        entry-path  (str output-dir "/" module-key ".js")]
    (emit! (str output-dir "/package.json") "{\n  \"type\": \"module\"\n}\n")
    (when-let [configured-path (mcp-config-path)]
      ;; The supported wrapper stages hook output so live readers see only an
      ;; atomically published merged registry. Raw Shadow keeps the configured path.
      (let [mcp-config-path (or (not-empty (System/getenv "MUSE_MCP_CONFIG_OUTPUT"))
                                configured-path)
            server-name (get-in cfg [:root :info :name] "eta-mu-claude")]
        (emit! mcp-config-path (mcp-config-json server-name entry-path)))))
  build-state)
