---
category: "kanban"
labels: "review, github, publisher, truth"
type: "task"
write-id: "1788048695246-0.q2ycixhgnyauvbwt66j"
points: "3"
title: "Make self-authored review publication truthful"
priority: "P0"
status: "ready"
uuid: "1bc0a6b5-3286-4e43-afc4-8cf8ae0549a0"
created_at: "2026-08-30T00:10:25.672Z"
---

# Make self-authored review publication truthful

## Outcome

Give the GitHub review publisher an explicit, tested contract for pull requests authored by the same GitHub App identity that publishes the evidence review, without converting a self-review into independent approval.

## Revision-bound evidence

- The eta-mu reusable review publisher submits the model envelope through Muse's `.ημ/review/publish-opencode-review.cjs`.
- A no-finding envelope deterministically selects `event: APPROVE`.
- When an automated pull request is authored by `eta-mu-ai[bot]` and the eta-mu App token publishes the review, GitHub rejects the self-approval with HTTP 422.
- Exact checkout and valid model evidence do not make self-approval admissible; publisher identity is a separate contract boundary.

## Acceptance criteria

- [ ] Determine the authenticated publisher identity and pull-request author before choosing the GitHub review event.
- [ ] Never call GitHub `createReview` with `APPROVE` or `REQUEST_CHANGES` when publisher and author are the same identity.
- [ ] Define an explicit self-authored outcome: either publish COMMENT-only evidence or fail with a machine-readable unsupported state; do not claim an independent approval.
- [ ] Keep different-author APPROVE, COMMENT, and REQUEST_CHANGES behavior unchanged.
- [ ] Add deterministic publisher tests for matching bot identities, different identities, and author metadata that is absent or malformed.
- [ ] Make the eta-mu terminal caller distinguish a completed self-review record from an independent approval when branch policy needs the latter.
- [ ] Document the operator-visible behavior and retain the exact attempted event and identity decision in evidence.

## Non-goals

- Do not bypass branch protection.
- Do not mint or switch to a human credential.
- Do not reinterpret a GitHub 422 response as success.

---
Validated against the current publisher contract: envelope validation deterministically selects APPROVE for zero findings, but publication does not model authenticated publisher identity versus PR author. Scope the repair at Muse publisher law/shape plus eta terminal outcome integration; no credential or branch-protection bypass.

Canonical GitHub projection created as octave-commons/muse issue #12 after the card reached ready; the issue carries this card UUID and exact publisher evidence.
---