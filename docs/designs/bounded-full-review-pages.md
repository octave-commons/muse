# Bounded complete-input review pages

**Proposed; not implementation or deployment admission.** New child
[`5e08a9c7-1c1e-4072-8b44-47043d5f0218`](../agile/kanban/5e08a9c7-1c1e-4072-8b44-47043d5f0218.md)
is an Incoming / 3-point continuation under the existing complete-input owner.
It does not change that parent's status or infer readiness from merged code.

## 1. Research and current-source boundary

The [preserved research](../../.ημ/diagnostics/bounded-review-pages-plan/2026-10-07-mimo-pagination-capacity.md)
and [data record](../../.ημ/diagnostics/bounded-review-pages-plan/2026-10-07-mimo-pagination-capacity.json)
identify Truth run 37630384285: 6,532,456 complete input bytes, 830 pages,
701 delivered and 699 assessed before its 120-minute timeout, with no verdict.
No completed page output was observed truncated. The research proposes fewer
bounded calls, not content filtering or a guaranteed completion time.

This draft starts at fresh Muse main
`c1369c223cf3c57e3e31934a6d746bfcdfe73f5a`, which contains merged PR19 and PR20.
The root research's observed runtime `0b9a914...` is an ancestor, not the current
implementation. Current `read-diff-chunk` also returns `:restart-required?` and
latches rereads after assessment; current host-trace verification rejects failed
calls, stale chronology and same-invocation restart. Preserve those guards.

The research's 413-page / 19,043-byte maximum result is an offline sizing model
using the older response envelope. It is not an executed current-main or candidate
test. Future sizing must include the current `:restart-required?` field and the
actual encoder. The archived native OpenCode config has no `tool_output` setting;
it is not a dump of every effective configuration layer or an executable digest.

Source seams at this base:

