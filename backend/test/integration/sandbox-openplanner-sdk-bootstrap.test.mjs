// SPDX-License-Identifier: GPL-3.0-or-later
// Explicit, nondefault cold-source integration. No services, models or databases.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { chmod, copyFile, lstat, mkdir, mkdtemp, readFile, readlink, rm, symlink, writeFile } from 'node:fs/promises';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';

const repo = fileURLToPath(new URL('../../../', import.meta.url));
const pin = '07085d6557b75834ce6f50e6c54b8ca47e1c7c08';
const sourceTree = '1dc69c04c8e832661d8062c15ee24a853f135d35';
const selectedInventory = '3f67946839edd484db2973dd19b2eb822e41e10ada9ac878bb2d24468839aece';
const roots = ['packages/openplanner-sdk', 'packages/stores/document-hydration',
  'packages/stores/cache', 'package.json', 'pnpm-workspace.yaml', 'pnpm-lock.yaml'];
const sha = (value) => createHash('sha256').update(value).digest('hex');

async function execute(args, { cwd, env, timeout = 15 * 60_000, stream = true } = {}) {
  const child = spawn(args[0], args.slice(1), {
    cwd, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'],
  });
  let stdout = '', stderr = '', timedOut = false, interrupted = null, hard, escalation;
  let result;
  const stop = (signal) => {
    if (child.pid) try { process.kill(-child.pid, signal); } catch (error) {
      if (error.code !== 'ESRCH') throw error;
    }
  };
  const boundedStop = () => {
    stop('SIGTERM');
    escalation ??= new Promise((done) => { hard = setTimeout(() => { stop('SIGKILL'); done(); }, 5000); });
  };
  const timer = setTimeout(() => { timedOut = true; boundedStop(); }, timeout);
  const interrupt = (signal) => { interrupted ??= signal; boundedStop(); };
  const onInt = () => interrupt('SIGINT'), onTerm = () => interrupt('SIGTERM');
  process.once('SIGINT', onInt); process.once('SIGTERM', onTerm);
  child.stdout.on('data', (chunk) => { stdout += chunk; if (stream) process.stdout.write(chunk); });
  child.stderr.on('data', (chunk) => { stderr += chunk; process.stderr.write(chunk); });
  try {
    result = await new Promise((resolveResult, reject) => {
      child.once('error', reject);
      child.once('close', (code, signal) => resolveResult({ code, signal, stdout, stderr, timedOut, interrupted }));
    });
    if (interrupted) { const error = new Error(`Fixture interrupted by ${interrupted}`); error.result = result; throw error; }
    return result;
  } finally {
    clearTimeout(timer);
    if (timedOut || interrupted) await escalation;
    clearTimeout(hard);
    process.removeListener('SIGINT', onInt); process.removeListener('SIGTERM', onTerm);
  }
}

async function assertStopped(pid) {
  for (let attempt = 0; attempt < 50; attempt++) {
    try { process.kill(pid, 0); } catch (error) { if (error.code === 'ESRCH') return; throw error; }
    const stat = await readFile(`/proc/${pid}/stat`, 'utf8').catch(() => '');
    if (!stat || stat.slice(stat.lastIndexOf(')') + 2).startsWith('Z ')) return;
    await new Promise((done) => setTimeout(done, 20));
  }
  assert.fail(`owned descendant ${pid} remains live after escalation`);
}

async function checked(args, options) {
  const result = await execute(args, options);
  assert.equal(result.signal, null, `signal running ${args[0]}`);
  assert.equal(result.timedOut, false, `timeout running ${args[0]}`);
  assert.equal(result.code, 0, `prerequisite failed: ${args.join(' ')}\n${result.stderr}`);
  return result.stdout;
}

// Execute the complete authored literal shell stanza, not a command-order mock.
function shellStep(workflow, name, indent = 6) {
  const prefix = ' '.repeat(indent);
  const marker = `${prefix}- name: ${name}`;
  const lines = workflow.split('\n');
  const positions = lines.flatMap((line, index) => line === marker ? [index] : []);
  assert.equal(positions.length, 1, `one authored ${name} stanza`);
  let index = positions[0] + 1;
  while (index < lines.length && !lines[index].startsWith(`${prefix}- `) && lines[index] !== `${prefix}  run: |`) index++;
  assert.equal(lines[index], `${prefix}  run: |`, `literal run body for ${name}`);
  const body = [];
  for (index++; index < lines.length; index++) {
    const line = lines[index];
    if (line && !line.startsWith(`${prefix}    `)) break;
    body.push(line ? line.slice(indent + 4) : '');
  }
  assert.ok(body.some((line) => line.trim()), `nonempty ${name} body`);
  return body.join('\n');
}

