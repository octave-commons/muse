# Complete immutable review input

License: GPL-3.0-or-later.

This is bounded, user-authorized preparation in an isolated worktree at Muse18
`19456bb4c5f4d5ce1ac52ca854221516302e0c2e`. No commit, push, review request or
merge is authorized in this slice. Manual incoming card:
`0bd45066-9d1c-4cc4-a28e-fcfecf7eac7a`; no lifecycle or engine event is claimed.
After the parent merged Muse18, the isolated branch was fast-forwarded to the
existing merge `68d0e53d1369c1c0577fc3eacedb6a1b402b2f30`. Its tree is identical
to 19456; no repair commit or history rewrite occurred.

## Failure and historical evidence

The old prompt bounded reads to candidate risk zones, and eta-mu's b5 workflow
replaced its full diff with a 300,000-byte preview. Empty confirmed findings
could therefore produce approval after incomplete input.

Historical native `.agents` #8 review `5402360561` was COMMENTED on
`5c85ba67e49d8dd994faacb083e446909affa8ba` at `2026-10-03T19:16:42Z`, describing
23 of 40 staged files. Review `5402523716` was APPROVED on
`a064989a94f648859062051d55d389819294543a` at `2026-10-03T20:08:40Z`, explicitly
describing 22 of 40 files and a 300,000/664,183-byte truncated diff. Both were
submitted by `eta-mu-ai[bot]`. These are incomplete historical reviews, not
qualification for this repair. The retained a064 context records effective
Muse `05b4f1c5e5bf2297bccf113a56c17e246d769d47`.

Caller source remains intentionally pinned to
`b5b28237c45323cdc1914317260192163d957735`. Paired eta-mu preparation starts at
merged #339 `b18764d47c0b8da0b2d31f02d8bdd889323ee65c`. These ancestry and input
identities are distinct from the unpublished repair source.

## Repair

The existing Node filesystem boundary checks schema, fixed full-input path,
commit identities, byte count and SHA-256, then decodes UTF-8 without loss.
Missing or corrupt input refuses `review_begin`; beginning a new pass clears
the current prior session and its owned submission output before verification.
No fallback can treat `pr.diff` as complete input. This also defensively covers
the parent's conditional reuse concern; production reachability of that old
concern is not asserted as a proven Muse18 defect.

The existing pure `.cljc` review session partitions verified text into lossless
pages of at most 8,192 UTF-16 units or 128 lines, retaining surrogate pairs.
`review_read_diff_chunk` records delivery; `review_assess_diff_chunk` records
the reviewer's separate changed-hunk assessment. Submission requires every
page to be assessed. The envelope retains source manifest, coverage counts and
page offsets/notes; assessment events retain their notes and timestamps.

The prompt requires every changed hunk to be assessed, with relevant surrounding
code as needed. It does not demand exhaustive proof or every unchanged file.
Candidate validation, added-head-line rules, Git-quoted Unicode identity and
deterministic publisher semantics remain in their existing owners. No parallel
review engine or board implementation is introduced.

## Actual local verification

- Frozen baseline reproducer: 1 test / 2 assertions, 2 semantic failures,
  0 errors, exit 1. The original pre-repair targeted suite also had 2 semantic
  failures (18 tests / 73 assertions). Both demonstrate partial approval.
- A separate compiled-tool RED fixture proves that a failed second begin could
  retain an earlier submission file. The final fixture requires both session
  reset and absence of any stale publishable output.
- Targeted pure domain: 20 tests / 81 assertions, zero failures/errors.
- Full compiled Muse suite: 208 tests / 565 assertions, zero failures/errors;
  test compilation has zero warnings.
- Filesystem tests reject deleted tails, same-length corruption, redirected
  paths, missing files/manifests and invalid UTF-8; exact restored bytes pass.
- `npm run test:review-input`: cold actual eight-tool profile, 204 reader pages;
  missing tail and prefix-only submission refuse, all recovered pages assessed
  submit APPROVE. The unchanged publisher accepts that envelope. Invalid begin
  cannot reuse the prior completed session or submission. No model or GitHub
  write occurs.
- Eta-mu's actual observer compilation step runs against this Muse source and
  checks all 22 runtime tools and permission entries, with zero warnings.
- Publisher tests: 10/10. Full lint: zero errors/warnings. All three declared
  cold host builds pass with shared dependencies and isolated publish homes.
- Babashka exercises the portable lossless Unicode paging law.

Verification commands and durable logs are recorded with append-only receipts
under the repair evidence directory
`/home/err/spaces/review-repair/.ημ/full-input-preparation-20261003/`.
The frozen baseline proof is `/tmp/muse-full-input-red/` and is copied there.
Local fixture coverage does not certify native model review or approval.

## Release checkpoint

The new workflow and Muse profile must be qualified and selected together.
An old Muse profile lacks the reader tools; an old workflow lacks the required
full-input manifest. Eta-mu's current default Muse selection remains 05b in
this uncommitted preparation, so parent must select the qualified new Muse
commit before hosted qualification of the changed workflow. Existing callers
remain on b5 until a separately reviewed functional revision update.

Parent owns exact-head hosted checks/reviews, findings settlement, release
selection and subsequent merge decisions. No paid credits, quota retries,
provider watcher changes or additional historical repair rounds are requested.
The existing 20,754-byte receipt prefix has SHA-256
`d0fd2f0761438833727ceb87dd4361811a1a49cf3c2ca6cd1b5509785ab1c7f2`.
Historical receipts and board events are preserved byte-for-byte.
