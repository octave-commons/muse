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
  for (const stage of ['deterministic', 'map-change', 'generate-candidates', 'adversarial-validate']) {
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
  for (const file of inventory.stdout.split('\0').filter(Boolean)) {
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
    ['shadow-cljs', ['release', 'opencode-plugin']],
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
  for (const stage of ['deterministic', 'map-change', 'generate-candidates']) {
    assert.equal((await call('review_record_evidence', {stage, note: 'Synthetic stage evidence'}, ctx))['ok?'], true);
  }
  const premature = await call('review_record_evidence',
    {stage: 'adversarial-validate', note: 'Synthetic attempt before tail assessment'}, ctx);
  assert.equal(premature['ok?'], false, 'unassessed tail must keep the candidate stage open');
  assert.equal((await call('review_status', {}, ctx)).stage, 'adversarial-validate');
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
  console.log(JSON.stringify({result: 'pass', tools: tools.length, pages: count,
    missing_tail_refused: true, premature_publish_refused: true, recovered_tail_finding_retained: true,
    recovered_full_input_accepted: true, stale_chronology_refused: true,
    same_session_reset_refused: true, host_id_reset_refused: true, healthy_readmission_refused: true,
    stale_submission_removed: true, fresh_invocation_accepted: true, cleanup_fault_artifact_observed: true,
    native_review: false}));
} finally {fs.rmSync(fixture, {recursive: true, force: true});}
