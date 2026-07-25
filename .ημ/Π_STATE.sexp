;;Π_STATE.sexp — deterministic handoff snapshot
;; Generated: 2026-07-25T16:01:00Z

(state
  (branch "main")
  (head "b073836")
  (dirty-count 27)
  (owned-count 27)
  (concurrent-count 0)
  (blocked-count 0)
  (verification "clj-kondo 0 errors 0 warnings, shadow-cljs 143 tests 0 failures"))

(manifest
  (hash-receipts "sha256:d045c9155b516832170cebb8f6b15440fbc2f91f1de3c71f59d28b5c5af97ddf")
  (hash-shadow "sha256:81e941568e122b1dadc4111f2f0d72c1acb536ed917ac913c8af43ec4d12c714"))

(reasons
  (decision
    (choice "stage-all")
    (rationale "Solo workspace, all changes are owned")))
