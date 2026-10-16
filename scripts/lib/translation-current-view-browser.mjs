/** Owned browser boundary for the synthetic core-current-view proof. GPL-3.0-or-later. */
import assert from 'node:assert/strict';
import { createServer } from 'node:net';
import { spawn } from 'node:child_process';
import { createHash, randomUUID } from 'node:crypto';
import { lstat, readFile, writeFile, mkdir, realpath } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { installDeferred } from './translation-current-view-deferred.mjs';
import { runCoreScenarios, backgroundResponses } from './translation-current-view-scenarios.mjs';

const digest = data => createHash('sha256').update(data).digest('hex');
const refuse = category => { throw Object.assign(Error(category), { category }); };
const safeCategories = new Set(['core-interrupted', 'core-cli-refused', 'core-browser-proof-failed']);
export const safeDiagnostic = error => ({ category: safeCategories.has(error?.category)
  ? error.category : 'core-browser-failed' });

function exactOrigin(value) {
  const url = new URL(value);
  assert.ok(['127.0.0.1', '[::1]', 'localhost'].includes(url.hostname));
  assert.equal(url.protocol, 'http:'); assert.equal(url.username, ''); assert.equal(url.password, '');
  assert.equal(url.origin, value); return url.origin;
}

/** The only forwarded branch is an exact same-origin frozen GET/HEAD, with no redirect. */
export function createNetworkGuard(config) {
  const origin = exactOrigin(config.origin);
  assert.match(config.runId, /^[a-zA-Z0-9_-]{8,80}$/);
  const allowed = new Set([...config.assets, ...config.navigation]);
  assert.ok([...allowed].every(p => p.startsWith('/') && !p.startsWith('//') && !p.includes('?')));
  const counts = { blocked: 0, continuedReads: 0, forwardedWrites: 0, websocketMocks: 0, mockedBackground: 0, unknownWebsockets: 0 };
  let phase = 'controlled';
  function decide(request) {
    let url; try { url = new URL(request.url); } catch { return 'abort'; }
    if (!['GET', 'HEAD'].includes(request.method) || url.origin !== origin) return 'abort';
    if (Object.hasOwn(config.background ?? {}, url.pathname + url.search)) return 'fixture';
    if (phase === 'authentication' && url.pathname === '/api/auth/context' && !url.search) return 'continue';
    return !url.search && !url.hash && allowed.has(url.pathname) ? 'continue' : 'abort';
  }
  async function route(route) {
    const request = route.request();
    const decision = decide({ method: request.method(), url: request.url() });
    if (decision === 'fixture') {
      counts.mockedBackground += 1; const url = new URL(request.url());
      await route.fulfill({ status: 200, contentType: 'application/json', body: JSON.stringify(config.background[url.pathname + url.search]) });
    } else if (decision === 'continue') {
      // route.continue can carry a redirect beyond the intercepted URL. A bounded
      // public fetch with maxRedirects0 keeps even a changed read target closed.
      try {
        const response = await route.fetch({ maxRedirects: 0, timeout: 10000 });
        if (response.status() >= 300 && response.status() < 400) {
          counts.blocked += 1; await route.abort('blockedbyclient');
        } else { counts.continuedReads += 1; await route.fulfill({ response }); }
      } catch { counts.blocked += 1; await route.abort('blockedbyclient'); }
    } else { counts.blocked += 1; await route.abort('blockedbyclient'); }
  }
  return { decide, route, authenticate: () => { phase = 'authentication'; },
    seal: () => { phase = 'controlled'; }, report: () => ({ ...counts, runId: config.runId, phase }),
    websocket: socket => {
      let known = false;
      try { const url = new URL(socket.url()); const http = new URL(origin);
        known = url.protocol === 'ws:' && url.host === http.host && url.pathname === '/ws/stream'
          && [...url.searchParams.keys()].every(x => ['session_id', 'conversation_id'].includes(x));
      } catch { /* Unknown socket is closed and retained as a failed guard. */ }
      counts.websocketMocks += 1; if (!known) counts.unknownWebsockets += 1; socket.close();
    } };
}

/** Attempt every known-owned teardown, preserving primary failure separately. */
export async function closeOwned(resources, primary = null) {
  const cleanupErrors = [];
  for (const resource of resources) {
    try { await resource.close(); } catch { cleanupErrors.push(resource.name); }
  }
  return { primary, cleanupErrors, cleanupComplete: cleanupErrors.length === 0 };
}

