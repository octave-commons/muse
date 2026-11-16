# CLAUDE.md

@AGENTS.md

<!--
AGENTS.md is the single source of truth for every agent harness. Claude Code
imports it here so it loads even when a parent directory has its own CLAUDE.md.
Anything below this comment must be true only for Claude Code.
-->

## Claude Code

- **Activation is a build output.** `npm run build` or `npm run build:claude`
  writes `.claude/settings.json`, `.claude/generated-hooks/*.sh` and
  `.mcp.json`. Git ignores all three because they hold checkout-specific
  absolute paths. Before a build, this repository gives Claude Code no project
  hooks and no project MCP server. Rebuild after moving the checkout.
- **What fires.** The generated settings register one `PreToolUse` hook with no
  matcher, so `.claude/generated-hooks/pre-tool-use.sh` runs before every tool
  call. It executes `.claude/dist/claude-server.js --hook tool/requested`,
  which carries the `kanban-gate` hooks described under Board operations in
  `AGENTS.md`. A hook that throws fails open with a stderr note; the done gate
  itself rejects when Rheos is unreachable.
- **`.mcp.json` has two writers.** The `mcp-server` and `claude-server` builds
  both overwrite it. After `npm run build:claude` it names server
  `eta-mu-claude` (`.claude/dist/claude-server.js`); after `npm run build:mcp`
  it names `eta-mu-receipt-river` (`.mcp/dist/receipt-river.js`). Check which
  one it names after a full `npm run build`.
- **The handwritten `.claude/hooks/` scripts do not fire.**
  `audit-mcp-call.sh` would append each tool call to
  `.eta-mu/claude-mcp-audit.jsonl`, `post-edit.sh` is an empty post-edit stub,
  and `reinject-context.sh` prints a reminder meant for after compaction. They
  are tracked with mode 100644 (not executable), and neither the generated
  settings nor any previously tracked settings file references them. Do not
  assume they run.
