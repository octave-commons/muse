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

// The same optimized exports carry a sorted full cohort, without semantic credit.
const cohortInput = Buffer.from(JSON.stringify({kind: 'synthetic-cohort-context',
  native: {threads: [{id: 'PRRT_fixture_A', root: 7, body: 'Member A ημ 😀'},
    {id: 'PRRT_fixture_B', root: 8, body: 'Member B full original review'},
    {id: 'PRRT_unrelated', root: 9, body: 'Unrelated context retained'}]},
  source: 'complete immutable source bytes\n'.repeat(700), tail: 'complete cohort tail ημ 😀'}));
const cohortSource = {...nativeSource, schema: 'open-hax.positive-cohort-assessment-input/v1',
  purpose: 'positive-cohort-assessment', target: {repository: 'synthetic/optimized-export-smoke',
    repository_id: 'R_fixture', repository_database_id: 3, pr_id: 'PR_fixture', pr_number: 1,
    cohort_targets: [['PRRT_fixture_A', 7], ['PRRT_fixture_B', 8]]},
  full_input: {path: 'positive-cohort-assessment-input.txt', bytes: cohortInput.length, sha256: sha256(cohortInput)}};
for (const [representation, encode] of [['string', bytes => bytes.toString('utf8')],
  ['Buffer', bytes => bytes], ['Uint8Array', bytes => new Uint8Array(bytes)]]) {
  const result = {representation, purpose: 'positive-cohort-assessment', assertions: 0};
  const equal = (actual, expected) => { result.assertions++; assert.deepEqual(actual, expected); };
  const refuses = (input, source) => { result.assertions++; assert.throws(() =>
    verifier.prepareReviewInvocationContext(input, encode(Buffer.from(JSON.stringify(source))), tools, submissionFile)); };
  try {
    const context = verifier.prepareReviewInvocationContext(encode(cohortInput),
      encode(Buffer.from(JSON.stringify(cohortSource))), tools, submissionFile);
    equal(context.inputSource, cohortSource);
    equal(context.pages.at(-1).end, cohortInput.toString('utf8').length);
    equal(context.pageCount > 1, true);
    const {response, submission} = smokeInvocation(context, cohortInput);
    const verdict = verifier.verifyReviewInvocation(encode(response), encode(submission), context);
    equal(verdict.ok, true);
    equal(verdict.acceptedInvocation.fullInputSha256, sha256(cohortInput));
    equal(verdict.acceptedInvocation.pageCount, context.pageCount);
    equal(verdict.acceptedInvocation.responseSha256, sha256(response));
    equal(verdict.acceptedInvocation.submissionSha256, sha256(submission));
    equal(Object.hasOwn(verdict, 'preservation'), false);
    equal(Object.hasOwn(verdict, 'agreement'), false);
    for (const changed of [{...cohortSource, proposal_id: 72},
      {...cohortSource, source_inventory_sha256: 'f'.repeat(64)},
      {...cohortSource, target: {...cohortSource.target, cohort_targets: [['PRRT_fixture_A', 7], ['PRRT_fixture_B', 10]]}}]) {
      const changedContext = verifier.prepareReviewInvocationContext(encode(cohortInput),
        encode(Buffer.from(JSON.stringify(changed))), tools, submissionFile);
      const refused = verifier.verifyReviewInvocation(encode(response), encode(submission), changedContext);
      equal(refused.ok, false); equal(refused.acceptedInvocation, null);
    }
    const events = response.toString('utf8').trim().split('\n').map(JSON.parse);
    const omitted = events.filter(event => !(event.part.tool === 'review_read_diff_chunk' && event.part.state.input.id === context.pageCount));
    const incomplete = verifier.verifyReviewInvocation(omitted.map(JSON.stringify).join('\n'), encode(submission), context);
    equal(incomplete.ok, false); equal(incomplete.acceptedInvocation, null);
    refuses(encode(cohortInput.subarray(0, 100)), cohortSource);
    refuses(encode(cohortInput), {...cohortSource, purpose: 'rejection-assessment'});
    refuses(encode(cohortInput), {...cohortSource, target: {...cohortSource.target, cohort_targets: [42]}});
    refuses(encode(cohortInput), {...cohortSource, target: {...cohortSource.target, thread_id: 'PRRT_fixture_A'}});
    refuses(encode(cohortInput), {...cohortSource, full_input: {...cohortSource.full_input, bytes: cohortInput.toString('utf8').length}});
    refuses(encode(cohortInput), {...cohortSource, full_input: {...cohortSource.full_input, sha256: 'f'.repeat(64)}});
    refuses(new Uint8Array([255]), cohortSource);
    result.pass = true;
  } catch (error) { result.pass = false; result.error = {name: error.name, message: error.message}; }
  results.push(result);
}
// Explicit ordinary-v2 transport through the existing native JS export, with
// independently held original metadata as well as producer coordinates. These
// synthetic fixture identities are not a native origin proof, and
// no invocation, model, provider, publisher or tool-state completion is simulated.
const v2Texts = [full.toString('utf8'), '{"gate":"pass","limitation":"synthetic only"}\r\n',
  '\uFEFFdeterministic original tail ημ 😀\r\n'];