/** Finite CLI child; no shell interpolation, inherited provider keys or unbounded output. */
export async function runOwnedChild(argv, { cwd, timeout = 30000, env = {} } = {}) {
  assert.ok(Array.isArray(argv) && argv.length && argv.every(x => typeof x === 'string'));
  return await new Promise((resolve, reject) => {
    const child = spawn(argv[0], argv.slice(1), { cwd, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
    let stdout = '', bytes = 0, expired = false, killTimer;
    const terminate = () => { expired = true; try { process.kill(-child.pid, 'SIGTERM'); } catch {}
      killTimer = setTimeout(() => { try { process.kill(-child.pid, 'SIGKILL'); } catch {} }, 1000); };
    const timer = setTimeout(terminate, timeout);
    child.stdout.on('data', b => { bytes += b.length; if (bytes > 65536) terminate(); else stdout += b; });
    child.stderr.on('data', b => { bytes += b.length; if (bytes > 65536) terminate(); });
    child.once('error', () => { clearTimeout(timer); clearTimeout(killTimer); reject(Error('core-cli-unavailable')); });
    child.once('close', code => { clearTimeout(timer); clearTimeout(killTimer);
      if (expired || code !== 0) reject(Error('core-cli-failed')); else resolve(stdout); });
  });
}

async function regular(file, expected) {
  assert.ok(path.isAbsolute(file)); const info = await lstat(file);
  assert.ok(info.isFile() && !info.isSymbolicLink());
  const bytes = await readFile(file); if (expected) assert.equal(digest(bytes), expected); return bytes;
}

/** Require the native CLI URL object and exact owned-page identity. */
export function assertOwnedCLIURL(value, expected) {
  assert.ok(value !== null && typeof value === 'object' && !Array.isArray(value));
  assert.ok(Object.hasOwn(value, 'url'));
  assert.equal(typeof value.url, 'string');
  assert.ok(value.url.length > 0);
  assert.equal(value.url, expected);
}

/** Caller supplies a source/dist/tool freeze produced after the combined QA gates. */
export async function validateRuntimeConfig(config) {
  assert.match(config.sourceCommit, /^[0-9a-f]{40}$/); exactOrigin(config.origin);
  assert.equal(await realpath(config.sourceRoot), config.sourceRoot);
  assert.ok(path.isAbsolute(config.evidenceDir) && path.isAbsolute(config.profileDir));
  assert.ok(config.profileDir.startsWith(config.evidenceDir + path.sep));
  assert.ok(Number.isInteger(config.cdpPort) && config.cdpPort > 1024 && config.cdpPort < 65536);
  for (const item of config.frozenFiles) await regular(item.path, item.sha256);
  const proof = JSON.parse(await regular(config.preflight.path, config.preflight.sha256));
  assert.equal(proof.source_commit, config.sourceCommit); assert.equal(proof.run_id, config.runId);
  assert.equal(proof.org_id, config.orgId); assert.equal(proof.sft_anonymous_status, 401);
  assert.equal(proof.dispatch_anonymous_status, 401); assert.equal(proof.state_unchanged, true);
  assert.equal(proof.project, config.project); assert.match(proof.state_sha256, /^[0-9a-f]{64}$/);
  const writer = JSON.parse(await regular(config.writerProof.path, config.writerProof.sha256));
  assert.equal(writer.sourceCommit, config.sourceCommit); assert.equal(writer.normalExit, 0);
  assert.deepEqual(writer.calls, { queue: 0, agent: 0, eventTurn: 0 });
  assert.equal(writer.actualRouteGuards, true);
  const auth = await lstat(config.apiKeyFile); assert.ok(auth.isFile() && !auth.isSymbolicLink());
  assert.equal(auth.mode & 0o077, 0); assert.equal(auth.uid, process.getuid());
  assert.ok(config.frozenFiles.some(x => x.path === path.join(config.sourceRoot, 'backend/dist/server.js')));
  assert.ok(config.frozenFiles.some(x => x.path.endsWith('/browsers.json')));
  await verifyDist(config); return proof;
}

async function verifyDist(config) {
  const { readdir } = await import('node:fs/promises');
  const expected = new Set(config.frozenFiles.map(x => x.path));
  async function walk(directory) {
    assert.ok((await lstat(directory)).isDirectory());
    for (const name of await readdir(directory)) {
      const file = path.join(directory, name), info = await lstat(file);
      assert.ok(!info.isSymbolicLink());
      if (info.isDirectory()) await walk(file);
      else { assert.ok(info.isFile() && expected.has(file)); }
    }
  }
  await walk(path.join(config.sourceRoot, 'backend/dist'));
  await walk(path.join(config.sourceRoot, 'frontend/dist'));
}
async function availablePort(port) {
  const server = createServer();
  await new Promise((resolve, reject) => { server.once('error', reject); server.listen(port, '127.0.0.1', resolve); });
  await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve()));
}

