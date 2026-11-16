# AGENTS.md — Muse

The single source of truth for every agent harness in this repository (Claude
Code, Codex, OpenCode and others). `CLAUDE.md` imports it and adds only
Claude-Code-specific notes. Board process detail: [`docs/agile/AGENTS.md`](docs/agile/AGENTS.md).
Toolchain, build and run detail: [`docs/DEPLOY.md`](docs/DEPLOY.md).

## What this repository is

Muse is a ClojureScript compatibility and compilation system for agent
harnesses. Plugins are authored as pure data (an EDN/CLJC DSL under `.ημ/`),
compiled into host-native artifacts for OpenCode, Claude Code and MCP clients,
and kept current by a small filesystem daemon. Muse owns linking, target
compilation and host adapters, not actor, event, ledger or workflow semantics;
read the accepted [compatibility boundary](docs/architecture/compatibility-boundary.md)
before moving ownership. `.ημ/plugins/*.cljs` are conformance fixtures and
reference integrations; the flagship is `receipt_river`. `.eta-mu` is an ASCII
symlink to `.ημ`.

## Commands

Run from the repository root (Node.js ≥ 20.3.0, Java 21, Clojure CLI, clj-kondo).
Every target builds from this checkout alone; sibling checkouts and source stubs
are not prerequisites.

```sh
npm ci --ignore-scripts         # install
npm test                        # compile the :test build, run target/test/test.cjs
npm run lint                    # clj-kondo on src/cljs src/clj test/cljs .ημ; fails on warnings
npm run build                   # prebuild generators, then release daemon, opencode-plugin,
                                #   mcp-server, claude-server, review-invocation
npm run build:opencode          # .opencode/dist + plugin shims + host config
npm run build:mcp               # .mcp/dist/receipt-river.js + .mcp.json
npm run build:claude            # .claude/dist + Claude hook config
npm run build:review-invocation # .opencode/dist/review-invocation.cjs
npm run test:build-cold         # each host's EDN build command in a disposable cold copy
npm run test:review-input       # full-review-input and review-invocation verifiers
scripts/bootstrap.sh            # install, build, test, lint; --start-daemon also starts pm2
npx --no-install shadow-cljs release daemon  # dist-daemon/daemon.js only
pm2 start ecosystem.config.cjs  # daemon as pm2 app eta-mu-daemon
pm2 logs eta-mu-daemon
```

CI (`.github/workflows/ci.yml`) runs `npm test`, `npm run build`,
`npm run test:build-cold`, `npm run test:review-input` and `npm run lint`, and
fails unless the test log contains `0 failures, 0 errors.`

- **One namespace:** narrow the `:test` build's `:ns-regexp` in
  `shadow-cljs.edn` (currently `(open-hax\.sol\..*-test$|eta-mu\..*-test$)`)
  without committing it, or `require` the `-test` namespace at a REPL
  (shadow-cljs nREPL port 4501) and call `cljs.test/run-tests`.
- **Mongo:** the Mongo ledger integration test passes by skipping when no
  mongod is reachable, so a plain green run is not evidence of Mongo behavior.
  Point `ETA_MU_MONGO_URI` at a reachable server when a change touches the
  Mongo boundary.

### Generated artifacts

Never hand-edit build outputs; change the source and rebuild.

- `src/gen/` — entrypoints generated from `.ημ/config/*/root.edn`.
- `.opencode/opencode.json` — tracked although `.gitignore` lists it, so builds
  show it in `git diff`. Also `.opencode/{dist,plugins}/`,
  `.opencode/package.json` and `.opencode/agents/` (edit
  `.ημ/config/opencode/agents/` instead).
- `.mcp/dist/`, `.mcp.json`, `.claude/dist/`, `.claude/settings.json`,
  `.claude/generated-hooks/`, `dist-daemon/`, `target/`. The handwritten
  scripts in `.claude/hooks/` are tracked source.

`.ημ/config/opencode/root.edn` declares `:publish`, so OpenCode builds also
write shims and agents to `~/.config/opencode/{plugins,agents}`, which every
OpenCode session on the machine loads. The global shim imports this checkout's
dist by **absolute path**: rebuild `opencode-plugin` after cloning or moving
the repository.

## Architecture

### Build pipeline (EDN → registry → host)