async function isolatedEnvironment(root) {
  const home = join(root, 'home'), config = join(root, 'config');
  await mkdir(home); await mkdir(config);
  const user = join(config, 'user.npmrc'), global = join(config, 'global.npmrc');
  await writeFile(user, '', { flag: 'wx', mode: 0o600 });
  await writeFile(global, '', { flag: 'wx', mode: 0o600 });
  return { PATH: process.env.PATH, CI: 'true', HOME: home, XDG_CONFIG_HOME: config,
    XDG_CACHE_HOME: join(root, 'cache'), NPM_CONFIG_USERCONFIG: user,
    NPM_CONFIG_GLOBALCONFIG: global, NPM_CONFIG_REGISTRY: 'https://registry.npmjs.org',
    GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1', GIT_TERMINAL_PROMPT: '0',
    JAVA_HOME: process.env.JAVA_HOME ?? '/usr/lib/jvm/java-21-openjdk-amd64',
    JAVA_TOOL_OPTIONS: `-Duser.home="${home}"` };
}

test('cold authored Sandbox preparation resolves the genuine pinned SDK search-core export',
  { timeout: 45 * 60_000 }, async () => {
    const base = process.env.KNOXX_SANDBOX_SDK_RUN_ROOT ?? tmpdir();
    await mkdir(base, { recursive: true });
    const owned = await mkdtemp(join(base, 'knoxx-sdk-cold-'));
    try {
      const env = await isolatedEnvironment(owned);
      assert.equal((await checked(['pnpm', '--version'], { cwd: owned, env })).trim(), '10.20.0');
      assert.match(process.version, /^v24\./);
      const source = process.env.KNOXX_SANDBOX_SDK_SOURCE ?? resolve(repo, '../openplanner');
      const workspace = join(owned, 'workspace', 'knoxx');
      const raw = join(workspace, '.sandbox-deps', 'openplanner');
      await mkdir(dirname(raw), { recursive: true });
      await checked(['git', 'clone', '--shared', '--no-checkout', '--quiet', source, raw], { env, cwd: owned });
      assert.equal((await checked(['git', '--no-replace-objects', '-C', raw, 'rev-parse', 'HEAD'], { env, cwd: owned })).trim(), pin);
      assert.equal((await checked(['git', '--no-replace-objects', '-C', raw, 'rev-parse', `${pin}^{tree}`], { env, cwd: owned })).trim(), sourceTree);
      const entries = await checked(['git', '--no-replace-objects', '-C', raw,
        'ls-tree', '-r', pin, '--', ...roots], { env, cwd: owned, stream: false });
      const inventory = [];
      for (const line of entries.trim().split('\n')) {
        const match = /^100644 blob ([0-9a-f]{40})\t(.+)$/.exec(line);
        assert.ok(match, 'fixture selected input is a regular pinned Git blob');
        const [, blob, path] = match;
        const content = await checked(['git', '--no-replace-objects', '-C', raw,
          'cat-file', 'blob', blob], { env, cwd: owned, stream: false });
        await mkdir(dirname(join(raw, path)), { recursive: true });
        await writeFile(join(raw, path), content);
        inventory.push(`${sha(content)}  ${path}\n`);
      }
      assert.equal(inventory.length, 47);
      assert.equal(sha(inventory.sort((a, b) => a.slice(66) < b.slice(66) ? -1 : a.slice(66) > b.slice(66) ? 1 : 0).join('')), selectedInventory);
      await assert.rejects(readFile(join(raw, 'packages/openplanner-sdk/dist/search-core.js')), { code: 'ENOENT' });
      const backend = join(workspace, 'backend');
      await mkdir(join(backend, 'test/js'), { recursive: true });
      await mkdir(join(backend, 'scripts'), { recursive: true });
      for (const path of ['backend/package.json', 'backend/pnpm-lock.yaml',
        'backend/test/js/openplanner_sdk_test_stub.mjs', 'backend/scripts/shadow-test-error-guard.cjs']) {
        await copyFile(join(repo, path), join(workspace, path));
      }
      const workflow = await readFile(join(repo, '.github/workflows/sandbox-bundle.yml'), 'utf8');
      const preparationName = workflow.includes('      - name: Prepare the pinned OpenPlanner SDK')
        ? 'Prepare the pinned OpenPlanner SDK' : "Materialize Knoxx's expected sibling path";
      const out = join(owned, 'out'), runner = join(owned, 'runner');
      await mkdir(join(out, 'logs'), { recursive: true }); await mkdir(runner);
      Object.assign(env, { GITHUB_WORKSPACE: workspace, OUT: out, RUNNER_TEMP: runner,
        GITHUB_RUN_ID: 'isolated-fixture', GITHUB_RUN_ATTEMPT: '1' });
      // GREEN copies only the reviewed script, then executes its authored caller.
      await mkdir(join(workspace, 'scripts'), { recursive: true });
      try { await copyFile(join(repo, 'scripts/build-sandbox-openplanner-sdk.sh'),
        join(workspace, 'scripts/build-sandbox-openplanner-sdk.sh')); }
      catch (error) { if (error.code !== 'ENOENT') throw error; }
      await checked(['/bin/bash', '--noprofile', '--norc', '-euo', 'pipefail', '-c',
        shellStep(workflow, preparationName)], { cwd: workspace, env });
      await checked(['/bin/bash', '--noprofile', '--norc', '-euo', 'pipefail', '-c',
        shellStep(workflow, 'Install backend dependencies')], { cwd: workspace, env });
      const stub = join(backend, 'test/js/openplanner_sdk_test_stub.mjs');
      assert.equal(sha(await readFile(stub)), sha(await readFile(join(repo, 'backend/test/js/openplanner_sdk_test_stub.mjs'))));
      const importArgs = [process.execPath, '--require',
        join(backend, 'scripts/shadow-test-error-guard.cjs'), '--input-type=module', '-e',
        `import {pathToFileURL} from 'node:url'; const sdk = await import(pathToFileURL(process.argv[1]).href); if(typeof sdk.vectorSearchWithQuality !== 'function') throw new Error('genuine SDK import unavailable'); console.log('Genuine pinned SDK search-core import resolved through owned Knoxx link.');`, stub];
      const imported = await execute(importArgs, { cwd: backend, env, timeout: 30_000 });
      console.log('CAUSAL-IMPORT', JSON.stringify({ code: imported.code, signal: imported.signal,
        source: pin, tree: sourceTree, selectedInventory }));
      assert.equal(imported.signal, null);
      assert.equal(imported.code, 0,
        `Genuine pinned search-core export must resolve after authored preparation.\n${imported.stderr}`);
      const initial = JSON.parse(await readFile(join(out, 'sdk/outcome.json'), 'utf8'));
      assert.equal(initial.outcome, 'success');
      assert.equal(initial.generated_exports.length, 3);
      const retained = JSON.parse(await readFile(join(out, 'sdk/source.json'), 'utf8'));
      assert.equal(retained.selected_inventory_sha256, selectedInventory);
      // No original checkout, original build, installed SDK or generated dist may supply restore.
      await rm(join(workspace, '../openplanner'));
      await rm(raw, { recursive: true, force: true });
      await rm(join(runner, 'knoxx-sdk-initial'), { recursive: true, force: true });
      const setupSection = workflow.split('              setup_commands: [')[1]?.split('              ],')[0];
      assert.ok(setupSection, 'actual manifest declares setup commands');
      const setups = setupSection.trim().split('\n').map((line) => JSON.parse(line.trim().replace(/,$/, '')));
      const restore = setups.filter((line) => line.includes('/sdk/build-sandbox-openplanner-sdk.sh'));
      assert.equal(restore.length, 1, 'one shared SDK restore command');
      assert.ok(setups.indexOf(restore[0]) < setups.findIndex((line) => line.includes('pnpm -C backend install')));
      env.KNOXX_SANDBOX_BUNDLE = out;
      await checked(['/bin/bash', '--noprofile', '--norc', '-euo', 'pipefail', '-c', restore[0]], { cwd: workspace, env });
      await checked(['/bin/bash', '--noprofile', '--norc', '-euo', 'pipefail', '-c', shellStep(workflow, 'Install backend dependencies')], { cwd: workspace, env });
      const restored = JSON.parse(await readFile(join(runner, 'knoxx-sdk-restored/evidence/outcome.json'), 'utf8'));
      assert.equal(restored.outcome, 'success');
      for (const key of ['source_revision', 'source_tree', 'selected_inventory_sha256', 'selected_relative_paths', 'excluded_inventory_sha256', 'excluded_relative_paths']) {
        assert.deepEqual(restored[key], initial[key], `initial/restore matching relative custody ${key}`);
      }
      assert.notEqual(restored.filtered_tree, initial.filtered_tree, 'owned absolute trees are distinct');
      const coldImport = await execute(importArgs, { cwd: backend, env, timeout: 30_000 });
      assert.equal(coldImport.signal, null); assert.equal(coldImport.code, 0, coldImport.stderr);
      console.log('COLD-RESTORE genuine search-core import resolved without original source/build; matching selected/excluded inventories.');

    } finally {
      await rm(owned, { recursive: true, force: true });
      await assert.rejects(readFile(join(owned, 'workspace/knoxx/backend/package.json')), { code: 'ENOENT' });
      console.log('CLEANUP owned cold-source fixture removed; primary sibling unchanged.');
    }
  });