/** Browser processes receive runtime paths, never the tour's credential environment. */
export function browserEnvironment(ambient) {
  return { PATH: ambient.PATH ?? '/usr/bin:/bin', LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8',
    ...(ambient.TMPDIR ? { TMPDIR: ambient.TMPDIR } : {}),
    NO_PROXY: '*', no_proxy: '*', HTTP_PROXY: '', HTTPS_PROXY: '', ALL_PROXY: '' };
}

async function configAt(file) {
  const config = JSON.parse(await regular(file)); await validateRuntimeConfig(config);
  const actual = await runOwnedChild(['git', '-C', config.sourceRoot, 'rev-parse', 'HEAD'],
    { env: { PATH: process.env.PATH }, timeout: 10000 });
  assert.equal(actual.trim(), config.sourceCommit);
  await runOwnedChild(['git', '-C', config.sourceRoot, '-c', 'core.fileMode=true', 'diff', '--exit-code', 'HEAD'],
    { env: { PATH: process.env.PATH }, timeout: 10000 });
  return config;
}

async function startOwner(config, resources) {
  const module = await import(pathToFileURL(config.playwrightEntry).href);
  await mkdir(config.evidenceDir, { mode: 0o700 }); await mkdir(config.profileDir, { mode: 0o700 });
  // Port is owned only after the browser binds it. A pre-existing port must be
  // refused by the outer runtime controller, and the context/CDP identity checked.
  await availablePort(config.cdpPort);
  const context = await module.chromium.launchPersistentContext(config.profileDir, {
    executablePath: config.chromiumExecutable, headless: true, serviceWorkers: 'block',
    env: browserEnvironment(process.env),
    colorScheme: 'dark', viewport: { width: 1600, height: 1000 },
    args: [`--remote-debugging-address=127.0.0.1`, `--remote-debugging-port=${config.cdpPort}`],
  });
  resources.push({ name: 'context', close: () => context.close() });
  const guard = createNetworkGuard({ ...config, background: backgroundResponses() });
  await context.route('**/*', guard.route);
  await context.routeWebSocket('**/*', guard.websocket);
  const pages = context.pages(); assert.equal(pages.length, 1);
  const page = pages[0]; assert.equal(page.url(), 'about:blank');
  const nonce = randomUUID(); await page.evaluate(value => { window.__coreOwner = value; }, nonce);
  await context.addInitScript(value => { window.__coreOwner = value; }, nonce);
  return { context, page, guard, nonce };
}

async function authenticate(owner, config) {
  const key = (await regular(config.apiKeyFile)).toString().trim(); assert.ok(key.length >= 16);
  await owner.context.setExtraHTTPHeaders({ 'X-API-Key': key });
  owner.guard.authenticate(); await owner.page.goto(config.origin + '/cms', { waitUntil: 'domcontentloaded' });
  const identity = await owner.page.evaluate(async () => {
    const result = await fetch('/api/auth/context', { redirect: 'error', credentials: 'include' });
    const body = await result.json(); return { status: result.status, orgId: body.org?.id, email: body.user?.email };
  });
  assert.equal(identity.status, 200); assert.equal(identity.orgId, config.orgId);
  assert.equal(identity.email, config.email);
  await owner.context.setExtraHTTPHeaders({}); owner.guard.seal();
  await owner.page.evaluate(({ email, slug }) => {
    localStorage.setItem('knoxx_user_email', email); localStorage.setItem('knoxx_org_slug', slug);
  }, { email: config.email, slug: config.orgSlug });
}

function cliFor(config, stopped = () => false) {
  const env = { PATH: process.env.PATH, HOME: process.env.HOME, LANG: 'C.UTF-8',
    NO_PROXY: '*', no_proxy: '*', HTTP_PROXY: '', HTTPS_PROXY: '', ALL_PROXY: '' };
  return async (...args) => {
    if (stopped() && args[0] !== 'close') refuse('core-interrupted');
    const text = await runOwnedChild([config.agentBrowserExecutable, '--session', config.runId,
      '--cdp', `http://127.0.0.1:${config.cdpPort}`, '--json', ...args], { cwd: config.sourceRoot, env });
    const result = JSON.parse(text); if (result.success === false) refuse('core-cli-refused');
    return result.data?.result ?? result.data ?? result;
  };
}

