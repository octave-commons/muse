// SPDX-License-Identifier: GPL-3.0-or-later
// Cold component-assessment qualification using actual compiled callbacks.
// Source authentication/native custody remains caller owned; synthetic held
// context is injected only through host context, outside model arguments.
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const roles = ['raw-diff', 'summary', 'log'];
const names = ['basehead.diff', 'summary.json', 'deterministic.log'];
const stages = ['deterministic', 'map-change', 'generate-candidates', 'adversarial-validate', 'publish'];
const identityKeys = ['id', 'role', 'component-sha256', 'component-byte-span', 'component-text-span',
  'view-byte-span', 'view-text-span', 'start', 'end'];
const hash = bytes => createHash('sha256').update(bytes).digest('hex');
const clone = value => structuredClone(value);
const select = (value, keys) => Object.fromEntries(keys.map(key => [key, value[key]]));
const encodeTrace = events => Buffer.from(events.map(event => JSON.stringify(event)).join('\n') + '\n');
const geometryCache = new Map();

function preserveActual(label, input, response, submission = null, prepared = null) {
  const root = input.evidenceRoot;
  assert.ok(root && path.isAbsolute(root), 'execution must retain actual traces in an absolute evidence directory');
  const dir = path.join(root, label);
  fs.mkdirSync(dir, {recursive: true});
  fs.writeFileSync(path.join(dir, 'response.jsonl'), response);
  fs.writeFileSync(path.join(dir, 'source.json'), JSON.stringify(input.source));
  if (submission) fs.writeFileSync(path.join(dir, 'submission.json'), submission);
  if (prepared) fs.writeFileSync(path.join(dir, 'prepared-caller-context.json'), JSON.stringify(prepared));
  const files = ['response.jsonl', 'source.json', ...(submission ? ['submission.json'] : []),
    ...(prepared ? ['prepared-caller-context.json'] : [])];
  fs.writeFileSync(path.join(dir, 'custody.json'), JSON.stringify({
    scope: 'local-no-model-fixture', nativeAuthentication: false, nativeReview: false,
    files: files.map(name => {const bytes = fs.readFileSync(path.join(dir, name));
      return {name, bytes: bytes.length, sha256: hash(bytes)};})}, null, 2));
}

function ownerGeometry(repo, dir, arrays) {
  // Reuse the actual portable owner, including its surrogate/line boundaries.
  // This fixture supplies no second pager or guessed page count.
  const key = arrays.map(hash).join(':');
  if (geometryCache.has(key)) return [...geometryCache.get(key)];
  const viewPath = path.join(dir, 'owning-geometry-view.txt');
  fs.writeFileSync(viewPath, Buffer.concat(arrays));
  const files = [viewPath, ...names.map(name => path.join(dir, name))];
  const expression = `(require '[eta-mu.shape.review-input :as input] '[clojure.string :as str]) ` +
    `(println (str "[" (str/join "," (map #(input/bounded-page-count (slurp %)) ` +
    `[${files.map(file => JSON.stringify(file)).join(' ')}])) "]"))`;
  const result = spawnSync('clojure', ['-M', '-e', expression],
    {cwd: repo, encoding: 'utf8', timeout: 60000, maxBuffer: 1048576});
  assert.equal(result.status, 0, result.error?.message || result.stderr);
  assert.match(result.stdout.trim(), /^\[\d+,\d+,\d+,\d+\]$/);
  const geometry = JSON.parse(result.stdout.trim());
  geometryCache.set(key, geometry);
  return [...geometry];
}

function stageOriginals({fixture, repo, evidenceRoot}, label, rawArrays, historical = null) {
  const dir = path.join(fixture, `assessment-${label}`, '.opencode/review-evidence');
  fs.mkdirSync(dir, {recursive: true});
  const arrays = rawArrays.map(bytes => Buffer.from(bytes));
  arrays.forEach((bytes, index) => assert.ok(bytes.length <= [4194304, 262144, 4194304][index]));
  const texts = arrays.map(bytes => new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(bytes));
  names.forEach((name, index) => fs.writeFileSync(path.join(dir, name), arrays[index]));
  const geometry = ownerGeometry(repo, dir, arrays);
  let bs = 0, ts = 0;
  const components = arrays.map((bytes, index) => {
    const be = bs + bytes.length, te = ts + texts[index].length;
    const component = {role: roles[index], path: names[index], bytes: bytes.length, sha256: hash(bytes),
      byte_span: [bs, be], text_span: [ts, te]};
    bs = be; ts = te; return component;
  });
  const view = Buffer.concat(arrays);
  assert.ok(view.length <= 9437184); assert.ok(geometry[0] <= 1024);
  const producer = historical?.producer || {repository: 'synthetic/assessment-fixture',
    head_sha: 'b'.repeat(40), base_sha: 'a'.repeat(40), diff_base_sha: 'a'.repeat(40),
    workflow_path: '.github/workflows/review.yml',
    workflow_ref: 'synthetic/assessment-fixture/.github/workflows/review.yml@refs/pull/1/merge',
    workflow_sha: 'd'.repeat(40), run_id: '2', run_attempt: '1', job_id: '3', artifact_id: '4',
    artifact_zip_sha256: 'e'.repeat(64)};
  const provenance = historical?.source.provenance || {repository: producer.repository,
    pull_request: '1', run_id: producer.run_id, run_attempt: producer.run_attempt,
    workflow_sha: producer.workflow_sha, workflow_ref: producer.workflow_ref};
  const source = {...(historical?.source || {}), schema: 'open-hax.review-input/v2',
    base_sha: producer.base_sha, head_sha: producer.head_sha, diff_base_sha: producer.diff_base_sha,
    full_diff: select(components[0], ['path', 'bytes', 'sha256']), components,
    view: {bytes: view.length, sha256: hash(view), text_units: ts, page_count: geometry[0]},
    producer: clone(producer), provenance: clone(provenance)};
  // Independently held complete metadata is fixed BEFORE candidate boundary use.
  // A same-attempt synthetic audit does not fabricate successful native reuse.
  const trusted = {producer: clone(producer), input_source: clone(source),
    reviewer_run_attempt: producer.run_attempt,
    content: {authorization: 'explicit', classification: 'authorized', credential_canary: false}};
  fs.writeFileSync(path.join(dir, 'input-manifest.json'), JSON.stringify(source));
  const ctx = {sessionID: `assessment-${label}`, directory: path.dirname(path.dirname(dir)),
    reviewInputContext: trusted};
  return {dir, ctx, source, trusted, arrays, texts, view, geometry, evidenceRoot,
    file: path.join(dir, 'submission.json')};
}

