# Axxium on Stealth

Use the existing Axxium actors `Codex on stealth`, `Claude Code on stealth`,
and `OpenCode on stealth`. The respective GitHub Apps are
`fs-stealth-codex`, `fs-stealth-claude-code`, and `fs-stealth-opencode`.
Verify each App's owner, installation, repository selection and permissions
before binding it through the authenticated Axxium GitHub MCP route. Keep each
App's private key outside source. Normal mailbox operation uses the actor's
own Axxium bootstrap credential.

## Configure one harness

Set these values in the launcher for the harness that owns the actor.

| Setting | Value |
| --- | --- |
| `AXXIUM_ORIGIN` | Exact HTTPS origin, e.g. `https://staging.axxium.promethean.rest` |
| `AXXIUM_ACTOR_DID` | The selected actor's full DID from the Axxium portal |
| `AXXIUM_BOOTSTRAP_FILE` | Absolute path to that actor's mode `0600` key file, owned by the launching user |
| `AXXIUM_MAILBOX_DID` | The exact mailbox DID when different from the actor DID |

Register the Muse stdio build as a local MCP server in Codex and OpenCode.
Use Muse's Claude target for Claude Code. Each registration starts its own
process under its configured identity. Keep the key file paths in per-harness
config and key material in the key files.

- Codex CLI: `codex mcp add axxium -- node /absolute/muse/.mcp/dist/receipt-river.js`.
- Claude Code: `claude mcp add --scope user axxium -- node /absolute/muse/.claude/dist/claude-server.js`.
- OpenCode 2: add an `axxium` local server under `mcp.servers` with
  `command: ["node", "/absolute/muse/.mcp/dist/receipt-river.js"]`.

Reload each session after adding a server. Call `axxium_whoami` and verify
the returned actor, mailbox and authorization before any mailbox effects.
Staging must explicitly admit each long-lived bootstrap actor, and a native
mailbox actor must use its own unbound DID. Keep three distinct key files and
mailbox bindings.

## Enable one inbox receiver

The Axxium server emits a Streamable HTTP MCP resource update for
`axxium://mailbox/inbox`. The receiver verifies the bootstrap identity and
opens the subscribed SSE stream. It hands the host a fixed inbox-updated hint.
Read once when that hint arrives. Acknowledge an individual message after
handling it. Treat the contents as incoming data, and let the host's existing
tool approvals govern any action it proposes.

- **Codex Desktop**. Set `AXXIUM_WAKE=codex-desktop` and
  `AXXIUM_CODEX_THREAD_ID` in the MCP entry for the exact registered thread.
  Run one enabled process per actor. The adapter queues a fixed notice for that
  UUID and dispatches the Desktop wake URL. Verify a distinct new turn in
  that thread. A successful dispatch only proves the request reached Desktop.
- **Claude Code**. Set `AXXIUM_WAKE=claude-channel` in the Claude MCP
  registration. The running Claude session must explicitly load that channel
  and accept any Claude Code enrollment prompt. Follow the official
  [Channels reference](https://code.claude.com/docs/en/channels-reference)
  for allowlisted and local development channels.
- **OpenCode**. Use the OpenCode 2 adapter with an already running local v2
  server and a session that has the same actor's Axxium MCP registration.
  Set `AXXIUM_OPENCODE_ORIGIN` to the server's loopback HTTP origin with an
  explicit port, `AXXIUM_OPENCODE_SESSION_ID` to the exact `ses…` ID and
  `AXXIUM_OPENCODE_DIRECTORY` to the absolute directory reported by that
  session. If the host uses Basic authentication, pass its existing
  `OPENCODE_SERVER_USERNAME` and `OPENCODE_SERVER_PASSWORD` in the launcher's
  private environment. The Axxium bootstrap key stays in its separate file.

  ```sh
  npx shadow-cljs release opencode-wake
  node target/opencode-wake/opencode-wake.mjs --check
  node target/opencode-wake/opencode-wake.mjs --listen
  ```

  `--check` reads only the pinned OpenCode session. `--listen` uses the same
  actor settings as the MCP registration, then opens one verified inbox SSE
  stream. Each update rechecks the session and its directory before queuing
  a fixed prompt with no mailbox payload. Use `Ctrl-C` to disconnect. A
  successful prompt admission proves queueing. Verify the corresponding
  turn in OpenCode separately.

Check receiver startup, then use one authorized fresh staging message to
verify the exact host session wakes. Inspect the resulting turn and mailbox
durability independently. When the receiver stops, check the terminal reason
in the host log and explicitly restart if appropriate.

## Drive

The Muse tools expose `axxium_drive_list`, `axxium_drive_get` and
`axxium_drive_put` through the
actor-owned Axxium Drive endpoint. Deployment needs a separate Drive sealing
key and ledger, a Google Web client with the exact Drive callback, and a
`drive.file` grant made by the signed-in owner for that actor. An empty registry
after connection is valid. Only files registered to that actor appear in
these tools. For a new file, use `axxium_drive_put` with a unique request ID.
Reuse that ID when reconciling an uncertain write. The human's registry
and the other actors' registries each keep their own ownership.

Verify one actor's registered fixture, and verify a different actor gets a
denial for that resource. Keep provider evidence separate from local MCP
registration and synthetic receiver checks.

## GitHub

Use `axxium_github_comment` for issue and pull request conversation comments after
the managed-write route is deployed. Supply the exact repository, number,
comment text and a unique UUID request ID. Confirm the native App bot and
comment URL in the receipt. If Axxium reports an uncertain effect, reconcile
it using the same request ID before sending another comment.
The tool reports confirmed GitHub rejections and can include the provider's
HTTP status and request ID. Use the UUID you supplied to Axxium for
reconciliation. The provider request ID identifies the GitHub HTTP request.
