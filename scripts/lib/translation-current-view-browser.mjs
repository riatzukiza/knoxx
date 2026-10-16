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
const diagnosticStages = new Set(['validate', 'owner', 'auth', 'cli-nonce', 'cli-url', 'cli-media', 'cli-downloads', 'scenarios']);
const diagnosticCases = new Set([
  ...['legacy-segment', 'resource-split', 'resource-bulk', 'legacy-document', 'whole-output', 'dispatch', 'retry']
    .flatMap(entry => ['project', 'unmount', 'remount'].flatMap(kind => ['success', 'error'].map(mode => `${entry}:${kind}-${mode}`))),
  'first-filter-render-success', 'first-filter-render-error', 'first-selected-detail',
  ...['project', 'locale', 'aba', 'unmount'].flatMap(boundary => ['success', 'error'].map(mode => `export-${boundary}-${mode}`)),
  'settled-feedback-success', 'settled-feedback-error', 'inventory-omission-first-observer', 'experimental-pre-first-B',
]);
const diagnosticNames = new Set(['Error', 'AssertionError', 'TypeError', 'RangeError', 'SyntaxError', 'ReferenceError', 'AggregateError']);
const diagnosticCodes = new Set(['ERR_ASSERTION', 'ENOENT', 'EACCES', 'EPERM', 'ECONNRESET', 'ECONNREFUSED', 'ETIMEDOUT']);
const diagnosticOwnCodes = new Set([...safeCategories, 'core-cli-unavailable', 'core-cli-failed']);
function diagnosticValue(value, key) {
  try {
    if (value === null || !['object', 'function'].includes(typeof value)) return undefined;
    const own = Object.getOwnPropertyDescriptor(value, key);
    if (own) return Object.hasOwn(own, 'value') ? own.value : undefined;
    const parent = Object.getPrototypeOf(value);
    const inherited = parent && Object.getOwnPropertyDescriptor(parent, key);
    return inherited && Object.hasOwn(inherited, 'value') ? inherited.value : undefined;
  } catch { return undefined; }
}
function safeProgress(value) {
  const result = {}, stage = diagnosticValue(value, 'stage'), number = diagnosticValue(value, 'caseNumber');
  const name = diagnosticValue(value, 'caseName'), completed = diagnosticValue(value, 'completedCases');
  if (diagnosticStages.has(stage)) result.stage = stage;
  if (Number.isInteger(number) && number >= 1 && number <= 57 && diagnosticCases.has(name)) {
    result.caseNumber = number; result.caseName = name;
  }
  if (Number.isInteger(completed) && completed >= 0 && completed <= 57) result.completedCases = completed;
  return result;
}
/** Closed diagnostic vocabulary only; never inspect messages, stacks, values or accessors. */
export function safeDiagnostic(error, progress) {
  const category = diagnosticValue(error, 'category');
  const result = { category: safeCategories.has(category) ? category : 'core-browser-failed' };
  if (progress === undefined) return result;
  Object.assign(result, safeProgress(progress));
  const name = diagnosticValue(error, 'name'), code = diagnosticValue(error, 'code');
  if (diagnosticNames.has(name)) result.errorName = name;
  if (diagnosticCodes.has(code)) result.errorCode = code;
  if (diagnosticOwnCodes.has(category)) result.ownErrorCode = category;
  return result;
}

const completionCategories = new Set(['core-adapter-inactive', 'core-request-invalid',
  'core-request-outside-owner', 'core-request-unexpected', 'core-first-render-checkpoint',
  'core-commit-rule', 'core-commit-release-not-pending', 'core-commit-request-already-started']);
const completionIds = new Set(['A-adopt-project', 'A-initial-manifest', 'A-inventory', 'A-manifest',
  'B-inventory', 'B-legacy', 'B-manifest', 'B']);
