# OpenCode DSL Design — Quick Reference

Decompiled from `docs/inbox/2026.07.11.17.19.10.md` and `2026.07.11.19.03.03.md`.

## Files

| File | What it covers |
|------|---------------|
| `01-dsl-design.md` | Macros, hiccup, normalize, compile, result algebra |
| `02-edn-config.md` | root.edn, fragments, imports, merge, handler registry |
| `03-profiles.md` | Allow/deny, wildcard selection, deny-effects |
| `04-opencode-integration.md` | Plugin vs Tool, tool() helper, shim pattern, generated output |
| `05-boundary-law.md` | Namespace rules, JS interop restrictions, wire contracts |
| `06-typescript-emission.md` | .d.ts from contract IR, Malli→TS translation, emitter arch |

## Implementation Order

```
1. DSL macros + schemas        (done: src/cljs/eta_mu/opencode/{dsl,schema,normalize,compile,profile}.cljc)
2. EDN config fragments        (done: .ημ/config/opencode/*.edn)
3. Actor system on envelopes   (done: src/cljs/eta_mu/actor*.cljc)
4. OpenCode plugin integration (BLOCKED: need compiler that emits .mjs from DSL data)
5. Boundary enforcement        (clj-kondo hooks for JS interop rules)
6. TypeScript emission         (future: src/ημ/emit/typescript.cljs)
```

## Key Insight

The DSL defines tools/hooks as **CLJS data with CLJS handler functions**.
The compiler renders them into **JS modules that OpenCode can load**.

Domain code never sees JS. Only the generated adapter touches the host.

## OpenCode Plugin Format

```js
// .opencode/plugins/my-plugin.js
export default async function(ctx) {
  return {
    tool: { my_tool: tool({ description, args, execute }) },
    "tool.execute.before": async (input, output) => { ... },
  }
}
```

## OpenCode Tool Format (standalone)

```js
// .opencode/tools/my-tool.js
import { tool } from "@opencode-ai/plugin"
export default tool({
  description: "...",
  args: { param: tool.schema.string() },
  async execute(args, context) { ... }
})
```
