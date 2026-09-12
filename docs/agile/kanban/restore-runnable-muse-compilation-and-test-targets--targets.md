---
category: "kanban"
labels: "sandbox, build"
dependency: []
type: "task"
write-id: "1789194172679-0.qhqj3zg7t7r6kq9ygn6"
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

---