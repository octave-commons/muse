# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

The muse is a ClojureScript workspace that authors OpenCode plugins as pure
data (an EDN/CLJC DSL) and runs a small filesystem daemon that keeps those
plugins built and published. Her flagship artifact is the `receipt_river`
plugin — an append-only `receipts.edn` execution ledger — the stable
reference implementation the wider eta-mu constellation should consume.

Deploying to a fresh machine: see `docs/DEPLOY.md` or run `scripts/bootstrap.sh`.
House rules for the layer architecture live in `AGENTS.md`; engineering style
(the fuller version) is in `STYLE.md`.

## Commands

```sh
# Tests (124 tests / 286 assertions currently; green without a local mongod)
shadow-cljs compile test

# Builds
shadow-cljs release daemon            # → dist-daemon/daemon.js
shadow-cljs release opencode-plugin   # → .opencode/dist + shims + host config
shadow-cljs release mcp-server        # → .mcp/dist/receipt-river.js + .mcp.json

# Daemon process (pm2)
pm2 start ecosystem.config.cjs        # app: eta-mu-daemon
pm2 logs eta-mu-daemon
```

Run a single test file/namespace by narrowing shadow-cljs's `:node-test`
`:ns-regexp` build config in `shadow-cljs.edn` (`(open-hax\.sol\..*-test$|eta-mu\..*-test$)`),
or `require` the target `-test` namespace directly at a REPL and call
`cljs.test/run-tests`.

`.opencode/opencode.json` is a **build output** of the `opencode-plugin`
target's flush hook — never hand-edit it, and don't be confused by it
appearing in `git diff`.

## Architecture

### The build pipeline (EDN → registry → boundary)

1. `.ημ/config/opencode/root.edn` and its `:imports` fragments declare which
   plugin resources are exposed (e.g.
   `{:resource plugins.receipt-river/plugin :expose [:receipt/*]}`), their
   permissions, profiles, and publish targets.
2. The `:opencode-plugin` shadow-cljs build's `:configure` hook
   (`eta-mu.opencode.build/generate-entrypoint`) generates
   `src/gen/eta_mu/gen/opencode_plugin.cljs`, which requires each plugin
   namespace and runs `apply-exposure → apply-profile → validate-registry! →
   compile-adapter`.
3. `eta-mu.boundaries.opencode/activate!` renders the compiled adapter into
   OpenCode `Hooks` — Malli → zod conversion happens only in this boundary.
4. The build's `:flush` hook (`eta-mu.opencode.build/emit-host-config`) writes
   the host artifacts: `.opencode/dist/eta-mu-actors.js` (bundle),
   `.opencode/plugins/eta-mu-actors.js` (a function-only export shim —
   OpenCode 1.17 requires every export of a plugin file to be a function),
   `.opencode/opencode.json`, `.opencode/package.json`, and agent markdown
   under `.opencode/agents/`.
5. If `root.edn` declares `:publish`, the same shims/agents are also written
   to `~/.config/opencode/plugins` and `~/.config/opencode/agents`, so every
   OpenCode session on the machine gets the plugins. The global shim imports
   the repo's dist by **absolute path** — moving the repo requires a rebuild.

The MCP server build (`:mcp-server` target, `src/clj/eta_mu/mcp/build.clj`)
follows the same generate-entrypoint / emit-host-config shape, targeting
`.mcp/dist` and `.mcp.json` instead.

### Namespace layering

| Layer | Pattern | Rule |
|---|---|---|
| `domain.*` | Business logic | No I/O. Pure functions only. |
| `boundaries.*` | Node fs/proc/watch, mongo, OpenCode host, fetch | The only namespaces allowed to touch I/O. |
| `dsl.*` / `dsl/{compile,normalize,profile,schema}` | Plugin DSL | `deftool`/`defhook`/`defplugin` emit host-agnostic data; compiled into adapters. |
| `actor.*` | Actor/envelope/store model | Backend-agnostic; see Ledger backends below. |
| `opencode.*` | Config/settings shaping | Pure merge logic consumed by the boundary. |

`AGENTS.md`/`STYLE.md` describe a stricter target layering
(`law.* → shape.* → extern.* → domain.* → infra.*`) for new construction;
existing code mostly maps `boundaries.*` ≈ `extern.*`/`infra.*` and
`domain.*` stays the same. When adding new namespaces, prefer the
`law/shape/extern/domain/infra` names and follow the Discovery →
Describe → specify → define → shape → extern → domain → infra build order.