function assertPage(input, page) {
  const index = roles.indexOf(page.role), component = input.source.components[index];
  assert.ok(index >= 0 && component);
  assert.equal(page['component-sha256'], component.sha256);
  const [a, b] = page['component-text-span'], [v, w] = page['view-text-span'];
  assert.equal(page.text, input.texts[index].slice(a, b));
  assert.equal(page.text, input.texts.join('').slice(v, w));
  assert.deepEqual([page.start, page.end], [v, w]);
  const localBytes = [Buffer.byteLength(input.texts[index].slice(0, a)),
    Buffer.byteLength(input.texts[index].slice(0, b))];
  assert.deepEqual(page['component-byte-span'], localBytes);
  assert.deepEqual(page['view-byte-span'], localBytes.map(n => n + component.byte_span[0]));
}

function reconstruct(input, pages) {
  assert.deepEqual(pages.map(page => page.id), pages.map((_, index) => index + 1));
  for (const page of pages) assertPage(input, page);
  const rebuilt = roles.map((role, index) => {
    const selected = pages.filter(page => page.role === role);
    assert.equal(selected.length, input.geometry[index + 1]);
    let end = 0;
    for (const page of selected) {
      assert.equal(page['component-text-span'][0], end);
      end = page['component-text-span'][1];
    }
    assert.equal(end, input.texts[index].length);
    const bytes = Buffer.from(selected.map(page => page.text).join(''));
    assert.equal(hash(bytes), input.source.components[index].sha256);
    assert.deepEqual(bytes, input.arrays[index]);
    return bytes;
  });
  assert.deepEqual(Buffer.concat(rebuilt), input.view);
  assert.equal(hash(Buffer.concat(rebuilt)), input.source.view.sha256);
  return rebuilt;
}

function assessmentFor(page) {
  // Deterministic synthetic observation construction over ACTUAL delivered text.
  // These references prove matching coverage, not native review/prose truth.
  const text = page.text;
  let quote, kind, extra = {};
  if (page.role === 'raw-diff') {
    quote = text.match(/^@@[^\r\n]*@@/m)?.[0];
    kind = quote ? 'changed-hunk' : 'no-changed-hunk';
  } else if (page.role === 'summary') {
    const field = text.match(/"(gate|status|limitation)"\s*:\s*"([^"\r\n]*)"/);
    quote = field?.[0]; kind = quote ? 'summary-field' : 'no-relevant-evidence';
    if (field) extra = {field: field[1], value: field[2]};
  } else {
    const context = text.match(/\b(result|failure|environment)\s*:\s*([^\r\n]*)/);
    quote = context?.[0]; kind = quote ? 'log-observation' : 'no-relevant-evidence';
    if (context) extra = {context: context[1], value: context[2]};
  }
  const at = quote ? text.indexOf(quote) : 0;
  const observation = {kind, 'text-span': quote ? [at, at + quote.length] : [0, text.length],
    text: quote || text, ...extra};
  if (!quote) observation.reason = 'No controlled evidence marker in this no-model payload slice; coverage reference only.';
  return {'page-ref': select(page, identityKeys), kind, observations: [observation]};
}

function traceFor(tool, input) {
  const events = []; let stopped = false;
  const evidenceRoot = input.evidenceRoot;
  assert.ok(evidenceRoot && path.isAbsolute(evidenceRoot));
  const eventDir = path.join(evidenceRoot, 'complete-actual-call-captures');
  fs.mkdirSync(eventDir, {recursive: true});
  const eventFile = path.join(eventDir, `${input.ctx.sessionID}-${process.pid}.jsonl`);
  const trace = {
    events,
    get stopped() {return stopped;},
    async call(name, args = {}) {
      assert.equal(stopped, false, 'failed/restart-required owner calls STOP this fixture invocation');
      const actualArgs = clone(args);
      const output = JSON.parse(await tool[name].execute(actualArgs, input.ctx));
      const n = events.length + 1;
      events.push({type: 'tool_use', timestamp: n, sessionID: input.ctx.sessionID,
        part: {type: 'tool', id: `assessment-part-${n}`, callID: `assessment-call-${n}`,
          sessionID: input.ctx.sessionID, tool: name,
          state: {status: 'completed', input: actualArgs, output: JSON.stringify(output)}}});
      fs.appendFileSync(eventFile, JSON.stringify(events.at(-1)) + '\n');
      if (!output['ok?'] || output['restart-required?']) {
        stopped = true;
        preserveActual(`stopped-${input.ctx.sessionID}`, input, trace.response(),
          fs.existsSync(input.file) ? fs.readFileSync(input.file) : null);
      }
      return output;
    },
    response(reason = 'stop') {
      const n = events.length + 1;
      return encodeTrace([...events, {type: 'step_finish', timestamp: n, sessionID: input.ctx.sessionID,
        part: {type: 'step-finish', id: `assessment-stop-${n}`, sessionID: input.ctx.sessionID, reason}}]);
    }
  }; return trace;
}

function preparedFor(verifier, tools, input) {
  const representations = [];
  let prepared;
  for (const [name, raw] of [['String', input.texts.join('')], ['Buffer', input.view],
    ['Uint8Array', new Uint8Array(input.view)]]) {
    const current = verifier.prepareReviewInvocationContext(raw, JSON.stringify(input.source), tools,
      input.file, input.trusted);
    assert.deepEqual(current.inputSource, input.source);
    assert.deepEqual(current.components, input.source.components);
    assert.deepEqual(current.view, input.source.view);
    assert.deepEqual(current.inputContext, input.trusted);
    assert.equal(current.pageCount, input.geometry[0]);
    assert.equal(current.fullInputSha256, hash(input.view));
    reconstruct(input, current.componentPages);
    if (prepared) assert.deepEqual(current, prepared);
    prepared = current;
    representations.push({representation: name, componentPages: current.componentPages.length,
      transportPages: current.pageCount, pass: true});
  }
  return {prepared, representations};
}

