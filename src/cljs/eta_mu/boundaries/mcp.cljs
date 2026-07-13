(ns eta-mu.boundaries.mcp
  "The MCP host boundary. This is the ONLY namespace allowed to touch the
   @modelcontextprotocol/sdk JS objects or the zod library on this target's
   behalf. Everything upstream (schema, normalize, profile, compile, plugin)
   is pure CLJC data; this namespace renders a compiled adapter into an
   MCP server any MCP client (Claude Code, Codex, ...) can connect to over
   stdio:
     tool args    → zod raw shape (z.ZodRawShape), via registerTool
     tool result  → {content: [{type: \"text\" text}]}
   Hooks have no MCP analogue (no host lifecycle to patch/reject into), so
   they are ignored on this target."
  (:require ["@modelcontextprotocol/sdk/server/mcp.js" :refer [McpServer]]
            ["@modelcontextprotocol/sdk/server/stdio.js" :refer [StdioServerTransport]]
            [eta-mu.dsl.zod :as zod]))

;; ---------------------------------------------------------------------------
;; Ingress / egress
;; ---------------------------------------------------------------------------

(defn- decode-args [js-args]
  (js->clj js-args :keywordize-keys true))

(defn- default-ctx
  "MCP tool calls carry no session/worktree metadata the way an OpenCode
   tool-call ctx does; the server always runs from the repo it was started
   in, so :directory/:worktree both resolve to the process cwd."
  []
  (let [cwd (.cwd js/process)]
    {:directory cwd :worktree cwd}))

(defn- encode-result
  "Handlers return plain CLJS data. Strings pass through as text content;
   anything else is serialized to JSON text content."
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