const v2Producer = {repository: source.provenance.repository, head_sha: source.head_sha,
    base_sha: source.base_sha, diff_base_sha: source.diff_base_sha,
    workflow_path: '.github/workflows/review.yml', workflow_ref: source.provenance.workflow_ref,
    workflow_sha: source.provenance.workflow_sha, run_id: '2', run_attempt: '1', job_id: '3',
    artifact_id: '4', artifact_zip_sha256: 'e'.repeat(64)};
function v2Fixture(texts) {
  let byteStart = 0, textStart = 0;
  const components = texts.map((text, index) => {
    const bytes = Buffer.from(text), byteEnd = byteStart + bytes.length, textEnd = textStart + text.length;
    const component = {role: ['raw-diff', 'summary', 'log'][index],
      path: ['basehead.diff', 'summary.json', 'deterministic.log'][index], bytes: bytes.length,
      sha256: sha256(bytes), byte_span: [byteStart, byteEnd], text_span: [textStart, textEnd]};
    byteStart = byteEnd; textStart = textEnd; return component;
  });
  const view = Buffer.from(texts.join('')), first = components[0];
  return {view, source: {...source, schema: 'open-hax.review-input/v2', components,
    full_diff: {path: first.path, bytes: first.bytes, sha256: first.sha256},
    view: {bytes: view.length, sha256: sha256(view), text_units: textStart, page_count: 1},
    producer: {...v2Producer}}};
}
const {source: v2Source, view: v2View} = v2Fixture(v2Texts);
const v2Trusted = {producer: {...v2Producer}, input_source: structuredClone(v2Source), reviewer_run_attempt: '2',
  producer_reuse: {producer: {...v2Producer}, conclusion: 'success'},
  content: {authorization: 'public', classification: 'authorized', credential_canary: false}};
