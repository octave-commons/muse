;;Π_STATE.sexp — deterministic handoff snapshot
;; Generated: 2026-08-13T10:47:41Z

(state
  (branch "main")
  (head "1057389")
  (dirty-count 1)
  (owned-count 1)
  (concurrent-count 0)
  (blocked-count 0)
  (verification "clj-kondo 0 errors 0 warnings (full src+test), shadow-cljs 184 tests 470 assertions 0 failures, release opencode-plugin 0 warnings"))

(manifest
  (hash-receipts "sha256:60fe94fb76e432b66794d9994fe2ea2be1a2625293983d1ad20aa0ed3cbe6b83")
  (hash-shadow "sha256:81e941568e122b1dadc4111f2f0d72c1acb536ed917ac913c8af43ec4d12c714"))

(reasons
  (decision
    (choice "grouped-commits")
    (rationale "All dirt owned by this session; split into seven explained commits instead of one snapshot lump")))
