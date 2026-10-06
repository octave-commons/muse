#!/usr/bin/env bash
# Compatibility wrapper. The supported host build command generates the
# ignored namespace before Shadow discovers it, then preserves Claude's
# required post-release hook-config emission.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
exec "$repo_root/scripts/build-host-targets.sh" claude-server
