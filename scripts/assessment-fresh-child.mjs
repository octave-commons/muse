// SPDX-License-Identifier: GPL-3.0-or-later
// Owned disposable no-model child, importing the actual newly compiled plugin.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import {createRequire} from 'node:module';
import path from 'node:path';
import {pathToFileURL} from 'node:url';
import {runFreshAssessmentChild} from './assessment-cold-fixtures.mjs';
const [bundle, modulePath, settingsFile, scenario] = process.argv.slice(2);
const tools = ['review_begin', 'review_read_diff_chunk', 'review_assess_diff_chunk', 'review_record_evidence',
  'review_propose_finding', 'review_classify_finding', 'review_status', 'review_submit'];
const settings = JSON.parse(fs.readFileSync(settingsFile));
const arrays = ['basehead.diff', 'summary.json', 'deterministic.log']
  .map(name => fs.readFileSync(path.join(settings.dir, name)));
const input = {...settings, arrays, texts: arrays.map(bytes =>
  new TextDecoder('utf-8', {fatal: true, ignoreBOM: true}).decode(bytes)), view: Buffer.concat(arrays)};
const {EtaMuActorsPlugin} = await import(pathToFileURL(bundle));
const {tool} = await EtaMuActorsPlugin({});
assert.deepEqual(Object.keys(tool).sort(), [...tools].sort());
const verifier = createRequire(import.meta.url)(modulePath);
const result = await runFreshAssessmentChild({tool, verifier, tools, input, scenario});
console.log(JSON.stringify(result));
