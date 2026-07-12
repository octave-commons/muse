# Π Handoff: 2026-07-11

## Snapshot
- **Commit**: HEAD (after this commit)
- **Tag**: `Π/dsl-v2`
- **Branch**: `main`
- **Description**: DSL v2: domain/boundaries/util layers, plugin system, config, tests passing

## Verification
- **Tests**: 104 tests, 231 assertions, 0 failures, 0 errors
- **Build**: shadow-cljs compile test, 0 warnings

## What's In
New ClojureScript architecture layers:
- `domain/` — business logic (receipts, repo, websearch, mycology)
- `boundaries/` — I/O adapters (fetch, node/fs, opencode)
- `util/` — shared utilities (prompt-section)
- `dsl.cljc` + `dsl/normalize.cljc` + `dsl/schema.cljc` — host-agnostic DSL
- `opencode/config.cljc` — config system
- `.ημ/plugins/` — plugin implementations (actors, receipt_river, session_mycology, websearch)
- `.ημ/config/` — opencode config files
- Tests for all new code

## What's Left Behind
- `.opencode/opencode.json` — concurrent dirt (not owned)
- `.eta-mu` — symlink to .ημ (already staged)

## Blockers
- Push requires write access to upstream remote
