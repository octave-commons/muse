#!/usr/bin/env bash
# Exercise the supported wrapper with isolated adapters and hostile owner paths.
set -euo pipefail

repo_root="$(cd -P "$(dirname "${BASH_SOURCE[0]}")/.." && pwd -P)"
fixture_root="$(mktemp -d)"
trap 'rm -rf -- "$fixture_root"' EXIT

for owner_kind in fifo linked-fifo oversized malformed foreign-pid; do
  fixture="$fixture_root/$owner_kind"
  mkdir -p "$fixture/scripts" "$fixture/bin" "$fixture/node_modules/.bin"
  cp "$repo_root/scripts/build-host-targets.sh" "$fixture/scripts/"
  printf '%s\n' '{"mcpServers":{"unrelated":{"command":"node","args":["keep.js"]}}}' \
    > "$fixture/.mcp.json"
  cp "$fixture/.mcp.json" "$fixture/before.json"
  cat > "$fixture/bin/clojure" <<'CLJ'
#!/usr/bin/env bash
set -euo pipefail
if [[ "$*" == *mcp-config-path* ]]; then
  printf '.mcp.json'
  exit
fi
mkdir -p src/gen/eta_mu/gen
printf '(ns eta-mu.gen.mcp-server)\n' > src/gen/eta_mu/gen/mcp_server.cljs
rm .muse-host-targets.lock/owner
case "$MUSE_TEST_OWNER_KIND" in
  fifo) mkfifo .muse-host-targets.lock/owner ;;
  linked-fifo)
    mkfifo owner-target
    ln -s "$PWD/owner-target" .muse-host-targets.lock/owner
    ;;
  oversized) printf '%033d\n' 1 > .muse-host-targets.lock/owner ;;
  malformed) printf 'not-a-pid\n' > .muse-host-targets.lock/owner ;;
  foreign-pid) printf '2147483647\n' > .muse-host-targets.lock/owner ;;
esac
CLJ
  cat > "$fixture/node_modules/.bin/shadow-cljs" <<'SHADOW'
#!/usr/bin/env bash
set -euo pipefail
: > shadow-called
printf '%s\n' '{"mcpServers":{"new":{"command":"node","args":[".mcp/dist/receipt-river.js"]}}}' \
  > "$MUSE_MCP_CONFIG_OUTPUT"
SHADOW
  chmod +x "$fixture/bin/clojure" "$fixture/node_modules/.bin/shadow-cljs"
  set +e
  MUSE_TEST_OWNER_KIND="$owner_kind" PATH="$fixture/bin:$PATH" \
    timeout --kill-after=1 5 "$fixture/scripts/build-host-targets.sh" mcp-server \
    > "$fixture/build.log" 2>&1
  status="$?"
  set -e
  if [[ "$status" -ne 64 ]]; then
    cat "$fixture/build.log" >&2
    printf 'owner fixture %s: expected bounded failure 64, got %s\n' "$owner_kind" "$status" >&2
    exit 1
  fi
  cmp "$fixture/before.json" "$fixture/.mcp.json"
  test ! -e "$fixture/.muse-host-targets-owners.json"
  test -d "$fixture/.muse-host-targets.lock"
  grep -Fq 'refused to release the MCP build lock' "$fixture/build.log"
  if [[ "$owner_kind" == fifo ]]; then
    test -p "$fixture/.muse-host-targets.lock/owner"
  elif [[ "$owner_kind" == linked-fifo ]]; then
    test -L "$fixture/.muse-host-targets.lock/owner"
    test -p "$fixture/owner-target"
  fi
  printf 'PASS acquired-owner %s: bounded refusal preserves public registry and lock\n' "$owner_kind"
done

fixture="$fixture_root/cold-opencode"
mkdir -p "$fixture/scripts" "$fixture/bin" "$fixture/node_modules/.bin"
cp "$repo_root/scripts/build-host-targets.sh" "$fixture/scripts/"
cat > "$fixture/bin/clojure" <<'CLJ'
#!/usr/bin/env bash
set -euo pipefail
mkdir -p src/gen/eta_mu/gen
printf '(ns eta-mu.gen.opencode-plugin)\n' > src/gen/eta_mu/gen/opencode_plugin.cljs
CLJ
cat > "$fixture/node_modules/.bin/shadow-cljs" <<'SHADOW'
#!/usr/bin/env bash
set -euo pipefail
[[ "$*" == 'release opencode-plugin --force-spawn' ]] || exit 73
test -s src/gen/eta_mu/gen/opencode_plugin.cljs
printf '%s\n' "$*" > release-arguments
SHADOW
chmod +x "$fixture/bin/clojure" "$fixture/node_modules/.bin/shadow-cljs"
test ! -e "$fixture/src/gen"
PATH="$fixture/bin:$PATH" "$fixture/scripts/build-host-targets.sh" opencode-plugin \
  > "$fixture/build.log" 2>&1
test -s "$fixture/release-arguments"
printf 'PASS cold OpenCode: generation precedes fresh-process release\n'