### The daemon

`src/cljs/eta_mu/daemon/core.cljs` (built via the `:daemon` shadow-cljs
target) is a filesystem watcher, not a server — it binds no ports.

- On start it scans for `.ημ/` directories under `~` (depth 1), `~/spaces`
  (depth 3), and `~/devel` (depth 4), and re-scans every 5 minutes.
- It watches each tree's `config/` and `plugins/` subdirectories. On a
  relevant change (`.edn/.cljc/.cljs/.clj`, debounced 400 ms) it reads that
  tree's `config/opencode/root.edn` and executes its plan: `:render` (write
  merged settings) or `:exec` (spawn the tree's `:build` command in the repo
  root).
- Boot convergence runs renders but not builds.
- It logs append-only JSONL to `~/.eta-mu/state/daemon/log.jsonl`.

### Ledger backends

The actor ledger backend is selected by environment variables
(`src/cljs/eta_mu/actor/backend.cljs`):

| Variable | Default | Meaning |
|---|---|---|
| `ETA_MU_LEDGER_BACKEND` | `file` | `file` \| `mongo` \| `memory` |
| `ETA_MU_LEDGER_ROOT` | `.eta-mu/ledgers` | Root for the file backend |
| `ETA_MU_MONGO_URI` | `mongodb://127.0.0.1:27017/?directConnection=true` | Mongo connection (credentials live only here) |
| `ETA_MU_MONGO_DB` | `eta_mu` | Database name |

`mongodb` is loaded dynamically, so plugin/daemon bundles don't require it
unless a mongo backend is configured. The mongo boundary
(`src/cljs/eta_mu/boundaries/mongo/ledger.cljs`) speaks
`@promethean-os/event-ledger`'s wire format directly (see its docstring for
why it doesn't call the package).

### receipt_river

Source: `.ημ/plugins/receipt_river.cljs` (host tool name `receipt_river`, id
`:receipt/river`), pure logic in `src/cljs/eta_mu/domain/receipts.cljc`.
Actions: `status | bootstrap | append | tail | validate` over a per-repo
append-only `receipts.edn`. This repo's own `receipts.edn` is both the ledger
and the living example of the format. Other worlds in the eta-mu
constellation should consume the plugin this repo's daemon publishes rather
than maintaining their own variant (`docs/DEPLOY.md` § "Serving other
worlds").

## Deployment gotchas (full detail in docs/DEPLOY.md)

- Clone this repo only under `~`, `~/spaces`, or `~/devel` — those are the
  daemon's scan roots.
- `shadow-cljs.edn` also lists `../katamorph/src/cljs` and
  `../event-ledger/src` as source paths for the `:app`/`:server` builds; they
  must exist (real or stub) for shadow-cljs classpath resolution even when
  building the daemon/plugin/test targets.
- `@promethean-os/event-ledger` is a `file:` dependency on a sibling `eta-mu`
  checkout — no registry fallback.
- Always rebuild `opencode-plugin` after cloning or moving the repo; the
  published global shim hardcodes an absolute path to this checkout's dist.

## House rules (from AGENTS.md / STYLE.md)

- **Zero warnings**: `clj-kondo`, type checks, and tests must all pass with
  zero warnings — treat warnings as failed contracts.
- **Categories vs. Contracts**: keep the grammar of lawful transformations
  (state space, transition vocabulary — "what kind of move this is")
  separate from admissibility enforcement (guards, evidence, side-effect
  constraints — "whether this counts as valid right now").
- **Modern ClojureScript**: use `^:async` metadata (CLJS ≥ 1.12.145) and
  `await`; never `core.async` channels or Promise chains in new code.
- Prefer `when-let` over nested `let`+`if`, and `->`/`->>` over nested `let`.
  No `utils` namespaces, no broad `:refer :all`. Register custom macros in
  `.clj-kondo/config.edn` on day one.
- **Kanban**: work is anchored on cards (`eta-mu kanban list`); status moves
  go through the CLI/Rheos FSM (`eta-mu kanban frontmatter <uuid> status
  <new-status>` or the Rheos `status-update` command), never hand-edited
  frontmatter — the file watcher flags direct edits as drift.
