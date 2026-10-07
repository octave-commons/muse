---
uuid: "0bd45066-9d1c-4cc4-a28e-fcfecf7eac7a"
title: "Require assessment of complete immutable review input"
status: incoming
priority: P1
points: 3
labels: review, full-input, completeness, 3sp
category: repair
---

# Require assessment of complete immutable review input

## Outcome

A passing verdict requires assessment of all changed hunks, including input
beyond a preview. Missing tail bytes cannot produce an approval; restored full
input can complete the existing review state machine.

## Scope

The user authorized this bounded upstream preparation on 2026-10-03, in an
isolated worktree based on Muse18 `19456bb4c5f4d5ce1ac52ca854221516302e0c2e`.
Extend the existing pure review session with lossless reader pages and separate
delivery/assessment observations. The filesystem boundary verifies eta-mu's
full-input manifest. Expose two read-only review tools, update the existing
reviewer contract and test actual compiled tools.

The paired eta-mu worktree starts at merged
`b18764d47c0b8da0b2d31f02d8bdd889323ee65c` and preserves `basehead.diff` before
creating `pr.diff`. Existing caller workflow pin
`b5b28237c45323cdc1914317260192163d957735` remains historical provenance.

## Non-goals

No second review engine or board parser, exhaustive proof of unchanged code,
approval inferred from coverage or empty findings, caller pin update, review
request, provider watcher, commit, push or merge in this preparation.

## Acceptance criteria

- [ ] Invalid manifests, missing bytes and invalid UTF-8 refuse review input.
- [ ] Every delivered page requires a recorded changed-hunk assessment.
- [ ] A prefix-only session cannot submit an approval.
- [ ] Restored complete input, assessed through actual compiled tools, can submit.
- [ ] Git-quoted Unicode path identity and publisher validation remain intact.
- [ ] Relevant tests, lint and cold profile compilation pass.
- [ ] Existing receipt and event history remains unchanged.

## Verification

Use pure review regression tests, filesystem corruption/recovery fixtures and
`npm run test:review-input` against an isolated cold compiled profile. Fixtures
do not invoke a model or publish native reviews. Record actual results and
source identities in append-only receipts and the full-input verification note.

## Authority

This is manual incoming Markdown input. No engine write ID, event, lifecycle
transition or hosted qualification is asserted. Parent owns release selection
and subsequent hosted review and merge qualification. Preparation remains
uncommitted until the user releases the explicit hold.

## Native Muse19 review follow-up — 2026-10-03

The parent authorized local repairs of CodeRabbit comments `4175445588` (P2,
retry after failed input admission) and `4175445601` (P1, retain the candidate
stage until every full-input page is assessed). Add RED/GREEN coverage for the
premature publish transition, recovery without restart, and a retained tail
finding through the actual compiled tools. Keep submission's defensive guard
and the existing prohibition on new findings at `:publish`.

Comment `4175445594` (P1) identifies the paired eta-mu staging/tool contract.
The prepared workflow remains unpublished; only after the parent commits the
corrected Muse source may eta-mu's three Muse selection sites and their tests
advance to that immutable commit. Both proposed revisions can qualify together
before caller activation. Existing callers retain the old compatible pair.
Parent owns commits, push, GitHub settlement, review requests and merge.

## Recover stale assessment chronology — 2026-10-06

The authorized workflow restoration exposed native Proxx452 review5434690539:
all hosted jobs succeeded and an approval was published, but seventeen pages
were reread after their assessments. The literal retained last-read-before-
assessment audit fails; later reassessment cannot erase that earlier trace.
The full input also contains 29 changed files while the added-lines index
reported 28 because one file is deletion-only. The native review remains failed
independent qualification and is not repeated or credited.

Repair the existing pure review state and compiled tools so any reread of an
assessed page permanently invalidates that invocation, removes its publishable
coverage, prevents a same-session restart from hiding the failure and removes
a prior owned submission if one exists. A fresh process may make the single
bounded recovery already supported by the qualified eta-mu runner. Repeated
reads before assessment and complete recovery of previously unassessed tail
pages remain permitted. Report all serialized Git diff files, preserving the
separate added-lines finding index.

Acceptance: reproduce the precise failures on unchanged reviewed Muse19 code;
pass meaningful pure and actual compiled-tool controls with the candidate;
retain every old test definition and historical receipt byte; run appropriate
tests and zero-warning lint. Existing timestamp, assessment, stage, identity,
quorum and publication laws remain required. This manual card-content addition
asserts no Rheos transition, hosted result, independent agreement or adoption.

