;; Π_STATE.sexp - Fork Tax State
;; Generated: 2026-07-12

(π-state
  (commit "ad6a7ce")
  (tag "Π/daemon-mongo-apifany")
  (branch "main")
  (timestamp "2026-07-12T00:00:00Z")
  (description "daemon runtime, apifany plugin, mongo ledger boundary, 124 tests passing")

  (staged-files
    ".clj-kondo/config.edn"
    ".eta-mu"
    ".gitignore"
    ".ημ/config/opencode/agents/muse.md"
    ".ημ/config/opencode/agents/phase-discover.md"
    ".ημ/config/opencode/permissions/default.edn"
    ".ημ/config/opencode/plugins/apifany.edn"
    ".ημ/config/opencode/profiles.edn"
    ".ημ/config/opencode/root.edn"
    ".ημ/plugins/actors.cljs"
    ".ημ/plugins/apifany.cljs"
    "docs/kanban/.events/ledger.edn"
    "ecosystem.config.cjs"
    "package.json"
    "package-lock.json"
    "shadow-cljs.edn"
    "src/clj/eta_mu/daemon/build.clj"
    "src/clj/eta_mu/opencode/build.clj"
    "src/cljs/eta_mu/actor.cljc"
    "src/cljs/eta_mu/actor/backend.cljs"
    "src/cljs/eta_mu/actor/envelope.cljc"
    "src/cljs/eta_mu/actor/memory.cljc"
    "src/cljs/eta_mu/actor/muse.cljc"
    "src/cljs/eta_mu/actor/store.cljc"
    "src/cljs/eta_mu/boundaries/mongo/client.cljs"
    "src/cljs/eta_mu/boundaries/mongo/ledger.cljs"
    "src/cljs/eta_mu/boundaries/node/fs.cljs"
    "src/cljs/eta_mu/boundaries/node/import.cljs"
    "src/cljs/eta_mu/boundaries/node/ledger.cljs"
    "src/cljs/eta_mu/boundaries/node/proc.cljs"
    "src/cljs/eta_mu/boundaries/node/watch.cljs"
    "src/cljs/eta_mu/daemon/core.cljs"
    "src/cljs/eta_mu/domain/daemon.cljc"
    "src/cljs/eta_mu/opencode/config.cljc"
    "src/cljs/eta_mu/opencode/settings.cljc"
    "test/cljs/eta_mu/actor/muse_test.cljs"
    "test/cljs/eta_mu/actor_test.cljs"
    "test/cljs/eta_mu/boundaries/mongo/ledger_test.cljs"
    "test/cljs/eta_mu/boundaries/node/ledger_test.cljs"
    "test/cljs/eta_mu/domain/daemon_test.cljs"
    "test/cljs/eta_mu/opencode/config_test.cljs"
    "test/cljs/eta_mu/opencode/settings_test.cljs")

  (untracked-left-behind
    ".opencode/opencode.json")

  (concurrent-dirt
    ".opencode/opencode.json (modified, gitignored)")

  (blockers
    "none")

  (verification
    (status "committed + pushed")
    (tests "passed - 124 tests, 286 assertions, 0 failures, 0 errors")
    (build "shadow-cljs compile test, 0 warnings")))
