# Boundary Law Reference

Source: `docs/inbox/2026.07.11.17.19.10.md` §7

## The Law

> **Domain and infrastructure operate on ημ values. Boundary namespaces alone may see JavaScript values, promises, callbacks, or host objects.**

## Namespace Rules

| Namespace | JS values? | Promise? | Responsibility |
|-----------|-----------|----------|---------------|
| `shape.*`, `law.*`, `domain.*` | Never | Never | Pure contracts, transformations, decisions |
| `application.*` | Never | Prefer no | Use-case orchestration |
| `infra.*` | Never directly | Never directly | Port implementations in ημ data |
| `boundaries.*` | Yes | Yes | Node/npm/framework translation |
| `runtime.*` | Yes | Yes | Process startup, lifecycle |
| Generated adapter | Yes | Yes | Host-specific glue only |

## Data Flow

```
domain → application → infra → boundaries.node.fastify → Fastify
```

NOT:
```
domain → infra.fastify → Fastify
```

## Boundary Naming

```
src/ημ/boundaries/
  js.cljs              ; minimal primitive conversions
  node/
    fs.cljs
    path.cljs
    streams.cljs
  fastify.cljs
  opencode.cljs
  npm/
    zod.cljs
    pino.cljs
```

No one imports npm directly outside `ημ.boundaries.*`.

## Wire Contracts at Boundaries

```clojure
(defn decode-request [request]
  {:http/method  (keyword (.-method request))
   :http/path    (.-url request)
   :http/headers (js->clj (.-headers request))
   :http/body    (js->clj (.-body request) :keywordize-keys true)})

(defn encode-response [{:http/keys [status headers body]}]
  {:status (or status 200) :body (clj->js body)})
```

Validate with Malli BEFORE the object gets deeper. This is "enforce an ingress contract," not "convert JS to Clojure."

## Promise Rule

> A Promise is a transport implementation detail, not a domain value.

```clojure
(defn ^:async read-text! [path]
  (await (.readFile fs path "utf8")))  ; returns Promise<string> to host

(defn ^:async load-contract! [path]    ; infra layer
  (let [text (await (fs/read-text! path))  ; await, then CLJS data
        value (reader/read-string text)]
    (contract/validate! value)))         ; no JS types leak in
```

## Forbidden Outside boundaries

```
js*, js-obj, js->clj, clj->js, aget, aset, alength,
goog.object/*, #js literals, .- / .? member access, ^js hints,
direct npm requires: ["fastify" :as ...]
```

Enforce with clj-kondo custom hooks.

## Effect Result Algebra

```clojure
{:ημ/result :ok, :value contract}
{:ημ/result :error, :error {:kind :contract/not-found :id id}}
{:ημ/result :error, :error {:kind :io/unavailable :retryable? true}}
```

At Fastify → status + JSON. At OpenCode → tool-result. At MCP → MCP error.
The core never learns those differences.

## Macros Generate Adapters

```clojure
(defroute create-contract
  {:method :post :path "/contracts"
   :input  [:map [:contract ημ.contract/contract]]
   :output [:map [:contract ημ.contract/contract]]}
  [{:keys [contract]} context]
  (contracts/create! context contract))
```

Macro generates Fastify decode/encode at the boundary. Handler body sees only ημ data.