// These subprocess doubles prove transport, configuration exclusion and failure
// propagation only. They never grant genuine SDK-semantic success credit.
test('shared helper refuses bad inputs and bounds failure/interrupt transports before backend use',
  { timeout: 5 * 60_000 }, async () => {
    const base = process.env.KNOXX_SANDBOX_SDK_RUN_ROOT ?? tmpdir();
    await mkdir(base, { recursive: true });
    const owned = await mkdtemp(join(base, 'knoxx-sdk-controls-'));
    try {
      const env = await isolatedEnvironment(owned);
      const raw = join(owned, 'raw');
      await checked(['git', 'clone', '--shared', '--no-checkout', '--quiet',
        process.env.KNOXX_SANDBOX_SDK_SOURCE ?? resolve(repo, '../openplanner'), raw], { env, cwd: owned });
      // Synthetic hostile inputs: no actual credential file is read.
      for (const path of ['.env', '.npmrc', '.config/auth.json', 'node_modules/old',
        'packages/openplanner-sdk/dist/search-core.js']) {
        await mkdir(dirname(join(raw, path)), { recursive: true });
        await writeFile(join(raw, path), 'synthetic forbidden input\n');
      }
      const helper = join(repo, 'scripts/build-sandbox-openplanner-sdk.sh');
      const workflow = await readFile(join(repo, '.github/workflows/sandbox-bundle.yml'), 'utf8');
      const action = await readFile(join(repo, '.github/actions/knoxx-setup/action.yml'), 'utf8');
      const captures = [];
      async function control(name, behavior, { source = raw, caller = null, interrupt = false, realSibling = false, siblingLink = null } = {}) {
        const root = join(owned, name), tools = join(root, 'tools');
        await mkdir(tools, { recursive: true });
        const record = join(root, 'calls.jsonl'), pidFile = join(root, 'child.pid');
        const pnpm = `#!${process.execPath}\nimport fs from 'node:fs';\nimport path from 'node:path';\nconst args=process.argv.slice(2);\nif(args[0]==='--version'){console.log(${JSON.stringify(behavior === 'wrong-version' ? '9.15.0' : '10.20.0')});process.exit(0);}\nconst cwd=args[1];\nfor(const p of ['.env','.npmrc','.git','.config','packages/openplanner-sdk/dist/search-core.js'])if(fs.existsSync(path.join(cwd,p)))throw Error('forbidden source available before lifecycle: '+p);\nfor(const key of ['GH_TOKEN','GITHUB_TOKEN','OPENAI_API_KEY','NODE_OPTIONS','NPM_TOKEN'])if(Object.hasOwn(process.env,key))throw Error('inherited forbidden environment name '+key);\nif(process.env.JAVA_TOOL_OPTIONS!== '-Duser.home=\"'+process.env.HOME+'\"')throw Error('JVM home not isolated');\nif(process.env.NPM_CONFIG_USERCONFIG===process.env.NPM_CONFIG_GLOBALCONFIG)throw Error('same npmrc pathname');\nfor(const key of ['NPM_CONFIG_USERCONFIG','NPM_CONFIG_GLOBALCONFIG'])if(fs.readFileSync(process.env[key]).length)throw Error('nonempty config');\nfs.appendFileSync(${JSON.stringify(record)},JSON.stringify(args)+'\\n');\nif(args.includes('install')) {${behavior === 'descendant' ? `const {spawn}=await import('node:child_process');spawn(process.execPath,['--input-type=module','-e',${JSON.stringify(`import fs from 'node:fs';process.on('SIGTERM',()=>{});fs.writeFileSync(${JSON.stringify(pidFile)},String(process.pid));setInterval(()=>{},1000);`)}],{stdio:'ignore'});setInterval(()=>{},1000);` : behavior === 'hang' ? `fs.writeFileSync(${JSON.stringify(pidFile)},String(process.pid));process.on('SIGTERM',()=>{});setInterval(()=>{},1000);` : `process.exit(${behavior === 'install-failure' ? 23 : 0});`}} else process.exit(${behavior === 'build-failure' ? 29 : 0});\n`;
        await writeFile(join(tools, 'pnpm'), pnpm); await chmod(join(tools, 'pnpm'), 0o700);
        const transportEnv = { ...env, PATH: `${tools}:${env.PATH}`,
          GH_TOKEN: 'synthetic', GITHUB_TOKEN: 'synthetic', OPENAI_API_KEY: 'synthetic',
          NPM_TOKEN: 'synthetic', NODE_OPTIONS: '--require=/synthetic-unavailable-node-option.cjs' };
        const run = join(root, 'run');
        let argv = ['/bin/bash', helper, 'prepare', '--source', source, '--run-root', run];
        if (caller) {
          const workspace = join(root, 'workspace', 'knoxx'); await mkdir(workspace, { recursive: true });
          await mkdir(join(workspace, 'scripts')); await copyFile(helper, join(workspace, 'scripts/build-sandbox-openplanner-sdk.sh'));
          const checkout = caller === 'Sandbox' ? '.sandbox-deps' : '_deps';
          await mkdir(join(workspace, checkout)); await symlink(raw, join(workspace, checkout, 'openplanner'));
          const out = join(root, 'out'), runner = join(root, 'runner'); await mkdir(out); await mkdir(runner);
          Object.assign(transportEnv, { GITHUB_WORKSPACE: workspace, OUT: out, RUNNER_TEMP: runner });
          const body = caller === 'Sandbox' ? shellStep(workflow, 'Prepare the pinned OpenPlanner SDK')
            : shellStep(action, 'Prepare the pinned OpenPlanner SDK and install Knoxx', 4);
          argv = ['/bin/bash', '--noprofile', '--norc', '-euo', 'pipefail', '-c', body];
          transportEnv.PWD = workspace;
          if (realSibling) { await mkdir(join(workspace, '../openplanner')); await writeFile(join(workspace, '../openplanner/preserve'), 'foreign sibling bytes'); }
          const siblingTarget = join(root, 'preserved-sibling-target');
          if (siblingLink) {
            if (siblingLink === 'live') { await mkdir(siblingTarget); await writeFile(join(siblingTarget, 'preserve'), 'foreign link-target bytes'); }
            await symlink(siblingTarget, join(workspace, '../openplanner'));
          }
          const result = await execute(argv, { cwd: workspace, env: transportEnv, timeout: 30_000, stream: false });
          assert.notEqual(result.code, 0); assert.equal(result.signal, null);
          if (realSibling || siblingLink) {
            assert.match(result.stderr, /Refusing an existing real OpenPlanner sibling/);
            if (realSibling) assert.equal(await readFile(join(workspace, '../openplanner/preserve'), 'utf8'), 'foreign sibling bytes');
            else {
              assert.equal(await readlink(join(workspace, '../openplanner')), siblingTarget);
              if (siblingLink === 'live') assert.equal(await readFile(join(siblingTarget, 'preserve'), 'utf8'), 'foreign link-target bytes');
              else await assert.rejects(lstat(siblingTarget), { code: 'ENOENT' });
            }
            await assert.rejects(readFile(record), { code: 'ENOENT' }); return;
          }
          const evidence = caller === 'Sandbox' ? join(out, 'sdk') : join(runner, 'knoxx-setup-sdk/evidence');
          const outcome = JSON.parse(await readFile(join(evidence, 'outcome.json'), 'utf8'));
          captures.push(outcome);
          assert.equal(outcome.install, 'failure'); assert.equal(outcome.build, 'not-run');
          assert.equal(await readlink(join(workspace, '../openplanner')).catch(() => null), null, 'failed prep never exposes sibling');
          return { outcome, evidence };
        }
        let result;
        if (interrupt) {
          const child = spawn(argv[0], argv.slice(1), { cwd: root, env: transportEnv, stdio: ['ignore', 'pipe', 'pipe'] });
          let output = ''; child.stdout.on('data', (chunk) => { output += chunk; });
          child.stderr.on('data', (chunk) => { output += chunk; });
          const terminal = new Promise((done, reject) => { child.once('error', reject); child.once('close', (code, signal) => done({ code, signal })); });
          const deadline = Date.now() + 20_000;
          while (!(await readFile(pidFile).catch(() => null))) {
            assert.ok(Date.now() < deadline, 'TERM-ignore child starts within finite control deadline');
            await new Promise((done) => setTimeout(done, 50));
          }
          const pid = Number(await readFile(pidFile, 'utf8')); child.kill('SIGTERM');
          const terminalTimer = setTimeout(() => child.kill('SIGKILL'), 10_000);
          try { result = await terminal; } finally { clearTimeout(terminalTimer); }
          assert.equal(result.code, 143, output); assert.equal(result.signal, null);
          await assertStopped(pid);
          console.log(`STANDALONE-HELPER ${behavior}: interrupted143; owned child/group no longer live; nonchild orphan reaping belongs to host.`);
        } else result = await execute(argv, { cwd: root, env: transportEnv, timeout: 30_000, stream: false });
        assert.notEqual(result.code, 0); assert.equal(result.signal, null);
        const outcome = JSON.parse(await readFile(join(run, 'evidence/outcome.json'), 'utf8'));
        if (behavior === 'wrong-version' || source !== raw) {
          assert.equal(outcome.install, 'not-run');
          await assert.rejects(readFile(record), { code: 'ENOENT' });
        } else {
          const calls = (await readFile(record, 'utf8')).trim().split('\n').map(JSON.parse);
          assert.equal(calls[0].includes('install'), true);
          assert.equal(calls.length, behavior === 'install-failure' || interrupt ? 1 : 2);
          if (behavior === 'missing-export') assert.match(outcome.error, /ENOENT/);
          if (interrupt) assert.equal(outcome.interrupted, 'SIGTERM');
        }
      }
      await control('missing-source', 'install-failure', { source: join(owned, 'absent') });
      await control('wrong-source', 'install-failure', { source: repo });
      await control('wrong-pnpm', 'wrong-version');
      await control('install-fails', 'install-failure');
      await control('build-fails', 'build-failure');
      await control('export-absent', 'missing-export');
      await control('Sandbox-real-sibling', 'install-failure', { caller: 'Sandbox', realSibling: true });
      await control('setup-action-real-sibling', 'install-failure', { caller: 'action', realSibling: true });
      await control('setup-action-live-sibling', 'install-failure', { caller: 'action', siblingLink: 'live' });
      await control('setup-action-broken-sibling', 'install-failure', { caller: 'action', siblingLink: 'broken' });
      const sandboxControl = await control('Sandbox-caller', 'install-failure', { caller: 'Sandbox' });
      await control('setup-action-caller', 'install-failure', { caller: 'action' });
      for (const key of ['source_revision', 'source_tree', 'selected_inventory_sha256',
        'selected_relative_paths', 'excluded_inventory_sha256', 'excluded_relative_paths']) {
        assert.deepEqual(captures[0][key], captures[1][key], `both callers share ${key}`);
      }
      assert.deepEqual(captures.map((capture) => capture.before_lifecycle.credential_env_names_present), [[], []]);
      await control('TERM-ignoring-install', 'hang', { interrupt: true });
      await control('TERM-leader-exits-descendant-closed-stdio', 'descendant', { interrupt: true });
      const descendantPid = join(owned, 'execute-descendant.pid');
      const descendantCode = `import fs from 'node:fs';process.on('SIGTERM',()=>{});fs.writeFileSync(${JSON.stringify(descendantPid)},String(process.pid));setInterval(()=>{},1000);`;
      const leaderCode = `import {spawn} from 'node:child_process';spawn(process.execPath,['--input-type=module','-e',${JSON.stringify(descendantCode)}],{stdio:'ignore'});setInterval(()=>{},1000);`;
      let sent = false;
      const interruptWhenReady = setInterval(async () => {
        if (!sent && await readFile(descendantPid).catch(() => null)) { sent = true; process.kill(process.pid, 'SIGTERM'); }
      }, 50);
      try {
        await assert.rejects(execute([process.execPath, '--input-type=module', '-e', leaderCode],
          { cwd: owned, env, timeout: 20_000, stream: false }), (error) => {
          assert.equal(error.result.interrupted, 'SIGTERM'); assert.equal(error.result.signal, 'SIGTERM'); return true;
        });
      } finally { clearInterval(interruptWhenReady); }
      assert.equal(sent, true); await assertStopped(Number(await readFile(descendantPid, 'utf8')));
      console.log('STANDALONE-FIXTURE external SIGTERM retained; leader closed before escalation; closed-stdio TERM-ignore descendant no longer live.');

      // A failed transport install still leaves the pristine selected archive.
      // Reject hostile archives before invoking either lifecycle command.
      const originalMetadata = JSON.parse(await readFile(join(sandboxControl.evidence, 'source.json'), 'utf8'));
      const originalArchive = await readFile(join(sandboxControl.evidence, 'source.tar.gz'));
      for (const bad of ['checksum', 'identity', 'extra-path', 'missing-path', 'changed-source', 'symlink', 'traversal', 'helper']) {
        const root = join(owned, `archive-${bad}`), bundle = join(root, 'bundle'), files = join(root, 'files');
        await mkdir(bundle, { recursive: true }); await mkdir(files);
        const metadata = structuredClone(originalMetadata);
        const archive = join(bundle, 'source.tar.gz');
        await writeFile(archive, originalArchive);
        if (bad === 'checksum') await writeFile(archive, Buffer.from('invalid source archive'));
        if (bad === 'identity') metadata.source_revision = '0'.repeat(40);
        if (bad === 'helper') metadata.helper_sha256 = '0'.repeat(64);
        if (!['checksum', 'identity', 'helper'].includes(bad)) {
          await checked(['tar', '-xzf', archive, '-C', files], { cwd: root, env, stream: false });
          const paths = [...metadata.selected_relative_paths];
          const target = paths.find((path) => path.endsWith('package.json'));
          if (bad === 'extra-path') { await writeFile(join(files, 'extra'), 'extra'); paths.push('extra'); }
          if (bad === 'missing-path') paths.splice(paths.indexOf(target), 1);
          if (bad === 'changed-source') await writeFile(join(files, target), 'changed selected input');
          if (bad === 'symlink') { await rm(join(files, target)); await symlink('/synthetic-unavailable', join(files, target)); }
          const tarArgs = ['tar', '-czf', archive, '-C', files];
          if (bad === 'traversal') tarArgs.push('--transform=s,^,../,');
          await checked([...tarArgs, '--', ...paths], { cwd: root, env, stream: false });
          metadata.source_archive_sha256 = sha(await readFile(archive));
        }
        await writeFile(join(bundle, 'source.json'), JSON.stringify(metadata));
        const result = await execute(['/bin/bash', helper, 'restore', '--bundle', bundle, '--run-root', join(root, 'run')],
          { cwd: root, env, timeout: 30_000, stream: false });
        assert.notEqual(result.code, 0, `reject ${bad} archive`); assert.equal(result.signal, null);
        const outcome = JSON.parse(await readFile(join(root, 'run/evidence/outcome.json'), 'utf8'));
        assert.equal(outcome.install, 'not-run', `${bad} refused before install`);
        assert.equal(outcome.build, 'not-run');
      }

      for (const id of ['sdk_source', 'sdk_prepare', 'sdk_restore']) {
        assert.ok(workflow.includes(`steps.${id}.outcome != 'success'`), `absent/skipped/failed ${id} blocks final aggregation`);
        assert.ok(workflow.includes(`${id}: $${id}`), `manifest retains actual ${id} outcome`);
      }
      for (const name of ['Run frontend typecheck', 'Run frontend tests and coverage', 'Prepare ingestion dependencies', 'Run ingestion tests', 'Collect generated outputs']) {
        const stanza = workflow.split(`      - name: ${name}`)[1]?.split('      - name:')[0];
        assert.ok(stanza?.includes('if: always()'), `${name} remains independently collected`);
      }
      console.log('TRANSPORT-ONLY controls: wrong/missing source, pnpm mismatch, install/build/export failure, both caller inventories, hostile paths/environment, skipped aggregation and TERM-ignore escalation. No SDK semantic credit.');
    } finally {
      await rm(owned, { recursive: true, force: true });
      await assert.rejects(lstat(owned), { code: 'ENOENT' });
      console.log('CLEANUP owned transport fixtures removed.');
    }
  });