- [Pure page geometry](../../src/cljs/eta_mu/domain/review.cljc#L78), currently
  8,192 UTF-16 units / 128 newlines.
- [Read, chronology and assessment](../../src/cljs/eta_mu/domain/review.cljc#L139).
- [Plugin publication of a transition](../../.ημ/plugins/review_pipeline.cljs#L38)
  and [read tool](../../.ημ/plugins/review_pipeline.cljs#L85).
- [Compact JSON encoding](../../src/cljs/eta_mu/boundaries/opencode.cljs#L28).
- [CJS preparation shares the same paginator](../../src/cljs/eta_mu/extern/review_invocation.cljs#L110)
  and [invocation law](../../src/cljs/eta_mu/law/review_invocation.cljc).
- [Compiled tool verifier](../../scripts/verify-full-review-input.mjs) and
  [optimized public invocation verifier](../../scripts/verify-review-invocation.mjs).

## 2. Selected proposed page contract

One canonical pure page constructor supplies both review sessions and verifier
preparation. No second paginator is introduced in eta-mu, a workflow, or a host
test. For each nonempty input interval, select the largest ending boundary that
simultaneously satisfies:

| Quantity | Inclusive ceiling |
| --- | ---: |
| UTF-16 code units in `chunk.text` | 16,384 |
| U+000A characters in `chunk.text` | 256 |
| UTF-8 bytes of the **whole compact successful read response** | 49,152 |

The interval ends only between Unicode scalar values. Its id is the next positive
integer; its start is the prior end. Empty input has zero pages. There are no
zero-length pages, gaps, overlap, Unicode normalization, newline conversion,
omitted controls, filtered evidence, or special treatment of low-priority files.
Concatenating every `chunk.text` must reproduce the validated original UTF-8 bytes.

The response budget includes field names, punctuation, decimal id/start/end,
`ok?`, the current `restart-required?` boolean, and encoded text. Use the larger
boolean encoding when constructing canonical geometry so a chronology flag
cannot change page boundaries. JSON-escaped quotes, backslashes and controls
count after escaping; raw character count alone is insufficient. A named pure
size calculation belongs with page construction. For the actual check, select a
read-specific preflight helper in the existing OpenCode boundary: it reuses the
actual compact result encoder, measures the encoded string's UTF-8 bytes, and
returns that string only within the limit. The plugin calls this helper **before**
publishing the candidate session or recording a successful read. Keeping JS
encoding in the boundary does not mean waiting for the post-handler encoder.
Tests must demonstrate agreement between the pure calculation and the actual
encoder, not assume it.

The three limits are upper bounds. Escape-heavy input may produce a page smaller
than today's 8,192 units. For example, 16,384 U+0001 characters require roughly
96 KiB (98,304 bytes) for the escaped contents alone and cannot form one page. Split them without omitting
bytes. Do not pretty-print successful read responses: their compact encoding has
one physical line, distinct from diff newline count.

## 3. Delivery and compatibility

The current [apply-step!](../../.ημ/plugins/review_pipeline.cljs#L38)
stores the session and records a successful event before returning a response;
[wrap-execute](../../src/cljs/eta_mu/boundaries/opencode.cljs#L45)
encodes it afterward. An additional size check only at that latter point would
be too late. The proposed read path must instead:

1. Calculate the pure candidate transition and full outward response, excluding
   the internal `:session`, without publishing the candidate.
2. Call the boundary preflight to compact-encode that response once and check
   the actual UTF-8 byte count against 49,152.
3. Only after acceptance, publish the candidate session, perform its existing
   reread invalidation/artifact handling and record the successful read event.
4. Return the exact preflighted encoded string. The existing
   [string pass-through](../../src/cljs/eta_mu/boundaries/opencode.cljs#L34)
   returns it without another JSON encoding, reconstructed map or added fields.

Encoding or bounds failure publishes neither the candidate session nor a
successful read event. It is a failed review call, supplies no new delivery or
assessment credit and ends the invocation under the existing failed-call law.
It cannot revive an already invalidated invocation, erase history, permit a
same-process restart, or make a prior artifact eligible. Existing invalidation
and failure evidence remain authoritative. Other tools retain their current
path. This is one specific read-page preflight, not a generic transaction hook
or replacement tool transport; it makes no crash-atomicity guarantee for the
existing state/event effects.

The supported host contract is an effective UTF-8 output limit **at least 49,152
bytes** and physical-line limit **at least one**, with the same compact result
transport. OpenCode v1.18.18's published defaults are 51,200 bytes / 2,000 lines;
the additional 2 KiB is margin, not payload excluded from accounting.
[Pinned host adapter](https://github.com/anomalyco/opencode/blob/31406ccc51b4bd2a4e1e086b2bcaa5f7f804f26d/packages/opencode/src/tool/registry.ts#L149)
and [limiter](https://github.com/anomalyco/opencode/blob/31406ccc51b4bd2a4e1e086b2bcaa5f7f804f26d/packages/opencode/src/tool/truncate.ts#L75).

**Choose refusal, not dynamic smaller-page negotiation, for an unsupported host.**
The shared launch owner must establish the effective host/version/configuration
before enabling this profile; a known lower or unestablished limit does not
authorize launch. The plugin context currently supplies no authenticated
effective host-limit value. This child therefore does not pretend to detect or
negotiate arbitrary host configuration. Its bounded deliverable is the producer
contract and compiled output proof. Shared caller activation remains blocked
until its existing owner verifies the deployment precondition across configuration
overrides. Do not raise host limits, suppress truncation metadata or treat a
saved oversized-output file as already delivered input.

No tool argument, page identity or submission schema change is proposed. A
changed paginator changes page geometry, so the compiled tools and public
invocation verifier must be consumed from the **same qualified Muse revision**.
Old in-flight sessions and old native reviews are never resumed or reinterpreted
using new geometry. eta-mu's budgets remain 45/120 minutes. Neither its caller pin
nor any active review process changes in this story.

## 4. Acceptance evidence and test matrix

| Case | Required observation |
| --- | --- |
| Ordinary ASCII, long lines | All bytes retained; pages honor all three caps. Report call-count reduction on the pinned full input as sizing evidence. |
| Quotes, backslashes, C0 controls | Complete encoded result stays within 49,152 bytes; a naive 16,384-unit-only candidate fails this witness. |
| BMP and supplementary Unicode | No surrogate split; independent UTF-8 encoding of concatenated pages equals original bytes. |
| CRLF and missing final newline | Exact original terminators/end preserved. |
| Current healthy and invalidated read responses | Extra `restart-required?` field counted; old chronology semantics unchanged. |
| Encoder/envelope drift or encoding failure | Actual boundary preflight refuses before candidate session publication or a successful read event; delivered/assessed credit does not advance and the current failed-call invocation verifier refuses publication. |
| Accepted encoded response | The compiled tool returns exactly the preflighted string, with no post-validation re-encoding or envelope growth; parsed response fields remain unchanged. |
| Missing, corrupt or unassessed tail | Begin/assessment/publication refuses at its existing boundary; restored full input uses the established fresh-invocation recovery rules. |
| Confirmed tail finding | All input assessed and the finding remains in the resulting non-approval verdict. |
| Host lower than contract or unknown | No deployment qualification; never claim lossless delivery from the producer bound alone. |
| Same-source optimized exports | Public preparation and verification agree with actual compiled page offsets and raw submission identity. |

The current reader is already lossless. Its correctness controls should pass the
baseline; do not manufacture an unrelated failing expectation to label performance
work RED. Preserve the observed capacity baseline and add meaningful transport
boundary/refusal tests before the candidate. Record actual baseline/candidate
outcomes according to the admitted workflow. Future commands are existing
`npm test`, `npm run lint`, `npm run test:review-input` and required builds, with
zero warnings. None ran during this planning work.

The cold compiled test currently bypasses OpenCode's limiter. Candidate
qualification must test the final encoded string against the pinned host boundary,
requiring `truncated: false` and exact delivered content. A new general-purpose
host harness or a different provider is outside the three-point estimate; if
that becomes necessary, stop and re-scope instead of claiming completion from a
mock that merely returns its input.

## 5. Scope, ownership and practical limit

The three-point estimate covers one pure page contract, its specific plugin
delivery check, and directly corresponding existing test/compiled verifier
changes. It preserves all findings, five-stage order, source/hash binding, strict
invocation history, quorum and publication laws. No state-machine replacement,
new reviewer, token-policy change, timeout extension or review-content omission
is included. The complete-input parent and subsequent caller qualification remain
unfinished where their own acceptance is unfinished.

Reducing required page calls from 1,660 to an analytically estimated 826 is a
capacity hypothesis. Total input and reasoning obligations remain unchanged;
larger pages can increase per-call latency and compaction pressure. A native
canary is a later authorized measurement, not evidence supplied by this plan or a
guarantee that PR60 finishes within 120 minutes.
