// SPDX-License-Identifier: GPL-3.0-or-later
// Smoke-test the optimized CJS exports with synthetic DATA, never a native review.
import assert from 'node:assert/strict';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
assert.ok(process.argv.length <= 3, 'Only an optional compiled module path is supported');
const modulePath = path.resolve(repo, process.argv[2] || '.opencode/dist/review-invocation.cjs');
const verifier = createRequire(import.meta.url)(modulePath);
assert.equal(typeof verifier.prepareReviewInvocationContext, 'function');
assert.equal(typeof verifier.verifyReviewInvocation, 'function');
assert.equal(typeof verifier.classifyLengthEndedReview, 'function');
const sha256 = bytes => createHash('sha256').update(bytes).digest('hex');
const stages = ['deterministic', 'map-change', 'generate-candidates', 'adversarial-validate', 'publish'];
const tools = ['review_begin', 'review_read_diff_chunk', 'review_assess_diff_chunk',
  'review_record_evidence', 'review_propose_finding', 'review_classify_finding', 'review_status', 'review_submit'];
const full = Buffer.from('diff --git a/notes.txt b/notes.txt\n--- a/notes.txt\n+++ b/notes.txt\n@@ -1 +1 @@\n-old\n+😀\n');
const source = {schema: 'open-hax.review-input/v1', base_sha: 'a'.repeat(40), head_sha: 'b'.repeat(40),
  diff_base_sha: 'a'.repeat(40), full_diff: {path: 'basehead.diff', bytes: full.length, sha256: sha256(full)},
  provenance: {repository: 'synthetic/optimized-export-smoke', pull_request: '1', run_id: '2', run_attempt: '1',
    workflow_sha: 'd'.repeat(40), workflow_ref: 'synthetic/optimized-export-smoke/workflow@refs/pull/1/merge'}};
const manifest = Buffer.from(JSON.stringify(source));
const submissionFile = path.join(repo, '.opencode/review-evidence/smoke-submission.json');

/** Construct synthetic successful host events using pages from the actual preparation export. */
function smokeInvocation(context, input = full) {
  const text = input.toString('utf8');
  const pages = context.pages;
  const notes = pages.map(page => ({...page, note: `Synthetic smoke assessment of page ${page.id}.`}));
  const body = {schema: 'open-hax.github-review/v1', event: 'COMMENT',
    summary: 'Synthetic optimized-export smoke; no native review credit.', comments: [],
    'input-source': context.inputSource,
    'input-coverage': {chunks: pages.length, delivered: pages.length, assessed: pages.length, missing: []},
    'input-assessments': notes};
  const sessionID = 'session-optimized-export-smoke';
  const events = [];
  const call = (tool, input, output) => {
    const n = events.length + 1;
    events.push({type: 'tool_use', timestamp: n, sessionID,
      part: {type: 'tool', id: `part-${n}`, callID: `call-${n}`, sessionID, tool,
        state: {status: 'completed', input, output: JSON.stringify(output)}}});
  };
  call('review_begin', {}, {'ok?': true, stage: stages[0], stages, 'input-source': context.inputSource,
    'diff-stats': {files: 1, bytes: text.length, 'truncated?': false},
    'input-coverage': {chunks: pages.length, delivered: 0, assessed: 0, missing: pages.map(page => page.id)}});
  for (const [index, page] of pages.entries()) {
    call('review_read_diff_chunk', {id: page.id}, {'ok?': true,
      chunk: {...page, text: text.slice(page.start, page.end)}});
    call('review_assess_diff_chunk', {id: page.id, note: notes[index].note}, {'ok?': true, 'chunk-id': page.id,
      coverage: {chunks: pages.length, delivered: index + 1, assessed: index + 1,
        missing: pages.slice(index + 1).map(remaining => remaining.id)}});
  }
  for (const stage of stages) {
    call('review_record_evidence', {stage, note: `Synthetic ${stage} evidence.`}, {'ok?': true});
  }
  call('review_submit', {summary: body.summary}, {'ok?': true, event: body.event, file: submissionFile,
    'inline-comments': 0});
  events.push({type: 'step_finish', timestamp: events.length + 1, sessionID,
    part: {type: 'step-finish', id: 'part-stop', sessionID, reason: 'stop'}});
  return {response: Buffer.from(events.map(event => JSON.stringify(event)).join('\n') + '\n'),
    submission: Buffer.from(JSON.stringify(body))};
}

