;; Π_STATE.sexp - deterministic handoff snapshot
;; Generated: 2026-08-13T16:19:40Z

(state
  (branch "main")
  (head "387280cea0f966d7a1e4ecba0ea2d3275768275d")
  (owned-count 2)
  (concurrent-count 0)
  (blocked-count 1)
  (verification
    (pass "clj-kondo --lint src test: 0 errors, 0 warnings")
    (pass "Receipt River validation: 16 lines")
    (pass "git diff --check")
    (blocked "shadow-cljs: 184 tests, 470 assertions, 3 watcher assertions fail under host inotify instance exhaustion")))

(manifest
  (implementation-commit "387280cea0f966d7a1e4ecba0ea2d3275768275d")
  (hash-receipts "sha256:ec73507ec7b6b6213cb8bb8985ba932a36b7e0123a2cc734313f10e329bdcf00")
  (hash-kanban-ledger "sha256:917c5f0e64334f6d351ea73c789f76ce95c80bd53ecfb673c50f16d55f3445ac"))

(blockers
  (watcher-regression
    (card "f856fdbd-e5a9-4229-80f7-183f098b2324")
    (host-limit "fs.inotify.max_user_instances=128")
    (decision "source experiments restored; dedicated watcher lifecycle design required")))
