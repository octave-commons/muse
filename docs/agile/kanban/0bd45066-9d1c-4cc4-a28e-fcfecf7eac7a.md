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
