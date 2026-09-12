# Build and run Muse locally

Muse owns the compatibility compiler, artifact convergence daemon, and host adapters described in [the accepted boundary](architecture/compatibility-boundary.md). Sol application exports are owned by eta-mu; Muse's obsolete `app`, `server-dev`, and `server` build targets have been retired because their namespaces are absent here.

## Toolchain and installation

Use Node.js 20 or newer, Java 21, Clojure CLI, and clj-kondo. CI pins Clojure CLI 1.12.5.1654; the repository pins shadow-cljs 3.4.4 in both manifests. The Foresight sandbox provides these tools in its existing shared toolchain.

`deps.edn` contains all compiler dependencies. Neither installation nor compilation requires sibling source stubs or the deprecated standalone event-ledger package.

```sh
npm ci --ignore-scripts
npm run build
npm test
npm run lint
```

The build compiles all four supported targets:

| Target | Artifact |
|---|---|
| daemon | dist-daemon/daemon.js |
| opencode-plugin | .opencode/dist/eta-mu-actors.js and configured host shims |
| mcp-server | .mcp/dist/receipt-river.js and MCP host configuration |
| claude-server | .claude/dist/claude-server.js and Claude host configuration |

Build hooks write host configuration and configured publish shims. Absolute-path shims must be rebuilt when the checkout moves. Test compilation and the emitted Node test process run separately so assertion failures propagate to CI; no assertions are inferred from the compiler's exit code alone.

## Run

```sh
node dist-daemon/daemon.js
```

The daemon writes startup/readiness and watcher events to stdout and its user state log. Its current discovery policy scans the user's home at depth 1, spaces at depth 3, and devel at depth 4. A checkout outside those roots can build and test normally, but the daemon will not automatically watch it. Explicit scan-root configuration is a remaining daemon capability; moving the repository is not required for compilation or host adapter use.

`scripts/bootstrap.sh` performs installation, builds, tests, and lint. Its optional `--start-daemon` flag additionally requires PM2 and starts the named daemon; the default command does not change persistent processes.

## Ledger compatibility

The current Muse adapters support file (default), memory, and optional Mongo providers. Mongo is dynamically loaded only when selected. The local file adapter is still a compatibility implementation; canonical Clio integration remains separate work, not a claim established by a successful build.

| Variable | Meaning |
|---|---|
| ETA_MU_LEDGER_BACKEND | file, memory, or mongo |
| ETA_MU_LEDGER_ROOT | File ledger directory |
| ETA_MU_MONGO_URI | Explicit Mongo connection URI when selected |
| ETA_MU_MONGO_DB | Mongo database name |

The standalone event-ledger package is deprecated. New canonical event envelope, append, ordering, and replay behavior belongs to eta-mu/packages/clio.
