---
category: "kanban"
labels: "build, host-targets, recovery"
type: "task"
write-id: "1788050987050-0.i3gsuxrsv799hnf9tjc"
points: "3"
title: "Make generated host targets work from a cold checkout"
priority: "P1"
status: "testing"
uuid: "muse-issue-11-cold-generated-host-targets"
created_at: "2026-08-29T23:57:40.532Z"
---

# Make generated host targets work from a cold checkout

GitHub issue: https://github.com/octave-commons/muse/issues/11

## Outcome

Muse has one supported host-target build command that deterministically generates
the ignored entrypoint namespaces before Shadow performs namespace discovery.
OpenCode, MCP, and Claude builds all work from an empty `src/gen`.

## Problem

The three Shadow targets name `eta-mu.gen.*` module entry namespaces, but their
`:configure` hooks create those files after Shadow has already tried to resolve
the module graph on a cold checkout. PR #5 pre-generated them only inside one
sandbox workflow; direct callers and daemon plans remain broken.

## Scope

- Add one checked-in `scripts/build-host-targets.sh` command for one target or all.
- Generate only the selected target entrypoint from pinned `.ημ/config` inputs,
  then invoke the repository-pinned Shadow executable.
- Preserve Claude's required post-build hook-config emission.
- Route source config build plans, bootstrap/wrapper scripts, CI/sandbox workflows,
  and live contributor/deployment docs through the supported command.
- Add cold-checkout and second-build idempotency evidence to CI.

## Acceptance criteria

- A checkout with no `src/gen` builds OpenCode, MCP, and Claude through the
  supported command.
- Generated entrypoint bytes derive only from the pinned `.ημ/config` inputs.
- A second generation/build produces identical `src/gen` hashes.
- Unsupported targets fail before invoking Clojure or Shadow.
- Existing daemon and Claude post-build behavior remains explicit.
- CI runs the cold path, checks idempotency, and rejects build warnings.
- Existing ClojureScript tests and shell syntax checks pass.

## Non-goals

Changing the generated module contracts, committing `src/gen`, changing plugin
semantics, or making raw `shadow-cljs release <host-target>` a supported public
command.

---
Accepted bounded repair: GitHub issue #11 and the constellation cleanup authorization select the documented supported-command option. Raw Shadow host-target releases remain implementation details; every live caller will route through one generator-first command.

Implementation plan: add a fail-closed target dispatcher that runs the matching Clojure generator before pinned npx shadow-cljs, preserves Claude's post-build config emission, update all live build plans/callers/docs, and make CI prove cold-checkout plus second-build source-generation idempotency and zero warnings.

Implementation evidence (2026-08-30): one generator-first script now owns OpenCode/MCP/Claude builds; all three source config plans, daemon test/example, bootstrap and Claude wrapper, live README/CLAUDE/deploy docs, CI, and sandbox bundle route through it. The cold clone began with no src/gen and no node_modules; npm ci passed. Direct generator execution on an isolated dependency-free Clojure alias produced exactly three entry files, and a second run preserved hashes claude=134c7160…, mcp=9df0e497…, opencode=57c7fa6a…. shellcheck, bash syntax, changed Clojure lint (0 errors/0 warnings), CI actionlint, EDN config parsing, request-validation exit 64, and git diff --check pass. The full pinned build/test could not resolve thheller/shadow-cljs 3.4.4 because this harness's Maven proxy at 127.0.0.1:39999 was unavailable; hosted cold-build CI remains required evidence.

Review disposition (PR #14): exact head 3fbcb6c passed CI including the cold generated-host build, the full ClojureScript suite, Sandbox bundle, and eta-mu evidence review; Codex and eta-mu found no implementation defect. CodeRabbit prerequisite wording is corrected in docs/DEPLOY.md. Its MD003 finding is confirmed as the canonical Rheos comment-writer defect open-hax/eta-mu#295; direct projection edits are prohibited, so the upstream writer owns that formatting fix.

Review bounce (PR #14): Codex found two exact-head defects after the documentation correction: multi-target builds allowed the Claude target to overwrite the generic MCP registration in .mcp.json, and CI matched WARNING: but not Shadow numbered WARNING #N banners. Both findings are accepted; return to implementation to merge MCP registrations deterministically and exercise both warning forms.

Corrective implementation evidence: multi-target requests now accumulate each generated MCP document, reject conflicting duplicate registrations, sort server keys, and publish their union after all builds. CI asserts exactly eta-mu-claude and eta-mu-receipt-river with their expected compiled paths, hashes .mcp.json across both builds, and self-tests a warning regex covering WARNING: plus Shadow WARNING #N banners. bash -n, shellcheck, actionlint, regex positive/negative fixtures, and git diff --check pass locally; exact-head hosted cold-build CI remains required.

Exact-head review finding accepted (PR #14): after an all-target build, either supported single-target MCP rebuild rewrote .mcp.json with its own one-server document and dropped the other integration. Keep the card in testing. The correction must preserve unbuilt registrations, let a rebuilt target refresh its own registration, reject conflicting duplicate output within one request, and prove all -> mcp-server -> claude-server -> all behavior in hosted CI.

Corrective implementation evidence: every MCP-producing request now seeds a temporary registry from the current .mcp.json (or an empty registry on a cold checkout), merges target-generated registrations with generated output authoritative for rebuilt names, tracks same-request output separately for conflict rejection, sorts keys, and publishes the union. CI now asserts the exact two expected registrations after all, mcp-server, claude-server, and a second all build, including byte-identical registry hashes. bash -n, shellcheck, actionlint, git diff --check, and isolated preserve/refresh/conflict merge fixtures pass locally; the full local build remains blocked before source compilation by the unavailable Maven proxy, so exact-head hosted CI remains required.
---