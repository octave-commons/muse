# Resumed Muse sandbox gates

The same sandbox now executes Muse's four declared targets, generated Claude hook, three independent cold host builds, Clojure lint and native Mongo integration tests.

The generated hook directory is entirely emitter-owned and ignored, covering PostToolUse and future supported hook names as well as PreToolUse. The sandbox bundle now installs pinned clj-kondo 2026.08.04 and executes both declared cold-host verification and lint, matching the standard CI gate coverage.

| Actual command | Result | Seconds |
| --- | --- | ---: |
| npm run build | Four releases; zero compiler warnings; Claude configuration emitted | 62.496 |
| node scripts/verify-claude-hook-wrapper.mjs | Real generated executable, shell-sensitive checkout path | 1.020 |
| npm run test:build-cold | OpenCode, MCP and Claude from copies without generated sources | 172.912 |
| npm run lint | Zero errors and warnings | 0.757 |
| npm test | 198 tests, 517 assertions, zero failures/errors; actual Mongo, no skip | 22.552 |

[Command metadata](evidence/muse-final-gates.json) and [supervisor outcome](evidence/muse-final-supervisor.txt) accompany the individual command outputs. The source was the published 4e2ab4f1459df85afa3f589a6e6fa155f9caecc4 checkpoint plus these two reviewed build-ownership corrections. The supervisor also verified that the build introduced no checkout-specific Git changes and that generated source/artifacts remained untracked.

The first recovered gates had npm warnings caused by unsupported inherited http-proxy, store-dir and nodedir configuration. The final supervisor removed only those inapplicable configuration entries from the npm child environment, including uppercase and lowercase spellings; normal HTTP/HTTPS network configuration remained. It failed on any warning rather than suppressing diagnostics. Its initial native Mongo setup failed before spawning the server because the output stream was not yet open; synchronous file descriptors fixed that harness setup.

MongoDB 8.0.13 used an owned temporary data directory, TCP loopback, --nounixsocket and a 0.25 GB WiredTiger cache. Server and clients ran in one supervisor because sandbox command sessions have separate loopback namespaces. The supervisor required an actual ping before tests, rejected the legacy unavailable-Mongo skip text, verified executed assertion totals, and stopped its own Mongo process afterward. No PM2 service was started.

The existing canonical Rheos card and append-only board ledger record the plan and evidence. Historical events were preserved. This verification proves the declared Muse compatibility targets, not a completed migration of its legacy Mongo wire adapter to Clio. Remote review and CI acceptance must still target the published successor.

Published output preserves command results and diagnostics but redacts local JVM home/truststore options and normalizes disposable checkout paths. Original command output remains local. These files contain only this public repository's disposable build and test results; no application content, credentials, or broader environment logs are included.
