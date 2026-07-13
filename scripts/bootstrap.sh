#!/usr/bin/env bash
# Bootstrap the muse on a fresh machine: verify prerequisites, install,
# build daemon + opencode plugin, run tests, and start the daemon under pm2.
#
# katamorph and event-ledger are consumed as immutable git-ref deps
# (deps.edn :git/sha for the CLJS source; package.json github: ref for the
# npm dist) — no eta-mu sibling checkout, no stubbed source paths.
#
# Prereqs (install out-of-band, see docs/DEPLOY.md §1):
#   node >= 20, java, clojure CLI, shadow-cljs, pm2
#
# The repo must live under one of the daemon's scan roots:
#   ~ (depth 1), ~/spaces (depth 3), ~/devel (depth 4)

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

say()  { printf '\033[1;36m[muse]\033[0m %s\n' "$*"; }
fail() { printf '\033[1;31m[muse]\033[0m %s\n' "$*" >&2; exit 1; }

# --- 1. prerequisites -------------------------------------------------------
for tool in node npm java clojure shadow-cljs pm2; do
  command -v "$tool" >/dev/null 2>&1 || fail "missing prerequisite: $tool (see docs/DEPLOY.md §1)"
done
say "prerequisites present: node $(node -v), shadow-cljs, clojure, java, pm2 $(pm2 -v 2>/dev/null | tail -1)"

# --- 2. scan-root check -----------------------------------------------------
case "$repo_root" in
  "$HOME"/spaces/*|"$HOME"/devel/*|"$HOME"/*) : ;;
  *) say "WARNING: $repo_root is outside ~, ~/spaces, ~/devel — the daemon will not discover this tree" ;;
esac

# --- 3. install -------------------------------------------------------------
# npm resolves @promethean-os/event-ledger from its github: ref; the CLJS
# source of katamorph + event-ledger is fetched by tools.deps (deps.edn).
say "npm install"
npm install

# --- 4. build ---------------------------------------------------------------
say "building daemon (shadow-cljs release daemon)"
shadow-cljs release daemon

say "building opencode plugin + publishing shims (shadow-cljs release opencode-plugin)"
shadow-cljs release opencode-plugin

say "running tests (shadow-cljs compile test)"
shadow-cljs compile test

# --- 5. run under pm2 -------------------------------------------------------
say "starting daemon under pm2"
pm2 startOrRestart ecosystem.config.cjs
pm2 save

say "done. verify with:"
say "  pm2 status eta-mu-daemon"
say "  tail -f ~/.eta-mu/state/daemon/log.jsonl"
say "  ls ~/.config/opencode/plugins/   # published shims"
