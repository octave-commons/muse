/** Build each declared host from tracked source without generated entrypoints or compiler output. */
import assert from 'node:assert/strict';
import {spawnSync} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';

const repo=path.resolve(path.dirname(fileURLToPath(import.meta.url)),'..');
const hosts={opencode:'.opencode/dist/eta-mu-actors.js',mcp:'.mcp/dist/receipt-river.js',claude:'.claude/dist/claude-server.js'};
const selected=process.argv.slice(2);
assert.ok(selected.every(host=>Object.hasOwn(hosts,host)),'Choose opencode, mcp or claude');
const inventory=spawnSync('git',['ls-files','-z'],{cwd:repo,encoding:'utf8'});
assert.equal(inventory.status,0,inventory.stderr);
const java=spawnSync('java',['-XshowSettings:properties','-version'],{encoding:'utf8'});
assert.equal(java.status,0,java.stderr);
const javaHome=java.stderr.match(/^\s*user\.home = (.+)$/m)?.[1];
assert.ok(javaHome,'The compiler home is needed to share the existing Maven cache');

/** Decode the owned string-only build vector without interpreting arbitrary EDN as code. */
function buildCommand(source) {
  const vector=source.match(/:build\s+\[([^\]]+)\]/)?.[1];
  assert.ok(vector,'The host must declare its build command');
  const words=vector.match(/"(?:\\.|[^"\\])*"/g)||[];
  assert.equal(vector.replace(/"(?:\\.|[^"\\])*"/g,'').trim(),'','Build argv must contain only strings');
  return words.map(word=>JSON.parse(word));
}

for(const host of selected.length?selected:Object.keys(hosts)) {
  const fixture=fs.mkdtempSync(path.join(os.tmpdir(),`muse-cold-${host}-`));
  try {
    const checkout=path.join(fixture,'checkout');
    fs.mkdirSync(checkout);
    for(const file of inventory.stdout.split('\0').filter(Boolean)) {
      if(/^(?:src\/gen\/|target\/|\.shadow-cljs\/|\.claude\/dist\/|\.opencode\/dist\/|\.mcp\/dist\/)/.test(file))continue;
      const source=path.join(repo,file), destination=path.join(checkout,file);
      if(fs.lstatSync(source).isDirectory())continue; // Independently owned Git links are not compiler source.
      fs.mkdirSync(path.dirname(destination),{recursive:true});
      if(fs.lstatSync(source).isSymbolicLink())fs.symlinkSync(fs.readlinkSync(source),destination);
      else fs.copyFileSync(source,destination);
    }
    fs.symlinkSync(path.join(repo,'node_modules'),path.join(checkout,'node_modules'),'dir');
    const home=path.join(fixture,'compiler-home');
    fs.mkdirSync(home);
    if(fs.existsSync(path.join(javaHome,'.m2')))fs.symlinkSync(path.join(javaHome,'.m2'),path.join(home,'.m2'),'dir');
    const env={...process.env,PATH:`${path.join(checkout,'node_modules/.bin')}${path.delimiter}${process.env.PATH||''}`,
      JAVA_TOOL_OPTIONS:`${process.env.JAVA_TOOL_OPTIONS||''} -Duser.home="${home}"`};
    const [command,...args]=buildCommand(fs.readFileSync(path.join(checkout,`.ημ/config/${host}/root.edn`),'utf8'));
    assert.ok(!fs.existsSync(path.join(checkout,'src/gen')),'The host starts without generated source');
    const result=spawnSync(command,args,{cwd:checkout,env,encoding:'utf8',timeout:120000,maxBuffer:8*1024*1024});
    process.stdout.write(result.stdout||'');
    process.stderr.write(result.stderr||'');
    assert.equal(result.status,0,`${host}: ${result.error?.message||result.stderr}`);
    assert.ok(fs.existsSync(path.join(checkout,hosts[host])),`${host} emitted its actual bundle`);
    if(host==='claude') {
      const settings=JSON.parse(fs.readFileSync(path.join(checkout,'.claude/settings.json'),'utf8'));
      assert.ok(settings.hooks.PreToolUse[0].hooks[0].command);
      const wrapper=fs.readFileSync(path.join(checkout,settings.hooks.PreToolUse[0].hooks[0].command),'utf8');
      assert.ok(wrapper.includes(path.join(checkout,hosts[host])),'The wrapper names this newly built checkout');
    }
    process.stdout.write(`PASS cold declared ${host} build with shared dependencies and isolated publish home\n`);
  } finally {fs.rmSync(fixture,{recursive:true,force:true});}
}
