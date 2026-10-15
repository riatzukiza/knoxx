// SPDX-License-Identifier: GPL-3.0-or-later
// Explicit, nondefault cold-source integration. No services, models or databases.
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import { copyFile, mkdir, mkdtemp, readFile, rm, writeFile } from 'node:fs/promises';
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
  let stdout = '', stderr = '', timedOut = false;
  const stop = (signal) => {
    if (child.pid) try { process.kill(-child.pid, signal); } catch (error) {
      if (error.code !== 'ESRCH') throw error;
    }
  };
  const timer = setTimeout(() => {
    timedOut = true; stop('SIGTERM');
    const hard = setTimeout(() => stop('SIGKILL'), 2000); hard.unref();
  }, timeout);
  const interrupt = () => stop('SIGTERM');
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  child.stdout.on('data', (chunk) => { stdout += chunk; if (stream) process.stdout.write(chunk); });
  child.stderr.on('data', (chunk) => { stderr += chunk; process.stderr.write(chunk); });
  try {
    return await new Promise((resolveResult, reject) => {
      child.once('error', reject);
      child.once('close', (code, signal) => resolveResult({ code, signal, stdout, stderr, timedOut }));
    });
  } finally {
    clearTimeout(timer);
    process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
  }
}

async function checked(args, options) {
  const result = await execute(args, options);
  assert.equal(result.signal, null, `signal running ${args[0]}`);
  assert.equal(result.timedOut, false, `timeout running ${args[0]}`);
  assert.equal(result.code, 0, `prerequisite failed: ${args.join(' ')}\n${result.stderr}`);
  return result.stdout;
}

// Execute the complete authored literal shell stanza, not a command-order mock.
function shellStep(workflow, name) {
  const marker = `      - name: ${name}`;
  const lines = workflow.split('\n');
  const positions = lines.flatMap((line, index) => line === marker ? [index] : []);
  assert.equal(positions.length, 1, `one authored ${name} stanza`);
  let index = positions[0] + 1;
  while (index < lines.length && !lines[index].startsWith('      - ') && lines[index] !== '        run: |') index++;
  assert.equal(lines[index], '        run: |', `literal run body for ${name}`);
  const body = [];
  for (index++; index < lines.length; index++) {
    const line = lines[index];
    if (line && !line.startsWith('          ')) break;
    body.push(line ? line.slice(10) : '');
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
    JAVA_HOME: process.env.JAVA_HOME ?? '/usr/lib/jvm/java-21-openjdk-amd64' };
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
      const imported = await execute([process.execPath, '--require',
        join(backend, 'scripts/shadow-test-error-guard.cjs'), '--input-type=module', '-e',
        `import {pathToFileURL} from 'node:url'; const sdk = await import(pathToFileURL(process.argv[1]).href); if(typeof sdk.vectorSearchWithQuality !== 'function') throw new Error('genuine SDK import unavailable'); console.log('Genuine pinned SDK search-core import resolved through owned Knoxx link.');`, stub],
      { cwd: backend, env, timeout: 30_000 });
      console.log('CAUSAL-IMPORT', JSON.stringify({ code: imported.code, signal: imported.signal,
        source: pin, tree: sourceTree, selectedInventory }));
      assert.equal(imported.signal, null);
      assert.equal(imported.code, 0,
        `Genuine pinned search-core export must resolve after authored preparation.\n${imported.stderr}`);
    } finally {
      await rm(owned, { recursive: true, force: true });
      await assert.rejects(readFile(join(owned, 'workspace/knoxx/backend/package.json')), { code: 'ENOENT' });
      console.log('CLEANUP owned cold-source fixture removed; primary sibling unchanged.');
    }
  });
