(ns eta-mu.boundaries.claude
  "The Claude Code host boundary. This is the ONLY namespace allowed to touch
   JS values, the zod library, node:fs, or the MCP SDK on this target's
   behalf.

   Claude Code has two planes:
     - MCP tools: external capabilities the agent can invoke, served as a
       persistent stdio MCP server (`serve!`/`render-server`).
     - Hooks: Claude invokes a one-shot host-native command per lifecycle
       event, feeding it the event payload on stdin and reading a decision
       back from stdout -- there is no persistent process and no [input ctx]
       CLJS call the way OpenCode's plugin API provides one directly.
       `run-hook-cli!` bridges that: it decodes Claude's single flat JSON
       payload into the DSL's canonical [input output] shape, runs the same
       compiled hook chain the OpenCode target runs, and prints Claude's
       expected {\"continue\": ...} decision JSON. The generated entrypoint's
       `init` (see eta-mu.claude.build/entrypoint-source) dispatches to this
       instead of `serve!` when invoked with `--hook <canonical-event>`, so
       one compiled artifact serves both planes."
  (:require ["@modelcontextprotocol/sdk/server/mcp.js" :refer [McpServer]]
            ["@modelcontextprotocol/sdk/server/stdio.js" :refer [StdioServerTransport]]
            ["node:fs" :as node-fs]
            [clojure.string :as str]
            [eta-mu.dsl.events :as events]
            [eta-mu.dsl.zod :as zod]))

;; ---------------------------------------------------------------------------
;; Ingress / egress (shared with MCP boundary)
;; ---------------------------------------------------------------------------

(defn- decode-args [js-args]
  (js->clj js-args :keywordize-keys true))

(defn- default-ctx
  "Claude Code MCP tool calls carry no session metadata; the server runs
   from the repo it was started in."
  []
  (let [cwd (.cwd js/process)]
    {:directory cwd :worktree cwd}))

(defn- encode-result
  [result]
  #js {:content #js [#js {:type "text"
                           :text (if (string? result)
                                   result
                                   (js/JSON.stringify (clj->js result)))}]})

(defn- wrap-handler [handler]
  (fn [js-args]
    (-> (js/Promise.resolve (handler (decode-args js-args) (default-ctx)))
        (.then encode-result))))

;; ---------------------------------------------------------------------------
;; Rendering the compiled adapter
;; ---------------------------------------------------------------------------

(defn- register-tool! [server {:keys [name description args handler]}]
  (.registerTool server name
                 #js {:description description
                      :inputSchema (zod/args->zod-shape args)}
                 (wrap-handler handler)))

(defn render-server
  "Compiled adapter {:tools [...]} → a connected McpServer instance.
   `info` is passed straight to the SDK, e.g. {:name ... :version ...}."
  [{:keys [tools inits]} info]
  (doseq [init inits] (init))
  (let [server (McpServer. (clj->js info))]
    (doseq [tool tools] (register-tool! server tool))
    server))

(defn serve!
  "Start an MCP server for `adapter` over stdio. Returns the connect promise."
  [adapter info]
  (let [server (render-server adapter info)]
    (.connect server (StdioServerTransport.))))

;; ---------------------------------------------------------------------------
;; Hook execution -- the CLI-invoked plane
;; ---------------------------------------------------------------------------

(defn hook-arg
  "The canonical event name passed as `--hook <event>` on argv, or nil.
   `argv` is `js/process.argv` (or an equivalent array) -- [node, script, ...]."
  [argv]
  (let [args (js->clj argv)
        idx (.indexOf args "--hook")]
    (when (and (>= idx 0) (< (inc idx) (count args)))
      (nth args (inc idx)))))

(defn- qualified-name
  "Full \"ns/name\" string for a namespaced keyword -- `name` alone drops
   the namespace, which silently corrupted an earlier version of this: a
   hook registered on :tool/requested was discovered back as bare
   \"requested\", which `(keyword \"requested\")` turns into :requested, an
   event events/for-host has never heard of."
  [kw]
  (str (namespace kw) "/" (name kw)))