// A real Java process supplies this isolation assertion; the pnpm double stops
// before an SDK install and grants no SDK build/import success credit.
test('actual JVM resolves the helper-owned fresh home before SDK lifecycle', { timeout: 30_000 }, async () => {
  const base = process.env.KNOXX_SANDBOX_SDK_RUN_ROOT ?? tmpdir();
  await mkdir(base, { recursive: true });
  const owned = await mkdtemp(join(base, 'knoxx JVM home '));
  try {
    const env = await isolatedEnvironment(owned), tools = join(owned, 'tools');
    await mkdir(tools);
    const observed = join(owned, 'actual-jvm-properties.txt');
    await writeFile(join(tools, 'pnpm'), `#!${process.execPath}\nimport fs from 'node:fs';import {spawnSync} from 'node:child_process';if(process.argv[2]==='--version'){console.log('10.20.0');process.exit(0);}const java=spawnSync('java',['-XshowSettings:properties','-version'],{encoding:'utf8'});if(java.status!==0)throw Error('actual JVM probe failed');fs.writeFileSync(${JSON.stringify(observed)},java.stdout+java.stderr);process.exit(23);\n`);
    await chmod(join(tools, 'pnpm'), 0o700);
    env.PATH = `${tools}:${env.PATH}`;
    const run = join(owned, 'run with spaces');
    const result = await execute(['/bin/bash', join(repo, 'scripts/build-sandbox-openplanner-sdk.sh'), 'prepare',
      '--source', process.env.KNOXX_SANDBOX_SDK_SOURCE ?? resolve(repo, '../openplanner'), '--run-root', run],
    { cwd: owned, env, stream: false });
    assert.notEqual(result.code, 0, 'transport install intentionally refuses');
    const outcome = JSON.parse(await readFile(join(run, 'evidence/outcome.json'), 'utf8'));
    const properties = await readFile(observed, 'utf8');
    assert.equal(properties.split('\n').find((line) => /^\s*user.home = /.test(line))?.trim(),
      `user.home = ${join(run, 'home')}`, 'actual lifecycle JVM must resolve the fresh owned home, including spaces');
    assert.equal(outcome.jvm_user_home, join(run, 'home'), 'helper preflight independently records verified JVM home');
    assert.equal(outcome.before_lifecycle.jvm_user_home, join(run, 'home'));
    assert.ok(outcome.java_version.split('\n').some((line) => line.trim() === `user.home = ${join(run, 'home')}`));
    assert.equal(outcome.install, 'failure'); assert.equal(outcome.build, 'not-run');
    console.log('REAL-JVM fresh owned user.home, including spaces, verified before refused transport install.');
  } finally { await rm(owned, { recursive: true, force: true }); await assert.rejects(lstat(owned), { code: 'ENOENT' }); }
});

