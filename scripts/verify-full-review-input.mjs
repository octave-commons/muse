// SPDX-License-Identifier: GPL-3.0-or-later
// Exercise the actual compiled review tools from cold source, without a model or publication.
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import {createHash} from 'node:crypto';
import fs from 'node:fs';
import {createRequire, syncBuiltinESMExports} from 'node:module';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath, pathToFileURL} from 'node:url';

Error.stackTraceLimit = 50;

// A recovery is a different process, never just a new host session id.
if (process.argv[2] === '--fresh-invocation') {
  const [bundle, evidence, scenario] = process.argv.slice(3);
  const {EtaMuActorsPlugin} = await import(pathToFileURL(bundle));
  const {tool} = await EtaMuActorsPlugin({});
  const ctx = {sessionID: 'fresh-child', directory: path.dirname(path.dirname(evidence))};
  const call = async (name, args) => JSON.parse(await tool[name].execute(args, ctx));
  const begin = await call('review_begin', {});
  assert.equal(begin['ok?'], true);
  const count = begin['input-coverage'].chunks;
  assert.equal((await call('review_read_diff_chunk', {id: 1}))['ok?'], true);
  assert.equal((await call('review_read_diff_chunk', {id: 1}))['ok?'], true,
    'rereads before assessment remain permitted');
  for (let id = 1; id <= count; id++) {
    if (id !== 1) assert.equal((await call('review_read_diff_chunk', {id}))['ok?'], true);
    assert.equal((await call('review_assess_diff_chunk',
      {id, note: `Synthetic changed-hunk assessment of page ${id}`}))['ok?'], true);
  }
  for (const stage of ['deterministic', 'map-change', 'generate-candidates', 'adversarial-validate', 'publish']) {
    assert.equal((await call('review_record_evidence', {stage, note: 'Complete fresh child fixture'}))['ok?'], true);
  }
  assert.equal((await call('review_submit', {summary: 'Complete synthetic child; no native review'}))['ok?'], true);
  const submission = path.join(evidence, 'submission.json');
  const envelope = JSON.parse(fs.readFileSync(submission, 'utf8'));
  assert.equal(envelope.event, 'APPROVE');
  assert.equal(envelope['input-coverage'].assessed, count);
  if (scenario === 'cleanup') {
    assert.equal((await call('review_read_diff_chunk', {id: 1}))['restart-required?'], true);
    assert.equal(fs.existsSync(submission), false);
    assert.equal((await call('review_begin', {}))['ok?'], false);
    assert.equal((await call('review_submit', {summary: 'No stale reuse'}))['ok?'], false);
  }
  if (scenario === 'cleanup-fault') {
    const unlink = fs.unlinkSync;
    fs.unlinkSync = filename => {
      if (filename === submission) throw Object.assign(new Error('Synthetic owned unlink failure'), {code: 'EACCES'});
      return unlink(filename);
    };
    syncBuiltinESMExports();
    try { await assert.rejects(() => call('review_read_diff_chunk', {id: 1}), /Synthetic owned unlink failure/); }
    finally { fs.unlinkSync = unlink; syncBuiltinESMExports(); }
    assert.equal(fs.existsSync(submission), true, 'retain the actual surviving artifact in this fault fixture');
    assert.equal((await call('review_status', {}))['restart-required?'], true);
    assert.equal((await call('review_submit', {summary: 'Surviving file grants no new submission'}))['ok?'], false);
  }
  await new Promise((resolve, reject) => {
    process.stdout.write(`${JSON.stringify({result: 'pass', pid: process.pid, scenario, envelope,
      surviving_artifact: fs.existsSync(submission)})}\n`, error => error ? reject(error) : resolve());
  });
  process.exit(0);
}

const repo = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const fixture = fs.mkdtempSync(path.join(os.tmpdir(), 'muse-full-review-'));
const tools = ['review_begin', 'review_read_diff_chunk', 'review_assess_diff_chunk',
  'review_record_evidence', 'review_propose_finding', 'review_classify_finding', 'review_status', 'review_submit'];
