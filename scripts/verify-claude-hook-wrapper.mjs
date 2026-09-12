/** Run the real compiled Claude emitter and its emitted hook from a path containing shell metacharacters. */
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import process from 'node:process';
import {fileURLToPath} from 'node:url';

const repo=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const artifact=path.join(repo,'.claude/dist/claude-server.js');
assert.ok(fs.existsSync(artifact),'Run npm run build before the compiled hook verification');
assert.ok(fs.statSync(artifact).mtimeMs>=fs.statSync(path.join(repo,'src/cljs/eta_mu/boundaries/claude.cljs')).mtimeMs,'Rebuild the Claude artifact after changing its boundary');
const fixture=fs.mkdtempSync(path.join(os.tmpdir(),'muse-emitted-hook-'));
try{
  const checkout=path.join(fixture,"checkout with spaces and 'quotes' $(touch expanded) `touch expanded-too`");
  const dist=path.join(checkout,'.claude/dist');
  fs.mkdirSync(dist,{recursive:true});
  fs.copyFileSync(artifact,path.join(dist,'claude-server.js'));
  fs.copyFileSync(path.join(repo,'.claude/dist/package.json'),path.join(dist,'package.json'));
  fs.symlinkSync(path.join(repo,'node_modules'),path.join(checkout,'node_modules'),'dir');
  const emission=spawnSync(process.execPath,[path.join(dist,'claude-server.js'),'--emit-hook-config'],{cwd:checkout,encoding:'utf8',timeout:10000});
  assert.equal(emission.status,0,emission.stderr);
  const settings=JSON.parse(fs.readFileSync(path.join(checkout,'.claude/settings.json'),'utf8'));
  const command=settings.hooks.PreToolUse[0].hooks[0].command;
  const wrapper=path.resolve(checkout,command);
  assert.ok(wrapper.startsWith(path.join(checkout,'.claude/hooks/')));
  assert.ok(fs.statSync(wrapper).mode&0o111,'The actual emitted wrapper must be executable');
  const result=spawnSync(wrapper,[],{cwd:checkout,input:JSON.stringify({tool_name:'Read',tool_input:{file_path:'README.md'}}),encoding:'utf8',timeout:10000});
  assert.equal(result.status,0,result.stderr);
  assert.ok(!result.stderr.includes('[kanban-gate hook error]'),result.stderr);
  const decision=JSON.parse(result.stdout.trim());
  assert.equal(typeof decision.continue,'boolean','The actual host hook must produce its Claude decision');
  assert.ok(!fs.existsSync(path.join(checkout,'expanded')));
  assert.ok(!fs.existsSync(path.join(checkout,'expanded-too')));
  process.stdout.write(`PASS compiled emitter and executable PreToolUse wrapper preserve spaces, apostrophes and shell syntax; decision=${decision.continue}\n`);
}finally{fs.rmSync(fixture,{recursive:true,force:true});}
