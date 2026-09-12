# Claude hook path verification

Run from the Muse checkout:

```bash
pnpm build
pnpm test
pnpm lint
node scripts/verify-claude-hook-wrapper.mjs
```

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
  invoking `pnpm test`. Without Mongo, the historical suite still skips that
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
