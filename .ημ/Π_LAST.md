# Π Last Handoff

**Date:** 2026-08-13
**Branch:** main
**Head:** 1057389

## Commits (oldest → newest)

- `e563fc9` lint: register defcapability/defimplementation/defexposure with clj-kondo
- `899ea71` feat: deep-merge concatenates sequential values in layer order
- `fc58f52` feat: merge :settings fragments into the emitted project opencode.json
- `6cee5fa` config: reconfigure built-in plan/build agents via a settings fragment
- `166367f` test: cover every feature on the opencode agents docs page
- `7ec09a1` docs: layered settings-merge worked example
- `1057389` chore: ignore generated .opencode/package.json

## What this session did

Reconfiguring OpenCode's built-in agents through the config file was only
possible via the daemon's `:emit` path; the build flush wrote `$schema` +
permissions only. Now `emit-host-config` deep-merges every `:settings`
fragment over the base, so `.opencode/opencode.json` carries `:agent`
config from `.ημ/config/opencode/settings/agents.edn` (plan: deny
edit/bash; build: `git push *` → ask). `deep-merge` learned to concatenate
sequential values in layer order so accumulation fields (`instructions`,
`skills.paths`) stop silently replacing across fragments. Tests cover every
feature/example on opencode.ai/docs/agents, round-tripped through
JSON.parse.

## Verification
- clj-kondo (full src+test): 0 errors, 0 warnings
- shadow-cljs test: 184 tests, 470 assertions, 0 failures, 0 warnings
- shadow-cljs release opencode-plugin: 0 warnings

## Concurrent Dirt
- None. All modified/untracked paths were owned by this session and committed.
- Generated leftovers intentionally untracked/ignored: `.opencode/package.json` (now gitignored), `target/`, `.opencode/dist/` (already ignored).

## Anomalies
- Kanban board empty (`eta-mu kanban count` → 0); worked off-board, noted in receipts.
- `.opencode/opencode.json` is tracked despite its own .gitignore entry (grandfathered); kept the tracked copy truthful by committing the regenerated artifact.
