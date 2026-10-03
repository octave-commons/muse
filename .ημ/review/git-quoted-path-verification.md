# Canonical Git-quoted review paths

License: GPL-3.0-or-later.

## Repair and boundary

Muse's staged-diff index previously stored the literal quoted `+++` pathname.
Canonical `.ημ/receipts.edn` lookup failed despite added receipt lines, while
the quoted alias was admitted by the tool but rejected by the publisher.

`eta-mu.shape.git-diff-path` now decodes Git C quoting and UTF-8 octal **bytes**
before destination-prefix removal. It is pure `.cljc`, with only core/string
operations and no added dependency or host decoder. Raw Unicode, distinct
normalization forms, spaces and escaped filename controls retain their exact
identity. Invalid quoting, octal values, NUL and malformed UTF-8 throw before a
session is admitted. The domain index resets at each Git file header and
recognizes `+++` metadata only outside hunks, so header-looking added content
remains content. Existing added-head-line and publisher checks remain intact.

Manual incoming card: `cdfb84f8-1c83-4e0e-a6f5-960427d341dd`. The user authorized
this scoped implementation and delegated review requests, lifecycle
qualification and consumption to the parent. No ready/done transition,
planning-review qualification or engine event is claimed here.

## Historical input provenance

These identities describe the **old native input**, not this repaired artifact:

- Worktree ancestry: merged Muse #17/default main
  `e1cc8d96fd1182a6ed7c64e73a2eec1c911a9838`.
- Live reusable workflow pin: `b5b28237c45323cdc1914317260192163d957735`.
  Its default Muse input is `05b4f1c5e5bf2297bccf113a56c17e246d769d47`.
  Native #125 context artifact 11272388358 confirms that effective Muse pin.
- Old indexer at effective 05b and merged e1cc has identical blob
  `b77eeaccb48c337ec2a195a97bb0046628903d10`, SHA-256
  `309abbf7ea11129ac2b6565aacd0e27b5a94561b187e75f1325a94e328c8f4e4`.
- Foresight #125 review `5400555116`, submitted `2026-10-03T11:37:48Z`, head
  `5b7aeea44f92948a5e1cc6b469e64ebc11d9ad8f`, run `37119211019`, evidence
  artifact `11273295157`, generated `2026-10-03T11:18:26.184Z`.
  The 25,659-byte diff SHA-256 is
  `d6b4a81041c57f4773915c8db1c74144173a71394ecc613f8e0f39e3568bd30b`.
- Foresight #126 review `5400431395`, submitted `2026-10-03T11:03:34Z`, head
  `59cebacf6150246d351d22e9eb07c041289270ec`, run `37117938917`, evidence
  artifact `11271649033`, generated `2026-10-03T10:55:04.781Z`.
  The 40,556-byte diff SHA-256 is
  `a1df4128caf478257969711ad1384f1fae051e910214c634c7753a0350b4ae33`.
- Both native diffs were complete and revision-bound. Source archaeology traces
  the omitted decoding to Muse `f26538bd6c5ec3ef6084be9d2424d5b1daef648c`.
  The original read-only proof/artifacts remain unchanged at
  `/home/err/spaces/review-repair/.ημ/muse-quoted-paths-20261003/`.

## Actual local verification

Red commit `44cc8a09217fa7de552d1f63e3345616c7532aca` records the
same Git-emitted receipt header, canonical index/admission and quoted-alias
rejection tests: **14 tests / 41 assertions / 3 failures / 0 errors**, exit 1.

Green verification used Node **22.20.0** for compiled tests/builds and publisher
tests; NBB **1.3.204** for the targeted suite and historical artifact replays:

- Targeted domain: **17 tests / 71 assertions**, zero failures/errors.
- Full compiled Muse suite: **202 tests / 546 assertions**, zero failures/errors;
  test compilation reported zero warnings.
- Existing publisher suite plus canonical UTF-8/alias/context controls:
  **10 tests**, all pass. GitHub operations are stubs, not native publication.
- `npm run lint`: **zero errors, zero warnings**.
- `npm run build`: daemon, OpenCode plugin, MCP server and Claude server each
  compile with **zero warnings**. Builds use an isolated JVM publish home with
  the existing Maven cache linked; real global OpenCode paths are not published.
- `npm run test:build-cold`: **all three declared hosts pass** from fresh tracked
  source copies with shared dependencies and isolated publish homes.
- Pure Clojure/Babashka smoke: quoted receipt UTF-8, supplementary codepoint and
  decomposed Unicode identity controls pass.
- An isolated review-only profile, importing the existing review-pipeline
  fragment, compiles with zero warnings and exposes the **six actual review
  tools**. Seven actual `git -c core.quotePath=true diff --find-renames`
  filename cases pass: ASCII, `.ημ`, composed/decomposed accents, supplementary
  Unicode, quote/backslash/TAB/LF, and leading/trailing spaces.
  Canonical paths survive tool submission and unchanged publisher validation;
  quoted aliases and context-line candidates are rejected. Both native diffs
  admit canonical receipt candidates (125:166–169;126:169). Malformed input
  leaves no session that can submit an approval.

The isolated probe was bootstrapped with the same explicit generator step used
by the ETA workflow. Its test harness uses Node's `fileURLToPath` for Unicode
artifact locations. Initial probe-harness/bootstrap failures were corrected;
the successful run above tests unchanged repair source bytes. No model was
invoked and no native inline review was published or certified. The synthetic
candidate probes do not adjudicate either historical receipt observation.

Verified source SHA-256: shape
`168a2ab5e16cdd377e1749fbb1278ce6cb82ecd0676968dc69e8e38d83e9dfba`, domain
`d3f8f9c167d288749b14c4c6b84d83536b9048fb7ca2ea51a81a63dd5e504fcf`.
The isolated compiled review-only bundle SHA-256 is
`b52aad7e5c4c03447628b6f6681324e00a007e0311c4e109603a55ab3aa8b4da`.

Repository commands:

```sh
nbb -cp src/cljs:test/cljs -e "(require '[cljs.test :as t] '[eta-mu.domain.review-test]) (t/run-tests 'eta-mu.domain.review-test)"
node --test .ημ/review/publish-opencode-review.test.cjs
npm test
npm run lint
npm run build
npm run test:build-cold
```

The NBB evidence runner additionally sets process exit status from the test
summary; plain `run-tests` does not itself guarantee nonzero failure exit.
External logs and compiled-tool probe are retained under
`/home/err/spaces/review-repair/.ημ/muse-quoted-diff-paths-*`.

## Parent consumption handoff

Only Muse source, tests, this verification note, the incoming card and appended
root `receipts.edn` entries change. Existing main receipt bytes remain an exact
prefix: **17,221 bytes**, SHA-256
`e75ab23c21834f292b2093d79754bd437224fbabb4964f0deec3f8a0644c987e`.
Existing board/event history is unchanged. Package manifests, lockfiles,
workflow inputs and caller pins are unchanged; quotePath=false was never used.

The repair does not automatically change b5's default Muse selection. After
parent review and merge, consumers must select the qualified exact Muse commit
through the existing `muse_revision` input (or a separately reviewed default
propagation). The workflow compiles the plugin and takes publisher machinery
from that same Muse checkout. Parent owns requests, exact-head review and
hosted qualification, merge, and caller-pin propagation. This PR is ready with
auto-merge off; it does not claim hosted repair consumption or deployment.
