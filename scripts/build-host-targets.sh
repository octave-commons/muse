#!/usr/bin/env bash
# Generate a host target's ignored entry namespace before Shadow discovers the
# module graph, then build it with this checkout's pinned Shadow executable.

set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"

usage() {
  printf 'Usage: %s <opencode-plugin|mcp-server|claude-server|all> [...]\n' "$0" >&2
}

fail() {
  printf '[muse host build] %s\n' "$*" >&2
  exit 64
}

if (($# == 0)); then
  usage
  exit 64
fi

if (($# > 1)); then
  for target in "$@"; do
    [[ "$target" != all ]] || fail "'all' cannot be combined with another target"
  done
fi

targets=("$@")
if [[ "${targets[0]}" == all ]]; then
  targets=(opencode-plugin mcp-server claude-server)
fi

# Validate the complete request before probing or invoking any build tool.
for target in "${targets[@]}"; do
  case "$target" in
    opencode-plugin|mcp-server|claude-server) ;;
    *)
      usage
      fail "unsupported target: $target"
      ;;
  esac
done

command -v clojure >/dev/null 2>&1 || fail "missing prerequisite: clojure"
command -v node >/dev/null 2>&1 || fail "missing prerequisite: node"

shadow="$repo_root/node_modules/.bin/shadow-cljs"
[[ -x "$shadow" ]] || fail "missing pinned Shadow executable; run npm ci first"

build_target() {
  local target="$1"
  local generator
  local entrypoint

  case "$target" in
    opencode-plugin)
      generator="eta-mu.opencode.build/generate-entrypoint"
      entrypoint="src/gen/eta_mu/gen/opencode_plugin.cljs"
      ;;
    mcp-server)
      generator="eta-mu.mcp.build/generate-entrypoint"
      entrypoint="src/gen/eta_mu/gen/mcp_server.cljs"
      ;;
    claude-server)
      generator="eta-mu.claude.build/generate-entrypoint"
      entrypoint="src/gen/eta_mu/gen/claude_server.cljs"
      ;;
  esac

  printf '[muse host build] generating %s from .ημ/config\n' "$entrypoint"
  clojure -M -e "((requiring-resolve '$generator) {})"
  [[ -s "$entrypoint" ]] || fail "generator did not write $entrypoint"

  printf '[muse host build] releasing %s\n' "$target"
  "$shadow" release "$target"

  if [[ "$target" == claude-server ]]; then
    printf '[muse host build] emitting Claude hook configuration\n'
    node .claude/dist/claude-server.js --emit-hook-config
  fi
}

for target in "${targets[@]}"; do
  build_target "$target"
done