export async function runBrowserProof(config) {
  let owner, cli, primary = null, interrupted = false;
  const resources = []; let results, signalReject;
  const signal = new Promise((_, reject) => { signalReject = reject; });
  signal.catch(() => {});
  const interrupt = () => { interrupted = true; signalReject(Object.assign(Error('core-interrupted'), { category: 'core-interrupted' })); };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  try {
    await validateRuntimeConfig(config); owner = await startOwner(config, resources);
    await authenticate(owner, config); cli = cliFor(config, () => interrupted);
    resources.unshift({ name: 'cli', close: () => cli('close') });
    assert.equal(await cli('eval', 'window.__coreOwner'), owner.nonce);
    assertOwnedCLIURL(await cli('get', 'url'), config.origin + '/cms');
    await cli('set', 'media', 'dark');
    const downloads = downloadRecorder(owner.page, config.evidenceDir);
    resources.unshift({ name: 'downloads', close: downloads.close });
    // This public context-owned cleanup remains available when interrupt stops CLI work.
    resources.unshift({ name: 'adapter', close: () => owner.page.evaluate(restoreOwnedAdapter, { runId: config.runId }) });
    results = await Promise.race([runCoreScenarios({ cli, config, installSource: installDeferred.toString(),
      guardReport: () => owner.guard.report(), ...downloads }), signal]);
    assert.equal(owner.guard.report().blocked, 0, 'Controlled traffic escaped the window adapter');
    assert.equal(owner.guard.report().unknownWebsockets, 0);
  } catch (error) { primary = safeDiagnostic(error); }
  interrupted = true;
  const cleanup = await closeOwned(resources, primary);
  process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
  const proof = { sourceCommit: config.sourceCommit, runId: config.runId, synthetic: true,
    results, guard: owner?.guard.report(), ...cleanup };
  if (owner) await writeFile(path.join(config.evidenceDir, 'core-browser-proof.json'), JSON.stringify(proof, null, 2), { mode: 0o600, flag: 'wx' });
  if (primary || !cleanup.cleanupComplete) refuse('core-browser-proof-failed'); return proof;
}

/** Serializable public-page cleanup; never adopts another run's adapter. */
export function restoreOwnedAdapter(expected, world = globalThis) {
  const adapter = world.__knoxxControlledCore;
  if (!adapter) return { restored: false };
  if (typeof expected.runId !== 'string' || !adapter.report().runId.startsWith(expected.runId + '-'))
    throw Error('core-adapter-cleanup-ownership');
  adapter.restore(); return { restored: true };
}

function downloadRecorder(page, directory) {
  const pending = [];
  const received = download => {
    const number = pending.length;
    const promise = (async () => {
      const file = path.join(directory, `owned-export-${number}.jsonl`);
      await download.saveAs(file);
      return { filename: download.suggestedFilename(), digest: digest(await regular(file)) };
    })();
    promise.catch(() => {}); pending.push(promise);
  };
  page.on('download', received);
  return {
    downloadCount: () => pending.length,
    async checkDownload(previous, filename, content) {
      for (let i = 0; i < 100 && pending.length === previous; i += 1)
        await new Promise(resolve => setTimeout(resolve, 50));
      assert.equal(pending.length, previous + 1);
      const observed = await pending[previous];
      assert.equal(observed.filename, filename); assert.equal(observed.digest, digest(content));
    },
    async noDownload(previous) {
      await new Promise(resolve => setTimeout(resolve, 100)); assert.equal(pending.length, previous);
    },
    async close() {
      page.off('download', received);
      // Context teardown is also attempted by closeOwned if a save failed.
      const result = await Promise.race([Promise.allSettled(pending),
        new Promise((_, reject) => { const timer = setTimeout(() => reject(Error('core-download-terminal-timeout')), 5000); timer.unref(); })]);
      assert.ok(result.every(x => x.status === 'fulfilled'));
    },
  };
}

async function fromTour(templateFile, handoffFile) {
  const template = JSON.parse(await regular(templateFile)), handoff = JSON.parse(await regular(handoffFile));
  for (const field of ['runId', 'sourceCommit', 'orgId', 'email', 'orgSlug', 'project', 'sourceRoot', 'origin'])
    assert.equal(handoff[field], template[field]);
  const config = { ...template, apiKeyFile: handoff.apiKeyFile,
    preflight: { path: handoff.preflightFile, sha256: digest(await regular(handoff.preflightFile)) } };
  const file = path.join(path.dirname(handoffFile), 'core-runtime-config.json');
  await writeFile(file, JSON.stringify(config), { flag: 'wx', mode: 0o600 }); return file;
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  try {
    const mode = process.argv[2]; assert.ok(['--run', '--from-tour'].includes(mode));
    let file = process.argv[3];
    if (mode === '--from-tour') file = await fromTour(file, process.argv[4]);
    const config = await configAt(file);
    await runBrowserProof(config); console.log('PASS synthetic core browser proof; no live mutation forwarded');
  } catch (error) { console.error(JSON.stringify(safeDiagnostic(error))); process.exitCode = 1; }
}