for (const [representation, encode] of [['string', bytes => bytes.toString('utf8')],
  ['Buffer', bytes => bytes], ['Uint8Array', bytes => new Uint8Array(bytes)]]) {
  const result = {representation, purpose: 'ordinary-v2', assertions: 0, native_origin: false};
  const contexts = [['missing-context', null],
    ['wrong-producer', {...v2Trusted, producer: {...v2Producer, head_sha: 'f'.repeat(40)}}],
    ['unauthorized', {...v2Trusted, content: {...v2Trusted.content, authorization: 'unauthorized'}}],
    ['unknown-authorization', {...v2Trusted, content: {...v2Trusted.content, authorization: 'unknown'}}],
    ['sensitive', {...v2Trusted, content: {...v2Trusted.content, classification: 'sensitive'}}],
    ['credential-canary', {...v2Trusted, content: {...v2Trusted.content, credential_canary: true}}]];
  for (const [control, trusted] of contexts) {
    const refusal = {representation, purpose: 'ordinary-v2-context-refusal', control,
      specialized_predecessor_enforcement: false};
    try {
      const args = [encode(v2View), encode(Buffer.from(JSON.stringify(v2Source))), tools, submissionFile];
      if (trusted !== null) args.push(trusted);
      assert.throws(() => verifier.prepareReviewInvocationContext.call(null, ...args));
      refusal.pass = true;
    } catch (error) { refusal.pass = false; refusal.error = {name: error.name, message: error.message}; }
    results.push(refusal);
  }
  try {
    const context = verifier.prepareReviewInvocationContext.call(null, encode(v2View),
      encode(Buffer.from(JSON.stringify(v2Source))), tools, submissionFile, v2Trusted);
    for (const [actual, expected] of [[context.inputSource, v2Source],
      [context.fullInputSha256, sha256(v2View)], [context.pageCount, 1],
      [context.inputSource.full_diff, source.full_diff]]) {
      result.assertions++; assert.deepEqual(actual, expected);
    }
    // Established V2 preparation still requires role/component references;
    // generic whole-view notes
    // remain refused even with a complete legacy-shaped eight-tool/five-stage trace.
    const generic = smokeInvocation(context, v2View);
    const verdict = verifier.verifyReviewInvocation(generic.response, generic.submission, context);
    result.assertions++; assert.equal(verdict.ok, false);
    result.assertions++; assert.equal(verdict.code, 'returned-page-binding');
    result.assertions++; assert.equal(verdict.acceptedInvocation, null);
    result.pass = true;
  } catch (error) { result.pass = false; result.error = {name: error.name, message: error.message}; }
  results.push(result);
  const blankRefusal = {representation, purpose: 'ordinary-v2-blank-coordinate-refusal',
    assertions: 0, native_origin: false};
  try {
    for (const field of ['repository', 'workflow_path', 'workflow_ref', 'run_id',
      'run_attempt', 'job_id', 'artifact_id']) {
      for (const blank of [' ', '\t\n']) {
        const producer = {...v2Producer, [field]: blank};
        const candidate = {...v2Source, producer};
        const caller = {...v2Trusted, producer: {...producer}, input_source: structuredClone(candidate),
          producer_reuse: {producer: {...producer}, conclusion: 'success'}};
        blankRefusal.assertions++;
        assert.throws(() => verifier.prepareReviewInvocationContext.call(null, encode(v2View),
          encode(Buffer.from(JSON.stringify(candidate))), tools, submissionFile, caller),
          `matching blank producer ${field} ${JSON.stringify(blank)}`);
      }
    }
    for (const blank of [' ', '\t\n']) {
      blankRefusal.assertions++;
      assert.throws(() => verifier.prepareReviewInvocationContext.call(null, encode(v2View),
        encode(Buffer.from(JSON.stringify(v2Source))), tools, submissionFile,
        {...v2Trusted, reviewer_run_attempt: blank}), `blank reviewer attempt ${JSON.stringify(blank)}`);
    }
    blankRefusal.pass = true;
  } catch (error) { blankRefusal.pass = false; blankRefusal.error = {name: error.name, message: error.message}; }
  results.push(blankRefusal);
  const originalRefusal = {representation, purpose: 'ordinary-v2-independent-original-identity-refusal',
    assertions: 0, native_origin: false};
  try {
    const stale = ['older raw diff\n', '{"gate":"older"}\n', 'older deterministic log\n'];
    const replacements = ['raw-diff', 'summary', 'log'].map((label, index) => {
      const texts = [...v2Texts]; texts[index] = stale[index]; return [label, texts];
    });
    replacements.push(['all originals', stale]);
    for (const [label, texts] of replacements) {
      const candidate = v2Fixture(texts);
      originalRefusal.assertions++;
      assert.ok(verifier.prepareReviewInvocationContext.call(null, encode(candidate.view),
        encode(Buffer.from(JSON.stringify(candidate.source))), tools, submissionFile,
        {...v2Trusted, input_source: structuredClone(candidate.source)}),
        `${label} replacement is coherent against its own separately held synthetic identity`);
      originalRefusal.assertions++;
      assert.throws(() => verifier.prepareReviewInvocationContext.call(null, encode(candidate.view),
        encode(Buffer.from(JSON.stringify(candidate.source))), tools, submissionFile, v2Trusted),
        `${label} replacement must refuse against the unchanged held current originals`);
    }
    const unbound = {...v2Trusted}; delete unbound.input_source;
    originalRefusal.assertions++;
    assert.throws(() => verifier.prepareReviewInvocationContext.call(null, encode(v2View),
      encode(Buffer.from(JSON.stringify(v2Source))), tools, submissionFile, unbound),
      'current coordinates without independently held input identity must refuse');
    originalRefusal.pass = true;
  } catch (error) { originalRefusal.pass = false; originalRefusal.error = {name: error.name, message: error.message}; }
  results.push(originalRefusal);
  const attempts = {representation, purpose: 'ordinary-v2-attempt-reuse-refusal',
    assertions: 0, native_origin: false};
  try {
    const prepare = (candidate, caller) => verifier.prepareReviewInvocationContext.call(null,
      encode(v2View), encode(Buffer.from(JSON.stringify(candidate))), tools, submissionFile, caller);
    const current = {...v2Trusted, reviewer_run_attempt: '1'}; delete current.producer_reuse;
    attempts.assertions++; assert.ok(prepare(v2Source, current));
    attempts.assertions++; assert.ok(prepare(v2Source, v2Trusted));
    for (const attempt of ['9', '0', '01']) {
      const producer = {...v2Producer, run_attempt: attempt};
      const candidate = {...v2Source, producer};
      const caller = {...v2Trusted, producer, input_source: structuredClone(candidate),
        reviewer_run_attempt: attempt === '9' ? '1' : '2',
        producer_reuse: {producer: {...producer}, conclusion: 'success'}};
      attempts.assertions++; assert.throws(() => prepare(candidate, caller));
    }
    const missing = {...v2Trusted}; delete missing.producer_reuse;
    for (const caller of [missing,
      {...v2Trusted, producer_reuse: {producer: {...v2Producer}, conclusion: 'failure'}},
      {...v2Trusted, producer_reuse: {conclusion: 'success'}},
      {...v2Trusted, producer_reuse: {producer: {...v2Producer, artifact_id: 'other'}, conclusion: 'success'}},
      {...v2Trusted, reviewer_run_attempt: '0'}, {...v2Trusted, reviewer_run_attempt: 'later'}]) {
      attempts.assertions++; assert.throws(() => prepare(v2Source, caller));
    }
    const claimed = {...v2Source, producer_reuse: structuredClone(v2Trusted.producer_reuse)};
    attempts.assertions++; assert.throws(() => prepare(claimed,
      {...missing, input_source: structuredClone(claimed)}));
    attempts.pass = true;
  } catch (error) { attempts.pass = false; attempts.error = {name: error.name, message: error.message}; }
  results.push(attempts);
}
for (const [representation, encode] of [['Buffer', bytes => Buffer.from(bytes)],
  ['Uint8Array', bytes => new Uint8Array(bytes)], ['SharedArrayBuffer', bytes => {
    const view = new Uint8Array(new SharedArrayBuffer(bytes.length)); view.set(bytes); return view;
  }]]) {
  const owned = {representation, purpose: 'ordinary-v2-owned-byte-boundary', assertions: 0, native_origin: false};
  const realDecode = TextDecoder.prototype.decode;
  try {
    const raw = encode(v2View); Object.defineProperty(raw, 'length', {value: 0});
    owned.assertions++; assert.ok(verifier.prepareReviewInvocationContext.call(null,
      raw, JSON.stringify(v2Source), tools, submissionFile, v2Trusted), 'valid intrinsic bytes ignore shadowed length');
    const oversized = new Uint8Array(9437185); oversized.set(v2View);
    const hostile = encode(oversized); Object.defineProperty(hostile, 'length', {value: v2View.length});
    owned.assertions++; assert.throws(() => verifier.prepareReviewInvocationContext.call(null,
      hostile, JSON.stringify(v2Source), tools, submissionFile, v2Trusted), 'intrinsic cap precedes allocation');
    const race = v2Fixture(['old\n', '{}', 'log\n']);
    const changed = Buffer.from('new\n{}log\n');
    const candidate = {...race.source, view: {...race.source.view, sha256: sha256(changed)}};
    const caller = {...v2Trusted, input_source: structuredClone(candidate)};
    const mutable = encode(race.view); let reached = false;
    TextDecoder.prototype.decode = function(bytes, options) {
      if (bytes?.byteLength === 4 && !reached) { reached = true; mutable.set(Buffer.from('new\n'), 0); }
      return realDecode.call(this, bytes, options);
    };
    owned.assertions++; assert.throws(() => verifier.prepareReviewInvocationContext.call(null,
      mutable, JSON.stringify(candidate), tools, submissionFile, caller), 'hash/decode mutation cannot admit mixed identities');
    owned.assertions++; assert.equal(reached, true, 'actual decoder interleaving reached');
    owned.assertions++; assert.equal(Buffer.from(mutable).subarray(0, 4).toString(), 'new\n');
    owned.pass = true;
  } catch (error) { owned.pass = false; owned.error = {name: error.name, message: error.message}; }
  finally { TextDecoder.prototype.decode = realDecode; }
  results.push(owned);
}
const failures = results.filter(result => !result.pass).length;
console.log(JSON.stringify({result: failures ? 'fail' : 'pass', runtime: process.version, modulePath,
  moduleSha256: sha256(fs.readFileSync(modulePath)), results,
  pass: results.length - failures, fail: failures, native_review: false}));
process.exitCode = failures ? 1 : 0;
