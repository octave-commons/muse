# OpenCode Integration Reference

Source: `docs/inbox/2026.07.11.17.19.10.md` §4 §5, `docs/inbox/2026.07.11.19.03.03.md`

## Two Concepts: Plugins vs Tools

| Concept | Location | What it does |
|---------|----------|-------------|
| **Plugin** | `.opencode/plugins/*.js` | Returns a hooks map. Can also register tools via `tool` key. |
| **Tool** | `.opencode/tools/*.js` | Standalone tool definition file. Filename = tool name. |

A **plugin** can provide tools. A **tool** is just a tool.

## Plugin Contract (from OpenCode docs)

```js
// .opencode/plugins/my-plugin.js
export default async function MyPlugin(ctx) {
  // ctx has: project, directory, worktree, client, $
  return {
    tool: {
      my_tool: tool({ ... }),  // registered via tool() helper
    },
    "tool.execute.before": async (input, output) => { ... },
    "tool.execute.after": async (input, output) => { ... },
  }
}
```

- Receives context object
- Returns hooks map keyed by lifecycle event
- `tool` key contains tool definitions (created via `tool()` from `@opencode-ai/plugin`)
- Lifecycle hooks: `tool.execute.before`, `tool.execute.after`, `session.*`, `file.*`, `permission.*`, etc.

## Tool Contract (standalone)

```js
// .opencode/tools/database.js
import { tool } from "@opencode-ai/plugin"
export default tool({
  description: "Query the database",
  args: { query: tool.schema.string() },
  async execute(args, context) { ... }
})
```

- Filename becomes tool name
- Multiple exports = multiple tools (`math_add`, `math_multiply`)
- Uses `tool()` helper with Zod schemas

## Tool() Helper

`tool()` from `@opencode-ai/plugin` creates tool definitions with:
- `description` — what it does
- `args` — Zod schema object
- `execute(args, context)` — handler function

`context` has: `agent`, `sessionID`, `messageID`, `directory`, `worktree`

## CLJS → OpenCode Compilation Flow

```
deftool/defhook DSL (CLJS data)
        ↓
normalize → registry (merged, deduped, linked)
        ↓
compile → two outputs:
  1. .opencode/plugins/eta-mu.mjs  (plugin: hooks + tool map)
  2. .opencode/opencode.json       (config: plugin paths, permissions)
```

## The Shim Pattern

The CLJS compiles to a Node ESM module. A tiny JS shim bridges to OpenCode:

```text
shadow-cljs compile → dist/eta-mu.js (Node ESM, exports handlers)
                      ↓
.opencode/plugins/eta-mu.mjs (imports dist/eta-mu.js, wraps with tool())
```

The shim is ~20 lines. All logic is in CLJS. The shim only does:
1. Import compiled CLJS handlers
2. Call `tool()` helper to register them
3. Export the plugin function

## Generated opencode.json

```json
{
  "$schema": "https://opencode.ai/config.json",
  "plugin": ["file://./plugins/eta-mu.mjs"],
  "permission": {
    "muse_*": "allow",
    "phase_*": "allow"
  }
}
```

Only host concerns. Intentionally dumb.

## Key Distinction

The DSL defines tools/hooks as **CLJS data with CLJS handler functions**.
The compiler renders them into **JS modules that OpenCode can load**.

- `deftool` source = CLJS implementation, Malli schema, metadata
- Compiled output = JS file using `tool()` helper with Zod schema, importing CLJS handler
- Domain code never sees JS. Only the generated adapter touches the host.