const completionId = value => completionIds.has(value);
function completionList(value, limit, project) {
  if (!Array.isArray(value)) return undefined;
  const length = diagnosticValue(value, 'length');
  if (!Number.isSafeInteger(length) || length < 0 || length > limit) return undefined;
  const result = [];
  for (let index = 0; index < length; index += 1) {
    const item = project(diagnosticValue(value, String(index)));
    if (item === undefined) return undefined;
    result.push(item);
  }
  return result;
}
function safeCompletionDetails(value) {
  try {
    const pending = completionList(diagnosticValue(value, 'pending'), 64, id => completionId(id) ? id : undefined);
    const records = completionList(diagnosticValue(value, 'records'), 256, token =>
      typeof token === 'string' && token.length <= 80 && /^(request|release|commit|boundary):/.test(token)
        && completionId(token.slice(token.indexOf(':') + 1)) ? token : undefined);
    const checkpoints = completionList(diagnosticValue(value, 'checkpoints'), 64, item => {
      const id = diagnosticValue(item, 'id'), ok = diagnosticValue(item, 'ok');
      const observation = diagnosticValue(item, 'observation');
      if (id !== 'commit:B' || typeof ok !== 'boolean'
          || (observation !== undefined && observation !== 'first-mutation-observer-delivery')) return undefined;
      return { id, ok, ...(observation ? { observation } : {}) };
    });
    if (!pending || !records || !checkpoints) return undefined;
    const failure = diagnosticValue(value, 'firstFailure'); let firstFailure;
    if (failure !== undefined) {
      const category = diagnosticValue(failure, 'category'), id = diagnosticValue(failure, 'id');
      if (!completionCategories.has(category) || (id !== undefined && !completionId(id))) return undefined;
      firstFailure = { category, ...(id === undefined ? {} : { id }) };
    }
    return { pending, records, checkpoints, ...(firstFailure ? { firstFailure } : {}) };
  } catch { return undefined; }
}
/** Four-counter ABI plus optional finite case57 tokens; no DOM, URLs, bodies, errors or accessors. */
export function safeCompletionReport(value) {
  const failed = diagnosticValue(value, 'failed');
  const keys = ['pendingCount', 'consumedCount', 'stepCount'];
  const counts = keys.map(key => diagnosticValue(value, key));
  if (typeof failed !== 'boolean' || counts.some(x => !Number.isSafeInteger(x) || x < 0)) return undefined;
  const details = safeCompletionDetails(diagnosticValue(value, 'details'));
  return { failed, pendingCount: counts[0], consumedCount: counts[1], stepCount: counts[2],
    ...(details ? { details } : {}) };
}

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
    if (Object.hasOwn(config.background ?? {}, url.pathname + url.search))
      return request.method === 'GET' && !url.hash && request.body == null ? 'fixture' : 'abort';
    if (phase === 'authentication' && url.pathname === '/api/auth/context' && !url.search) return 'continue';
    return !url.search && !url.hash && allowed.has(url.pathname) ? 'continue' : 'abort';
  }
  async function route(route) {
    const request = route.request();
    const decision = decide({ method: request.method(), url: request.url(), body: request.postData?.() });
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
    child.once('error', () => { clearTimeout(timer); clearTimeout(killTimer); reject(Object.assign(Error('core-cli-unavailable'), { category: 'core-cli-unavailable' })); });
    child.once('close', code => { clearTimeout(timer); clearTimeout(killTimer);
      if (expired || code !== 0) reject(Object.assign(Error('core-cli-failed'), { category: 'core-cli-failed' })); else resolve(stdout); });
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

/** Seed only the owned profile's public empty-chat identity before app navigation. */
export function installOwnedChatStorage({ origin, runId }, world = globalThis) {
  if (world.location.origin !== origin) return false;
  if (!/^[a-zA-Z0-9_-]{8,80}$/.test(runId)) throw Error('core-chat-run-invalid');
  const sessionId = runId + '-cms-session', conversationId = runId + '-cms-conversation';
  const key = 'knoxx_cms_chat_session_id', snapshotKey = 'knoxx_cms_chat_session_state:' + sessionId;
  const storage = world.localStorage, selected = storage.getItem(key), raw = storage.getItem(snapshotKey);
  if (selected !== null && selected !== sessionId) throw Error('core-chat-owner-mismatch');
  if (raw !== null) {
    let snapshot;
    try { snapshot = JSON.parse(raw); } catch { throw Error('core-chat-snapshot-invalid'); }
    if (!snapshot || Array.isArray(snapshot) || snapshot.sessionId !== sessionId || snapshot.conversationId !== conversationId)
      throw Error('core-chat-snapshot-owner-mismatch');
  }
  // Validate both identities before either write. Retain later app-updated fields.
  if (raw === null) storage.setItem(snapshotKey, JSON.stringify({ sessionId, conversationId,
    activeActorId: 'cms_chat', messages: [], latestRun: null, runtimeEvents: [], isSending: false }));
  if (selected === null) storage.setItem(key, sessionId);
  return true;
}

async function startOwner(config, resources) {
  const module = await import(pathToFileURL(config.playwrightEntry).href);
  await mkdir(config.evidenceDir, { mode: 0o700 }); await mkdir(config.profileDir, { mode: 0o700 });
  const downloadsDirectory = path.join(config.evidenceDir, 'native-downloads');
  await mkdir(downloadsDirectory, { mode: 0o700 });
  // Port is owned only after the browser binds it. A pre-existing port must be
  // refused by the outer runtime controller, and the context/CDP identity checked.
  await availablePort(config.cdpPort);
  const context = await module.chromium.launchPersistentContext(config.profileDir, {
    executablePath: config.chromiumExecutable, headless: true, serviceWorkers: 'block', downloadsPath: downloadsDirectory,
    env: browserEnvironment(process.env),
    colorScheme: 'dark', viewport: { width: 1600, height: 1000 },
    args: [`--remote-debugging-address=127.0.0.1`, `--remote-debugging-port=${config.cdpPort}`],
  });
  resources.push({ name: 'context', close: () => context.close() });
  const guard = createNetworkGuard({ ...config, background: backgroundResponses(config.runId) });
  await context.route('**/*', guard.route);
  await context.routeWebSocket('**/*', guard.websocket);
  const pages = context.pages(); assert.equal(pages.length, 1);
  const page = pages[0]; assert.equal(page.url(), 'about:blank');
  const nonce = randomUUID(); await page.evaluate(value => { window.__coreOwner = value; }, nonce);
  await context.addInitScript(value => { window.__coreOwner = value; }, nonce);
  await context.addInitScript(installOwnedChatStorage, { origin: config.origin, runId: config.runId });
  return { context, page, guard, nonce, downloadsDirectory };
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
  let owner, cli, downloads, primary = null, interrupted = false;
  const resources = [], scenarioCleanupErrors = []; let results, signalReject, failureProgress, completionFailure;
  const progress = { stage: 'validate', completedCases: 0 };
  const signal = new Promise((_, reject) => { signalReject = reject; });
  signal.catch(() => {});
  const interrupt = () => { interrupted = true; signalReject(Object.assign(Error('core-interrupted'), { category: 'core-interrupted' })); };
  process.once('SIGINT', interrupt); process.once('SIGTERM', interrupt);
  try {
    await validateRuntimeConfig(config); progress.stage = 'owner'; owner = await startOwner(config, resources);
    progress.stage = 'auth'; await authenticate(owner, config); cli = cliFor(config, () => interrupted);
    resources.unshift({ name: 'cli', close: () => cli('close') });
    progress.stage = 'cli-nonce'; assert.equal(await cli('eval', 'window.__coreOwner'), owner.nonce);
    progress.stage = 'cli-url'; assertOwnedCLIURL(await cli('get', 'url'), config.origin + '/cms');
    progress.stage = 'cli-media'; await cli('set', 'media', 'dark');
    progress.stage = 'cli-downloads';
    const nativeBrowser = owner.context.browser();
    assert.equal(nativeBrowser.contexts().length, 1); assert.equal(nativeBrowser.contexts()[0], owner.context);
    const downloadTransport = await nativeBrowser.newBrowserCDPSession();
    resources.unshift({ name: 'download-transport', close: () => downloadTransport.detach() });
    // A second CDP client's attach resets the default context download path.
    // Reclaim the original owner's explicit path before any download is started.
    await downloadTransport.send('Browser.setDownloadBehavior', {
      behavior: 'allowAndName', downloadPath: owner.downloadsDirectory, eventsEnabled: true,
    });
    downloads = downloadRecorder(owner.page, config.evidenceDir);
    resources.unshift({ name: 'downloads', close: downloads.close });
    // This public context-owned cleanup remains available when interrupt stops CLI work.
    resources.unshift({ name: 'adapter', close: () => owner.page.evaluate(restoreOwnedAdapter, { runId: config.runId }) });
    progress.stage = 'scenarios';
    results = await Promise.race([runCoreScenarios({ cli, config, installSource: installDeferred.toString(),
      onCase: value => Object.assign(progress, safeProgress(value)),
      onCleanupError: name => scenarioCleanupErrors.push(name),
      onCompletionFailure: value => { completionFailure = safeCompletionReport(value); },
      guardReport: () => owner.guard.report(), ...downloads }), signal]);
    progress.completedCases = results.length;
    assert.equal(owner.guard.report().blocked, 0, 'Controlled traffic escaped the window adapter');
    assert.equal(owner.guard.report().unknownWebsockets, 0);
  } catch (error) { failureProgress = safeProgress(progress); primary = safeDiagnostic(error, failureProgress); }
  interrupted = true;
  const cleanup = await closeOwned(resources, primary);
  cleanup.cleanupErrors.unshift(...scenarioCleanupErrors);
  cleanup.cleanupComplete = cleanup.cleanupErrors.length === 0;
  process.removeListener('SIGINT', interrupt); process.removeListener('SIGTERM', interrupt);
  const proof = { sourceCommit: config.sourceCommit, runId: config.runId, synthetic: true,
    results, progress: failureProgress ?? safeProgress(progress), guard: owner?.guard.report(),
    downloadFailures: downloads?.report() ?? [],
    ...(completionFailure ? { completionFailure } : {}), ...cleanup };
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
  const pending = [], failures = [];
  const received = download => {
    const number = pending.length;
    const promise = (async () => {
      const file = path.join(directory, `owned-export-${number}.jsonl`);
      let stage = 'save-as';
      try {
        await download.saveAs(file); stage = 'verify-file';
        return { filename: download.suggestedFilename(), digest: digest(await regular(file)) };
      } catch (error) {
        const cause = diagnosticValue(error, 'cause');
        failures.push({ index: number, stage, thrownKind: error === null ? 'null' : typeof error,
          primary: safeDiagnostic(error, {}), ...(cause === undefined ? {} : { cause: safeDiagnostic(cause, {}) }) });
        throw error;
      }
    })();
    promise.catch(() => {}); pending.push(promise);
  };
  page.on('download', received);
  return {
    downloadCount: () => pending.length,
    report: () => failures.map(value => ({ ...value, primary: { ...value.primary },
      ...(value.cause ? { cause: { ...value.cause } } : {}) })),
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
