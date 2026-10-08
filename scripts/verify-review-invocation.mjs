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
function smokeInvocation(context) {
  const text = full.toString('utf8');
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
const failures = results.filter(result => !result.pass).length;
console.log(JSON.stringify({result: failures ? 'fail' : 'pass', runtime: process.version, modulePath,
  moduleSha256: sha256(fs.readFileSync(modulePath)), results,
  pass: results.length - failures, fail: failures, native_review: false}));
process.exitCode = failures ? 1 : 0;