async function complete(tool, verifier, tools, input, candidate = false, rereadBeforeAssessment = false,
                        lengthStageCount = null) {
  const {prepared, representations} = preparedFor(verifier, tools, input);
  const trace = traceFor(tool, input);
  const begin = await trace.call('review_begin');
  assert.equal(begin['ok?'], true);
  assert.deepEqual(begin['input-source'], input.source);
  assert.equal(begin['input-coverage'].chunks, prepared.componentPages.length);
  const status = await trace.call('review_status');
  assert.equal(status['ok?'], true);
  const delivered = [], records = [];
  for (const expected of prepared.componentPages) {
    const read = await trace.call('review_read_diff_chunk', {id: expected.id});
    assert.equal(read['ok?'], true); assert.deepEqual(read.chunk, expected);
    if (rereadBeforeAssessment && expected.id === 1) {
      const repeated = await trace.call('review_read_diff_chunk', {id: expected.id});
      assert.equal(repeated['ok?'], true); assert.deepEqual(repeated.chunk, expected);
      assert.equal(repeated['restart-required?'], false);
    }
    delivered.push(read.chunk);
    const assessment = assessmentFor(read.chunk);
    const assessed = await trace.call('review_assess_diff_chunk', {id: expected.id,
      note: 'No-model exact delivered component reference.', assessment});
    assert.equal(assessed['ok?'], true); assert.deepEqual(assessed.assessment, assessment);
    records.push(assessment);
  }
  reconstruct(input, delivered);
  const emptyRecords = [];
  for (const component of input.source.components.filter(component => component.bytes === 0)) {
    const assessment = {kind: 'empty-component', 'component-ref': component};
    const result = await trace.call('review_assess_diff_chunk',
      {note: 'Explicit empty original identity; no invented page.', assessment});
    assert.equal(result['ok?'], true); assert.deepEqual(result.assessment, assessment);
    emptyRecords.push(assessment);
  }
  for (const stage of lengthStageCount === null ? stages : stages.slice(0, lengthStageCount)) {
    if (candidate && stage === 'adversarial-validate') {
      assert.equal((await trace.call('review_propose_finding', {id: 'synthetic-x', severity: 'low',
        category: 'test-gap', claim: 'Controlled source-location fixture.', path: 'x', line: 1,
        body: 'No-model fixture candidate; not a native finding.', confidence: 0.9, blocking: false}))['ok?'], true);
      assert.equal((await trace.call('review_classify_finding', {id: 'synthetic-x', status: 'rejected',
        rationale: 'Inert fixture candidate; no actual defect asserted.'}))['ok?'], true);
    }
    assert.equal((await trace.call('review_record_evidence',
      {stage, note: 'No-model complete role-bound qualification fixture.'}))['ok?'], true);
  }
  if (lengthStageCount !== null) {
    assert.ok(Number.isInteger(lengthStageCount) && lengthStageCount >= 1 && lengthStageCount <= 5);
    assert.equal(fs.existsSync(input.file), false);
    const response = trace.response('length');
    preserveActual(`length-${input.ctx.sessionID}`, input, response, null, prepared);
    return {trace, prepared, delivered, records, emptyRecords, representations, response};
  }
  assert.equal((await trace.call('review_submit',
    {summary: 'Inert no-model complete-input fixture; no native review/approval credit.'}))['ok?'], true);
  const submission = fs.readFileSync(input.file);
  const envelope = JSON.parse(submission);
  assert.deepEqual(envelope['input-source'], input.source);
  assert.deepEqual(envelope['input-assessments'].map(entry => entry.assessment), records);
  assert.deepEqual(envelope['empty-component-assessments'].map(entry => entry.assessment), emptyRecords);
  for (const component of input.source.components) {
    const role = envelope['input-coverage'].roles[component.role];
    const count = prepared.componentPages.filter(page => page.role === component.role).length;
    assert.deepEqual(select(role, ['bytes', 'sha256', 'chunks', 'delivered', 'assessed', 'missing', 'empty?', 'empty-observed?']),
      {bytes: component.bytes, sha256: component.sha256, chunks: count, delivered: count, assessed: count,
        missing: [], 'empty?': component.bytes === 0, 'empty-observed?': component.bytes === 0});
  }
  const verdict = verifier.verifyReviewInvocation(trace.response(), submission, prepared);
  assert.equal(verdict.ok, true);
  assert.equal(verdict.acceptedInvocation.fullInputSha256, hash(input.view));
  assert.equal(verdict.acceptedInvocation.responseSha256, hash(trace.response()));
  assert.equal(verdict.acceptedInvocation.submissionSha256, hash(submission));
  preserveActual(`complete-${input.ctx.sessionID}`, input, trace.response(), submission, prepared);
  return {trace, prepared, submission, delivered, records, emptyRecords, envelope, verdict, representations};
}

export async function runFreshAssessmentChild({tool, verifier, tools, input, scenario}) {
  if (scenario === 'failed-note-stop') {
    const {prepared} = preparedFor(verifier, tools, input), trace = traceFor(tool, input);
    assert.equal((await trace.call('review_begin'))['ok?'], true);
    assert.equal((await trace.call('review_read_diff_chunk', {id: 1}))['ok?'], true);
    assert.equal((await trace.call('review_assess_diff_chunk', {id: 1, note: 'Generic unsupported note.'}))['ok?'], false);
    assert.equal(trace.stopped, true);
    assert.equal(trace.events.length, 3, 'no review calls follow the actual failure');
    assert.equal(fs.existsSync(input.file), false);
    assert.equal(verifier.verifyReviewInvocation(trace.response(), null, prepared).ok, false);
    return {pid: process.pid, scenario, actualFailedCalls: 1, subsequentCalls: 0,
      responseSha256: hash(trace.response()), submissionPresent: false, nativeReview: false};
  }
  assert.equal(scenario, 'fresh-complete');
  const actual = await complete(tool, verifier, tools, input);
  return {pid: process.pid, scenario, actualFailedCalls: 0, accepted: actual.verdict.ok,
    responseSha256: hash(actual.trace.response()), submissionSha256: hash(actual.submission), nativeReview: false};
}

