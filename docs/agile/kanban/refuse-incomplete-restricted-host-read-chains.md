---
uuid: e6c2c7ff-c19c-42b0-b1db-e2d5180bf4ac
labels: review-restoration, canonical-admission, restricted-host-read
---

# Refuse malformed supporting reads under an explicit trusted profile

## Outcome

The existing canonical review invocation guard refuses an actual opened supporting
file read that starts beyond line 1 or abandons its returned continuation. The
profile is explicitly selected in trusted prepared context and bound by the
supervisor/publisher expected-context hash. Default generic review behavior stays
byte-compatible; this candidate supplies no native approval or recovery authority.

## Scope and plan

1. Preserve the Muse ba7f baseline and every existing test definition/assertion.
   Adapt only the four existing admission/decoder forms needed by the selected profile;
   preserve the other 66 original source forms byte for byte.
2. Define the selected restricted-read profile and a pure whole-history receipt
   guard in the existing invocation law. Reuse the shared admission entry for
   verification, generic omission refusal and existing length classification.
3. Preserve structured HOST read metadata at the existing extern. Add a narrow
   source-bound preparation API whose output carries the selected profile, while
   preserving the existing preparation export and its default output.
4. Add the new preparation export to the existing node-library build; parent owns
   all real workflow/source materialization, capability/pin/context-hash wiring.
5. Add separate synthetic test namespaces. Run unchanged domain/law/extern suites,
   original optimized-export smoke, source-built RED/GREEN controls, and a read-only
   replay of the identical EBF raw trace with default versus selected context.

The profile identifies eta-mu.restricted-host-read/v1 and the actual EBF HOST EDN
source SHA256 2c7d86dc58a34a8956dfc8e3828a6cda4b14b0dc17d7a76ab207cddedef416f7.
Opened read chains require actual returned start 1 (omitted first offset only with
that witness), explicit contiguous continuation and genuine terminal/content
witnesses. Capped totals may change, including actual 890 -> 1205. Reads after
FIRST remain within whole-history admission; closure is required at whole-trace
terminal, without adding supporting-file BEFORE FIRST or other stage timing.
The source-qualified text formatter excludes loaded-instruction/reminder variants
with an explicit unsupported-shape refusal. Its exact line-clipping suffix is
conservatively refused even when the receipt advertises EOF/truncated=false.

## Non-goals

No live producer hook, new workflow/component/provider, new recovery class or
outer budget. No universal all-file/per-call archive rule. No native/model/network
operation or healthy EBF repeat. No shared worktree/Git/config/history/credential
write or branch-protection change. No inferred omitted output or cognition.

## Acceptance

- Actual EBF raw response remains accepted by default existing ba7f behavior and
  receives a terminal canonical refusal only with the prospective selected profile.
- Healthy synthetic complete, contiguous/capped and omitted-first-offset controls
  pass; missing/malformed receipt fields, wrong range/path/content, unfinished
  chains, unknown/source-mismatched profiles and false EOF witnesses refuse.
- Numbered apparent footer/closing tags remain file content. A literal identical
  clipping suffix is ambiguous and refuses; no omitted bytes are reconstructed.
- Failure after FIRST is caught; generic missing-submit and length routes do not
  recover from the new protocol failures. Existing MAX2 and classifiers remain.
- All original test files, definitions and assertions remain byte exact. Of 70
  original source forms, 66 stay byte exact and four admission/decoder forms adapt.
  A zero-fuzz inverse restores immutable ba7f bytes for the three modified files
  and removes both additions.
- Appropriate unchanged suites and new controls pass with zero compiler/kondo
  warnings; any unavailable or failed check remains explicit.

## Verification and limits

Owned baseline/candidate export, source diffs/inverse, source-built module/test
identities, commands/stdout/stderr, real versus synthetic results, unchanged frozen
packet/Git control hashes and full manifest/seal form the handoff. Source-built
callbacks run offline; native artifact programs are never executed. This manual
card is candidate content only: no operational status or engine event is created,
and no Rheos validation is claimed. Parent retains normal canonical all-gates
review and merge authority. Branch protection was not inspected by this worker.

## Public boundary regression verification

Native review5455536592 requests a repository prepare-to-verify round-trip test.
The existing `eta-mu.extern.review-invocation-host-read-test` namespace exercises
that public JSON boundary, including the camel-case `sourceSha256` key, completed
and abandoned supporting read chains, malformed selected-context keys, and length
classification. All original test files and every production byte are preserved.
The original D0E producer passes these new tests; a synthetic wrong-key control
demonstrates refusal without claiming a prior production defect. The review finding remains open pending native disposition.

Existing full-diff chronology is separate from supporting-read closure: deliver
and assess every full-diff page before the first evidence stage, and place each
page's final assessment after its last read. Supporting reads may follow FIRST,
but every opened selected-profile chain must close at whole-trace terminal.
A native approval alone does not establish these conditions; qualification
requires the actual source-bound input and trace.


## Raw profile preparation verification

The same public five-argument preparation API also accepts the selected profile
as an object, JSON string or UTF-8 bytes. Two additional direct tests verify
healthy and unfinished supporting read chains in all three forms, and the safe
preparation error for malformed JSON, duplicate keys, invalid UTF-8, wrong keys,
unsupported profile identity and invalid source digest. The existing ED producer
already passes these controls: this closes a test coverage gap reported in native
review5456759586, without claiming a production bug or changing recovery authority.
The source-built suite completed 113 tests and 1,357 assertions with zero failures,
errors or compiler warnings. Fresh current-head hosted checks and full native
review qualification remain required after publication.
