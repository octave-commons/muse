---
category: "kanban"
labels: "review, evidence, summary, truth"
type: "task"
write-id: "1788048695810-0.kz5hez9zjaj1l1oizxy"
points: "3"
title: "Bind review summary claims to current evidence"
priority: "P0"
status: "ready"
uuid: "9f35579b-e522-4445-a022-daa6f07ae3da"
created_at: "2026-08-30T00:10:41.098Z"
---

# Bind review summary claims to current evidence

## Outcome

Prevent a revision-bound review from presenting numerical or factual claims copied from predecessor commits as though they describe the current exact-head evidence.

## Revision-bound evidence

- Foresight PR #49 exact-head workflow run `33280341858` bound expected, executed, and completion SHA to `f3480655b326f8352e1d410d239a1690503d86e8`.
- Published eta-mu App review `5059407992` repeated predecessor counts `43/180` and `86 receipts / 45 appended`.
- The current immutable head evidence was `44/196`, aggregate `94/426`, and `87 receipts / 46 appended`, recorded in Foresight audit clarification comment `5465470648`.
- The no-finding verdict may still be exact-head while its free-form approval narrative is factually stale; SHA binding alone does not validate summary claims.

## Acceptance criteria

- [ ] Treat pull-request diff text, historical receipts, and predecessor review prose as non-authoritative for claims about the current deterministic run.
- [ ] Define a submission law for current-evidence claims: either derive the published deterministic section mechanically or require machine-checkable references to current artifact fields.
- [ ] Reject or omit unsupported numerical claims rather than publishing them as current evidence.
- [ ] Keep qualitative no-finding summaries possible without inventing gate counts.
- [ ] Add a regression fixture where predecessor counts appear in the diff while current `summary.json` and `deterministic.log` contain different counts.
- [ ] Prove the published summary uses only the current artifact values or contains no numerical assertion.
- [ ] Preserve exact-head, changed-line, and untrusted-diff protections already enforced by the review pipeline.
- [ ] Document which parts of a review body are deterministic evidence, model assessment, and non-authoritative narrative.

## Non-goals

- Do not infer arbitrary prose truth with another model.
- Do not treat a revision match as validation of every sentence.
- Do not weaken the no-finding evidence threshold.

---
Validated against Foresight PR #49 run 33280341858 and review 5059407992: exact-head SHA binding succeeded while predecessor numeric counts were copied into the current approval body. Scope the repair to a machine-checkable current-evidence narrative boundary with a predecessor-in-diff regression; do not add model-on-model fact checking.

Canonical GitHub projection created as octave-commons/muse issue #13 after the card reached ready; the issue carries this card UUID and Foresight run/review/comment evidence.
---