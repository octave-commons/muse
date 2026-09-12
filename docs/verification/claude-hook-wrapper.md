# Claude hook path verification

Run from the Muse checkout:

```bash
npm run build
npm test
npm run lint
node scripts/verify-claude-hook-wrapper.mjs
npm run test:build-cold
```

Direct host builds use `npm run build:opencode`, `npm run build:mcp` and
`npm run build:claude`. Each invokes the same declared `prebuild` generator
before Shadow resolves its entrypoint. The Claude wrapper then emits hook
configuration from the completed bundle. The cold-build check executes each
host's actual EDN build command in a disposable source copy with no generated
entrypoints or compiler output. It shares installed Node and Maven dependencies
and isolates the compiler's publish home, so global plugin paths are unaffected.

The build releases all four owned targets and then asks the freshly compiled
Claude server to emit hook configuration. The verification copies that actual
artifact into a disposable checkout whose path contains spaces, apostrophes,
`$(...)` and backticks. It invokes the compiled emitter, reads the emitted
PreToolUse command, executes the generated executable wrapper with a real hook
request, and checks the returned Claude decision. It rejects the hook's error
fallback and verifies that the shell did not execute the path's substitution text.
The fixture shares installed Node packages and is removed on completion. Each
child process has a ten-second limit.

The ClojureScript regression separately invokes the same wrapper generator against
a small real Node entrypoint, asserting exact argv and stdin preservation. The
fix is at the shell boundary: each entry-path/event word is single quoted, with
embedded apostrophes encoded using adjacent quoted segments. `node --` also keeps
the entry path out of Node's option parser.

Verification for this review change:

- Four-target release: daemon 69 files, OpenCode 119, MCP 119, Claude 121;
  zero compiler warnings, 33.133 seconds.
- Actual Node suite with an isolated native Mongo server: 198 tests,
  517 assertions, zero failures/errors, 22.584 seconds. The existing Mongo ledger
  round trip ran; its unavailable-server skip did not occur. To run that same
  integration against an existing local server, set `ETA_MU_MONGO_URI` when
  invoking `npm test`. Without Mongo, the historical suite still skips that
  integration; a plain green run alone is not evidence of Mongo behavior.
- Full Clojure lint and wrapper JavaScript lint/syntax checks pass.
- Actual compiled emitter/wrapper verification passes in about one second.

The original build card used the non-UUID identity `muse-owned-build-targets`.
Rheos correctly refuses identity edits. Replacement card
`45b3e181-d0b6-4c0f-938e-7e03d1805ce3` was created through Rheos, then its fresh
projection filename was aligned with the repository's UUIDv4 naming rule before a
recorded comment. All status transitions were lawful. The old identity was
archived through Rheos, and its complete projection is retained in
[muse-build-history.md](muse-build-history.md) outside the active board. All
31,208 bytes of the prior event ledger remain an unchanged prefix. This preserves
the old evidence without rewriting an immutable identity or leaving an invalid
card in the task tree.

The direct-host generation follow-up was verified separately from a cold source
copy: OpenCode 119 files, MCP 119 files and Claude 121 files, each with zero
compiler warnings; all three completed in 85.774 seconds. The original direct
OpenCode command failed because `eta-mu.gen.opencode-plugin` was unavailable.
After routing through `prebuild`, the complete `npm run build` passed in
35.416 seconds and `npm test` with native local Mongo passed 198 tests and
517 assertions in 17.490 seconds. Configured Clojure lint, JavaScript lint and
shell syntax checks passed with zero warnings/errors. The actual compiled hook
verification passed in 0.573 seconds. Generated checkout-specific hook paths
were excluded from the source change.

## Cold-build CI follow-up, 2026-09-12

The CI workflow now executes `npm run test:build-cold`, so a generation bypass in any declared host command fails the job even after the normal build warmed this checkout. The verifier itself creates independent copies without generated source or compiler output. README and CLAUDE host examples use `npm run build:opencode`, `npm run build:mcp`, and `npm run build:claude`; remaining direct daemon builds need no generated host entrypoint.

After scratch cleanup, the published PR head was restored and its locked `npm ci` succeeded. The exact new CI command passed all three real host builds in 82.112 seconds with zero compiler warnings. Full source/test lint also passed with zero errors/warnings. [Run metadata](evidence/muse-review-cold-ci-command.json) and [actual output](evidence/muse-review-cold-ci-command.txt) are durable; paths in the metadata identify the original sandbox run. This successor changes CI/docs only and does not claim a new full Mongo test run. Existing full-suite observations above remain historical.

The current canonical Rheos CLI requires `comment UUID --text TEXT`; its help corrected an initial rejected invocation with the older positional syntax. The two successful card comments were appended through Rheos; old ledger facts and the card's review state were preserved. No PM2 process was started.

## Plugin lint coverage follow-up, 2026-09-12

The lint command now includes `.ημ`, matching the plugin source used by the real
compiler. Its first expanded run found two warnings: an unused destructuring
binding in the EDN ledger plugin and an unused string require in the review
pipeline plugin. Removing those names preserves behavior and produces zero lint
errors and warnings without changing the lint rules.

Fresh verification of this source change:

- [Expanded lint before the fix](evidence/muse-plugin-lint-red.json): exit 2,
  two warnings; [after the fix](evidence/muse-plugin-lint-green.json): exit 0,
  zero errors and warnings.
- [All four production releases](evidence/muse-plugin-build.json): daemon 69
  files, OpenCode 119, MCP 119, Claude 121; zero warnings, 66.968 seconds.
- [Actual native Mongo test run](evidence/muse-plugin-native-mongo-tests.json):
  198 tests, 517 assertions, zero failures or errors, 43.614 seconds. The Mongo
  round trip ran; no integration skip occurred. An uncaught-exception guard was
  preloaded into the Node test process.

Each metadata file has matching `.txt` output in the same directory. Paths are
provenance from this sandbox run. Rheos appended the implementation and outcome
comments without changing the card's review state or historical ledger facts.
Generated hook paths from the build are excluded from this source commit.

The sandbox bundle workflow now invokes `npm run build`, the real compiled
hook verification, and `npm test`. This uses the same entrypoint generation
and post-release Claude config emission as a developer build. Actual verification
on 2026-09-12 rebuilt all four targets with zero warnings, ran the emitted hook
from a path containing spaces and shell metacharacters, and passed lint with
zero warnings (39.263s). The exact declared test command passed 198 tests and
517 assertions against native loopback Mongo with no integration skip (18.327s).
See `evidence/muse-sandbox-declared-{build,test}.{json,txt}` for preserved
metadata and output; only trailing blank lines were removed from copied logs.

The generated `.claude/settings.json` and `pre-tool-use.sh` are no longer
tracked. Their emitter remains authoritative; the other handwritten hook scripts
are preserved. `CLAUDE.md` now describes the actual self-contained build targets.
This successor was prepared through GitHub after the same sandbox disconnected
with `409 environment_offline`. The previous build/hook results above predate
this ignore/documentation change. A fresh sandbox build and clean-source check
remain required before merge; hosted checks do not replace that missing run.