const results = [];
for (const [representation, encode] of [['string', bytes => bytes.toString('utf8')],
  ['Buffer', bytes => bytes], ['Uint8Array', bytes => new Uint8Array(bytes)]]) {
  const result = {representation, assertions: 0};
  const equal = (actual, expected) => { result.assertions++; assert.deepEqual(actual, expected); };
  try {
    const context = verifier.prepareReviewInvocationContext(encode(full), encode(manifest), tools, submissionFile);
    equal(context.fullInputSha256, sha256(full));
    equal(context.inputSource.full_diff.bytes, full.length);
    equal(context.pages.at(-1).end, full.toString('utf8').length);
    equal(context.inputSource, source);
    const {response, submission} = smokeInvocation(context);
    const verdict = verifier.verifyReviewInvocation(encode(response), encode(submission), context);
    equal(verdict.ok, true);
    equal(verdict.reasonKind, null);
    equal(verdict.acceptedInvocation.responseSha256, sha256(response));
    equal(verdict.acceptedInvocation.submissionSha256, sha256(submission));
    equal(verdict.acceptedInvocation.fullInputSha256, sha256(full));
    equal(verdict.acceptedInvocation.pageCount, context.pageCount);
    const lengthContext = {...context, fullDiff: full.toString('utf8'), sessionID: 'session-optimized-export-smoke'};
    const lengthVerdict = verifier.classifyLengthEndedReview(encode(response), encode(submission), lengthContext,
      {invocationState: 'completed', exitCode: 0, submissionState: 'present',
        responseSha256Before: sha256(response), responseSha256After: sha256(response),
        contextBefore: lengthContext, contextAfter: lengthContext});
    equal(lengthVerdict.eligible, false, 'A healthy completed review cannot authorize length recovery.');
    equal(Object.hasOwn(lengthVerdict, 'ok'), false);
    equal(Object.hasOwn(lengthVerdict, 'acceptedInvocation'), false);
    equal(Object.hasOwn(lengthVerdict, 'approval'), false);
    result.pass = true;
  } catch (error) {
    result.pass = false;
    result.error = {name: error.name, message: error.message};
  }
  results.push(result);
}
const nativeInput = Buffer.from(JSON.stringify({kind: 'synthetic-native-context',
  native: {body: 'Original explanatory conversation ημ 😀\n> quoted source, retained as data',
    proposal: 'Inert fixture; no native policy assessment'},
  source: 'selected immutable source bytes\n'.repeat(700), tail: 'complete tail ημ 😀'}));
const nativeSource = {schema: 'open-hax.actionability-input/v1', purpose: 'actionability',
  head_sha: 'b'.repeat(40), context_sha256: 'c'.repeat(64), proposal_id: 71,
  proposal_body_sha256: 'd'.repeat(64), source_inventory_sha256: 'e'.repeat(64),
  target: {repository_id: 'R_fixture', repository_database_id: 3, pr_id: 'PR_fixture',
    pr_number: 1, thread_id: 'PRRT_fixture', root_comment_id: 7},
  full_input: {path: 'actionability-input.txt', bytes: nativeInput.length, sha256: sha256(nativeInput)},
  provenance: source.provenance};
