# PR60 complete-input review capacity: bounded page proposal

**Status: source-grounded proposal, not implementation admission.** This audit
creates only this note and its [data/source-pin record](2026-10-07-mimo-pagination-capacity.json).
It preserves the original timeout, every input byte, and the existing review
requirements. No model run, JVM, native simulation, source change, card
transition, caller update, or review retry was performed.

## Observed pipeline and failure

Truth run **37630384285**, job **112823239739**, reviewed head
`5ce5decae9ab756082d7e693834b4904a4c33e42`. It was cancelled after the configured
120-minute job budget with **701 pages delivered, 699 assessed, and no verdict**.
The preserved JSONL independently contains those counts; all 1,400 completed
page-read/assessment outputs have `truncated: false`. This was incomplete review
at timeout, not observed page truncation. [Native run](https://github.com/octave-commons/Truth/actions/runs/37630384285)

The native job log specifies `opencode/mimo-v2.6-flash-free`, selects OpenCode
`1.18.18`, and successfully runs `npm install --global opencode-ai@1.18.18`.
Archived context artifact **11485639091** identifies Muse
`0b9a91492c8355e6933dc2164d35668cb76d9e60`; its generated OpenCode config has no
`tool_output` override. The shared workflow is pinned at
`a91a73e0e5efa165baf6d7247338f3ef17751752`. This is native version/config evidence,
not an inference from a local installation. The installed executable's digest
was not captured, so binary build equivalence is not claimed.

The separate PR38 run **37651983184**, reported by root, completed review and
verification before its GitHub App publication-token step failed. It is not a
second pagination timeout and is excluded from this capacity sample.

## Exact boundary that constrains larger pages

- Muse's pure paginator uses **8,192 UTF-16 units / 128 newline characters** and
  preserves surrogate pairs. Read and assessment are separate session events.
  [Pinned reader/session, lines 78–149](https://github.com/octave-commons/muse/blob/0b9a91492c8355e6933dc2164d35668cb76d9e60/src/cljs/eta_mu/domain/review.cljc#L78)
- The compiled tool serializes its complete result with compact JSON. Delivery
  is recorded by the plugin before the host receives that string.
  [Adapter, lines 28–48](https://github.com/octave-commons/muse/blob/0b9a91492c8355e6933dc2164d35668cb76d9e60/src/cljs/eta_mu/boundaries/opencode.cljs#L28),
  [plugin, lines 37–49 and 77–82](https://github.com/octave-commons/muse/blob/0b9a91492c8355e6933dc2164d35668cb76d9e60/.%CE%B7%CE%BC/plugins/review_pipeline.cljs#L37)
- The official OpenCode `v1.18.18` tag resolves to
  `31406ccc51b4bd2a4e1e086b2bcaa5f7f804f26d`, matching the inspected source.
  Its plugin adapter passes the string through a **51,200-byte / 2,000-line**
  output limiter. The byte count is UTF-8, after JSON encoding. Oversized compact
  JSON can lose the entire one-line payload and become a truncation notice.
  [Host adapter, lines 149–162](https://github.com/anomalyco/opencode/blob/31406ccc51b4bd2a4e1e086b2bcaa5f7f804f26d/packages/opencode/src/tool/registry.ts#L149),
  [limiter, lines 75–109](https://github.com/anomalyco/opencode/blob/31406ccc51b4bd2a4e1e086b2bcaa5f7f804f26d/packages/opencode/src/tool/truncate.ts#L75)

Consequently, changing only 8,192 to 16,384 is not a general lossless contract.
For example, a page of 16,384 U+0001 characters produces a **98,365-byte** complete
JSON response, including its current id/start/end fields. Those characters are
valid UTF-8 and JSON-escapable; the solution must not omit or forbid input just
to make a larger page fit. Logical diff newlines are escaped within compact JSON,
so the host's physical-line limit is a different measure.

## Concrete sizing evidence

Artifact **11485224100** contains the exact full `basehead.diff`: **6,532,456
bytes**, SHA-256 `69e1531063f20141fa6f64c483d7fdcef861fa519597867b0a798b1c76d64aa0`.
It decodes to 6,527,158 UTF-16 units. An offline calculation used the actual
greedy unit/newline boundaries, retained surrogate pairs, then further reduced
candidate pages if their whole compact JSON response exceeded 49,152 UTF-8 bytes.
Both sequences concatenate to the original UTF-8 bytes. All **701 native page
objects and their serialized response strings** match the baseline calculation
exactly. This is an analytical sizing model, not execution of candidate Muse code.

| Measure | Current contract | Proposed bounded contract |
| --- | ---: | ---: |
| Maximum UTF-16 units | 8,192 | 16,384 |
| Maximum newline characters | 128 | 256 |
| Complete encoded response ceiling | implicit host compatibility | 49,152 bytes |
| Pages for this exact input | 830 | 413 |
| Largest complete JSON page | 9,562 bytes | 19,043 bytes |
| Minimum read + assessment calls | 1,660 | 826 |
| Full reconstructed bytes/hash | exact | exact |

The proposed ceiling is **48 KiB for the whole encoded response**, leaving 2 KiB
below this host's default limit. It is not a limit on raw text alone. Escape-heavy
input may need pages smaller than 8,192 units; input remains complete.

A useful capacity model is
`T = T_fixed + c_bytes × full_input_bytes + c_pages × page_count + context/provider effects`.
The proposed input saves **834 required page calls (50.24%)** in this sample.
It does not reduce the bytes or changed hunks that must be assessed. One timeout
cannot identify the model coefficients: doubling useful text per call can also
increase assessment latency. There is no defensible 2× throughput claim or
promise of finishing within 120 minutes. Larger pages also change model attention
and compaction pressure; exact delivery alone is not proof of good review.

## Smallest proposed implementation boundary

Canonical Rheos read recovered **Incoming, 3 points** owner
`0bd45066-9d1c-4cc4-a28e-fcfecf7eac7a`, “Require assessment of complete immutable
review input,” in `muse-full-review-input` at `9aa938e74a0e85abd2c0280e81bcbe88f459aef7`.
This branch-specific state is an ownership pointer, not current implementation
admission. A scoped continuation there is preferable to a parallel review engine.
The inspected eta board also retains `opencode-mimo-evidence-review-agent` as
InProgress / 5 points for shared workflow integration.

A proposed **3-point Muse follow-up** can own:

1. A named bounded page contract: at most 16,384 UTF-16 units, 256 newlines,
   and 49,152 UTF-8 bytes for the complete current encoded page response. Split
   further when necessary; preserve ids, contiguous offsets, Unicode and input.
2. A specific read-page transport check before recording delivery, so an encoder
   or envelope change cannot silently create credit for an oversized response.
   No generic transport framework, host-limit disabling, or replacement tool.
   A host configured below this ceiling must be explicitly rejected as incompatible
   or supply a smaller effective bound; do not assume arbitrary host settings.
3. Existing compiled-tool and publication-guard qualification plus an exact-input
   sizing record. Preserve every page's required assessment and all tail checks.

The existing workflow's final verifier already checks contiguous ranges and exact
full-input identity rather than hard-coding the 8,192 page limit, so no weaker
coverage policy is needed. Eta's **45/120-minute** choice remains unchanged.
Any later consumer pin and native canary require their own release; this note
authorizes neither. [Pinned final verifier, lines 1186–1229](https://github.com/open-hax/eta-mu/blob/a91a73e0e5efa165baf6d7247338f3ef17751752/.github/workflows/opencode-code-review.yml#L1186)

## Meaningful qualification and admission limits

Use the observed 830-page timeout as capacity baseline; do not invent a failing
losslessness expectation against the correct current reader. Existing correctness
tests should pass baseline. Add boundary tests for quotes, backslashes, C0
controls, multibyte BMP, astral boundaries, long lines, CRLF, and no terminal
newline. A naive larger-unit-only change must fail the encoded-output bound.

The cold compiled verifier currently invokes plugin tools directly and parses
their JSON; it bypasses the host limiter. Extend qualification to the actual
encoded result and the pinned host output boundary, requiring unchanged delivered
text and `truncated: false`, in addition to exact UTF-8 reconstruction. Preserve
prefix-only rejection, missing/unassessed-tail rejection, malformed ranges,
manifest corruption/recovery and a real retained tail finding that prevents
approval. [Existing compiled verifier, lines 63–115](https://github.com/octave-commons/muse/blob/0b9a91492c8355e6933dc2164d35668cb76d9e60/scripts/verify-full-review-input.mjs#L63)

Before code, the recovered owner needs canonical scope/admission and the precise
host-byte contract must be accepted. Before a native canary, qualified source and
an explicit shared-caller release are required. Keep the failed PR60 result and
its original full input; no unchanged retry, filtered input, inferred approval,
or timeout escalation is proposed.