export async function runAssessmentFixtures({fixture, checkout, bundle, tool, tools, verifier, env}) {
  const evidenceRoot = process.env.MUSE_ASSESSMENT_EVIDENCE_DIR || path.join(fixture, 'assessment-evidence');
  assert.ok(path.isAbsolute(evidenceRoot), 'explicit evidence output must be absolute');
  fs.mkdirSync(evidenceRoot, {recursive: true});
  const options = {fixture, repo: checkout, evidenceRoot};
  const results = [];
  const check = async (name, operation) => {
    await operation(); results.push({control: name, pass: true});
    console.log(`PASS cold ordinary-v2 assessment ${name}`);
  };
  const coreTexts = ['diff --git a/x b/x\n--- a/x\n+++ b/x\n@@ -1 +1 @@\n-old\r\n+ημ 😀\r\n',
    '{"gate":"pass","limitation":"synthetic only"}\r\n',
    '\uFEFFresult: pass\r\n' + 'log line\r\n'.repeat(130) +
    'diff --git a/support b/support\n--- a/support\n+++ b/support\n@@ -0,0 +1 @@\n+support-only\n' +
    '[eta-mu review] diff truncated at 4 bytes (was 8).\n' + 'L'.repeat(2101) + ' 😀\n'];
  const coreArrays = coreTexts.map(text => Buffer.from(text));
  const base = stageOriginals(options, 'healthy-eight-tools', coreArrays);
  let healthy;
  await check('complete-eight-tools-five-stages-exact-submission', async () => {
    healthy = await complete(tool, verifier, tools, base, true);
    const began = JSON.parse(healthy.trace.events[0].part.state.output);
    assert.deepEqual(began['diff-stats'], {files: 1, bytes: base.texts[0].length, 'truncated?': false});
    assert.deepEqual([...new Set(healthy.trace.events.map(event => event.part.tool))].sort(), [...tools].sort());
    assert.deepEqual(healthy.trace.events.filter(event => event.part.tool === 'review_record_evidence')
      .map(event => event.part.state.input.stage), stages);
    assert.ok(base.texts[2].split('\n').some(line => line.length > 2000));
    assert.ok(base.texts[2].startsWith('\uFEFF')); assert.ok(base.texts[0].includes('\r\n'));
    assert.deepEqual(healthy.envelope.comments, []);
  });
  await check('serialized-or-cloned-context-alone-does-not-prove-local-preparation', async () => {
    // Reached ONLY after real compiled callbacks, exact submission, and the
    // actual prepared caller object have healthy public admission above.
    // The same response/submission are retained; only the context carrier
    // changes. No fullDiff byte revalidation or native authentication is added.
    assert.equal(healthy.verdict.ok, true);
    assert.equal(Object.hasOwn(healthy.prepared, 'fullDiff'), false);
    const comparisons = [['JSONString', JSON.stringify(healthy.prepared)],
      ['UTF8Uint8Array', new Uint8Array(Buffer.from(JSON.stringify(healthy.prepared)))],
      ['structuredClone', clone(healthy.prepared)]].map(([representation, context]) => {
      const verdict = verifier.verifyReviewInvocation(healthy.trace.response(), healthy.submission, context);
      return {representation, fullDiffPresent: false, verdict};
    });
    const record = {kind: 'healthy-bound-serialized-context-comparison',
      healthyPreparedObjectAccepted: true, sameResponseSha256: hash(healthy.trace.response()),
      sameSubmissionSha256: hash(healthy.submission), comparisons,
      nativeAuthenticationClaim: false, sourceAdoption: false, qualificationPending: true};
    fs.writeFileSync(path.join(evidenceRoot,
      'actual-healthy-serialized-context-comparison.json'), JSON.stringify(record, null, 2));
    console.log(JSON.stringify(record));
    // Capture both actual comparisons before asserting; a failed healthy
    // neighbor never executes or earns specialized refusal credit here.
    for (const comparison of comparisons) {
      assert.equal(comparison.verdict.ok, false, comparison.representation);
      assert.equal(comparison.verdict.acceptedInvocation, null, comparison.representation);
    }
  });
  await check('actual-prepared-object-held-snapshot-binds-protected-fields', async () => {
    const fresh = () => verifier.prepareReviewInvocationContext(base.view, JSON.stringify(base.source),
      tools, base.file, base.trusted);
    const comparisons = [];
    for (const [field, mutate] of [
      ['reviewTools-coherent-addition', context => {context.reviewTools.push('read');}],
      ['inputContext-coherent-authorization', context => {context.inputContext.content.authorization = 'public';}],
      ['componentPages-payload', context => {
        context.componentPages[0].text = 'X' + context.componentPages[0].text.slice(1);
      }]
    ]) {
      const context = fresh();
      const before = verifier.verifyReviewInvocation(healthy.trace.response(), healthy.submission, context);
      assert.equal(before.ok, true, `${field} starts with its own healthy registered object`);
      mutate(context);
      const after = verifier.verifyReviewInvocation(healthy.trace.response(), healthy.submission, context);
      comparisons.push({field, freshPreparedObjectInitiallyAccepted: before.ok, verdict: after});
    }
    fs.writeFileSync(path.join(evidenceRoot,
      'actual-registered-object-held-snapshot-comparison.json'), JSON.stringify(comparisons, null, 2));
    for (const comparison of comparisons) {
      assert.equal(comparison.verdict.ok, false, comparison.field);
      assert.equal(comparison.verdict.acceptedInvocation, null, comparison.field);
    }
    const withSession = fresh(); withSession.sessionID = base.ctx.sessionID;
    assert.equal(verifier.verifyReviewInvocation(healthy.trace.response(), healthy.submission, withSession).ok, true);
    const withBytes = fresh(); withBytes.fullDiff = Buffer.from(base.view);
    assert.equal(verifier.verifyReviewInvocation(healthy.trace.response(), healthy.submission, withBytes).ok, true);
    const withSessionAndBytes = fresh(); withSessionAndBytes.sessionID = base.ctx.sessionID;
    withSessionAndBytes.fullDiff = new Uint8Array(base.view);
    assert.equal(verifier.verifyReviewInvocation(healthy.trace.response(), healthy.submission, withSessionAndBytes).ok, true);
    const wrongBytes = fresh(); wrongBytes.fullDiff = Buffer.from(base.view); wrongBytes.fullDiff[0] ^= 1;
    const wrongBytesResult = verifier.verifyReviewInvocation(healthy.trace.response(), healthy.submission, wrongBytes);
    assert.equal(wrongBytesResult.ok, false); assert.equal(wrongBytesResult.acceptedInvocation, null);
  });
  await check('component-surrogate-boundary-and-original-crlf-bom-neighbor', async () => {
    const input = stageOriginals(options, 'surrogate-boundary', [Buffer.from('x'.repeat(8191) + '😀tail\r\n'),
      Buffer.from('{"gate":"pass"}\r\n'), Buffer.from('\uFEFFresult: pass\r\n')]);
    const actual = await complete(tool, verifier, tools, input);
    const rawPages = actual.delivered.filter(page => page.role === 'raw-diff');
    assert.equal(rawPages.length, 2); assert.equal(rawPages[0]['component-text-span'][1], 8191);
    assert.ok(rawPages[1].text.startsWith('😀')); assert.ok(input.texts[2].startsWith('\uFEFF'));
  });
  for (const omission of ['summary', 'log', 'assessment', 'log-tail']) {
    await check(`actual-${omission}-omission-stops-before-stage`, async () => {
      const input = stageOriginals(options, `omit-${omission}`, coreArrays);
      const {prepared} = preparedFor(verifier, tools, input), trace = traceFor(tool, input);
      assert.equal((await trace.call('review_begin'))['ok?'], true);
      const lastLogId = prepared.componentPages.filter(page => page.role === 'log').at(-1).id;
      assert.ok(prepared.componentPages.filter(page => page.role === 'log').length > 1);
      for (const expected of prepared.componentPages) {
        if ((omission === 'summary' && expected.role === 'summary') ||
            (omission === 'log' && expected.role === 'log') ||
            (omission === 'log-tail' && expected.id === lastLogId)) continue;
        const read = await trace.call('review_read_diff_chunk', {id: expected.id});
        assert.equal(read['ok?'], true); assert.deepEqual(read.chunk, expected);
        if (omission === 'assessment' && expected.role === 'summary') continue;
        assert.equal((await trace.call('review_assess_diff_chunk', {id: expected.id,
          note: 'Actual retained partial-input reference.', assessment: assessmentFor(read.chunk)}))['ok?'], true);
      }
      assert.equal((await trace.call('review_record_evidence',
        {stage: 'deterministic', note: 'Required input is incomplete.'}))['ok?'], false);
      assert.equal(trace.stopped, true); assert.equal(fs.existsSync(input.file), false);
      assert.equal(verifier.verifyReviewInvocation(trace.response(), null, prepared).ok, false);
    });
  }
  const mismatchCases = [
    ['role', record => {record['page-ref'].role = 'log';}],
    ['digest', record => {record['page-ref']['component-sha256'] = 'f'.repeat(64);}],
    ['span', record => {record['page-ref']['component-text-span'][0] += 1;}],
    ['observation-span', record => {record.observations[0]['text-span'][0] += 1;}],
    ['observation-payload', record => {record.observations[0].text = 'replacement';}],
    ['inner-kind', record => {record.observations[0].kind = 'log-observation';}]
  ];
  for (const [name, mutate] of mismatchCases) {
    await check(`actual-reference-${name}-refusal`, async () => {
      const input = stageOriginals(options, `mismatch-${name}`, coreArrays);
      const {prepared} = preparedFor(verifier, tools, input), trace = traceFor(tool, input);
      assert.equal((await trace.call('review_begin'))['ok?'], true);
      const read = await trace.call('review_read_diff_chunk', {id: 1});
      assert.equal(read['ok?'], true); const record = assessmentFor(read.chunk); mutate(record);
      assert.equal((await trace.call('review_assess_diff_chunk',
        {id: 1, note: 'Actual deliberately mismatched reference.', assessment: record}))['ok?'], false);
      assert.equal(trace.stopped, true); assert.equal(fs.existsSync(input.file), false);
      assert.equal(verifier.verifyReviewInvocation(trace.response(), null, prepared).ok, false);
    });
  }
  for (const [name, role, field, value] of [['summary-field', 'summary', 'field', 'unobserved'],
    ['summary-value', 'summary', 'value', 'failure'], ['log-context', 'log', 'context', 'unobserved'],
    ['log-value', 'log', 'value', 'failure']]) {
    await check(`actual-${name}-refusal-with-unchanged-quote`, async () => {
      const input = stageOriginals(options, `inner-${name}`, coreArrays);
      const {prepared} = preparedFor(verifier, tools, input), trace = traceFor(tool, input);
      assert.equal((await trace.call('review_begin'))['ok?'], true);
      const expected = prepared.componentPages.find(page => page.role === role);
      const read = await trace.call('review_read_diff_chunk', {id: expected.id});
      assert.equal(read['ok?'], true); assert.deepEqual(read.chunk, expected);
      const record = assessmentFor(read.chunk), heldQuote = record.observations[0].text;
      record.observations[0][field] = value; assert.equal(record.observations[0].text, heldQuote);
      assert.equal((await trace.call('review_assess_diff_chunk', {id: expected.id,
        note: 'Deliberate semantic reference mismatch.', assessment: record}))['ok?'], false);
      assert.equal(trace.stopped, true);
    });
  }
  await check('generic-note-does-not-assess-v2-page', async () => {
    const input = stageOriginals(options, 'generic-note', coreArrays), trace = traceFor(tool, input);
    assert.equal((await trace.call('review_begin'))['ok?'], true);
    assert.equal((await trace.call('review_read_diff_chunk', {id: 1}))['ok?'], true);
    assert.equal((await trace.call('review_assess_diff_chunk', {id: 1, note: 'Generic note.'}))['ok?'], false);
    assert.equal(trace.stopped, true);
  });
  for (const prefix of [0, 1, 2]) {
    await check(`empty-${prefix}-role-prefix-refusal`, async () => {
      const input = stageOriginals(options, `empty-prefix-${prefix}`, [Buffer.alloc(0), Buffer.alloc(0), Buffer.alloc(0)]);
      const {prepared} = preparedFor(verifier, tools, input), trace = traceFor(tool, input);
      const begin = await trace.call('review_begin'); assert.equal(begin['ok?'], true);
      assert.equal(begin['input-coverage'].chunks, 0);
      for (const component of input.source.components.slice(0, prefix)) {
        const assessment = {kind: 'empty-component', 'component-ref': component};
        const actual = await trace.call('review_assess_diff_chunk', {note: 'Actual empty role.', assessment});
        assert.equal(actual['ok?'], true); assert.deepEqual(actual.assessment, assessment);
      }
      assert.equal((await trace.call('review_record_evidence',
        {stage: 'deterministic', note: 'Missing explicit empty roles.'}))['ok?'], false);
      assert.equal(verifier.verifyReviewInvocation(trace.response(), null, prepared).ok, false);
    });
  }
  for (const [name, arrays] of [['all-empty', [Buffer.alloc(0), Buffer.alloc(0), Buffer.alloc(0)]],
    ['mixed-empty', [Buffer.alloc(0), Buffer.from('{"gate":"pass"}'), Buffer.alloc(0)]]]) {
    await check(`${name}-complete-explicit-identity`, async () => {
      const input = stageOriginals(options, name, arrays), actual = await complete(tool, verifier, tools, input);
      assert.equal(actual.emptyRecords.length, input.source.components.filter(component => !component.bytes).length);
    });
  }
  for (const mode of ['wrong', 'replayed']) {
    await check(`empty-${mode}-identity-cannot-cover-other-roles`, async () => {
      const input = stageOriginals(options, `empty-${mode}`, [Buffer.alloc(0), Buffer.alloc(0), Buffer.alloc(0)]);
      const trace = traceFor(tool, input); assert.equal((await trace.call('review_begin'))['ok?'], true);
      const raw = input.source.components[0];
      if (mode === 'wrong') {
        const wrong = {...raw, role: 'unknown'};
        assert.equal((await trace.call('review_assess_diff_chunk', {note: 'Wrong empty role.',
          assessment: {kind: 'empty-component', 'component-ref': wrong}}))['ok?'], false);
      } else {
        for (let n = 0; n < 2; n++) {
          const result = await trace.call('review_assess_diff_chunk', {note: 'Only raw-diff empty role.',
            assessment: {kind: 'empty-component', 'component-ref': raw}});
          if (!result['ok?']) break;
        }
        if (!trace.stopped) assert.equal((await trace.call('review_record_evidence',
          {stage: 'deterministic', note: 'Summary and log remain unobserved.'}))['ok?'], false);
      }
      assert.equal(trace.stopped, true); assert.equal(fs.existsSync(input.file), false);
    });
  }
  await check('model-serialized-context-does-not-replace-host-held-context', async () => {
    const input = stageOriginals(options, 'missing-host-context', coreArrays);
    delete input.ctx.reviewInputContext;
    const trace = traceFor(tool, input);
    const result = await trace.call('review_begin', {reviewInputContext: clone(input.trusted)});
    assert.equal(result['ok?'], false); assert.equal(trace.stopped, true);
    assert.equal(fs.existsSync(input.file), false);
  });
  await check('wrong-host-held-originals-refuse-before-admission', async () => {
    const input = stageOriginals(options, 'wrong-host-originals', coreArrays);
    input.ctx.reviewInputContext.input_source.components[2].sha256 = 'f'.repeat(64);
    const trace = traceFor(tool, input);
    assert.equal((await trace.call('review_begin'))['ok?'], false);
    assert.equal(trace.stopped, true); assert.equal(fs.existsSync(input.file), false);
  });
  await check('support-only-source-location-refuses', async () => {
    const input = stageOriginals(options, 'support-source', coreArrays), trace = traceFor(tool, input);
    assert.equal((await trace.call('review_begin'))['ok?'], true);
    assert.equal((await trace.call('review_propose_finding', {id: 'support-only', severity: 'low', category: 'contract',
      claim: 'Inert support fixture.', path: 'support', line: 1, body: 'Support is not changed source.',
      confidence: 0.9, blocking: false}))['ok?'], false);
    assert.equal(trace.stopped, true);
  });
  await check('serialized-trace-and-submission-reference-mutations-refuse', async () => {
    const events = healthy.trace.events;
    const readAt = events.findIndex(event => event.part.tool === 'review_read_diff_chunk');
    const assessAt = events.findIndex(event => event.part.tool === 'review_assess_diff_chunk');
    const terminal = JSON.parse(healthy.trace.response().toString().trim().split('\n').at(-1));
    const envelope = JSON.parse(healthy.submission);
    const mutations = [
      [clone(events), clone(envelope)], [clone(events), clone(envelope)], [clone(events), clone(envelope)]
    ];
    const readOutput = JSON.parse(mutations[0][0][readAt].part.state.output); readOutput.chunk.text += 'replacement';
    mutations[0][0][readAt].part.state.output = JSON.stringify(readOutput);
    mutations[1][0][assessAt].part.state.input.assessment['page-ref'].role = 'log';
    mutations[2][1]['input-assessments'][0].assessment['page-ref'].role = 'log';
    for (const [trace, submission] of mutations) {
      const result = verifier.verifyReviewInvocation(encodeTrace([...trace, terminal]),
        Buffer.from(JSON.stringify(submission)), healthy.prepared);
      assert.equal(result.ok, false); assert.equal(result.acceptedInvocation, null);
    }
    const forgedContext = clone(healthy.prepared); forgedContext.componentPages[0]['component-sha256'] = 'f'.repeat(64);
    assert.equal(verifier.verifyReviewInvocation(healthy.trace.response(), healthy.submission, forgedContext).ok, false);
  });
  await check('post-assessment-reread-is-irreversible-and-removes-submission', async () => {
    const result = await healthy.trace.call('review_read_diff_chunk', {id: 1});
    assert.equal(result['ok?'], true); assert.equal(result['restart-required?'], true);
    assert.equal(healthy.trace.stopped, true); assert.equal(fs.existsSync(base.file), false);
    assert.equal(verifier.verifyReviewInvocation(healthy.trace.response(), healthy.submission, healthy.prepared).ok, false);
  });
  await check('second-begin-does-not-reset-an-admitted-session', async () => {
    const input = stageOriginals(options, 'second-begin', coreArrays), trace = traceFor(tool, input);
    assert.equal((await trace.call('review_begin'))['ok?'], true);
    assert.equal((await trace.call('review_begin'))['ok?'], false);
    assert.equal(trace.stopped, true); assert.equal(fs.existsSync(input.file), false);
  });
  await check('actual-last-read-before-assessment-neighbor-remains-valid', async () => {
    const input = stageOriginals(options, 'read-before-assess', coreArrays);
    await complete(tool, verifier, tools, input, false, true);
  });
  await check('combined-component-page-ceiling-with-admitted-aggregate-neighbor', async () => {
    for (const [lines, count] of [[130816, 1024], [130817, 1025], [131070, 1026]]) {
      const input = stageOriginals(options, `combined-cap-${count}`,
        [Buffer.from('\n'), Buffer.from('\n'), Buffer.from('\n'.repeat(lines))]);
      assert.ok(input.source.view.page_count <= 1024);
      assert.equal(input.geometry.slice(1).reduce((a, b) => a + b), count);
      for (const raw of [input.view, new Uint8Array(input.view), input.texts.join('')]) {
        if (count === 1024) {
          const prepared = verifier.prepareReviewInvocationContext(raw, JSON.stringify(input.source),
            tools, input.file, input.trusted);
          assert.equal(prepared.componentPages.length, count);
        } else {
          assert.throws(() => verifier.prepareReviewInvocationContext(raw, JSON.stringify(input.source),
            tools, input.file, input.trusted), /invalid full-input context/);
        }
      }
    }
  });
  await check('actual-length-prefix-complete-roles-empties-and-held-custody', async () => {
    const results = [];
    for (const [label, arrays, stageCount] of [['nonempty-one', coreArrays, 1],
      ['nonempty-five', coreArrays, 5], ['mixed-empty', [Buffer.alloc(0), Buffer.from('{"gate":"pass"}'), Buffer.alloc(0)], 1],
      ['all-empty', [Buffer.alloc(0), Buffer.alloc(0), Buffer.alloc(0)], 1]]) {
      const input = stageOriginals(options, `length-${label}`, arrays);
      const actual = await complete(tool, verifier, tools, input, false, false, stageCount);
      assert.equal(fs.existsSync(input.file), false);
      assert.equal(verifier.verifyReviewInvocation(actual.response, null, actual.prepared).ok, false);
      for (const [representation, carrier] of [['String', input.texts.join('')],
        ['Buffer', Buffer.from(input.view)], ['Uint8Array', new Uint8Array(input.view)]]) {
        const context = verifier.prepareReviewInvocationContext(input.view, JSON.stringify(input.source),
          tools, input.file, input.trusted);
        context.fullDiff = carrier;
        const custody = {invocationState: 'completed', exitCode: 0, submissionState: 'missing',
          responseSha256Before: hash(actual.response), responseSha256After: hash(actual.response),
          contextBefore: context, contextAfter: context};
        const verdict = verifier.classifyLengthEndedReview(actual.response, null, context, custody);
        assert.equal(verdict.eligible, true);
        assert.equal(verdict.code, 'new-complete-invocation-required');
        assert.equal(verdict.pageCount, actual.delivered.length);
        assert.deepEqual(verdict.recordedStages, stages.slice(0, stageCount));
        assert.equal(Object.hasOwn(verdict, 'acceptedInvocation'), false);
        for (const candidate of [JSON.stringify(context), new Uint8Array(Buffer.from(JSON.stringify(context))), clone(context)]) {
          assert.equal(verifier.classifyLengthEndedReview(actual.response, null, candidate, custody).eligible, false);
        }
        for (const changed of [{...custody, contextBefore: clone(context)},
          {...custody, contextAfter: clone(context)}, {...custody, responseSha256After: 'f'.repeat(64)},
          {...custody, exitCode: 1}, JSON.stringify(custody)]) {
          assert.equal(verifier.classifyLengthEndedReview(actual.response, null, context, changed).eligible, false);
        }
        for (const role of roles) {
          const partial = actual.trace.events.filter(event => {
            const args = event.part.state.input, output = JSON.parse(event.part.state.output);
            return output.chunk?.role !== role && args.assessment?.['page-ref']?.role !== role &&
              args.assessment?.['component-ref']?.role !== role;
          });
          const n = partial.length + 1;
          const response = encodeTrace([...partial, {type: 'step_finish', timestamp: actual.trace.events.length + 1,
            sessionID: input.ctx.sessionID, part: {type: 'step-finish', id: `partial-length-${n}`,
              sessionID: input.ctx.sessionID, reason: 'length'}}]);
          const partialCustody = {...custody, responseSha256Before: hash(response), responseSha256After: hash(response)};
          assert.equal(verifier.classifyLengthEndedReview(response, null, context, partialCustody).eligible, false);
        }
        if (representation !== 'String' && carrier.length) {
          carrier[0] ^= 1;
          assert.equal(verifier.classifyLengthEndedReview(actual.response, null, context, custody).eligible, false);
        }
        results.push({label, representation, eligible: true, componentPages: verdict.pageCount,
          recordedStages: verdict.recordedStages, responseSha256: hash(actual.response),
          submissionPresent: false, nativeAuthentication: false, externalMAX2PolicyQualified: false});
      }
    }
    fs.writeFileSync(path.join(evidenceRoot, 'actual-component-length-recovery.json'), JSON.stringify(results, null, 2));
    console.log(JSON.stringify({kind: 'local-actual-component-length-prefixes', results, nativeReview: false}));
  });
  await check('failed-call-stop-and-bounded-two-fresh-processes', async () => {
    const input = stageOriginals(options, 'two-processes', coreArrays);
    const settings = path.join(path.dirname(path.dirname(input.dir)), 'caller-held-context.json');
    fs.writeFileSync(settings, JSON.stringify({ctx: input.ctx, source: input.source,
      trusted: input.trusted, geometry: input.geometry, file: input.file, dir: input.dir, evidenceRoot}));
    const settingsHash = hash(fs.readFileSync(settings));
    const childScript = fileURLToPath(new URL('./assessment-fresh-child.mjs', import.meta.url));
    const children = [];
    const launch = scenario => {
      // This private no-model fixture preserves the existing host-owned MAX2
      // allowance. It does not implement or qualify the external host policy.
      assert.ok(children.length < 2, 'no third fixture invocation can be launched');
      const result = spawnSync(process.execPath, [childScript, bundle,
        path.join(checkout, '.opencode/dist/review-invocation.cjs'), settings, scenario],
        {env, encoding: 'utf8', timeout: 60000, maxBuffer: 8 * 1024 * 1024});
      process.stdout.write(result.stdout || ''); process.stderr.write(result.stderr || '');
      assert.equal(result.status, 0, result.error?.message || result.stderr);
      const actual = JSON.parse(result.stdout.trim().split('\n').at(-1));
      assert.notEqual(actual.pid, process.pid); children.push(actual); return actual;
    };
    const failed = launch('failed-note-stop'), recovered = launch('fresh-complete');
    assert.equal(failed.actualFailedCalls, 1); assert.equal(failed.subsequentCalls, 0);
    assert.equal(recovered.accepted, true); assert.notEqual(failed.pid, recovered.pid);
    assert.throws(() => launch('not-launched-third-attempt'), /no third fixture invocation/);
    assert.equal(children.length, 2); assert.equal(hash(fs.readFileSync(settings)), settingsHash);
    console.log(JSON.stringify({kind: 'local-fresh-process-stop-control', attempts: children,
      maximumFixtureAttempts: 2, thirdProcessLaunched: false, externalHostPolicyQualified: false}));
  });

  const retained = process.env.MUSE_RETAINED_REVIEW_INPUT_DIR;
  let retainedAuditStatus = 'unavailable-unqualified';
  const expectedSizes = [1274799, 1043, 782474];
  const expectedHashes = ['2b95569066d824726b5771a7f6b796dc9aa7acf07680014dd7bc4f816dc90f9c',
    'c40d2fa8df569a70d03c3e009198eada4a21191a262e9f4e197a1793ccf213f5',
    'cc604a171b9747d32330f54811d80ad18e6703d3c234a47d96f836e48210a61a'];
  if (retained) {
    assert.ok(path.isAbsolute(retained), 'retained fixture selection must be an explicit absolute directory');
    await check('retained-real-2058316-original-bytes-per-role-actual-tool-reconstruction', async () => {
    const retainedArrays = names.map((name, index) => {
      const file = path.join(retained, name);
      assert.equal(fs.statSync(file).size, expectedSizes[index]);
      assert.ok(expectedSizes[index] <= [4194304, 262144, 4194304][index]);
      const bytes = fs.readFileSync(file); assert.equal(hash(bytes), expectedHashes[index]); return bytes;
    });
    assert.ok(retainedArrays[2].length > 50000);
    const old = JSON.parse(fs.readFileSync(path.join(retained, 'input-manifest.json')));
    const producer = {repository: old.provenance.repository, head_sha: old.head_sha, base_sha: old.base_sha,
      diff_base_sha: old.diff_base_sha, workflow_path: '.github/workflows/opencode-code-review.yml',
      workflow_ref: old.provenance.workflow_ref, workflow_sha: old.provenance.workflow_sha,
      run_id: '38490599695', run_attempt: '2', job_id: '115648942312', artifact_id: '11866214766',
      artifact_zip_sha256: '0f722f27a2e98332f9cf2e622fae9581066c8b2744421a936eb01b679dcad8e8'};
    const input = stageOriginals(options, 'retained-real', retainedArrays, {source: old, producer});
    const actual = await complete(tool, verifier, tools, input);
    assert.equal(input.view.length, 2058316);
    const maxLines = input.texts.map(text => text.split('\n').reduce((n, line) => Math.max(n, line.length), 0));
    assert.ok(maxLines.some(length => length > 2000));
    assert.deepEqual(input.source.full_diff, old.full_diff); assert.deepEqual(input.source.preview, old.preview);
    const componentGeometry = roles.map((role, index) => ({role, bytes: retainedArrays[index].length,
      sha256: hash(retainedArrays[index]), pages: actual.delivered.filter(page => page.role === role).length}));
    console.log(JSON.stringify({result: 'pass', kind: 'retained-real-component-assessment-local-audit',
      custody: retained, components: componentGeometry, view: input.source.view,
      representations: actual.representations, returnedComponentPages: actual.delivered.length,
      originalMaxLineUTF16Units: maxLines, originalLeadingBOM: input.texts.map(text => text.startsWith('\uFEFF')),
      originalCRLF: input.texts.map(text => text.includes('\r\n')), boundaryNeighborsQualifiedSeparately: true,
      responseSha256: hash(actual.trace.response()), submissionSha256: hash(actual.submission),
      currentModuleSha256: hash(fs.readFileSync(path.join(checkout, '.opencode/dist/review-invocation.cjs'))),
      callerContextSynthetic: true, syntheticSameAttempt: true, successfulNativeReuseClaim: false,
      historicalAvailabilityChecked: false, nativeProducerAuthenticated: false, models: 0,
      providerRequests: 0, nativeReview: false, sourceAdoption: false, approvalCredit: false}));
    });
    retainedAuditStatus = 'passed-local-byte-fixture-only';
  } else {
    console.log(JSON.stringify({kind: 'retained-real-component-assessment-local-audit',
      status: retainedAuditStatus, executed: false, qualified: false,
      reason: 'MUSE_RETAINED_REVIEW_INPUT_DIR is absent; no archive was selected or silently substituted.',
      syntheticControlsStillExecuted: true, nativeReview: false, sourceAdoption: false}));
  }
  console.log(JSON.stringify({result: 'pass', kind: 'cold-eight-tool-ordinary-v2-assessment-fixture',
    controls: results, controlCount: results.length, syntheticControlsPassed: true,
    retainedAuditStatus, evidenceRoot, bundleSha256: hash(fs.readFileSync(bundle)),
    nativeAuthentication: false, nativeReview: false, sourceAdoption: false, approvalCredit: false}));
  return results;
}