The receiving boundary also needs the complete actual host call history. Add
portable invocation law and a compiled CJS adapter that prepare canonical page
geometry from verified full input, reject irreversible last-read violations,
require five actual successful stage calls and bind the submit call to the
unchanged artifact. The paired eta-mu supervisor and final Git validation consume
this same source; they do not implement another review state machine. Actual
file-deletion failure may leave a prior artifact, so artifact deletion alone
cannot establish eligibility. Unknown or malformed traces and host tool errors
remain unestablished. Typed healthy omission and successful stale-read evidence
may use the existing shared two-invocation bound, never a third attempt.

Only fresh full native qualification and normal protected merges of both source
repairs permit caller activation. Local compiled and transport fixture results
provide no native agreement, approval, completed round or source adoption.

The regular build and review-input gate must also exercise the optimized CJS
exports that eta-mu consumes. Build the review-invocation target alongside the
existing hosts, then use its public preparation and verification exports to
check string, Buffer and Uint8Array input plus exact raw artifact digests. The
old optimized crypto interop failure must fail this same smoke check. Hosted
CI for the initial b1 source did not run this export gate; that coverage limit
remains recorded until the successor receives its own hosted qualification.

The initial native review staged Muse `05b4` through the old reusable caller `b5b`,
which exposes neither required full-input page tool. Both model invocations
ended without submission. The missing-tool contract is established; the cause
of omitted submission is not observed. Advance only the reusable workflow pin
to the actually reviewed and protected-merged Eta342 commit `09a`, whose immutable
default selects the compatible full-input Muse `0b` tools. Keep caller permissions, named
secrets, evidence commands and every review gate unchanged. The successor
needs fresh native full-input qualification; the failed initial run stays failed.

## Flush the fresh-process result — 2026-10-07

CodeRabbit current29 root4201964868 identifies an immediate child exit before
piped JSON is flushed. Await the stdout write callback and reject callback
errors, then retain the same explicit exit0 needed by the compiled plugin.
Three controlled delayed-pipe original-script cases lose their JSON with exit0;
the three corrected cases deliver every byte and all204 assessed pages. Both
cold8tool204page smoke runs pass. The150ms delay is synthetic, not an observed
production outage. No domain, stage, tool or invocation guard changes here.

Current29 nativeMiMo is genuinely APPROVED with independently replayed49 input
checks passing. Its separate publication34 audit remains33/34 FAILED because
the historical author request also contained a title; all whole body bytes
match. Preserve that failed result and every historical receipt byte. The new
head requires fresh full native qualification and current hostedCI. The paired
Eta full-registry custody and strict failed-call prompt repair remains local
and unpublished until the actual qualified Muse merge. This manual content
addition records no Rheos transition, native approval or independent agreement.

## Align prompts with strict invocation admission — 2026-10-07

Native Muse20 review5436579674/root4202152173 exposes a real contract mismatch:
this source's verifier refuses failed review calls and repeated stage/submit
records while the old prompts explicitly recommend those repair loops. Align
both source prompts: any failed review call ends that invocation without
submission, retry or restart. Only the host's verified typed recovery policy
may admit one fresh bounded complete-input invocation; failure alone supplies
no retry authority. Preserve every existing law, LAST, coverage and binding guard.

Add one focused existing-law test using actual producer stage/submit refusals,
then successful calls: both traces remain unestablished; the healthy complete
five-stage trace passes. Add explicit publish to the fresh-child fixture while
preserving stdout completion/error handling. The same final test on original
source and candidate passes266tests1152assertions; no old RED is claimed. Kondo
and compilation have zero warnings; cold8tool204page and public30assertion
export checks pass. This is local source evidence, not native qualification.

The actual7f review covers all21pages17paths but contains a refused classification
call and an older trigger-event PR-body snapshot. Retain all49 original PASS
predicates and both supplemental failures. The next source description is
written body-only at the actual old published head before the source push, so
the new event snapshot carries it; no unpublished SHA is used in that request.
No later body is credited as model input without actual evidence.

This manual scope/verification content asserts no Rheos state transition.
Append a new correction receipt and reflection; never edit historical lines.
Every new native review, current hosted check, genuine finding, quorum and
separate convergence gate remains required before an actual normal merge.


## Native026 begin-description correction

Actual MiMo review5436874985/root4202400351 identifies a stale model-facing
`review_begin` description that tells the agent to restore input and retry
initial admission. Both reviewed prompts and the strict invocation law require
a failed review call to end that invocation. Input repair belongs to the host.

Scope: remove the restore-and-retry instruction from the begin description and
state that a failed begin ends the invocation. Preserve every executable tool
body, strict law guard, existing test byte, immutable caller pin, and historical
receipt/reflection byte. Run the applicable existing compiler/linter, then
append a typed correction receipt and reflection. Parent alone publishes an
ordinary successor; fresh full native review, current CI, all finding
settlements and the normal merge gate remain required. This card content is
manually authored planning; operational status remains unchanged.