(defn emit-config-requested? [argv]
  (boolean (some #(= % "--emit-hook-config") (js->clj argv))))

(defn- read-stdin-sync []
  (try (.readFileSync node-fs 0 "utf8") (catch :default _ "{}")))

(defn- decode-claude-payload [raw]
  (try (js->clj (js/JSON.parse raw) :keywordize-keys true)
       (catch :default _ {})))

(defn- claude-payload->input+output
  "Claude's PreToolUse-family payload is one flat object
   ({:tool_name ... :tool_input ... :session_id ... :cwd ...}), unlike
   OpenCode's native two-argument (input, output) hook call. Build the same
   canonical shape the DSL's handlers expect from it."
  [payload]
  (let [cwd (or (:cwd payload) (.cwd js/process))]
    [{:tool (:tool_name payload)
      :session/id (:session_id payload)
      :directory cwd
      :worktree cwd}
     {:args (:tool_input payload)}]))

(defn- decision->claude-json [result]
  (case (:effect result)
    :reject
    {:continue false
     :stopReason "hook blocked this action"
     :hookSpecificOutput
     {:hookEventName "PreToolUse"
      :permissionDecision "deny"
      :permissionDecisionReason (or (:message result) "blocked by policy")}}
    ;; :patch has no Claude-native representation (Claude doesn't let a
    ;; PreToolUse hook rewrite the call's arguments) -- treat as allow.
    {:continue true}))

(defn run-hook-cli!
  "Run `adapter`'s compiled hook chain for `canonical-event` against the
   payload on stdin, print Claude's expected decision JSON to stdout, and
   exit. `canonical-event` is a bare string like \"tool/requested\" (no
   leading colon -- `keyword` parses the `/` as the namespace separator on
   both Clojure and ClojureScript, so this round-trips through JSON/argv
   without needing custom encoding)."
  [adapter canonical-event]
  (let [composed (get-in adapter [:hooks (keyword canonical-event)])]
    (if-not composed
      (do (println (js/JSON.stringify (clj->js {:continue true})))
          (.exit js/process 0))
      (let [payload (decode-claude-payload (read-stdin-sync))
            [input output] (claude-payload->input+output payload)]
        (-> (js/Promise.resolve (composed input output))
            (.then (fn [result]
                     (println (js/JSON.stringify (clj->js (decision->claude-json result))))
                     (.exit js/process 0)))
            (.catch (fn [err]
                      ;; A hook that throws/rejects fails open with a loud
                      ;; stderr note -- an enforcement bug should not be
                      ;; able to wedge every tool call in the session.
                      (js/console.error "[kanban-gate hook error]" err)
                      (println (js/JSON.stringify (clj->js {:continue true})))
                      (.exit js/process 0))))))))

;; ---------------------------------------------------------------------------
;; .claude/settings.json + wrapper-script generation
;;
;; Deliberately NOT done from the JVM-side shadow-cljs :flush build hook
;; (eta-mu.claude.build/emit-host-config used to try this): a :flush-stage
;; hook that shelled out to `node <entry> --list-hook-events` observed a
;; stale/empty adapter -- shadow-cljs's own final write of this target's
;; compiled output does not appear to precede that stage's hooks running.
;; This runs instead as a second, separate `node` invocation *after*
;; `scripts/build-host-targets.sh claude-server` has completed Shadow's release
;; phase (see scripts/build-host-targets.sh), against this exact process's own,
;; definitely-complete `adapter` -- no cross-process ordering assumption
;; needed.
;; ---------------------------------------------------------------------------

(defn- event-to-script-name
  "\"PreToolUse\" -> \"pre-tool-use\"."
  [event-name]
  (-> event-name
      (str/replace #"[A-Z]" #(str "-" (str/lower-case %)))
      (str/replace #"^-" "")))

(defn- settings-json [claude-events->script-names]
  (str "{\n  \"hooks\": {\n"
       (str/join ",\n"
                 (map (fn [[claude-event script-name]]
                        (str "    " (js/JSON.stringify claude-event) ": [\n"
                             "      {\n"
                             "        \"hooks\": [\n"
                             "          {\n"
                             "            \"type\": \"command\",\n"
                             "            \"command\": \".claude/hooks/" script-name ".sh\"\n"
                             "          }\n"
                             "        ]\n"
                             "      }\n"
                             "    ]"))
                      claude-events->script-names))
       "\n  }\n}\n"))

(defn- hook-script-source [entry-path canonical-event]
  (str "#!/bin/bash\n"
       "# Generated by eta-mu.boundaries.claude/emit-hook-config! -- do not edit.\n"
       "# Canonical event: " canonical-event "\n"
       "exec node " entry-path " --hook " canonical-event "\n"))

(defn emit-hook-config!
  "Write .claude/settings.json + .claude/hooks/*.sh for every canonical
   event `adapter` actually has a hook registered against. `entry-path` is
   this running process's own script path (`js/process.argv[1]`) -- the
   generated wrapper scripts re-invoke that exact same compiled artifact
   with `--hook <event>` per call."
  [adapter entry-path]
  (let [canonical-events (map qualified-name (keys (:hooks adapter)))]
    (if (empty? canonical-events)
      (js/console.warn "[eta-mu.boundaries.claude] no hooks registered on the active adapter -- .claude/settings.json will have no hook entries. Check .ημ/config/*/profiles.edn allow-lists if a plugin's hooks were expected.")
      (let [claude-events->script-names
            (into {}
                  (map (fn [ce]
                         (let [claude-event (events/for-host (keyword ce) :claude)]
                           [claude-event (event-to-script-name claude-event)])))
                  canonical-events)
            script-name->canonical
            (into {} (map (fn [ce] [(event-to-script-name (events/for-host (keyword ce) :claude)) ce])) canonical-events)]
        (.mkdirSync node-fs ".claude/hooks" #js {:recursive true})
        (doseq [[script-name canonical-event] script-name->canonical
                :let [path (str ".claude/hooks/" script-name ".sh")]]
          (.writeFileSync node-fs path (hook-script-source entry-path canonical-event) "utf8")
          (.chmodSync node-fs path 493)) ;; 0o755
        (.writeFileSync node-fs ".claude/settings.json" (settings-json claude-events->script-names) "utf8")
        (println (str "[eta-mu.boundaries.claude] wired " (count canonical-events)
                      " Claude hook(s): " (str/join ", " canonical-events))))))
  (.exit js/process 0))
