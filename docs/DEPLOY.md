# Deploying the muse to another machine

Goal: a fresh machine running the muse daemon under pm2, building and
publishing her OpenCode plugins (including `receipt_river`) globally, so
agents on that machine consume *her* stable plugin instead of a local variant.

`scripts/bootstrap.sh` automates steps 2–6 and verifies step 1. Read this
document once anyway — the pitfalls section explains everything that is
machine-specific.

## 1. Prerequisites (installed out-of-band)

None of these are pinned in the repo; install them however the host prefers.

| Tool | Why | Check |
|---|---|---|
| Node.js ≥ 20 + npm | Runs daemon and plugin bundles | `node -v` |
| Java (JDK 11+) | shadow-cljs compiler | `java -version` |
| Clojure CLI | shadow-cljs dependency resolution | `clojure --version` |
| shadow-cljs | Build tool (`npm i -g shadow-cljs` or pnpm global) | `shadow-cljs --version` |
| pm2 | Daemon process manager (`npm i -g pm2`) | `pm2 -v` |
| mongod (optional) | Only if using the mongo ledger backend | — |

## 2. Directory layout — siblings matter

The muse expects two sibling checkouts next to her own:

```
<root>/                  e.g. ~/spaces
├── muse/                this repo
└── eta-mu/              provides packages/event-ledger (file: npm dependency)
```

Clone the muse under `~`, `~/spaces`, or `~/devel` — those are the only roots
the daemon scans for `.ημ/` trees (depths 1 / 3 / 4 respectively). `~/spaces`
is the canonical home.

```sh
mkdir -p ~/spaces && cd ~/spaces
git clone <muse-remote> muse
git clone <eta-mu-remote> eta-mu     # needed for @promethean-os/event-ledger
```

**Known break #1:** `shadow-cljs.edn` also lists `../katamorph/src/cljs` and
`../event-ledger/src` as source paths. These belong to the `:app`/`:server`
builds, not the daemon/plugin/test builds, but shadow-cljs still expects the
directories on the classpath. Either clone those repos as siblings, or create
empty stubs (`scripts/bootstrap.sh` does the latter):

```sh
mkdir -p ~/spaces/katamorph/src/cljs ~/spaces/event-ledger/src
```

## 3. Install

```sh
cd ~/spaces/muse
npm install    # resolves file:../eta-mu/packages/event-ledger — fails if the sibling is missing
```

**Known break #2:** `@promethean-os/event-ledger` is a `file:` dependency.
There is no registry fallback; the `eta-mu` sibling must exist before
`npm install`.

## 4. Build

```sh
shadow-cljs release daemon            # → dist-daemon/daemon.js
shadow-cljs release opencode-plugin   # → .opencode/dist + shims + host config
shadow-cljs release mcp-server        # → .mcp/dist/receipt-river.js + .mcp.json
shadow-cljs compile test              # sanity: 124 tests / 286 assertions / 0 failures
```

**Known break #3:** the global publish shim
(`~/.config/opencode/plugins/eta-mu-actors.js`) imports
`.opencode/dist/eta-mu-actors.js` by **absolute path**. It is only valid for
the checkout that built it. Always rebuild `opencode-plugin` after cloning or
moving the repo — never copy a shim from another machine.

## 5. Run the daemon

```sh
pm2 start ecosystem.config.cjs    # app: eta-mu-daemon
pm2 save                          # persist across pm2 restarts
pm2 startup                       # print the command to run at boot (follow its instructions)
```

Verify:

```sh
pm2 status eta-mu-daemon
tail -f ~/.eta-mu/state/daemon/log.jsonl    # expect a daemon-start entry and tree scans
```

From here on, editing anything under `.ημ/config/` or `.ημ/plugins/` in a
watched tree triggers that tree's `:build` automatically (for the muse:
`shadow-cljs release opencode-plugin`).

## 6. Environment (only for non-default ledger backends)

Set in the pm2 environment (or `env:` block in `ecosystem.config.cjs`) if
needed:

```sh
ETA_MU_LEDGER_BACKEND=mongo                       # file (default) | mongo | memory
ETA_MU_MONGO_URI="mongodb://user:pass@host:27017/?directConnection=true"
ETA_MU_MONGO_DB=eta_mu
# or, for the file backend:
ETA_MU_LEDGER_ROOT=~/.eta-mu/ledgers
```

Credentials travel only in `ETA_MU_MONGO_URI`; nothing else discovers them.
The default URI assumes a local mongod at `127.0.0.1:27017` — override it on
any machine where Mongo lives elsewhere.

## 7. Serving other worlds (the eta-mu migration)

Agents in other repos should use the muse's published `receipt_river`, not a
locally-built variant. The publish flow already does this:
`root.edn`'s `:publish` writes shims to `~/.config/opencode/plugins/` and
agents to `~/.config/opencode/agents/`, so **every** OpenCode session on the
machine — including sessions inside eta-mu — resolves `receipt_river` from the
muse's dist.

To complete the migration on a machine that previously used eta-mu's variant:

1. Remove or disable eta-mu's own receipt plugin exposure (its
   `.ημ/config/opencode/plugins/*.edn` fragment or `.opencode/plugins/` shim)
   so it can't shadow the global one.
2. Rebuild the muse's `opencode-plugin` target (or touch a file under
   `.ημ/config/` and let the daemon do it).
3. In an OpenCode session inside the other repo, confirm the `receipt_river`
   tool responds to `status`, then `append` a test receipt and `validate`.

Per-repo state (`receipts.edn`) stays in each consuming repo; only the plugin
implementation is centralized.

## Pitfalls recap

| # | Symptom on a new machine | Cause | Fix |
|---|---|---|---|
| 1 | shadow-cljs classpath errors | Missing `../katamorph`, `../event-ledger` source paths | Clone siblings or create empty stub dirs |
| 2 | `npm install` fails | `file:../eta-mu/packages/event-ledger` | Clone `eta-mu` sibling first |
| 3 | OpenCode can't load global plugin | Absolute-path shim from another checkout | Rebuild `opencode-plugin` locally |
| 4 | Daemon never sees the repo | Cloned outside `~`, `~/spaces`, `~/devel` scan roots (or too deep) | Move the checkout under a scan root |
| 5 | Mongo backend hangs/fails | Default URI assumes local mongod | Set `ETA_MU_MONGO_URI` |
| 6 | Confusing `.opencode/opencode.json` diffs | It's a build output, regenerated by the flush hook | Don't hand-edit; treat as generated |
