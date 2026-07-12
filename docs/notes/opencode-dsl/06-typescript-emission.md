# TypeScript Declaration Emission Reference

Source: `docs/inbox/2026.07.11.17.19.10.md` §6

## Principle

TypeScript declarations are a **consumer projection**, not the universal type language.
Emit from contract IR, not from implementation inference.

## Pipeline

```
TypedClosure annotations (or DSL macro input)
        ↓
canonical ημ type / contract IR
        ├── Closure annotations
        ├── Malli schema / JSON Schema
        ├── OpenCode tool schema (Zod)
        └── TypeScript .d.ts
```

## Malli → TypeScript Translation

```clojure
[:string]              → string
[:int]                 → number
[:boolean]             → boolean
[:nil]                 → null | undefined
[:vector :string]      → string[]
[:set :keyword]        → Set<string>
[:map [:id :uuid]]     → { id: string }
[:or :string :int]     → string | number
[:maybe :string]       → string | null
[:tuple :string :int]  → [string, number]
```

## Emitter Architecture

```text
src/ημ/
  type/
    ir.cljc         ; normalized, serializable descriptor
    normalize.cljc
  emit/
    closure.cljs    ; Closure annotations
    malli.cljs      ; Malli validators
    json_schema.cljs
    typescript.cljs ; .d.ts output
    opencode.cljs   ; OpenCode plugin/tool adapter
```

## Normalized Descriptor

```clojure
{:ημ/kind       :tool
 :ημ/id         :research/search
 :ημ/export?    true
 :ημ/params     [{:name :input  :type [:ref :contract/research-search-input]}]
 :ημ/returns    [:promise [:ref :contract/research-search-output]]
 :ημ/effects    #{:network/search}
 :ημ/docs       {:summary "Search configured public sources."}}
```

## Macro Integration

```clojure
(deftool research-search
  {:input  :contract/research-search-input
   :output :contract/research-search-output
   :export :typescript}
  [ctx input] ...)

;; Macro produces:
;; 1. Executable var/function
;; 2. Descriptor in compile-time manifest
```

## Wire Types

```clojure
{:ημ/type :uuid      :wire/type :string :wire/format :uuid}
{:ημ/type :keyword   :wire/type :string :wire/pattern "^[^/]+/.+$"}
{:ημ/type :instant   :wire/type :string :wire/format :date-time}
```

Prevents drift between CLJS internal types and JS/TS wire representations.

## Best First Milestone

1. Named scalar types: string, boolean, number, integer, keyword-as-string, UUID-as-string
2. Maps with required/optional keys
3. Vectors, tuples, enums, unions, references
4. Async tool functions as `Promise<T>`
5. Tagged result/error unions
6. Snapshot tests comparing emitted `.d.ts` against golden files
7. Tiny TypeScript consumer fixture compiled with `tsc --noEmit`
