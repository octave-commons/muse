(ns eta-mu.mcp.build
  "Shadow-cljs build hooks for the MCP target (any MCP client — Claude Code,
   Codex, ...). Mirrors eta-mu.opencode.build: nothing here is
   plugin-specific, every name comes from the EDN config tree and the shadow
   build config.

   :configure  generate-entrypoint — reads .ημ/config/mcp/root.edn,
               collects the :resource symbols from exposure fragments, and
               generates the entrypoint namespace that requires them, runs
               the DSL pipeline, and starts the MCP server on init.
   :flush      emit-host-config — writes the output dir's package.json
               ESM marker and, when :publish {:mcp-config ...} is set,
               a project .mcp.json so Claude Code auto-discovers the
               built server."
  (:require [clojure.java.io :as io]
            [clojure.string :as str]
            [eta-mu.opencode.config :as config]))

(def ^:private root-path ".ημ/config/mcp/root.edn")

(def ^:private gen-ns 'eta-mu.gen.mcp-server)

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
     "  \"GENERATED from " root-path " by eta-mu.mcp.build — do not edit.\"\n"
     "  (:require\n"
     (str/join "\n" (map #(str "   [" % "]") requires))
     "\n"
     "   [eta-mu.boundaries.mcp :as host]\n"
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
     "  (host/serve! adapter (get-in config* [:root :info])))\n")))

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
    (spit f content)))

(defn- json-str [s]
  (str "\"" (str/replace s "\"" "\\\"") "\""))

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
  "Build hook (:flush stage)."
  {:shadow.build/stage :flush}
  [build-state & _]
  (let [{:keys [output-dir modules]} (:shadow.build/config build-state)
        cfg (config/read-config root-path)]
    (emit! (str output-dir "/package.json") "{\n  \"type\": \"module\"\n}\n")
    (when-let [mcp-config-path (get-in cfg [:root :publish :mcp-config])]
      (let [server-name (get-in cfg [:root :info :name] "eta-mu-mcp")
            module-key  (name (ffirst modules))
            entry-path  (str output-dir "/" module-key ".js")]
        (emit! mcp-config-path (mcp-config-json server-name entry-path)))))
  build-state)
