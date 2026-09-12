---
category: "kanban"
labels: "sandbox, build"
dependency: []
type: "task"
write-id: "1789197681338-0.esbi0dhqw3hhh83qlc0"
points: "2"
title: "Restore runnable Muse compilation and test targets"
priority: "P1"
status: "review"
uuid: "muse-owned-build-targets"
created_at: "2026-09-12T06:16:13.682Z"
---

# Restore runnable Muse compilation and test targets

## Outcome

_What is true when this is done._

## Scope

- _TODO_

## Acceptance criteria

- _TODO_

---
Discovery: app/server-dev/server export absent open-hax.sol namespaces; the accepted compatibility boundary assigns Muse compiler, daemon, and host adapters. Plan: retire those stale Sol application targets, expose build commands for all four existing Muse targets, run emitted Node tests with their real exit code, and record clean compiler/lint evidence. Preserve canonical domain ownership and every existing test.

Verified npm build: daemon 69 files, OpenCode 119, MCP 119, Claude 121, all compiler warnings 0. npm test executes emitted Node process: 184 tests / 470 assertions, 0 failures/errors. Canonical clj-kondo on src/cljs, src/clj and test/cljs: 0 errors / 0 warnings. Updated CI to run the actual test process and all owned targets; bootstrap now verifies by default and makes persistent PM2 start explicit. Local daemon scan-root configurability and Clio integration remain distinct capability gaps.

Self-review: checked every removed target names absent Sol namespaces against accepted ownership boundary; all four supported targets retained. CI now runs actual Node exit status, four builds, and pinned clj-kondo 2026.08.04. Foreground daemon boot reached daemon-ready in 0.128 seconds, then the task-owned process was stopped; no PM2 processes started. bash -n bootstrap and git diff --check pass. External code review remains pending.

PR16 review scope: preserve the single four-target release and run the freshly emitted Claude server with --emit-hook-config after the compiler exits; verify all hook commands point at this checkout without committing machine-specific generated paths. Rename the projected card to muse-owned-build-targets.md to match its UUID, preserve historical ledger facts, and append current npm build/test/lint provenance after verification. No PM2 services are started.

PR16 fresh verification (2026-09-12): npm run build completed all four owned targets (daemon 69 files, OpenCode 119, MCP 119, Claude 121), every compiler warning count zero, actual exit 0 in 45.739s. The post-release emitted Claude server wired its active tool/requested hook; its PreToolUse command points at this checkout and passes bash -n. Machine-specific generated paths were verified as artifacts and excluded from the commit. npm test ran the actual emitted Node process: 197 tests, 506 assertions, zero failures/errors, exit 0 in 17.597s. npm run lint: zero errors/warnings. Only inherited npm option deprecation settings were removed from the final command environment; no project warning rules were changed. The filename now matches UUID muse-owned-build-targets. Historic 184/470 evidence remains an unchanged historical observation; this comment records the current snapshot. Self-review: single four-target release still runs before hook discovery; every historical ledger byte remains a prefix; no PM2 services started.

Hosted CI on bcab2e4 reproduced a fresh-checkout build failure after daemon succeeds: eta-mu.gen.opencode-plugin is unavailable because Shadow indexed classpaths before configure hooks generated src/gen. The sandbox-bundle workflow already invokes all three generators before starting Shadow. Plan: reuse that existing generation recipe in the top-level prebuild lifecycle, prove old command fails and new command builds all four targets in an isolated fresh source worktree with shared dependencies, preserve post-release Claude configuration and append exact output evidence.

Cold-checkout verification: unmodified38641cb source with one shared node_modules symlink failed npm run build after daemon with missing eta-mu.gen.opencode-plugin in31.515s, matching hostedCI34678994363. Added only the existing three-generator recipe as prebuild; a second fresh worktree with no generated sources/compiler cache built daemon69/OpenCode119/MCP119/Claude121 with zero compiler warnings in51.960s, and post-release emitter wired the actual tool/requested hook. The command preserves the declared Clojure/JVM/Node tools and shares installed packages. Both old and fixed raw results are retained in recovery-muse-cold-build-red/green. Current197tests506assertions and lint0/0 remain unchanged source gates from38641cb.

---