;; Π_STATE.sexp - Fork Tax State
;; Generated: 2026-07-11

(π-state
  (commit "e1154b3")
  (tag "Π/dsl-refactor")
  (branch "main")
  (timestamp "2026-07-11T20:45:00Z")
  (description "DSL namespace refactor: opencode → dsl, host-agnostic compilation, opencode-plugin ESM migration")

  (staged-files
    "src/cljs/eta_mu/dsl/compile.cljc"
    "src/cljs/eta_mu/dsl/profile.cljc"
    "src/cljs/eta_mu/opencode/dsl.cljc"
    "src/cljs/eta_mu/opencode/entrypoint.cljs"
    "src/cljs/eta_mu/opencode/normalize.cljc"
    "src/cljs/eta_mu/opencode/plugin.cljc"
    "src/cljs/eta_mu/opencode/plugin.cljs"
    "src/cljs/eta_mu/opencode/schema.cljc"
    "src/cljs/eta_mu/opencode/compile.cljc"
    "test/cljs/eta_mu/dsl/normalize_test.cljs"
    "test/cljs/eta_mu/dsl/schema_test.cljs"
    "shadow-cljs.edn"
    ".gitignore")

  (untracked-left-behind
    ".eta-mu"
    "src/clj/"
    "src/cljs/eta_mu/boundaries/"
    "src/cljs/eta_mu/domain/"
    "src/cljs/eta_mu/dsl.cljc"
    "src/cljs/eta_mu/dsl/normalize.cljc"
    "src/cljs/eta_mu/dsl/schema.cljc"
    "src/cljs/eta_mu/opencode/config.cljc"
    "src/cljs/eta_mu/util/"
    "test/cljs/eta_mu/domain/"
    "test/cljs/eta_mu/dsl_test.cljs"
    "test/cljs/eta_mu/opencode/"
    "test/cljs/eta_mu/util/"
    "receipts.edn"
    "pseudo/"
    "STYLE.md"
    "openhax-kanban.json"
    ".ημ/plugins/"
    ".ημ/config/opencode/plugins/receipt-river.edn"
    ".ημ/config/opencode/plugins/session-mycology.edn"
    ".ημ/config/opencode/plugins/websearch.edn"
    "docs/inbox/2026.07.11.20.28.09.md")

  (concurrent-dirt
    ".opencode/opencode.json (modified)"
    ".ημ/config/opencode/permissions/default.edn (modified)"
    ".ημ/config/opencode/plugins/actors.edn (modified)"
    ".ημ/config/opencode/profiles.edn (modified)"
    ".ημ/config/opencode/root.edn (modified)"
    ".ημ/config/opencode/tools/receipt-river.edn (deleted)"
    "docs/inbox/2026.07.11.17.19.10.md (modified)"
    "docs/inbox/2026.07.11.17.19.19.md (deleted)"
    "docs/inbox/2026.07.11.19.03.03.md (deleted)"
    "docs/notes/opencode-dsl/ (all deleted)")

  (blockers
    "No remote configured - push skipped")

  (verification
    (status "committed")
    (tests "passed - 104 tests, 231 assertions, 0 failures, 0 errors")
    (build "passed - shadow-cljs compile test, 0 warnings")))
