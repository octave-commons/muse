# Profile System Reference

Source: `docs/inbox/2026.07.11.17.19.10.md` §5 (profiles)

## Profiles.edn

```clojure
{:dev
 {:allow #{:capability/research-search
           :capability/browser-inspect
           :capability/git-status}
  :audit :verbose}

 :ci
 {:allow #{:capability/git-status}
  :deny-effects #{:network/search :browser/control}
  :audit :strict}

 :personal
 {:allow #{:capability/*}
  :audit :full}}
```

## Selection Logic

```clojure
(defn selected? [{:keys [enable deny]} {:keys [id tags]}]
  (and
   (or (empty? enable)
       (some #(or (= id %)
                  (and (keyword? %)
                       (= "*" (name %))
                       (= (namespace id) (namespace %))))
             enable))
   (not (some #(or (= id %)
                   (and (= "*" (name %))
                        (= (namespace id) (namespace %))))
              deny))))

(defn apply-profile [profile registry]
  (update registry :tools #(filterv (partial selected? profile) %)))
```

## Key Rules

- Profile selects **how a host is allowed to expose capabilities**
- Not universal semantics of a capability
- Compiler rejects config that enables a tool whose declared effects are denied by profile
- Selection uses `namespace/*` wildcard: `:research/*` matches `:research/search`
- `deny-effects` blocks tools that declare those effects, regardless of allow list

## Pipeline

```
opencode.edn + fragments + profile
  → normalized registry EDN
  → validated linked registry
  → OpenCode plugin/tool adapters + opencode.json
```

Profile selected via `OPENCODE_PROFILE=dev` env var or build config.
Output is deterministic, snapshot-testable.