1. `.ημ/config/{opencode,mcp,claude}/root.edn` each `:imports` fragments from
   `.ημ/config/shared/` (profiles, permissions, plugin exposure such as
   `{:resource plugins.receipt-river/plugin :expose [:receipt/*]}`) and
   declare a `:profile`, a `:build` command and optional `:publish` targets.
2. Each target's shadow-cljs `:configure` hook (`generate-entrypoint` in
   `src/clj/eta_mu/{opencode,mcp,claude}/build.clj`; `npm run prebuild` runs
   all three) writes `src/gen/eta_mu/gen/{opencode_plugin,mcp_server,claude_server}.cljs`,
   which requires each plugin namespace and runs `apply-exposure →
   apply-profile → validate-registry! → compile-adapter`.
3. `eta-mu.boundaries.{opencode,mcp,claude}` render the compiled adapter into
   host hooks and tools. Only these boundaries require the Malli → zod
   conversion in `eta-mu.dsl.zod`.
4. Each target's `:flush` hook (`emit-host-config`) writes that target's host
   artifacts listed above: for OpenCode, the bundle
   `.opencode/dist/eta-mu-actors.js` and the function-only export shim
   `.opencode/plugins/eta-mu-actors.js` (OpenCode 1.17 requires every export of
   a plugin file to be a function); for MCP and Claude, the output
   `package.json` and `.mcp.json`. The compiled Claude server writes its own
   hook config afterwards (`scripts/build-claude-target.sh` explains why).

### Namespace architecture

| Layer | Pattern | Rule |
|---|---|---|
| `law.*` | Contracts/Malli | No I/O. Validators only. |
| `shape.*` | Data morphisms | Pure, domain-agnostic. |
| `extern.*` | Raw JS/Node/browser/SDK edges | Decode foreign data into defined shapes at the edge. |
| `domain.*` | Business logic | No I/O. Pure functions only. |
| `infra.*` | Transport/DB/Queue | No domain policy. |

Namespaces that predate this layering:

- `boundaries.*` — Node fs/proc/watch, Mongo, fetch and host codecs; the
  existing I/O edge, playing the `extern.*`/`infra.*` role.
- `dsl.*` — the plugin DSL; `deftool`/`defhook`/`defplugin` emit host-agnostic
  data that is compiled into adapters.
- `actor.*` — actor/envelope/store model; backend-agnostic.
- `daemon.*` — the filesystem-watcher daemon's effect orchestration.
- `opencode.*` — config/settings shaping; pure merge logic consumed by the boundary.

Only `extern.*` and `boundaries.*` perform I/O directly. `infra.*`, `daemon.*`
and the actor runtime perform effects only through them. `domain.*`, `law.*`,
`shape.*`, `dsl.*` and `opencode.*` do no I/O. Name new namespaces with the
`law/shape/extern/domain/infra` layers and follow the construction order below.

### Daemon

`src/cljs/eta_mu/daemon/core.cljs` (shadow-cljs `:daemon` target) is a
filesystem watcher, not a server: it binds no ports.

- It discovers `.ημ/` directories under `~` (depth 1), `~/spaces` (depth 3)
  and `~/devel` (depth 4) and rescans every 5 minutes. A checkout elsewhere
  builds and tests normally but is not watched.
- On a `.edn`, `.cljc`, `.cljs` or `.clj` change under a tree's `config/` or
  `plugins/` (debounced 400 ms) it reads each `config/{opencode,mcp,claude}/root.edn`
  and runs its plan: `:render` writes merged settings; `:exec` runs that tree's
  `:build` in the repository root, one build per repository at a time.
- Boot convergence renders but does not build.
- It logs append-only JSONL to `~/.eta-mu/state/daemon/log.jsonl`.

### Ledger backends

`src/cljs/eta_mu/actor/backend.cljs` selects the actor ledger backend:

| Variable | Default | Meaning |
|---|---|---|
| `ETA_MU_LEDGER_BACKEND` | `file` | `file` \| `mongo` \| `memory` |
| `ETA_MU_LEDGER_ROOT` | `.eta-mu/ledgers` | Root for the file backend |
| `ETA_MU_MONGO_URI` | `mongodb://127.0.0.1:27017/?directConnection=true` | Mongo connection; credentials live only here |
| `ETA_MU_MONGO_DB` | `eta_mu` | Database name |

