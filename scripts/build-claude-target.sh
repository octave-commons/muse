#!/usr/bin/env bash
# Builds the claude-server shadow-cljs target, then runs the freshly
# compiled artifact once in --emit-hook-config mode so it can write
# .claude/settings.json + .claude/hooks/*.sh from its OWN, definitely-fully-
# written adapter -- shadow-cljs's :flush build-hook stage does its work
# before the compiler's own final output write for this target (confirmed
# empirically: a :flush-stage hook that shelled out to `node <entry> --list-
# hook-events` saw a stale/empty artifact), so hook discovery/settings
# generation cannot live in a JVM-side :flush hook here. Doing it as a
# second, separate `node` invocation after `shadow-cljs release` has fully
# exited sidesteps that ordering question entirely instead of relying on an
# assumption about shadow-cljs's internal stage sequencing.
set -euo pipefail

cd "$(dirname "${BASH_SOURCE[0]}")/.."

npx shadow-cljs release claude-server
node .claude/dist/claude-server.js --emit-hook-config
