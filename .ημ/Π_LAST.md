# Π Fork Tax - Last Snapshot

## Commit
- **Hash**: TBD (pending commit)
- **Tag**: `Π/dsl-refactor`
- **Branch**: `main`
- **Timestamp**: 2026-07-11

## Summary
DSL namespace refactor: moved `eta-mu.opencode.*` → `eta-mu.dsl.*` for host-agnostic compilation. The DSL layer now describes adapters (tools, hooks, init fns) as pure data; target boundaries (OpenCode, etc.) render into host-specific formats. Also migrated `opencode-plugin` shadow-cljs build from `:node` + `:advanced` to `:esm` + `:simple` with build hooks.

## What Changed
- **Renamed**: `eta-mu.opencode.compile` → `eta-mu.dsl.compile`
- **Renamed**: `eta-mu.opencode.profile` → `eta-mu.dsl.profile`
- **Renamed**: `eta-mu.opencode.normalize` tests → `eta-mu.dsl.normalize-test`
- **Renamed**: `eta-mu.opencode.schema` tests → `eta-mu.dsl.schema-test`
- **Deleted**: `eta-mu.opencode.dsl`, `eta-mu.opencode.entrypoint`, `eta-mu.opencode.normalize`, `eta-mu.opencode.plugin` (both .cljc and .cljs), `eta-mu.opencode.schema`
- **Updated**: `shadow-cljs.edn` — opencode-plugin build switched to ESM target with build hooks
- **Updated**: `.gitignore` — added `src/gen/`, `.opencode/dist/`, `.shadow-cljs/`

## Verification
- [x] Tests passed (104 tests, 231 assertions, 0 failures)
- [x] Build passed (shadow-cljs compile test, 0 warnings)
- [ ] Lint skipped (no clj-kondo config for new namespaces)

## Residual Dirt (intentionally not absorbed)
- `.eta-mu/` symlink — untracked, not committed
- `src/clj/`, `src/cljs/eta_mu/boundaries/`, `src/cljs/eta_mu/domain/`, `src/cljs/eta_mu/util/` — new directories, concurrent work
- `src/cljs/eta_mu/dsl.cljc`, `src/cljs/eta_mu/dsl/normalize.cljc`, `src/cljs/eta_mu/dsl/schema.cljc` — new files, concurrent work
- `.ημ/config/opencode/plugins/*.edn` — new plugin configs, concurrent work
- `receipts.edn`, `pseudo/`, `STYLE.md` — untracked, concurrent work
- Various `.ημ/config/` modifications — concurrent config drift

## Next Π
When paying the fork tax again, ensure:
1. Remote is configured (`git remote add origin <url>`)
2. Concurrent dirt is absorbed or explicitly documented
3. New namespaces have clj-kondo configs
