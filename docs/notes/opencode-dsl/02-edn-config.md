# EDN Configuration Reference

Source: `docs/inbox/2026.07.11.17.19.10.md` §4-§5

## Layout

```
.ημ/
  config/opencode/
    root.edn              ; composition root
    profiles.edn
    permissions/default.edn
    plugins/
      actors.edn
      research.edn
      policy.edn
  contracts/
    tool.edn
    hook.edn
    capability/
      research.edn
  ledgers/
    agent/
    policy/

.opencode/               ; GENERATED, disposable
  opencode.json
  plugins/eta-mu.mjs
```

`.ημ/config/opencode/` = editable source. `.opencode/` = generated runtime boundary.

## root.edn

```clojure
{:ημ/opencode-version 1
 :id                  :knoxx/opencode

 :imports
 ["profiles.edn"
  "permissions/default.edn"
  "plugins/actors.edn"
  "plugins/research.edn"
  "plugins/policy.edn"]

 :profile :dev}
```

## Domain Fragment (plugins/research.edn)

```clojure
{:tools
 [{:id          :research/search
   :capability  :capability/research-search
   :name        "research_search"
   :description "Search configured public sources."
   :args        [:map [:query :string] [:limit {:optional true} [:int {:min 1 :max 50}]]]
   :requires    #{:network/search}
   :tags        #{:research :network}}]

 :hooks
 [{:id       :research/audit-results
   :event    :tool.execute.after
   :priority 10}]}
```

`:impl` is a qualified symbol, NOT a serialized function. EDN names a capability;
the CLJS compiler resolves it from a handler registry.

## Capability Contract (contracts/capability/research.edn)

```clojure
{:id          :capability/research-search
 :input       [:map [:query :string] [:limit {:optional true} :int]]
 :output      [:map [:findings [:vector :map]]]
 :effects     #{:network/search}
 :handler     ημ.domain.research/search}
```

Generic capability is NOT named `:opencode/tool`. It's `:capability/...`.
OpenCode config maps it to a host-specific name.

## Import & Merge Rules

```clojure
(defn merge-fragments [& fragments]
  (reduce (fn [out fragment]
            (-> out
                (update :tools  into (:tools fragment))
                (update :hooks  into (:hooks fragment))
                (update :agents into (:agents fragment))))
          {:tools [] :hooks [] :agents [] :mcp []}
          fragments))
```

Import order is explicit. Collections concatenate. No "last writer wins."

## Duplicate Detection

```clojure
(defn duplicate-ids [definitions]
  (->> definitions (map :id) frequencies
       (keep (fn [[id n]] (when (< 1 n) id))) sort))
```

Collisions are compile errors, not silent overwrites.

## Handler Registry (no arbitrary eval)

```clojure
(def handlers
  {'knoxx.tools.research/search     research/search
   'knoxx.policy.secrets/check!     secrets/check!})

(defn link-impl [handlers {:keys [id impl] :as definition}]
  (if-let [h (get handlers impl)]
    (assoc definition :handler h)
    (throw (ex-info "Unknown implementation" {:id id :impl impl}))))
```

EDN is untrusted-ish. Resolve only against a compile-time registry.

## Config is a Projection

```
ημ/contracts/*.edn  +  ημ/config/opencode/**/*.edn  +  CLJS handler registry
        ↓
  validated OpenCode registry
        ↓
  .opencode/opencode.json
  .opencode/plugins/eta-mu.mjs
  generated docs / tool manifest
```