`mongodb` is loaded dynamically, so bundles need it only when the mongo backend
is selected. The mongo boundary (`src/cljs/eta_mu/boundaries/mongo/ledger.cljs`)
writes `@open-hax/event-ledger`'s documented wire format directly (its
docstring explains why). It is a compatibility adapter: keep it conformant with
the authoritative package rather than forking its laws. That package is
deprecated; canonical event envelope, append, ordering and replay belong to
eta-mu/packages/clio.

## Ledgers and provenance

- **Receipt River:** this repository is in legacy mode; the ledger is the
  repo-root `receipts.edn`, which is also the living example of the format.
  Append only. Never rewrite, reorder or relocate prior entries, and never
  record secrets.
- **`receipt_river` tool:** `.ημ/plugins/receipt_river.cljs` (id
  `:receipt/river`; pure logic in `src/cljs/eta_mu/domain/receipts.cljc`)
  offers `status | bootstrap | append | tail | validate` over a single
  `receipts.edn`. Per the segmented-mode rules in
  `~/.agents/skills/receipt-river/SKILL.md`, never point it at a repository
  with `.ημ/receipts/` segments. Other repositories may consume the published plugin; that does not
  transfer event-ledger or runtime authority into Muse.
- **Session mycology:** `.ημ/session-mycology/ledger.md`.
- **Kanban events:** `.ημ/kanban-events/ledger.edn`, written only by Rheos.
- **Fork-tax snapshots:** `.ημ/Π_LAST.md` and `.ημ/Π_STATE.sexp`.

## Clojure house rules

### Architecture Paradigm: Categories vs. Contracts
When modeling domains, you must strictly differentiate between the grammar of motion and the enforcement of that motion.
- Categories: Describe the space of lawful possible transformations. They dictate "what kind of move this is" and define the state space, transition vocabulary, and general laws of composition for the runtime or a subsystem.
- Contracts: Decide whether a particular runtime entity, event, or transition is admissible under current obligations. They dictate "whether you are allowed to count it as a valid move right now" by defining guards, admissibility checks, evidence requirements, delivery expectations, and side-effect constraints.

### Zero Warnings
`clj-kondo`, type checks, and tests must all pass with zero warnings. Warnings
are failed contracts, not noise.

### Clojure Construction Order
Regardless of the kanban process, ClojureScript is **built in a fixed order**, because the
order *is* the dependency DAG: each layer compiles against already-defined lower layers.

```
Discovery → ( Describe → specify → define → shape → extern → domain → infra )
```

- **Discovery** — survey what already exists before constructing. Opens every cycle and
  recurs *inside* every step (see the anomaly rule below). Output: a named inventory of the
  shapes in play, including the ones that are already there.
- **Describe** — state the projection's intent in prose (a note, a kanban card body).
- **specify** — pin acceptance criteria / exit signals (the kanban "Clarify & Scope" pass).
- **define** — author the `law.*` that **describes** the shape (μ). A law is a description of
  what a valid instance is, not the data and not the transform; that is why it has no
  dependencies and comes first. (`define` and `shape` are complementary roles, not one
  artifact moved between layers — the law describes; the shape is the morphism it describes.)
- **shape** — `shape.*` pure morphisms that produce/consume the described shapes (parse,
  enrich, (de)serialize). Depends only on `law`.
- **extern** — `extern.*` raw JS / Node / browser / SDK boundaries, decoding foreign data
  into defined shapes at the edge. Nothing above `extern` touches a raw host object.
- **domain** — `domain.*` pure decisions over shaped data. Depends on `shape` + `law`.
- **infra** — `infra.*` effect orchestration composing `extern` adapters and `domain`
  decisions, returning CLJS data. Depends on everything below it.

#### The anomaly rule (a surprise at every step)
Every step is also a discovery step. You will keep finding shapes you didn't realize were
already there — in this package or a sibling.

