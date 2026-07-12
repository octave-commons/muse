# DSL Design Reference

Source: `docs/inbox/2026.07.11.17.19.10.md` §1-§3

## Core Principle
Data as IR. Macros are ergonomic constructors. Resulting values are plain data.
OpenCode hook maps / `tool()` calls are a compilation target, not the authoring surface.

## Registry Shape (the IR)

```clojure
{:opencode/kind :tool
 :id            :research/search
 :name          "research_search"
 :description   "..."
 :args          [:map [:query :string] [:limit {:optional true} :int]]
 :handler       research/search}

{:opencode/kind :hook
 :id            :policy/protect-env
 :event         :tool.execute.before
 :priority      100
 :handler       policy/protect-env!}

{:opencode/kind :plugin
 :id            :plugin/research
 :entries       [tool-or-hook-maps...]}
```

`:args` uses Malli forms — data, inspectable, can derive Zod/JSON-Schema/TS/docs.

## Macros (deftool / defhook / defplugin)

```clojure
(deftool search
  {:id          :research/search
   :description "Search public sources."
   :args        [:map [:query :string] [:limit {:optional true} :int]]}
  [{:keys [query limit]} _ctx]
  {:content (run-search query (or limit 10))})

(defhook deny-env-access
  {:id       :policy/deny-env-access
   :event    :tool.execute.before
   :priority 100}
  [{:keys [tool args]} _ctx]
  (when (env-file? (:path args))
    {:opencode/action :reject :message "Blocked"}))

(defplugin research-tools search-tool deny-env-access)
```

Rules:
- Macros emit plain data, no side effects, no global registration
- `deftool` → `{:opencode/kind :tool, :id ..., :handler (fn ...)}`
- `defhook` → `{:opencode/kind :hook, :event ..., :handler (fn ...)}`
- `defplugin` → `{:opencode/kind :plugin, :entries [...]}`

## Hiccup Composition (alternative to macros)

```clojure
(def research-plugin
  [:plugin {:id :plugin/research}
   [:tool {:id :research/search :description "Search." :args [:map [:query :string]]}
    research/search]
   [:hook {:id :policy/protect-env :event :tool.execute.before :priority 100}
    policy/protect-env!]])

(def app
  [:plugin {:id :plugin/knoxx}
   research-plugin browser-plugin policy-plugin])
```

`normalize` converts hiccup → canonical registry maps.

## Normalizer

```clojure
(defn normalize [[tag attrs & children :as form]]
  (case tag
    :plugin {:opencode/kind :plugin :id (:id attrs) :entries (mapv normalize children)}
    :tool   (let [[handler] children]
              (assoc attrs :opencode/kind :tool
                     :name (or (:name attrs) (name (:id attrs)))
                     :handler handler))
    :hook   (let [[handler] children]
              (assoc attrs :opencode/kind :hook :priority (or (:priority attrs) 0)
                     :handler handler))))
```

## Compiler (registry → OpenCode adapter)

1. Flatten plugin tree via `tree-seq`
2. Group hooks by `:event`, sort by `:priority`
3. Build one callback per event (many hooks → one OpenCode callback)
4. Convert Malli args → Zod schema for OpenCode tool()

## Result Algebra (for hooks)

```clojure
{:effect :continue}
{:effect :reject :message "Blocked by policy"}
{:effect :patch :output {:path "safe-replacement"}}
{:effect :log :level :warn :message "..."}
```

## Context Normalization

Tool functions receive normalized ημ context, never raw OpenCode context:

```clojure
{:session/id    "..."
 :workspace/root "/repo"
 :worktree/root  "/repo"
 :cwd            "/repo"
 :capabilities   #{:filesystem/read :network/search}}
```

## Final Vocabulary

```clojure
(deftool name options [input ctx] ...)
(defhook name options [event ctx] ...)
(defplugin name entries...)
(plugin [& entries])
(tool opts handler)
(hook opts handler)

;; Combinators
(only-in #{:dev} plugin)
(requires #{:network/search} tool)
(with-policy policy tool)
(named "browser_inspect" tool)
```