try {
  const checkout = path.join(fixture, 'checkout');
  const inventory = spawnSync('git', ['ls-files', '-z'], {cwd: repo, encoding: 'utf8'});
  assert.equal(inventory.status, 0, inventory.stderr);
  const sourceFiles = new Set(inventory.stdout.split('\0').filter(Boolean));
  // This exact authored dependency must be copied before root stages it.
  const strictJsonSource = 'src/cljs/eta_mu/extern/json.cljs';
  if (fs.existsSync(path.join(repo, strictJsonSource))) sourceFiles.add(strictJsonSource);
  for (const file of sourceFiles) {
    if (/^(?:src\/gen\/|target\/|\.shadow-cljs\/|\.claude\/dist\/|\.opencode\/dist\/|\.mcp\/dist\/)/.test(file)) continue;
    const source = path.join(repo, file), destination = path.join(checkout, file);
    const stat = fs.lstatSync(source);
    if (stat.isDirectory()) continue;
    fs.mkdirSync(path.dirname(destination), {recursive: true});
    if (stat.isSymbolicLink()) fs.symlinkSync(fs.readlinkSync(source), destination);
    else fs.copyFileSync(source, destination);
  }
  fs.symlinkSync(path.join(repo, 'node_modules'), path.join(checkout, 'node_modules'), 'dir');
  fs.writeFileSync(path.join(checkout, '.ημ/config/opencode/root.edn'), `
{:eta-mu/opencode-version 1 :id :eta-mu/full-review-verification
 :imports ["../shared/profiles.edn" "../shared/plugins/review-pipeline.edn" "permissions/full-review-test.edn"]
 :profile :dev :build ["shadow-cljs" "release" "opencode-plugin"]}
`);
  fs.mkdirSync(path.join(checkout, '.ημ/config/opencode/permissions'), {recursive: true});
  fs.writeFileSync(path.join(checkout, '.ημ/config/opencode/permissions/full-review-test.edn'),
    `{:permissions [{:id :review/full-input-test :applies #{${tools.map(name => `:${name}`).join(' ')}} :policy :allow}]}`);
  const java = spawnSync('java', ['-XshowSettings:properties', '-version'], {encoding: 'utf8'});
  assert.equal(java.status, 0, java.stderr);
  const javaHome = java.stderr.match(/^\s*user\.home = (.+)$/m)?.[1];
  assert.ok(javaHome);
  const compilerHome = path.join(fixture, 'compiler-home');
  fs.mkdirSync(compilerHome);
  if (fs.existsSync(path.join(javaHome, '.m2'))) fs.symlinkSync(path.join(javaHome, '.m2'), path.join(compilerHome, '.m2'), 'dir');
  const env = {...process.env, PATH: `${path.join(checkout, 'node_modules/.bin')}${path.delimiter}${process.env.PATH || ''}`,
    JAVA_TOOL_OPTIONS: `${process.env.JAVA_TOOL_OPTIONS || ''} -Duser.home="${compilerHome}"`};
  for (const [command, args] of [
    ['clojure', ['-M', '-e', "(require 'eta-mu.opencode.build) (eta-mu.opencode.build/generate-entrypoint {})"]],
    ['shadow-cljs', ['release', 'opencode-plugin', 'review-invocation']],
  ]) {
    const result = spawnSync(command, args, {cwd: checkout, env, encoding: 'utf8', timeout: 120000, maxBuffer: 8 * 1024 * 1024});
    process.stdout.write(result.stdout || '');
    process.stderr.write(result.stderr || '');
    assert.equal(result.status, 0, result.error?.message || result.stderr);
  }
  const bundle = path.join(checkout, '.opencode/dist/eta-mu-actors.js');
  const {EtaMuActorsPlugin} = await import(pathToFileURL(bundle));
  const {tool} = await EtaMuActorsPlugin({});
  assert.deepEqual(Object.keys(tool).sort(), [...tools].sort());
  const config = JSON.parse(fs.readFileSync(path.join(checkout, '.opencode/opencode.json'), 'utf8'));
  assert.deepEqual(Object.keys(config.permission).sort(), [...tools].sort());
  assert.ok(Object.values(config.permission).every(value => value === 'allow'));
  const call = async (name, args, ctx) => {
    try { return JSON.parse(await tool[name].execute(args, ctx)); }
    catch (error) { error.message = `${name} at ${ctx?.directory}: ${error.message}`; throw error; }
  };
  const evidence = path.join(fixture, 'consumer/.opencode/review-evidence');
  fs.mkdirSync(evidence, {recursive: true});
  const ctx = {sessionID: 'full-input-verification', directory: path.dirname(path.dirname(evidence))};
  const full = Buffer.from('diff --git a/large b/large\n--- a/large\n+++ b/large\n@@ -0,0 +1,26001 @@\n' +
    '+changed hunk\n'.repeat(26000) + '+tail ημ 😀\n');
  const manifest = {schema: 'open-hax.review-input/v1', base_sha: 'a'.repeat(40),
    diff_base_sha: 'a'.repeat(40), head_sha: 'b'.repeat(40), full_diff: {
      path: 'basehead.diff', bytes: full.length, sha256: createHash('sha256').update(full).digest('hex'),
    }};
  const input = path.join(evidence, 'basehead.diff');
  fs.writeFileSync(input, full.subarray(0, 300000));
  fs.writeFileSync(path.join(evidence, 'input-manifest.json'), JSON.stringify(manifest));
  fs.writeFileSync(path.join(evidence, 'pr.diff'), full.subarray(0, 300000));
  assert.equal((await call('review_begin', {}, ctx))['ok?'], false, 'missing tail must reject begin');
  assert.equal((await call('review_submit', {summary: 'No partial approval'}, ctx))['ok?'], false);
  fs.writeFileSync(input, full);
  const begin = await call('review_begin', {}, ctx);
  assert.equal(begin['ok?'], true);
  assert.deepEqual(begin['input-source'], manifest);
  const count = begin['input-coverage'].chunks;
  assert.ok(count > 1);
  const prefix = await call('review_read_diff_chunk', {id: 1}, ctx);
  await call('review_assess_diff_chunk', {id: 1, note: 'Synthetic assessment of the delivered first page'}, ctx);
  const premature = await call('review_record_evidence',
    {stage: 'deterministic', note: 'Synthetic attempt before tail assessment'}, ctx);
  assert.equal(premature['ok?'], false, 'unassessed tail must keep the first stage open');
  assert.equal((await call('review_status', {}, ctx)).stage, 'deterministic');
  assert.equal((await call('review_submit', {summary: 'Prefix alone is insufficient'}, ctx))['ok?'], false);
  let recovered = prefix.chunk.text;
  for (let id = 2; id <= count; id++) {
    const page = await call('review_read_diff_chunk', {id}, ctx);
    assert.equal(page['ok?'], true);
    assert.ok(page.chunk.text.length <= 8192);
    recovered += page.chunk.text;
    assert.equal((await call('review_assess_diff_chunk', {id, note: `Synthetic changed-hunk assessment of page ${id}`}, ctx))['ok?'], true);
  }
  assert.equal(recovered, full.toString('utf8'), 'all reader pages must preserve the omitted Unicode tail');
  for (const stage of ['deterministic', 'map-change', 'generate-candidates']) {
    assert.equal((await call('review_record_evidence', {stage, note: 'Synthetic stage evidence after complete input'}, ctx))['ok?'], true);
  }
  assert.equal((await call('review_propose_finding', {id: 'tail', severity: 'high',
    category: 'semantic-regression', claim: 'Synthetic recovered-tail candidate', path: 'large', line: 26001,
    body: 'Synthetic blocking fixture in the recovered Unicode tail', confidence: 0.95, blocking: true}, ctx))['ok?'], true);
  assert.equal((await call('review_classify_finding',
    {id: 'tail', status: 'confirmed', rationale: 'Synthetic recovered-tail fixture verified'}, ctx))['ok?'], true);
  assert.equal((await call('review_record_evidence',
    {stage: 'adversarial-validate', note: 'All pages assessed; tail finding classified'}, ctx))['ok?'], true);
  const tailSubmission = await call('review_submit', {summary: 'Complete synthetic review retains the tail finding'}, ctx);
  assert.equal(tailSubmission['ok?'], true);
  assert.equal(tailSubmission.event, 'REQUEST_CHANGES');
  const tailEnvelope = JSON.parse(fs.readFileSync(path.join(evidence, 'submission.json'), 'utf8'));
  assert.deepEqual(tailEnvelope.comments.map(comment => comment.line), [26001]);
  assert.equal((await call('review_propose_finding', {id: 'late', severity: 'low', category: 'test-gap',
    claim: 'Late synthetic candidate', path: 'large', line: 26001, body: 'Must remain forbidden at publish',
    confidence: 0.9, blocking: false}, ctx))['ok?'], false);
  // An admitted invocation cannot discard its healthy prior assessment history.
  const refusedRestart = await call('review_begin', {}, ctx);
  assert.equal(refusedRestart['ok?'], false);
  assert.equal(refusedRestart['restart-required?'], true);
  assert.equal((await call('review_status', {}, ctx))['restart-required?'], true);
  assert.equal((await call('review_submit', {summary: 'No reuse after readmission'}, ctx))['ok?'], false);
  assert.equal(fs.existsSync(path.join(evidence, 'submission.json')), false);
  const switched = {...ctx, sessionID: 'different-host-session'};
  assert.equal((await call('review_begin', {}, switched))['ok?'], false,
    'host session id cannot erase this process and evidence directory history');
  assert.equal((await call('review_submit', {summary: 'No host-id bypass'}, switched))['ok?'], false);

  const stageInput = name => {
    const destination = path.join(fixture, name, '.opencode/review-evidence');
    fs.mkdirSync(destination, {recursive: true});
    fs.writeFileSync(path.join(destination, 'basehead.diff'), full);
    fs.writeFileSync(path.join(destination, 'input-manifest.json'), JSON.stringify(manifest));
    return destination;
  };
  const freshProcess = (destination, scenario) => {
    const result = spawnSync(process.execPath, [fileURLToPath(import.meta.url), '--fresh-invocation',
      bundle, destination, scenario], {encoding: 'utf8', timeout: 60000, maxBuffer: 8 * 1024 * 1024});
    assert.equal(result.status, 0, result.error?.message || result.stderr);
    const payload = JSON.parse(result.stdout.trim().split('\n').at(-1));
    assert.notEqual(payload.pid, process.pid, 'a fresh invocation is an actual separate process');
    return payload;
  };
  const clean = freshProcess(stageInput('clean-consumer'), 'approval');
  const envelope = clean.envelope;
  assert.deepEqual(envelope['input-source'], manifest);
  assert.equal(envelope['input-assessments'].length, count);
  assert.ok(envelope['input-assessments'].every(page => page.note.includes('changed-hunk assessment')));
  createRequire(import.meta.url)(path.join(checkout, '.ημ/review/publish-opencode-review.cjs'))
    .validateEnvelope(envelope, new Map());

  // A stale chronology is an invocation failure, not a new assessment generation.
  const staleEvidence = stageInput('stale-consumer');
  const staleCtx = {sessionID: 'stale-assessment-verification',
    directory: path.dirname(path.dirname(staleEvidence))};
  assert.equal((await call('review_begin', {}, staleCtx))['ok?'], true);
  assert.equal((await call('review_read_diff_chunk', {id: 1}, staleCtx))['ok?'], true);
  assert.equal((await call('review_assess_diff_chunk',
    {id: 1, note: 'Synthetic assessment before a later reread'}, staleCtx))['ok?'], true);
  const reread = await call('review_read_diff_chunk', {id: 1}, staleCtx);
  assert.equal(reread['ok?'], true, 'the reread remains an actual delivery');
  assert.equal(reread.chunk.text, prefix.chunk.text);
  assert.equal(reread['restart-required?'], true, 'earlier assessment cannot follow the last read');
  const stale = await call('review_status', {}, staleCtx);
  assert.equal(stale['restart-required?'], true);
  assert.deepEqual(stale['invalidated-chunks'], [1]);
  assert.equal(stale['input-coverage'].assessed, 0);
  assert.equal((await call('review_assess_diff_chunk',
    {id: 1, note: 'Synthetic reassessment cannot erase an earlier call'}, staleCtx))['ok?'], false);
  assert.equal((await call('review_begin', {}, {...staleCtx, sessionID: 'host-id-bypass'}))['ok?'], false);
  assert.equal((await call('review_status', {}, staleCtx))['restart-required?'], true);
  assert.equal((await call('review_submit', {summary: 'Stale input must not approve'}, staleCtx))['ok?'], false);
  assert.equal(fs.existsSync(path.join(staleEvidence, 'submission.json')), false);

  // Reuse of the same evidence directory succeeds only in an actual fresh process.
  const recoveredChild = freshProcess(staleEvidence, 'cleanup');
  assert.equal(recoveredChild.surviving_artifact, false);
  const faultChild = freshProcess(stageInput('cleanup-fault-consumer'), 'cleanup-fault');
  assert.equal(faultChild.surviving_artifact, true,
    'filesystem cleanup failure is a receiver-verification obligation, never assumed absent');

  // Native context uses the same eight tools; the envelope remains an inert local carrier.
  const nativeEvidence = path.join(fixture, 'native-consumer/.opencode/review-evidence');
  fs.mkdirSync(nativeEvidence, {recursive: true});
  const nativeCtx = {sessionID: 'native-input-verification', directory: path.dirname(path.dirname(nativeEvidence))};
  const nativeData = {kind: 'synthetic-native-context',
    native: {body: 'Original explanatory conversation ημ 😀\n> quoted body', proposal: 'Inert fixture'},
    source: 'exact selected source bytes\n'.repeat(12000), tail: 'complete original tail ημ 😀'};
  const nativeFull = Buffer.from(JSON.stringify(nativeData));
  const nativeManifest = {schema: 'open-hax.actionability-input/v1', purpose: 'actionability',
    head_sha: 'b'.repeat(40), context_sha256: 'c'.repeat(64), proposal_id: 71,
    proposal_body_sha256: 'd'.repeat(64), source_inventory_sha256: 'e'.repeat(64),
    target: {repository_id: 'R_fixture', repository_database_id: 3, pr_id: 'PR_fixture',
      pr_number: 1, thread_id: 'PRRT_fixture', root_comment_id: 7},
    full_input: {path: 'actionability-input.txt', bytes: nativeFull.length,
      sha256: createHash('sha256').update(nativeFull).digest('hex')},
    provenance: {repository: 'synthetic/native-context', pull_request: '1', run_id: '2', run_attempt: '1',
      workflow_sha: 'd'.repeat(40), workflow_ref: 'synthetic/native-context/workflow@refs/heads/main'}};
  const boundaryResults = [];
  const strictManifest = JSON.stringify(nativeManifest);
  const ambiguous = ['{"schema":"invalid",' + strictManifest.slice(1),
    '{"sch\\u0065ma":"invalid",' + strictManifest.slice(1),
    '{"schema":"open-hax.actionability-input/v1",' + strictManifest.slice(1),
    '{"purpose":"diff",' + strictManifest.slice(1),
    strictManifest.replace('"context_sha256":', '"context_sha256":"invalid","context_sha256":'),
    strictManifest.replace('"sha256":', '"sha256":"invalid","sha256":'),
    '{"ignored":{"x":1,"x":2},' + strictManifest.slice(1),
    '{"ignored":1e309,' + strictManifest.slice(1)];
  const inputConsumer = (name, bytes, sourceText) => {
    const dir = path.join(fixture, name, '.opencode/review-evidence');
    fs.mkdirSync(dir, {recursive: true});
    fs.writeFileSync(path.join(dir, 'actionability-input.txt'), bytes);
    fs.writeFileSync(path.join(dir, 'input-manifest.json'), sourceText);
    return {dir, context: {sessionID: name, directory: path.dirname(path.dirname(dir))}};
  };
  for (const [index, sourceText] of ambiguous.entries()) {
    const {dir, context} = inputConsumer(`ambiguous-native-${index}`, nativeFull, sourceText);
    const result = {control: 'ambiguous-native-manifest', index};
    fs.writeFileSync(path.join(dir, 'submission.json'), 'stale inert submission');
    try {
      assert.equal((await call('review_begin', {}, context))['ok?'], false);
      assert.equal((await call('review_status', {}, context))['ok?'], false,
        'malformed manifest cannot store a session');
      assert.equal(fs.existsSync(path.join(dir, 'submission.json')), false);
      assert.equal(fs.existsSync(path.join(dir, 'review-events.jsonl')), false);
      result.pass = true;
    } catch (error) { result.pass = false; result.error = {name: error.name, message: error.message}; }
    boundaryResults.push(result);
  }
  const verifier = createRequire(import.meta.url)(path.join(checkout, '.opencode/dist/review-invocation.cjs'));
  for (const [index, text] of ['\uFEFF', '\uFEFForiginal context ημ 😀\n', '\uFEFF\uFEFFcontext'].entries()) {
    const bytes = Buffer.from(text);
    const source = {...nativeManifest, full_input: {path: 'actionability-input.txt',
      bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex')}};
    const {dir, context} = inputConsumer(`bom-native-${index}`, bytes, JSON.stringify(source));
    const result = {control: 'bom-native-lossless-pages', index, inputBytes: bytes.length};
    try {
      const begin = await call('review_begin', {}, context);
      assert.equal(begin['ok?'], true);
      assert.equal(begin['input-coverage'].chunks, 1);
      const page = await call('review_read_diff_chunk', {id: 1}, context);
      assert.equal(page['ok?'], true);
      assert.equal(page.chunk.text, text);
      const prepared = verifier.prepareReviewInvocationContext(bytes, Buffer.from(JSON.stringify(source)),
        tools, path.join(dir, 'submission.json'));
      assert.deepEqual(prepared.pages, [{id: page.chunk.id, start: page.chunk.start, end: page.chunk.end}]);
      assert.equal((await call('review_assess_diff_chunk', {id: 1, note: 'Assess the preserved BOM and original full text'}, context))['ok?'], true);
      for (const stage of ['deterministic', 'map-change', 'generate-candidates', 'adversarial-validate', 'publish']) {
        assert.equal((await call('review_record_evidence', {stage, note: 'Complete inert BOM byte-preservation control'}, context))['ok?'], true);
      }
      assert.equal((await call('review_submit', {summary: 'Inert complete BOM transport'}, context))['ok?'], true);
      const envelope = JSON.parse(fs.readFileSync(path.join(dir, 'submission.json')));
      assert.deepEqual(envelope['input-source'], source);
      assert.equal(envelope['input-coverage'].assessed, 1);
      result.pass = true;
    } catch (error) { result.pass = false; result.error = {name: error.name, message: error.message}; }
    boundaryResults.push(result);
  }
  // Deliberate decoder-loss fault proves native geometry is checked before session custody.
  const lostText = 'synthetic positive-byte decoder-loss control';
  const lostBytes = Buffer.from(lostText);
  const lostSource = {...nativeManifest, full_input: {path: 'actionability-input.txt', bytes: lostBytes.length,
    sha256: createHash('sha256').update(lostBytes).digest('hex')}};
  const lost = inputConsumer('native-decoder-loss', lostBytes, JSON.stringify(lostSource));
  const decoder = globalThis.TextDecoder;
  const lossResult = {control: 'native-nonempty-geometry-before-admission'};
  globalThis.TextDecoder = class extends decoder {
    decode(...args) { const text = super.decode(...args); return text === lostText ? '' : text; }
  };
  try {
    assert.equal((await call('review_begin', {}, lost.context))['ok?'], false);
    assert.equal((await call('review_status', {}, lost.context))['ok?'], false);
    assert.equal(fs.existsSync(path.join(lost.dir, 'submission.json')), false);
    assert.equal(fs.existsSync(path.join(lost.dir, 'review-events.jsonl')), false);
    lossResult.pass = true;
  } catch (error) { lossResult.pass = false; lossResult.error = {name: error.name, message: error.message}; }
  finally { globalThis.TextDecoder = decoder; }
  boundaryResults.push(lossResult);
  const emptyEvidence = path.join(fixture, 'ordinary-empty-consumer/.opencode/review-evidence');
  fs.mkdirSync(emptyEvidence, {recursive: true});
  const emptySource = {...manifest, full_diff: {path: 'basehead.diff', bytes: 0,
    sha256: createHash('sha256').update('').digest('hex')}};
  fs.writeFileSync(path.join(emptyEvidence, 'basehead.diff'), '');
  fs.writeFileSync(path.join(emptyEvidence, 'input-manifest.json'), JSON.stringify(emptySource));
  const emptyCtx = {sessionID: 'ordinary-empty-preserved', directory: path.dirname(path.dirname(emptyEvidence))};
  const emptyResult = {control: 'ordinary-empty-diff-admission-preserved'};
  try {
    const begin = await call('review_begin', {}, emptyCtx);
    assert.equal(begin['ok?'], true);
    assert.equal(begin['input-coverage'].chunks, 0);
    for (const stage of ['deterministic', 'map-change', 'generate-candidates', 'adversarial-validate', 'publish']) {
      assert.equal((await call('review_record_evidence', {stage, note: 'Supported empty ordinary diff'}, emptyCtx))['ok?'], true);
    }
    assert.equal((await call('review_submit', {summary: 'Inert empty diff compatibility'}, emptyCtx))['ok?'], true);
    assert.deepEqual(JSON.parse(fs.readFileSync(path.join(emptyEvidence, 'submission.json')))['input-source'], emptySource);
    emptyResult.pass = true;
  } catch (error) { emptyResult.pass = false; emptyResult.error = {name: error.name, message: error.message}; }
  boundaryResults.push(emptyResult);
  console.log(JSON.stringify({strict_manifest_bom_admission_controls: boundaryResults, native_review: false}));
  assert.equal(boundaryResults.filter(result => !result.pass).length, 0,
    'every actual compiled filesystem/admission/BOM control must pass');
  const nativeInput = path.join(nativeEvidence, 'actionability-input.txt');
  const nativeManifestPath = path.join(nativeEvidence, 'input-manifest.json');
  fs.writeFileSync(path.join(nativeEvidence, 'basehead.diff'), nativeFull);
  fs.writeFileSync(nativeInput, nativeFull.subarray(0, 300000));
  fs.writeFileSync(nativeManifestPath, JSON.stringify(nativeManifest));
  assert.equal((await call('review_begin', {}, nativeCtx))['ok?'], false, 'native prefix cannot fall back to complete diff file');
  fs.writeFileSync(nativeInput, nativeFull);
  fs.writeFileSync(nativeManifestPath, JSON.stringify({...nativeManifest, full_diff: manifest.full_diff}));
  assert.equal((await call('review_begin', {}, nativeCtx))['ok?'], false, 'mixed purposes refuse');
  fs.writeFileSync(nativeManifestPath, JSON.stringify(nativeManifest));
  const nativeBegin = await call('review_begin', {}, nativeCtx);
  assert.equal(nativeBegin['ok?'], true);
  assert.deepEqual(nativeBegin['input-source'], nativeManifest);
  const nativeCount = nativeBegin['input-coverage'].chunks;
  assert.ok(nativeCount > 1);
  assert.equal((await call('review_record_evidence',
    {stage: 'deterministic', note: 'No native pages assessed yet'}, nativeCtx))['ok?'], false);
  assert.equal((await call('review_submit', {summary: 'No unassessed context'}, nativeCtx))['ok?'], false);
  let nativeRecovered = '';
  for (let id = 1; id <= nativeCount; id++) {
    const page = await call('review_read_diff_chunk', {id}, nativeCtx);
    assert.equal(page['ok?'], true);
    nativeRecovered += page.chunk.text;
    assert.equal((await call('review_assess_diff_chunk', {id,
      note: `Synthetic native context and source assessment of page ${id}`}, nativeCtx))['ok?'], true);
  }
  assert.equal(nativeRecovered, nativeFull.toString('utf8'));
  assert.deepEqual(JSON.parse(nativeRecovered), nativeData, 'encoded data preserves every original decoded body');
  for (const stage of ['deterministic', 'map-change', 'generate-candidates', 'adversarial-validate', 'publish']) {
    assert.equal((await call('review_record_evidence', {stage,
      note: 'Complete synthetic native transport fixture; no policy judgment'}, nativeCtx))['ok?'], true);
  }
  const nativeSummary = 'Synthetic native assessment carrier ημ 😀\nNo native eligibility or publication.';
  const nativeSubmit = await call('review_submit', {summary: nativeSummary}, nativeCtx);
  assert.equal(nativeSubmit['ok?'], true);
  const nativeEnvelope = JSON.parse(fs.readFileSync(path.join(nativeEvidence, 'submission.json'), 'utf8'));
  assert.equal(nativeEnvelope.summary, nativeSummary);
  assert.deepEqual(nativeEnvelope['input-source'], nativeManifest);
  assert.equal(nativeEnvelope['input-assessments'].length, nativeCount);
  assert.equal(nativeEnvelope['input-coverage'].assessed, nativeCount);
  assert.deepEqual(nativeEnvelope.comments, []);

  // Distinct rejection input reuses the same fresh compiled tools and verifier.
  // This is an inert carrier control, never an independent native agreement.
  const rejectionData = {...nativeData, kind: 'synthetic-native-rejection-context',
    native: {body: 'Original finding conversation ημ 😀\n> quoted evidence',
      proposal: 'Inert current-head writer proposal; no native provenance'}};
  const rejectionFull = Buffer.from(JSON.stringify(rejectionData));
  const rejectionManifest = {...nativeManifest, schema: 'open-hax.rejection-assessment-input/v1',
    purpose: 'rejection-assessment', full_input: {path: 'rejection-assessment-input.txt',
      bytes: rejectionFull.length, sha256: createHash('sha256').update(rejectionFull).digest('hex')}};
  const rejectionResults = [];
  const rejectionConsumer = (name, bytes, sourceText) => {
    const dir = path.join(fixture, name, '.opencode/review-evidence');
    fs.mkdirSync(dir, {recursive: true});
    fs.writeFileSync(path.join(dir, 'rejection-assessment-input.txt'), bytes);
    fs.writeFileSync(path.join(dir, 'input-manifest.json'), sourceText);
    return {dir, context: {sessionID: name, directory: path.dirname(path.dirname(dir))}};
  };
  const rejectionCheck = async (control, run) => {
    const result = {control};
    try { await run(); result.pass = true; }
    catch (error) { result.pass = false; result.error = {name: error.name, message: error.message}; }
    rejectionResults.push(result);
  };
  await rejectionCheck('rejection-complete-pages-and-accepted-invocation', async () => {
    const {dir, context} = rejectionConsumer('rejection-complete', rejectionFull, JSON.stringify(rejectionManifest));
    const calls = [];
    const recorded = async (name, args) => {
      const output = await call(name, args, context), index = calls.length + 1;
      calls.push({type: 'tool_use', timestamp: index, sessionID: context.sessionID,
        part: {type: 'tool', id: `rejection-part-${index}`, callID: `rejection-call-${index}`,
          sessionID: context.sessionID, tool: name,
          state: {status: 'completed', input: args, output: JSON.stringify(output)}}});
      assert.equal(output['ok?'], true);
      return output;
    };
    const begin = await recorded('review_begin', {}), pages = begin['input-coverage'].chunks;
    assert.deepEqual(begin['input-source'], rejectionManifest);
    assert.ok(pages > 1);
    let recovered = '';
    for (let id = 1; id <= pages; id++) {
      const page = await recorded('review_read_diff_chunk', {id});
      recovered += page.chunk.text;
      await recorded('review_assess_diff_chunk', {id, note: `Inert whole rejection input page ${id}`});
    }
    assert.equal(recovered, rejectionFull.toString('utf8'));
    assert.deepEqual(JSON.parse(recovered), rejectionData);
    for (const stage of ['deterministic', 'map-change', 'generate-candidates', 'adversarial-validate', 'publish']) {
      await recorded('review_record_evidence', {stage, note: 'Inert full-input carrier; no native policy judgment'});
    }
    await recorded('review_submit', {summary: 'Inert rejection transport; no native agreement or review approval'});
    const stop = calls.length + 1;
    calls.push({type: 'step_finish', timestamp: stop, sessionID: context.sessionID,
      part: {type: 'step-finish', id: `rejection-stop-${stop}`, sessionID: context.sessionID, reason: 'stop'}});
    const response = Buffer.from(calls.map(event => JSON.stringify(event)).join('\n') + '\n');
    const submission = fs.readFileSync(path.join(dir, 'submission.json'));
    const envelope = JSON.parse(submission);
    assert.deepEqual(envelope['input-source'], rejectionManifest);
    assert.deepEqual(envelope.comments, []);
    const prepared = verifier.prepareReviewInvocationContext(rejectionFull, Buffer.from(JSON.stringify(rejectionManifest)),
      tools, path.join(dir, 'submission.json'));
    const result = verifier.verifyReviewInvocation(response, submission, prepared);
    assert.equal(result.ok, true);
    assert.equal(result.acceptedInvocation.pageCount, pages);
    assert.equal(result.acceptedInvocation.fullInputSha256, rejectionManifest.full_input.sha256);
    assert.equal(result.acceptedInvocation.responseSha256, createHash('sha256').update(response).digest('hex'));
    assert.equal(result.acceptedInvocation.submissionSha256, createHash('sha256').update(submission).digest('hex'));
    for (const changed of [
      {...rejectionManifest, proposal_id: 72},
      {...rejectionManifest, context_sha256: 'f'.repeat(64)},
      {...rejectionManifest, proposal_body_sha256: 'f'.repeat(64)},
      {...rejectionManifest, source_inventory_sha256: 'f'.repeat(64)},
      {...rejectionManifest, target: {...rejectionManifest.target, root_comment_id: 8}},
    ]) {
      const changedContext = verifier.prepareReviewInvocationContext(rejectionFull, Buffer.from(JSON.stringify(changed)),
        tools, path.join(dir, 'submission.json'));
      const refused = verifier.verifyReviewInvocation(response, submission, changedContext);
      assert.equal(refused.ok, false);
      assert.equal(refused.acceptedInvocation, null);
    }
    const forged = verifier.verifyReviewInvocation(Buffer.from(JSON.stringify({type: 'text', timestamp: 1,
      sessionID: context.sessionID, part: {type: 'text', id: 'nested-only', sessionID: context.sessionID,
        text: response.toString('utf8')}})), submission, prepared);
    assert.equal(forged.ok, false);
    assert.equal(forged.acceptedInvocation, null);
  });
  const rejectedSources = [
    {...rejectionManifest, purpose: 'actionability'},
    {...rejectionManifest, schema: 'open-hax.actionability-input/v1'},
    {...rejectionManifest, full_diff: manifest.full_diff},
    {...rejectionManifest, full_input: {...rejectionManifest.full_input, path: 'actionability-input.txt'}},
    {...rejectionManifest, full_input: {...rejectionManifest.full_input, path: '../rejection-assessment-input.txt'}},
    {...rejectionManifest, proposal_id: 0},
  ];
  const rejectionJson = JSON.stringify(rejectionManifest);
  const rejectedTexts = rejectedSources.map(source => JSON.stringify(source)).concat([
    '{"schema":"invalid",' + rejectionJson.slice(1),
    '{"sch\\u0065ma":"invalid",' + rejectionJson.slice(1),
    '{"ignored":{"x":1,"x":2},' + rejectionJson.slice(1),
    '\uFEFF' + rejectionJson,
  ]);
  for (const [index, sourceText] of rejectedTexts.entries()) {
    await rejectionCheck(`rejection-malformed-manifest-${index}`, async () => {
      const {dir, context} = rejectionConsumer(`rejection-malformed-${index}`, rejectionFull, sourceText);
      fs.writeFileSync(path.join(dir, 'submission.json'), 'inert stale carrier');
      assert.equal((await call('review_begin', {}, context))['ok?'], false);
      assert.equal((await call('review_status', {}, context))['ok?'], false);
      assert.equal(fs.existsSync(path.join(dir, 'submission.json')), false);
      assert.equal(fs.existsSync(path.join(dir, 'review-events.jsonl')), false);
    });
  }
  await rejectionCheck('rejection-prefix-cannot-read-other-purpose-fallback', async () => {
    const {dir, context} = rejectionConsumer('rejection-prefix', rejectionFull.subarray(0, 300000), JSON.stringify(rejectionManifest));
    fs.writeFileSync(path.join(dir, 'basehead.diff'), rejectionFull);
    fs.writeFileSync(path.join(dir, 'actionability-input.txt'), rejectionFull);
    assert.equal((await call('review_begin', {}, context))['ok?'], false);
    assert.equal((await call('review_status', {}, context))['ok?'], false);
  });
  for (const [index, text] of ['\uFEFF', '\uFEFForiginal proposal ημ 😀\n', '\uFEFF\uFEFFcontext'].entries()) {
    await rejectionCheck(`rejection-bom-lossless-${index}`, async () => {
      const bytes = Buffer.from(text), source = {...rejectionManifest, full_input: {
        path: 'rejection-assessment-input.txt', bytes: bytes.length,
        sha256: createHash('sha256').update(bytes).digest('hex')}};
      const {dir, context} = rejectionConsumer(`rejection-bom-${index}`, bytes, JSON.stringify(source));
      assert.equal((await call('review_begin', {}, context))['ok?'], true);
      const page = await call('review_read_diff_chunk', {id: 1}, context);
      assert.equal(page['ok?'], true);
      assert.equal(page.chunk.text, text);
      const prepared = verifier.prepareReviewInvocationContext(bytes, Buffer.from(JSON.stringify(source)), tools, path.join(dir, 'submission.json'));
      assert.deepEqual(prepared.pages, [{id: 1, start: 0, end: text.length}]);
    });
  }
  await rejectionCheck('rejection-decoder-loss-refuses-before-session', async () => {
    const text = 'synthetic rejection positive-byte decoder-loss control', bytes = Buffer.from(text);
    const source = {...rejectionManifest, full_input: {path: 'rejection-assessment-input.txt',
      bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex')}};
    const {dir, context} = rejectionConsumer('rejection-decoder-loss', bytes, JSON.stringify(source));
    const originalDecoder = globalThis.TextDecoder;
    globalThis.TextDecoder = class extends originalDecoder {
      decode(...args) { const decoded = super.decode(...args); return decoded === text ? '' : decoded; }
    };
    try {
      assert.equal((await call('review_begin', {}, context))['ok?'], false);
      assert.equal((await call('review_status', {}, context))['ok?'], false);
      assert.equal(fs.existsSync(path.join(dir, 'review-events.jsonl')), false);
    } finally { globalThis.TextDecoder = originalDecoder; }
  });
  console.log(JSON.stringify({rejection_input_controls: rejectionResults, native_agreement: false, native_review: false}));
  assert.equal(rejectionResults.filter(result => !result.pass).length, 0,
    'every fresh compiled rejection carrier control must pass');


  // Cohort transport is an inert full-input carrier, never native preservation.
  const cohortData = {...nativeData, kind: 'synthetic-positive-cohort-context',
    native: {threads: [{thread: 'PRRT_fixture_A', root: 7, body: 'Original member A ημ 😀'},
      {thread: 'PRRT_fixture_B', root: 8, body: 'Original member B and its full enclosing review'},
      {thread: 'PRRT_unrelated', root: 9, body: 'Nonmember corpus must remain complete'}],
      proposal: 'Inert single actual-proposal placeholder; no native provenance'}};
  const cohortFull = Buffer.from(JSON.stringify(cohortData));
  const cohortManifest = {...nativeManifest, schema: 'open-hax.positive-cohort-assessment-input/v1',
    purpose: 'positive-cohort-assessment', target: {repository: 'synthetic/native-context',
      repository_id: 'R_fixture', repository_database_id: 3, pr_id: 'PR_fixture', pr_number: 1,
      cohort_targets: [['PRRT_fixture_A', 7], ['PRRT_fixture_B', 8]]},
    full_input: {path: 'positive-cohort-assessment-input.txt', bytes: cohortFull.length,
      sha256: createHash('sha256').update(cohortFull).digest('hex')}};
  const cohortResults = [];
  const cohortConsumer = (name, bytes, sourceText) => {
    const dir = path.join(fixture, name, '.opencode/review-evidence');
    fs.mkdirSync(dir, {recursive: true});
    fs.writeFileSync(path.join(dir, 'positive-cohort-assessment-input.txt'), bytes);
    fs.writeFileSync(path.join(dir, 'input-manifest.json'), sourceText);
    return {dir, context: {sessionID: name, directory: path.dirname(path.dirname(dir))}};
  };
  const cohortCheck = async (control, run) => {
    const result = {control};
    try { await run(); result.pass = true; }
    catch (error) { result.pass = false; result.error = {name: error.name, message: error.message}; }
    cohortResults.push(result);
  };
  await cohortCheck('cohort-complete-actual-tools-and-accepted-invocation', async () => {
    const {dir, context} = cohortConsumer('cohort-complete', cohortFull, JSON.stringify(cohortManifest));
    const calls = [];
    const recorded = async (name, args) => {
      const output = await call(name, args, context), index = calls.length + 1;
      calls.push({type: 'tool_use', timestamp: index, sessionID: context.sessionID,
        part: {type: 'tool', id: `cohort-part-${index}`, callID: `cohort-call-${index}`,
          sessionID: context.sessionID, tool: name,
          state: {status: 'completed', input: args, output: JSON.stringify(output)}}});
      assert.equal(output['ok?'], true);
      return output;
    };
    const terminate = () => {
      const n = calls.length + 1;
      calls.push({type: 'step_finish', timestamp: n, sessionID: context.sessionID,
        part: {type: 'step-finish', id: `cohort-stop-${n}`, sessionID: context.sessionID, reason: 'stop'}});
      return Buffer.from(calls.map(event => JSON.stringify(event)).join('\n') + '\n');
    };
    const begin = await recorded('review_begin', {}), pages = begin['input-coverage'].chunks;
    assert.deepEqual(begin['input-source'], cohortManifest);
    assert.ok(pages > 1);
    let recovered = '';
    for (let id = 1; id <= pages; id++) {
      const page = await recorded('review_read_diff_chunk', {id});
      recovered += page.chunk.text;
      await recorded('review_assess_diff_chunk', {id, note: `Complete inert cohort page ${id}`});
    }
    assert.equal(recovered, cohortFull.toString('utf8'));
    assert.deepEqual(JSON.parse(recovered), cohortData);
    for (const stage of ['deterministic', 'map-change', 'generate-candidates', 'adversarial-validate', 'publish']) {
      await recorded('review_record_evidence', {stage, note: 'Inert full-corpus transport; no semantic or native judgment'});
    }
    await recorded('review_submit', {summary: 'Inert cohort carrier; no preservation agreement or review approval'});
    const response = terminate(), submission = fs.readFileSync(path.join(dir, 'submission.json'));
    const envelope = JSON.parse(submission);
    assert.deepEqual(envelope['input-source'], cohortManifest);
    assert.deepEqual(envelope.comments, []);
    const prepared = verifier.prepareReviewInvocationContext(cohortFull, Buffer.from(JSON.stringify(cohortManifest)),
      tools, path.join(dir, 'submission.json'));
    const result = verifier.verifyReviewInvocation(response, submission, prepared);
    assert.equal(result.ok, true);
    assert.equal(result.acceptedInvocation.pageCount, pages);
    assert.equal(result.acceptedInvocation.fullInputSha256, cohortManifest.full_input.sha256);
    assert.equal(result.acceptedInvocation.responseSha256, createHash('sha256').update(response).digest('hex'));
    assert.equal(result.acceptedInvocation.submissionSha256, createHash('sha256').update(submission).digest('hex'));
    for (const changed of [
      {...cohortManifest, proposal_id: 72},
      {...cohortManifest, context_sha256: 'f'.repeat(64)},
      {...cohortManifest, source_inventory_sha256: 'f'.repeat(64)},
      {...cohortManifest, target: {...cohortManifest.target, repository: 'synthetic/other'}},
      {...cohortManifest, target: {...cohortManifest.target, cohort_targets: [['PRRT_fixture_A', 7], ['PRRT_fixture_B', 10]]}},
    ]) {
      const changedContext = verifier.prepareReviewInvocationContext(cohortFull, Buffer.from(JSON.stringify(changed)),
        tools, path.join(dir, 'submission.json'));
      const refused = verifier.verifyReviewInvocation(response, submission, changedContext);
      assert.equal(refused.ok, false);
      assert.equal(refused.acceptedInvocation, null);
    }
    const omitted = calls.filter(event => !(event.part.tool === 'review_assess_diff_chunk' && event.part.state.input.id === pages));
    const incomplete = verifier.verifyReviewInvocation(Buffer.from(omitted.map(event => JSON.stringify(event)).join('\n') + '\n'), submission, prepared);
    assert.equal(incomplete.ok, false);
    assert.equal(incomplete.acceptedInvocation, null);
    // Capture the real late tool output after submission. It cannot restore acceptance.
    calls.pop();
    const late = await recorded('review_read_diff_chunk', {id: pages});
    assert.equal(late['restart-required?'], true);
    const refused = verifier.verifyReviewInvocation(terminate(), submission, prepared);
    assert.equal(refused.ok, false);
    assert.equal(refused.code, 'last-read-order');
    assert.equal(refused.acceptedInvocation, null);
    assert.equal(fs.existsSync(path.join(dir, 'submission.json')), false);
  });
  const cohortBadSources = [
    {...cohortManifest, purpose: 'rejection-assessment'},
    {...cohortManifest, schema: 'open-hax.rejection-assessment-input/v1'},
    {...cohortManifest, full_diff: manifest.full_diff},
    {...cohortManifest, full_input: {...cohortManifest.full_input, path: 'rejection-assessment-input.txt'}},
    {...cohortManifest, target: {...cohortManifest.target, cohort_targets: [42]}},
    {...cohortManifest, target: {...cohortManifest.target, cohort_targets: []}},
    {...cohortManifest, target: {...cohortManifest.target, cohort_targets: [['PRRT_fixture_B', 8], ['PRRT_fixture_A', 7]]}},
    {...cohortManifest, target: {...cohortManifest.target, cohort_targets: [['PRRT_fixture_A', 7], ['PRRT_fixture_A', 7]]}},
    {...cohortManifest, target: {...cohortManifest.target, thread_id: 'PRRT_fixture_A'}},
    {...cohortManifest, target: {...cohortManifest.target, root_comment_id: 7}},
  ];
  const cohortJson = JSON.stringify(cohortManifest);
  const cohortBadTexts = cohortBadSources.map(source => JSON.stringify(source)).concat([
    '{"purpose":"rejection-assessment",' + cohortJson.slice(1),
    '{"ignored":{"x":1,"x":2},' + cohortJson.slice(1), '\uFEFF' + cohortJson,
  ]);
  for (const [index, text] of cohortBadTexts.entries()) {
    await cohortCheck(`cohort-manifest-refusal-${index}`, async () => {
      const {dir, context} = cohortConsumer(`cohort-malformed-${index}`, cohortFull, text);
      fs.writeFileSync(path.join(dir, 'submission.json'), 'inert stale carrier');
      assert.equal((await call('review_begin', {}, context))['ok?'], false);
      assert.equal((await call('review_status', {}, context))['ok?'], false);
      assert.equal(fs.existsSync(path.join(dir, 'submission.json')), false);
      assert.equal(fs.existsSync(path.join(dir, 'review-events.jsonl')), false);
    });
  }
  for (const [index, bytes] of [cohortFull.subarray(0, 300000),
    Buffer.concat([cohortFull.subarray(0, -1), Buffer.from(' ')]), Buffer.from([255])].entries()) {
    await cohortCheck(`cohort-byte-hash-refusal-${index}`, async () => {
      const {dir, context} = cohortConsumer(`cohort-corrupt-${index}`, bytes, JSON.stringify(cohortManifest));
      for (const filename of ['basehead.diff', 'actionability-input.txt', 'rejection-assessment-input.txt']) {
        fs.writeFileSync(path.join(dir, filename), cohortFull);
      }
      assert.equal((await call('review_begin', {}, context))['ok?'], false);
      assert.equal((await call('review_status', {}, context))['ok?'], false);
    });
  }
  await cohortCheck('cohort-unassessed-pages-refuse-stage-and-submit', async () => {
    const {dir, context} = cohortConsumer('cohort-unassessed', cohortFull, cohortJson);
    assert.equal((await call('review_begin', {}, context))['ok?'], true);
    assert.equal((await call('review_record_evidence', {stage: 'deterministic', note: 'Unassessed input cannot complete'}, context))['ok?'], false);
    assert.equal((await call('review_submit', {summary: 'No omitted members'}, context))['ok?'], false);
    assert.equal(fs.existsSync(path.join(dir, 'submission.json')), false);
  });
  for (const [index, text] of ['\uFEFF', '\uFEFForiginal cohort ημ 😀\n', '\uFEFF\uFEFFcontext'].entries()) {
    await cohortCheck(`cohort-bom-lossless-${index}`, async () => {
      const bytes = Buffer.from(text), source = {...cohortManifest, full_input: {
        path: 'positive-cohort-assessment-input.txt', bytes: bytes.length,
        sha256: createHash('sha256').update(bytes).digest('hex')}};
      const {dir, context} = cohortConsumer(`cohort-bom-${index}`, bytes, JSON.stringify(source));
      assert.equal((await call('review_begin', {}, context))['ok?'], true);
      const page = await call('review_read_diff_chunk', {id: 1}, context);
      assert.equal(page['ok?'], true);
      assert.equal(page.chunk.text, text);
      const prepared = verifier.prepareReviewInvocationContext(bytes, Buffer.from(JSON.stringify(source)), tools, path.join(dir, 'submission.json'));
      assert.deepEqual(prepared.pages, [{id: 1, start: 0, end: text.length}]);
    });
  }
  await cohortCheck('cohort-decoder-loss-refuses-before-session', async () => {
    const text = 'synthetic cohort positive-byte decoder-loss control', bytes = Buffer.from(text);
    const source = {...cohortManifest, full_input: {path: 'positive-cohort-assessment-input.txt',
      bytes: bytes.length, sha256: createHash('sha256').update(bytes).digest('hex')}};
    const {dir, context} = cohortConsumer('cohort-decoder-loss', bytes, JSON.stringify(source));
    const originalDecoder = globalThis.TextDecoder;
    let losses = 0;
    globalThis.TextDecoder = class extends originalDecoder {
      decode(...args) {
        const decoded = super.decode(...args);
        if (decoded === text) { losses++; return ''; }
        return decoded;
      }
    };
    try {
      const begin = await call('review_begin', {}, context);
      const status = await call('review_status', {}, context);
      assert.equal(losses, 1, 'the actual full-input decoder must encounter the controlled byte-valid text');
      assert.deepEqual({beginRefused: begin['ok?'] === false, sessionAbsent: status['ok?'] === false,
        submissionAbsent: !fs.existsSync(path.join(dir, 'submission.json')),
        eventsAbsent: !fs.existsSync(path.join(dir, 'review-events.jsonl'))},
      {beginRefused: true, sessionAbsent: true, submissionAbsent: true, eventsAbsent: true},
      'cohort geometry must refuse decoder loss before session or event custody');
    } finally { globalThis.TextDecoder = originalDecoder; }
  });
  for (const [purpose, source, bytes, consumer] of [
    ['actionability', nativeManifest, nativeFull, inputConsumer],
    ['rejection-assessment', rejectionManifest, rejectionFull, rejectionConsumer],
  ]) {
    for (const [index, members] of [null, [], [['PRRT_fixture_A', 7], ['PRRT_fixture_B', 8]]].entries()) {
      await cohortCheck(`singular-${purpose}-cohort-coordinate-refusal-${index}`, async () => {
        const mixed = {...source, target: {...source.target, cohort_targets: members}};
        const {dir, context} = consumer(`singular-${purpose}-cohort-${index}`, bytes, JSON.stringify(mixed));
        fs.writeFileSync(path.join(dir, 'submission.json'), 'inert stale carrier');
        assert.equal((await call('review_begin', {}, context))['ok?'], false);
        assert.equal((await call('review_status', {}, context))['ok?'], false);
        assert.equal(fs.existsSync(path.join(dir, 'submission.json')), false);
        assert.equal(fs.existsSync(path.join(dir, 'review-events.jsonl')), false);
      });
    }
  }
  console.log(JSON.stringify({cohort_input_controls: cohortResults, native_preservation: false, native_review: false}));
  assert.equal(cohortResults.filter(result => !result.pass).length, 0,
    'every fresh compiled cohort carrier control must pass');

  console.log(JSON.stringify({result: 'pass', tools: tools.length, pages: count,
    missing_tail_refused: true, premature_publish_refused: true, recovered_tail_finding_retained: true,
    recovered_full_input_accepted: true, stale_chronology_refused: true,
    same_session_reset_refused: true, host_id_reset_refused: true, healthy_readmission_refused: true,
    stale_submission_removed: true, fresh_invocation_accepted: true, cleanup_fault_artifact_observed: true,
    native_context_pages: nativeCount, native_context_complete: true, native_context_unassessed_refused: true,
    native_context_carrier_only: true, native_review: false}));
  // Run public transport controls against THIS fresh optimized module, rather
  // than an unrelated warm repository bundle. Preserve inherited eight-tool and
  // native controls above; valid independently bound v2 preparation must pass,
  // while generic v2 invocation and unsuitable context remain refused.
  // The transport verifier supplies its separate trusted caller context through
  // the same native export's fifth-argument seam and checks contextless refusals.
  const transport = spawnSync(process.execPath,
    [path.join(checkout, 'scripts/verify-review-invocation.mjs'),
      path.join(checkout, '.opencode/dist/review-invocation.cjs')],
    {cwd: checkout, env, encoding: 'utf8', timeout: 30000, maxBuffer: 8 * 1024 * 1024});
  process.stdout.write(transport.stdout || '');
  process.stderr.write(transport.stderr || '');
  assert.equal(transport.status, 0, transport.error?.message || 'cold public transport controls failed');
  // Compile and execute the existing owning FS tests from this same fresh source
  // checkout. The node-test target retains its normal compiler/interop options;
  // only namespace selection and the owned output path are narrowed here.
  const fsOutput = path.join(checkout, 'target/test/cold-fs.cjs');
  const fsMerge = '{:ns-regexp "^eta-mu[.]boundaries[.]node[.]fs-test$" :output-to "target/test/cold-fs.cjs"}';
  const fsCompile = spawnSync('shadow-cljs', ['compile', 'test', '--config-merge', fsMerge],
    {cwd: checkout, env, encoding: 'utf8', timeout: 120000, maxBuffer: 8 * 1024 * 1024});
  process.stdout.write(fsCompile.stdout || '');
  process.stderr.write(fsCompile.stderr || '');
  assert.equal(fsCompile.status, 0, fsCompile.error?.message || fsCompile.stderr);
  const fsTests = spawnSync(process.execPath, [fsOutput],
    {cwd: checkout, env, encoding: 'utf8', timeout: 60000, maxBuffer: 8 * 1024 * 1024});
  process.stdout.write(fsTests.stdout || '');
  process.stderr.write(fsTests.stderr || '');
  assert.equal(fsTests.status, 0, fsTests.error?.message || fsTests.stderr);
  assert.match(fsTests.stdout, /Testing eta-mu\.boundaries\.node\.fs-test/);
  const fsSummary = fsTests.stdout.match(/Ran (\d+) tests containing (\d+) assertions\.\s+0 failures, 0 errors\./);
  assert.ok(fsSummary && Number(fsSummary[1]) > 0 && Number(fsSummary[2]) > 0,
    'cold owning filesystem tests must actually execute and pass');
  console.log(JSON.stringify({cold_v2_filesystem: 'pass', tests: Number(fsSummary[1]),
    assertions: Number(fsSummary[2]), moduleSha256: createHash('sha256').update(fs.readFileSync(fsOutput)).digest('hex'),
    namespace: 'eta-mu.boundaries.node.fs-test', native_review: false}));
} finally {fs.rmSync(fixture, {recursive: true, force: true});}