> If the discovery does **not** invalidate the shape of your targeted projections, you
> **describe the anomaly** (location + whether it's already-there reuse or a contradiction)
> and **keep going**. If it **does** invalidate a target, stop and re-`describe` that projection.

Anomalies are logged, not silently absorbed. A worked example of a Discovery pass and its
anomaly log: `docs/rheos-chat-ui-shape-discovery.md` in the open-hax/eta-mu repository.

### Modern ClojureScript
Always use `^:async` metadata (ClojureScript ≥ 1.12.145). Never use
`core.async` channels or Promise chains in new code.

```clojure
(defn ^:async fetch-data [url]
  (await (js/fetch url)))

(deftest ^:async fetch-test
  (is (some? (await (fetch-data "https://example.com")))))
```

### Idioms
- `when-let` over nested `let` + `if`
- `->` / `->>` over nested `let` forms
- No `utils` namespaces
- No broad `:refer :all`
- Custom macros registered in `.clj-kondo/config.edn` on day one

### Code Style
- **ClojureScript** (all new code): Reagent components, kebab-case functions, atoms for state, Tailwind CSS
- **Types**: Strict TypeScript enabled for legacy TS; ClojureScript uses Malli schemas for validation
- **Error handling**: Try/catch only when necessary, proper error logging via bus events
- **Formatting**: Consistent indentation, no unnecessary destructuring, single-responsibility functions

`STYLE.md` repeats a subset of these rules.

## Board operations

- **Work from a card.** Never work off-board. Anchor every implementation slice on a kanban task and record the scoped plan on the card before moving to implementation.
- **Move cards with the Rheos CLI.** Run commands from the **repo root** so the board resolves correctly:
  - `eta-mu kanban list` — current board.
  - `eta-mu kanban count` — column counts.
  - `eta-mu kanban comment <uuid> "note"` — append provenance to a card.
  - `eta-mu kanban frontmatter <uuid> status <new-status>` — lawful status change; delegates to Rheos and runs transition gates when required.
  - Status moves go through this CLI or the Rheos `status-update` command, never hand-edited frontmatter; the file watcher flags direct edits as drift.
- **Markdown authoring is first-class input.** Cards may be created and edited manually as Markdown with explicit `uuid:` identity, outcome, scope, acceptance and verification. Use comma-separated scalar labels. Manual creation does not require an invented `write-id` or `kanban.task-created` event. Rheos alone owns operational status, comments, transitions and drift reconciliation; never hand-edit an existing card status or fabricate engine events. A harness without Rheos may inspect artifacts and author card content but must not claim board validation.
- **Walk lawful hops.** There are no shortcut edges. To move a card multiple columns forward, step through each lawful transition in order. The direct `in_progress → review` edge exists only when the build-gate passes.
- **Done needs evidence.** The `kanban-gate` plugin (`.ημ/plugins/kanban_gate.cljs`, in the OpenCode and Claude targets) is meant to reject a `done` transition through a kanban status-update tool unless the card's comments hold a `<N> tests, <N> assertions, 0 failures` line and an explicit review or audit approval, plus, when `.ημ/kanban-done-gate.edn` names a `:dispatch-file`, every `ep <command>` the card names exists there. It rejects when Rheos (`RHEOS_HTTP_URL`, default `http://127.0.0.1:8791`) is unreachable. Its docstring records as unverified whether every host awaits the hook's result; the Claude boundary does. Do not treat it as proof of enforcement on other hosts. Its direct-edit guard matches only `docs/kanban/{stories,epics,chores}/`, not this repository's `docs/agile/kanban/`; the no-hand-edit rule applies regardless.
- **Keep provenance under `.ημ`.** The source of truth is `docs/agile/kanban` plus its `.events` symlink into `.ημ/kanban-events`. Never create a physical board ledger under `docs/`.

## Muse / plugin authoring skills

- **muse-config-authoring** — Create/modify `.ημ/config/opencode/` config trees (root.edn, plugin exposure fragments, profiles, permissions). Use when registering new plugins, adjusting tool exposure, or setting up a project's eta-mu config.
- **muse-plugin-authoring** — Write new muse plugins using the eta-mu DSL (deftool, defhook, defplugin). Use when creating new tools, hooks, or plugin bundles for the OpenCode target.
- **mcp-server-authoring** — Build MCP servers from muse plugins for Claude Code, Codex, and other MCP clients. Use when exposing tools via the Model Context Protocol.
- **claude-integration** — Set up Claude Code integration: the Claude target compiles the same DSL plugins into MCP tools + native hooks. Use when configuring how Claude Code interacts with a muse-powered repository.
