# Native context input purpose

The actionability transport uses the existing full-input filesystem boundary,
pager, eight review tools and terminal envelope. It does not authenticate native
actors or decide whether an actionability assessment is eligible. Canonical
pr-flow owns those decisions; a reviewed host must authenticate the complete
native context, proposal and selected immutable source before and after the
model child.

The manifest has `schema: open-hax.actionability-input/v1` and
`purpose: actionability`. Its sole input file is `actionability-input.txt`;
`full_input` records that exact path, positive UTF8 byte count and SHA256.
It records `head_sha`, `context_sha256`, positive `proposal_id`,
`proposal_body_sha256`, `source_inventory_sha256`, and a `target` map containing
`repository_id`, positive `repository_database_id`, `pr_id`, positive
`pr_number`, `thread_id` and positive `root_comment_id`. These are transport
coordinates, not self-authenticating native evidence. Existing `provenance`
records repository, pull request, run ID/attempt, workflow ref and workflow SHA.
Mixing in the ordinary `full_diff` descriptor is refused.
Commit and workflow identities use the existing 40-or-64 lowercase hex grammar;
content digests use 64 lowercase hex characters. Positive counts and numeric
coordinates must be safe integers. Native node identifiers must be nonblank
strings; their actual native identity remains a host verification obligation.

The caller writes complete data containing original native bodies, the exact
proposal and relevant selected source bytes. JSON encoding may escape embedded
newlines while preserving every decoded original body and its independent
hash. Muse pages the exact full text, checks every page is assessed before the
first evidence stage and retains its manifest in the terminal envelope.
UTF8 byte count differs from the existing UTF16 page coordinates.
Both filesystem and terminal adapters use the same strict JSON decoder.
Duplicate members, including escaped aliases and nested members, nonfinite
numbers and malformed UTF8 manifests refuse before descriptor admission.
Full-input decoding preserves every leading UTF8 BOM as text covered by the
manifest digest; a BOM-only positive-byte native input therefore has one page.
The native purpose requires nonempty page geometry, checked with the existing
invocation context law before a tool session or successful begin event is stored. Prefer byte arrays when
preparing context: a JavaScript string cannot attest an earlier UTF8 decoding.
The shared pager's reserved diff-truncation marker stays enforced; encoding
original bodies as data avoids presenting an embedded body line as that marker.

`review_read_diff_chunk`, `diff-stats`, `fullDiff` revalidation and the
`open-hax.github-review/v1` envelope keep their existing names and signatures.
In this purpose they are carrier mechanics, not a claim that native context is
a Git diff or that an envelope event authorizes a pull-review or issue comment.
A separately reviewed host route must prevent ordinary review publication,
validate the canonical assessment protocol, collect fresh native custody and
publish the exact issue-comment body through its authenticated App boundary.
The model receives no GitHub credentials.

Ordinary `open-hax.review-input/v1` remains unchanged, including its fixed
`basehead.diff`, complete commit identities and supported empty diff. Native
context cannot be empty. Unsupported purpose/schema, malformed coordinates,
redirected paths, missing/tampered bytes and invalid UTF8 refuse before a
review session can claim complete input.

## Source qualification

The focused filesystem, invocation-law and invocation-adapter controls passed
56 tests and 799 assertions. The initial complete new-purpose controls produced
24 failures and zero errors on the unchanged predecessor; a later positive-byte
zero-page control produced three failures on the first candidate before repair.
All inherited assertions in those namespaces remain present.

The actual cold eight-tool fixture passed both the existing 204-page diff case
and the complete 43-page native-context case. The optimized CJS checks passed
all three ordinary representations with 14 assertions each and all three native
representations with 29 assertions each. The latter verify exact byte and source
bindings, complete input chronology and unchanged terminal summary binding.
These fixtures use inert data and local submissions, with no model or native
publication. The deliberately refused calls in the eight-tool fixture exercise
receiver behavior; they do not constitute an accepted host invocation trace.

Run the existing compiled qualification commands after building the invocation
export and tests:

```sh
node target/test/test.cjs --test=eta-mu.law.review-invocation-test,eta-mu.boundaries.node.fs-test,eta-mu.extern.review-invocation-test
node scripts/verify-full-review-input.mjs
node scripts/verify-review-invocation.mjs
```

Qualification used owned dependencies with Node 22.20.0 and Java 21.0.12.1.
The final test, CJS and cold plugin compiles emitted zero warnings, and touched
lint passed with zero errors and warnings. Retained external captures preserve
predecessor failures and preparation errors separately. Native invocation,
publication, canonical policy admission and deployment require their own
reviewed host route and actual evidence.
