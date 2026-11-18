<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Codex app delivery admission preparation

This is the bounded preparation for Integration Desk `open-hax/axxium#47` and
assignment `axxium-codex-app-path-preparation-20261118`. It specifies an opt-in
queue-only contract; no runtime consumes the new validator yet. It does not
activate a receiver, invent durable queue storage, change permission policy,
or qualify native Dot webhook delivery.

## Existing implementation and observed gap

Maintained Muse base `59d2ff96426f7f66167f6200dc3ea2c2122c0628` contains
`extern.axxium-stream`, `extern.axxium-codex` and `law.axxium-inbox`.
The stream verifies its configured mailbox and admits the exact id-less
`notifications/resources/updated` hint for `axxium://mailbox/inbox`. That hint
has no message ID, event ID or ordered mailbox cursor. The Codex adapter calls
the installed CLI's `queue --thread ... --message ...` and then Desktop's wake
URL once per hint. Exit zero is dispatch evidence; it does not prove a distinct
turn, input visibility, work, deduplication or acknowledgment. The maintained
adapter has no durable hint checkpoint, reconciliation or ACK path.

The existing production receiver is a separate host-local Python overlay at
`~/.config/axxium/codex-production-receiver.py`, SHA-256
`3e94891a80a3dc6b2e6bd68d1fde64f00c92dcbf3360416d3716391b87fb3222`.
On 2026-11-18 it exhausted its systemd restart limit after initialization HTTP
503 responses during the production upgrade window. No recovery was activated
by this preparation. Registered Muse MCP processes remain a separate tool
surface: the observed entries had no wake opt-in or thread binding.

Read-only inspection of installed Codex app `26.1103.60933`, build `25894`,
bundled Codex `0.167.0-alpha.3.2`, found:

| Surface | Established contract | Limit |
| --- | --- | --- |
| Purpose `send_message_to_thread` | Calls the installed follow-up handler using `send-now` | Can steer active/pending work; no caller queue mode, exact-turn precondition or idempotency key |
| `turn/steer` schema | Requires an exact `expectedTurnId` | Steering is a separate operation, outside this queue-only preparation |
| Experimental `thread/queue/add` schema | Has `clientUserMessageId`, exact thread and input | Schema presence does not establish live feature availability, idempotency semantics or a supported enrollment path |
| CLI queue plus Desktop wake | Existing receiver uses these commands | Atomic no-steer behavior and restart reconciliation are not established by dispatch receipts |

Reading idle state and then calling generic follow-up leaves a race in which
another turn starts before dispatch. Do not substitute that sequence for an
atomic no-steer queue. The exact installed code/hash inventory is retained in
the private `axxium-codex-app-preparation-20261118` evidence directory. The
official [app-server documentation](https://learn.chatgpt.com/docs/app-server)
describes exact-turn steering; the
[Queue and Steer guidance](https://developers.openai.com/blog/mastering-codex-remote-for-engineering)
distinguishes queued next-turn input from steering active work. Neither grants
the installed purpose tool a queue option.

## Selected policy

Retain and coalesce inbox-change hints for the existing exact binding while
the target is active, pending, unavailable, awaiting approval, compacting or of
unknown state. This is an adapter obligation, not a claim that existing storage
implements it. Preserve each authenticated message/delivery identity when one
exists; never fabricate one from an id-less resource hint. One coalesced hint
means inspect the inbox once when admitted, not one guessed message per frame.
No periodic inbox reads or replay are introduced.

Automatic admission requires a separately verified atomic queue capability
that cannot steer or start a turn, accepts a stable caller submission ID, and
supports reconciliation after an uncertain response. It may enqueue against
known idle or active state; the eventual **idle wake** is a separate, unqualified
step until an adapter can prove it will not interrupt/steer an intervening turn.
Generic send-now and CLI exit zero do not satisfy these capabilities. Unknown,
pending, approval and compaction states remain held.

`law.axxium-codex-delivery/queue-admissible?` validates a proposed queue record
using trusted adapter evidence. It compares the whole approved binding with a
fresh observed binding, including origin, actor, mailbox, host, chat and binding
generation. Its input booleans are evidence assertions, not authentication:
calling this pure function does not prove ownership or authenticate a host.
Current host/actor/root ownership must be established by the existing owning
adapter before constructing these assertions; DID ancestry and `hostId: local`
are insufficient. A changed/revoked/unknown binding cannot be silently rebound.

Only an initial submission or a conclusively rejected attempt is admissible.
An accepted or uncertain attempt must be reconciled using its original
submission ID before any new dispatch. The law neither writes a receipt nor
provides exactly-once effects. Adapter/storage adoption requires its own review
and live qualification. Reuse the existing receiver and existing event/receipt
substrate; do not add a competing queue authority or second receiver.

## Evidence and ACK timing

Keep these observations separate: authenticated notification receipt; durable
queue acceptance; exact input visible to the intended chat; task claim;
progress/result/decline/error; and mailbox acknowledgment. A notification,
queue receipt or task claim cannot authorize ACK by itself. ACK requires an
exact message ID and a recorded processing disposition under the owner actor.
An uncertain business effect needs reconciliation, not ACK-and-retry. A terminal
decline may be acknowledged after its disposition is recorded; an error that
still needs work remains pending. Receipt, claim and result fields are local
evidence vocabulary here, not a newly fixed collaboration wire format.

## Bounded regression matrix

| Case | Required outcome | Preparation coverage |
| --- | --- | --- |
| Known idle or active target with verified atomic queue | Queue-only admissible; wake remains a separate step | Pure positive controls |
| Generic follow-up, CLI dispatch-only, unsupported queue | Held even if a preceding read reported idle | Pure negative controls; no live invocation |
| Active/pending/approval/compaction/unknown transition | No incidental steer; do not infer target from stale status | Pure negative state controls; atomic race still adapter work |
| Different owner binding, origin, actor, mailbox, host, chat or generation | Reject; no discovery-based rerouting | Exact-binding negative controls; authentication remains external |
| Expired/revoked binding or delayed old notification | Reject old generation even if the chat name is unchanged | Pure negative controls; live revocation untested |
| Duplicate hint/message and many coalesced hints | Preserve real IDs; one pending inbox inspection; no per-frame message invention | Documented adapter obligation; storage/coalescing unimplemented |
| Accepted request with lost response | Reconcile original submission ID; no blind redispatch | Pure uncertain/accepted negative controls; transport recovery untested |
| Confirmed rejected submission | Retry original logical submission after reconciliation, without changing target | Pure retry positive control |
| Restart before/after durable queue acceptance or result | Recover exact receipts, binding generation and unresolved work; never infer completion from exit zero | Documented adapter obligation; no receiver restart performed |
| Multiple pending messages | Preserve server sequence for the same mailbox; unrelated tasks do not inherit grants | Documented obligation; no new ordering engine |
| Receipt, queue acceptance or claim only | No mailbox ACK | Documented disposition boundary; no ACK adapter implemented |
| Recorded result/decline vs uncertain effect/error | ACK only the exact processed message; unresolved effects stay pending | Documented boundary; no production message test |

## Next bounded qualification

Implement one supported no-steer queue/reconciliation adapter under the existing
receiver owner, with exact binding evidence and durable receipts, then test
its race, duplicate, restart and ACK boundaries in an isolated fixture. Live
activation, a fresh staging message, exact idle-turn proof and native Dot
enrollment each require their own recorded evidence. This preparation changes
no model, thinking level, project/DAG semantics, session, credentials or grants.
