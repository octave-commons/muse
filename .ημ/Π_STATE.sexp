;; Π_STATE.sexp - Fork Tax State
;; Generated: 2026-07-11

(π-state
  (commit "HEAD")
  (tag "Π/dsl-v2")
  (branch "main")
  (timestamp "2026-07-11T23:15:00Z")
  (description "DSL v2: domain/boundaries/util layers, plugin system, config, tests passing")

  (staged-files
    ".gitignore"
    ".ημ/config/opencode/permissions/default.edn"
    ".ημ/config/opencode/plugins/actors.edn"
    ".ημ/config/opencode/plugins/receipt-river.edn"
    ".ημ/config/opencode/plugins/session-mycology.edn"
    ".ημ/config/opencode/plugins/websearch.edn"
    ".ημ/config/opencode/profiles.edn"
    ".ημ/config/opencode/root.edn"
    ".ημ/config/opencode/tools/receipt-river.edn"
    ".ημ/plugins/actors.cljc"
    ".ημ/plugins/receipt_river.cljs"
    ".ημ/plugins/session_mycology.cljs"
    ".ημ/plugins/websearch.cljs"
    "STYLE.md"
    "docs/inbox/2026.07.11.17.19.10.md"
    "docs/inbox/2026.07.11.19.03.03.md"
    "docs/inbox/2026.07.11.17.19.19.md"
    "docs/inbox/2026.07.11.20.28.09.md"
    "docs/notes/opencode-dsl/00-index.md"
    "docs/notes/opencode-dsl/01-dsl-design.md"
    "docs/notes/opencode-dsl/02-edn-config.md"
    "docs/notes/opencode-dsl/03-profiles.md"
    "docs/notes/opencode-dsl/04-opencode-integration.md"
    "docs/notes/opencode-dsl/05-boundary-law.md"
    "docs/notes/opencode-dsl/06-typescript-emission.md"
    "openhax-kanban.json"
    "receipts.edn"
    "src/clj/eta_mu/opencode/build.clj"
    "src/cljs/eta_mu/boundaries/fetch.cljs"
    "src/cljs/eta_mu/boundaries/node/fs.cljs"
    "src/cljs/eta_mu/boundaries/opencode.cljs"
    "src/cljs/eta_mu/domain/mycology.cljc"
    "src/cljs/eta_mu/domain/receipts.cljc"
    "src/cljs/eta_mu/domain/repo.cljc"
    "src/cljs/eta_mu/domain/websearch.cljc"
    "src/cljs/eta_mu/dsl.cljc"
    "src/cljs/eta_mu/dsl/normalize.cljc"
    "src/cljs/eta_mu/dsl/schema.cljc"
    "src/cljs/eta_mu/opencode/config.cljc"
    "src/cljs/eta_mu/opencode/plugin.cljs"
    "src/cljs/eta_mu/util/prompt_section.cljc"
    "test/cljs/eta_mu/domain/mycology_test.cljs"
    "test/cljs/eta_mu/domain/receipts_test.cljs"
    "test/cljs/eta_mu/domain/repo_test.cljs"
    "test/cljs/eta_mu/domain/websearch_test.cljs"
    "test/cljs/eta_mu/dsl_test.cljs"
    "test/cljs/eta_mu/opencode/config_test.cljs"
    "test/cljs/eta_mu/util/prompt_section_test.cljs")

  (untracked-left-behind
    ".eta-mu"
    ".opencode/opencode.json")

  (concurrent-dirt
    ".opencode/opencode.json (modified)")

  (blockers
    "Push requires write access to upstream remote")

  (verification
    (status "committed")
    (tests "passed - 104 tests, 231 assertions, 0 failures, 0 errors")
    (build "passed - shadow-cljs compile test, 0 warnings")))
