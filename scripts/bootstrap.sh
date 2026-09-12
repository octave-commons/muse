#!/usr/bin/env bash
# Install locked dependencies and verify Muse's owned compiler/host targets.
set -euo pipefail
repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$repo_root"
start_daemon=false
case "${1:-}" in
  "") ;;
  --start-daemon) start_daemon=true ;;
  *) echo "usage: scripts/bootstrap.sh [--start-daemon]" >&2; exit 2 ;;
esac
for tool in node npm java clojure clj-kondo; do
  command -v "$tool" >/dev/null 2>&1 || { echo "missing prerequisite: $tool" >&2; exit 1; }
done
if "$start_daemon"; then
  command -v pm2 >/dev/null 2>&1 || { echo "missing prerequisite: pm2" >&2; exit 1; }
fi
npm ci --ignore-scripts --no-audit --no-fund
npm run build
npm test
npm run lint
if "$start_daemon"; then
  pm2 startOrRestart ecosystem.config.cjs
  pm2 save
fi