test('fixture interruption lets the nested helper stop its owned TERM-ignore SDK child', { timeout: 30_000 }, async () => {
  const base = process.env.KNOXX_SANDBOX_SDK_RUN_ROOT ?? tmpdir(); await mkdir(base, { recursive: true });
  const owned = await mkdtemp(join(base, 'knoxx-sdk-nested-interrupt-'));
  let pid, timer;
  try {
    const env = await isolatedEnvironment(owned), tools = join(owned, 'tools'), ready = join(owned, 'child.pid');
    await mkdir(tools);
    await writeFile(join(tools, 'pnpm'), `#!${process.execPath}\nimport fs from 'node:fs';if(process.argv[2]==='--version'){console.log('10.20.0');process.exit(0);}fs.writeFileSync(${JSON.stringify(ready)},String(process.pid));process.on('SIGTERM',()=>{});setInterval(()=>{},1000);\n`);
    await chmod(join(tools, 'pnpm'), 0o700); env.PATH = `${tools}:${env.PATH}`;
    let sent = false, failure;
    timer = setInterval(async () => {
      if (!sent && await readFile(ready).catch(() => null)) { sent = true; pid = Number(await readFile(ready, 'utf8')); process.kill(process.pid, 'SIGTERM'); }
    }, 50);
    try {
      await execute(['/bin/bash', join(repo, 'scripts/build-sandbox-openplanner-sdk.sh'), 'prepare',
        '--source', process.env.KNOXX_SANDBOX_SDK_SOURCE ?? resolve(repo, '../openplanner'), '--run-root', join(owned, 'run')],
      { cwd: owned, env, timeout: 20_000, stream: false });
    } catch (error) { failure = error; }
    clearInterval(timer); assert.equal(sent, true); assert.equal(failure?.result?.interrupted, 'SIGTERM');
    await assertStopped(pid);
    assert.equal(failure.result.code, 143, 'outer cleanup allows helper to retain its interrupted outcome');
    assert.equal(failure.result.signal, null);
    const outcome = JSON.parse(await readFile(join(owned, 'run/evidence/outcome.json'), 'utf8'));
    assert.equal(outcome.interrupted, 'SIGTERM'); assert.equal(outcome.commands.at(-1).owned_group_kill_sent, true);
    console.log('NESTED-INTERRUPT actual fixture→helper→TERM-ignore transport exits143 with no live SDK child and retained outcome.');
  } finally {
    clearInterval(timer);
    if (pid) try { process.kill(pid, 'SIGKILL'); } catch (error) { if (error.code !== 'ESRCH') throw error; }
    await rm(owned, { recursive: true, force: true }); await assert.rejects(lstat(owned), { code: 'ENOENT' });
  }
});