for (const [representation, encode] of [['string', bytes => bytes.toString('utf8')],
  ['Buffer', bytes => bytes], ['Uint8Array', bytes => new Uint8Array(bytes)]]) {
  const result = {representation, purpose: 'actionability', assertions: 0};
  const equal = (actual, expected) => { result.assertions++; assert.deepEqual(actual, expected); };
  const refuses = (input, manifest) => { result.assertions++; assert.throws(() =>
    verifier.prepareReviewInvocationContext(input, encode(Buffer.from(JSON.stringify(manifest))), tools, submissionFile)); };
  try {
    const context = verifier.prepareReviewInvocationContext(encode(nativeInput),
      encode(Buffer.from(JSON.stringify(nativeSource))), tools, submissionFile);
    equal(context.inputSource, nativeSource);
    equal(context.fullInputSha256, sha256(nativeInput));
    equal(context.pages.at(-1).end, nativeInput.toString('utf8').length);
    equal(context.pageCount > 1, true);
    const {response, submission} = smokeInvocation(context, nativeInput);
    const verdict = verifier.verifyReviewInvocation(encode(response), encode(submission), context);
    equal(verdict.ok, true);
    equal(verdict.acceptedInvocation.fullInputSha256, sha256(nativeInput));
    equal(verdict.acceptedInvocation.pageCount, context.pageCount);
    equal(verdict.acceptedInvocation.submissionSha256, sha256(submission));
    const revalidated = {...context, fullDiff: nativeInput.toString('utf8')};
    equal(verifier.verifyReviewInvocation(encode(response), encode(submission), revalidated).ok, true);
    for (const changed of [{...nativeSource, context_sha256: 'f'.repeat(64)},
      {...nativeSource, proposal_id: 72}, {...nativeSource, head_sha: 'f'.repeat(40)},
      {...nativeSource, source_inventory_sha256: 'f'.repeat(64)},
      {...nativeSource, target: {...nativeSource.target, thread_id: 'PRRT_other'}}]) {
      const changedContext = verifier.prepareReviewInvocationContext(encode(nativeInput),
        encode(Buffer.from(JSON.stringify(changed))), tools, submissionFile);
      const refused = verifier.verifyReviewInvocation(encode(response), encode(submission), changedContext);
      equal(refused.ok, false);
      equal(refused.acceptedInvocation, null);
    }
    const body = JSON.parse(submission);
    const substituted = {...body, 'input-source': {...nativeSource, proposal_body_sha256: 'f'.repeat(64)}};
    equal(verifier.verifyReviewInvocation(encode(response), JSON.stringify(substituted), context).ok, false);
    equal(verifier.verifyReviewInvocation(encode(response), JSON.stringify({...body, summary: 'substituted'}), context).ok, false);
    const omitted = response.toString('utf8').trim().split('\n').map(JSON.parse)
      .filter(event => !(event.part.tool === 'review_assess_diff_chunk' && event.part.state.input.id === context.pageCount));
    const incomplete = verifier.verifyReviewInvocation(omitted.map(JSON.stringify).join('\n'), encode(submission), context);
    equal(incomplete.ok, false);
    equal(incomplete.acceptedInvocation, null);
    refuses(encode(nativeInput.subarray(0, 100)), nativeSource);
    refuses(encode(nativeInput), {...nativeSource, purpose: 'diff'});
    refuses(encode(nativeInput), {...nativeSource, full_diff: source.full_diff});
    refuses(encode(nativeInput), {...nativeSource, full_input: {...nativeSource.full_input, path: '../other'}});
    refuses(new Uint8Array([255]), nativeSource);
    refuses('', {...nativeSource, full_input: {path: 'actionability-input.txt', bytes: 0, sha256: sha256('')}});
    result.pass = true;
  } catch (error) {
    result.pass = false;
    result.error = {name: error.name, message: error.message};
  }
  results.push(result);
}
// Leading BOM is full input, including the positive-byte BOM-only case.
for (const [representation, encode] of [['string', bytes => bytes.toString('utf8')],
  ['Buffer', bytes => bytes], ['Uint8Array', bytes => new Uint8Array(bytes)]]) {
  for (const text of ['\uFEFF', '\uFEFForiginal context ημ 😀\n', '\uFEFF\uFEFFcontext']) {
    const bytes = Buffer.from(text), result = {representation, purpose: 'actionability-bom',
      inputBytes: bytes.length, assertions: 0};
    const equal = (actual, expected) => { result.assertions++; assert.deepEqual(actual, expected); };
    try {
      const source = {...nativeSource, full_input: {path: 'actionability-input.txt',
        bytes: bytes.length, sha256: sha256(bytes)}};
      const context = verifier.prepareReviewInvocationContext(encode(bytes),
        encode(Buffer.from(JSON.stringify(source))), tools, submissionFile);
      equal(context.pages, [{id: 1, start: 0, end: text.length}]);
      equal(context.pageCount, 1);
      equal(context.fullInputSha256, sha256(bytes));
      const {response, submission} = smokeInvocation(context, bytes);
      const verdict = verifier.verifyReviewInvocation(encode(response), encode(submission), context);
      equal(verdict.ok, true);
      equal(verdict.acceptedInvocation.pageCount, 1);
      equal(verifier.verifyReviewInvocation(encode(response), encode(submission),
        {...context, fullDiff: encode(bytes)}).ok, true);
      for (const duplicate of ['{"schema":"invalid",' + JSON.stringify(source).slice(1),
        '{"sch\\u0065ma":"invalid",' + JSON.stringify(source).slice(1),
        '{"purpose":"diff",' + JSON.stringify(source).slice(1),
        JSON.stringify(source).replace('"sha256":', '"sha256":"invalid","sha256":'),
        '{"ignored":{"x":1,"x":2},' + JSON.stringify(source).slice(1)]) {
        result.assertions++;
        assert.throws(() => verifier.prepareReviewInvocationContext(encode(bytes),
          encode(Buffer.from(duplicate)), tools, submissionFile));
      }
      result.pass = true;
    } catch (error) { result.pass = false; result.error = {name: error.name, message: error.message}; }
    results.push(result);
  }
}
const failures = results.filter(result => !result.pass).length;
console.log(JSON.stringify({result: failures ? 'fail' : 'pass', runtime: process.version, modulePath,
  moduleSha256: sha256(fs.readFileSync(modulePath)), results,
  pass: results.length - failures, fail: failures, native_review: false}));
process.exitCode = failures ? 1 : 0;
