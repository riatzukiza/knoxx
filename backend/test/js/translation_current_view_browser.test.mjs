import assert from 'node:assert/strict';
import { createServer } from 'node:http';
import test from 'node:test';

const browser = () => import('../../../scripts/lib/translation-current-view-browser.mjs');
const deferred = () => import('../../../scripts/lib/translation-current-view-deferred.mjs');
const scenarios = () => import('../../../scripts/lib/translation-current-view-scenarios.mjs');
const origin = 'http://127.0.0.1:42591';
const runId = 'controlled-core-test';
const config = { runId, origin, assets: ['/assets/actual.js'], navigation: ['/', '/cms', '/translations'] };

function inertWorld() {
  let forwarded = 0;
  const world = { location: { origin }, Response, URL, URLSearchParams,
    document: { querySelector: () => null, querySelectorAll: () => [], body: { innerText: '' } },
    fetch: () => { forwarded += 1; throw Error('uncontrolled transport'); } };
  return { world, forwarded: () => forwarded };
}
function step(id, overrides = {}) {
  return { id, method: 'GET', path: '/api/publications/translations/reviews', query: [],
    response: { status: 200, json: { project: 'A', reviews: [] } }, hold: true, ...overrides };
}

test('backstop defaults to abort for every non-read method, API and foreign target', async () => {
  const { createNetworkGuard } = await browser();
  const guard = createNetworkGuard(config);
  for (const method of ['POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS', 'CONNECT', 'TRACE', 'get']) {
    assert.equal(guard.decide({ method, url: origin + '/assets/actual.js' }), 'abort');
  }
  for (const url of [origin + '/api/translations/config', 'https://foreign.invalid/assets/actual.js',
    origin + '/assets/unknown.js', origin + '/assets/actual.js?unfrozen=1']) {
    assert.equal(guard.decide({ method: 'GET', url }), 'abort');
  }
  assert.equal(guard.decide({ method: 'GET', url: origin + '/assets/actual.js' }), 'continue');
  assert.equal(guard.decide({ method: 'HEAD', url: origin + '/cms' }), 'continue');
});

test('actual exported route handler never forwards adverse requests to an owned inert server', async () => {
  const { createNetworkGuard } = await browser();
  const received = [];
  const server = createServer((request, response) => { received.push(request.method); response.end('inert'); });
  await new Promise(resolve => server.listen(0, '127.0.0.1', resolve));
  const local = `http://127.0.0.1:${server.address().port}`;
  const guard = createNetworkGuard({ ...config, origin: local });
  try {
    for (const method of ['GET', 'POST', 'PUT', 'PATCH', 'DELETE', 'OPTIONS']) {
      let aborted = false;
      const request = { method: () => method, url: () => local + '/assets/actual.js' };
      await guard.route({ request: () => request, abort: async () => { aborted = true; },
        fetch: async options => {
          assert.equal(options.maxRedirects, 0);
          const response = await fetch(request.url(), { method, redirect: 'manual' });
          return { status: () => response.status };
        }, fulfill: async () => {} });
      assert.equal(aborted, method !== 'GET');
    }
    assert.deepEqual(received, ['GET']);
    assert.equal(guard.report().forwardedWrites, 0);
    assert.equal(guard.report().blocked, 5);
  } finally { await new Promise((resolve, reject) => server.close(error => error ? reject(error) : resolve())); }
});

test('guard refuses malformed configuration and keeps unknown failure data private', async () => {
  const { createNetworkGuard, safeDiagnostic } = await browser();
  for (const value of ['', 'http://user:secret@127.0.0.1', 'https://remote.invalid']) {
    assert.throws(() => createNetworkGuard({ ...config, origin: value }));
  }
  assert.deepEqual(safeDiagnostic(Error('SECRET')), { category: 'core-browser-failed' });
});

test('cleanup tries every owned operation and preserves primary failure without content', async () => {
  const { closeOwned } = await browser();
  const calls = [];
  const result = await closeOwned([{ name: 'promises', close: async () => { calls.push(1); throw Error('SECRET'); } },
    { name: 'browser', close: async () => { calls.push(2); } }], { category: 'first-checkpoint-failed' });
  assert.deepEqual(calls, [1, 2]);
  assert.equal(result.primary.category, 'first-checkpoint-failed');
  assert.deepEqual(result.cleanupErrors, ['promises']);
  assert.ok(!JSON.stringify(result).includes('SECRET'));
});

test('deferred API requests remain pending until exact owned release', async () => {
  const { installDeferred } = await deferred();
  const { world, forwarded } = inertWorld();
  const adapter = installDeferred({ runId, origin, steps: [step('old')] }, world);
  let done = false;
  const promise = world.fetch(origin + '/api/publications/translations/reviews').then(r => r.json()).then(v => { done = true; return v; });
  await Promise.resolve(); assert.equal(done, false);
  assert.deepEqual(adapter.report().pending, ['old']);
  adapter.release('old', 'success');
  assert.deepEqual(await promise, { project: 'A', reviews: [] });
  assert.equal(forwarded(), 0); adapter.restore();
});

test('first-render checkpoint is synchronous before returning the held fetch promise', async () => {
  const { installDeferred } = await deferred();
  const { world } = inertWorld();
  world.document.querySelector = () => ({ value: 'B' });
  const adapter = installDeferred({ runId, origin, steps: [step('B', { checkpoint: { project: 'B', absent: ['OLD-A'] } })] }, world);
  const pending = world.fetch('/api/publications/translations/reviews');
  assert.equal(adapter.report().checkpoints[0].id, 'B');
  assert.equal(adapter.report().checkpoints[0].ok, true);
  adapter.release('B', 'success'); await pending; adapter.restore();
});

test('wrong first-frame DOM latches failure and cannot receive later polling credit', async () => {
  const { installDeferred } = await deferred();
  const { world } = inertWorld();
  world.document.querySelector = () => ({ value: 'B' }); world.document.body.innerText = 'OLD-A';
  const adapter = installDeferred({ runId, origin, steps: [step('B', { checkpoint: { project: 'B', absent: ['OLD-A'] } })] }, world);
  await assert.rejects(world.fetch('/api/publications/translations/reviews'), /checkpoint/);
  world.document.body.innerText = '';
  assert.equal(adapter.report().failed, true); assert.equal(adapter.report().checkpoints[0].ok, false);
  adapter.restore();
});

test('unknown mutation method, identity, query or payload never falls through to native fetch', async () => {
  const { installDeferred } = await deferred();
  for (const [url, init] of [[origin + '/api/publications/translations/dispatch', { method: 'PUT', body: '{}' }],
    [origin + '/api/publications/translations/dispatch', { method: 'POST', body: '{"publication":"foreign"}' }],
    [origin + '/api/publications/translations/dispatch?extra=1', { method: 'POST', body: '{"publication":"owned"}' }],
    ['https://foreign.invalid/api/publications/translations/dispatch', { method: 'POST', body: '{}' }]]) {
    const { world, forwarded } = inertWorld();
    const adapter = installDeferred({ runId, origin, steps: [step('dispatch', { method: 'POST',
      path: '/api/publications/translations/dispatch', body: { publication: 'owned' } })] }, world);
    await assert.rejects(world.fetch(url, init)); assert.equal(forwarded(), 0); assert.equal(adapter.report().failed, true); adapter.restore();
  }
});

test('only exact payloads consume a step; duplicates and unknown releases refuse', async () => {
  const { installDeferred } = await deferred();
  const { world } = inertWorld();
  const adapter = installDeferred({ runId, origin, steps: [step('dispatch', { method: 'POST',
    path: '/api/publications/translations/dispatch', body: { publication: 'owned' } })] }, world);
  const pending = world.fetch('/api/publications/translations/dispatch', { method: 'POST', body: '{"publication":"owned"}' });
  adapter.release('dispatch', 'success'); await pending;
  await assert.rejects(world.fetch('/api/publications/translations/dispatch', { method: 'POST', body: '{"publication":"owned"}' }));
  assert.throws(() => adapter.release('dispatch', 'success')); adapter.restore();
});

test('weaker explicit event markers record order but supply no DOM-commit evidence', async () => {
  const { installDeferred } = await deferred();
  const { world } = inertWorld();
  const adapter = installDeferred({ runId, origin, steps: [step('A'), step('B')],
    requiredOrder: ['boundary:B', 'release:A', 'request:B'] }, world);
  const a = world.fetch('/api/publications/translations/reviews');
  adapter.markBoundary('B'); adapter.release('A', 'success'); await a;
  const b = world.fetch('/api/publications/translations/reviews'); adapter.release('B', 'success'); await b;
  assert.equal(adapter.assertComplete().ordered, true); adapter.restore();
  const next = installDeferred({ runId, origin, steps: [step('A'), step('B')],
    requiredOrder: ['boundary:B', 'release:A', 'request:B'] }, world);
  const x = world.fetch('/api/publications/translations/reviews'); next.markBoundary('B');
  const y = world.fetch('/api/publications/translations/reviews'); next.release('A', 'success'); next.release('B', 'success');
  await Promise.all([x, y]); assert.throws(() => next.assertComplete(), /order/); next.restore();
});

test('restoration settles owned promises and refuses another run or an overwritten fetch', async () => {
  const { installDeferred } = await deferred();
  const { world } = inertWorld(); const original = world.fetch;
  const adapter = installDeferred({ runId, origin, steps: [step('held')] }, world);
  const pending = world.fetch('/api/publications/translations/reviews');
  const outcome = assert.rejects(pending, /cleanup/); adapter.restore(); await outcome;
  assert.equal(world.fetch, original);
  const again = installDeferred({ runId, origin, steps: [] }, world);
  assert.throws(() => installDeferred({ runId: 'foreign', origin, steps: [] }, world));
  world.fetch = () => Promise.resolve(new Response('foreign'));
  assert.throws(() => again.restore(), /ownership/);
});

test('seven UI entries cover six runners with publication-only Dispatch/Retry and approval chain', async () => {
  const { entrySpecifications, makeWireFixture } = await scenarios();
  assert.equal(entrySpecifications.length, 7); assert.equal(new Set(entrySpecifications.map(x => x.runner)).size, 6);
  for (const entry of entrySpecifications) {
    const wire = makeWireFixture(runId, 'A', entry.id);
    assert.ok(wire.title.includes(runId)); assert.equal(wire.project, runId + '-A');
    if (entry.runner === 'dispatch') assert.deepEqual(wire.mutation.body, { publication: wire.publication });
    if (entry.runner === 'approval') assert.equal(wire.followup.path, '/api/publications/reconcile');
  }
});

test('authentication opens only exact context GET and is sealed before controlled traffic', async () => {
  const { createNetworkGuard } = await browser(); const guard = createNetworkGuard(config);
  const request = { method: 'GET', url: origin + '/api/auth/context' };
  assert.equal(guard.decide(request), 'abort'); guard.authenticate();
  assert.equal(guard.decide(request), 'continue');
  assert.equal(guard.decide({ ...request, method: 'POST' }), 'abort');
  assert.equal(guard.decide({ ...request, url: request.url + '?other=1' }), 'abort');
  guard.seal(); assert.equal(guard.decide(request), 'abort');
});

test('read-only redirect refuses before following a foreign or unreviewed target', async () => {
  const { createNetworkGuard } = await browser(); const guard = createNetworkGuard(config);
  let aborted = 0, continued = 0, fetches = 0, fulfilled = 0;
  await guard.route({ request: () => ({ method: () => 'GET', url: () => origin + '/assets/actual.js' }),
    fetch: async options => { fetches += 1; assert.equal(options.maxRedirects, 0); return { status: () => 302 }; },
    abort: async () => { aborted += 1; }, continue: async () => { continued += 1; },
    fulfill: async () => { fulfilled += 1; } });
  assert.deepEqual({ aborted, continued, fetches, fulfilled }, { aborted: 1, continued: 0, fetches: 1, fulfilled: 0 });
});

test('first observer result is sticky and an absent delivery never completes', async () => {
  const { installDeferred } = await deferred(); const { world } = inertWorld();
  const callbacks = [];
  world.MutationObserver = class { constructor(fn) { callbacks.push(fn); } observe() {} disconnect() {} };
  world.document.body.innerText = 'OLD';
  const adapter = installDeferred({ runId, origin, steps: [] }, world);
  adapter.watchCommit('B', { absent: ['OLD'] });
  assert.throws(() => adapter.assertComplete(), /first-commit/);
  callbacks[0](); world.document.body.innerText = '';
  callbacks[0]();
  assert.equal(adapter.report().checkpoints[0].ok, false);
  assert.throws(() => adapter.assertComplete(), /incomplete/); adapter.restore();
});

test('only fixed background GET fixtures are available and never use native fetch', async () => {
  const { installDeferred } = await deferred(); const { world, forwarded } = inertWorld();
  const adapter = installDeferred({ runId, origin, steps: [], background: { '/api/config': { inert: true } } }, world);
  assert.deepEqual(await (await world.fetch('/api/config')).json(), { inert: true });
  assert.equal(adapter.report().background.length, 1); assert.equal(forwarded(), 0);
  await assert.rejects(world.fetch('/api/config', { method: 'POST', body: '{}' }));
  assert.equal(forwarded(), 0); adapter.restore();
});

test('websocket backstop closes known background sockets and latches unknown destinations', async () => {
  const { createNetworkGuard } = await browser(); const guard = createNetworkGuard(config);
  let closed = 0;
  for (const url of ['ws://127.0.0.1:42591/ws/stream?session_id=controlled', 'wss://foreign.invalid/ws/stream',
    'ws://127.0.0.1:42591/ws/stream?unreviewed=1']) guard.websocket({ url: () => url, close: () => { closed += 1; } });
  assert.equal(closed, 3); assert.equal(guard.report().unknownWebsockets, 2);
});

test('unknown category strings cannot smuggle credential content into diagnostics', async () => {
  const { safeDiagnostic } = await browser();
  assert.deepEqual(safeDiagnostic({ category: 'core-secret-api-key-value' }), { category: 'core-browser-failed' });
  assert.deepEqual(safeDiagnostic({ category: 'core-interrupted' }), { category: 'core-interrupted' });
});

test('direct owned adapter cleanup is available after CLI work has been stopped', async () => {
  const { restoreOwnedAdapter } = await browser(); let restored = 0;
  const world = { __knoxxControlledCore: { report: () => ({ runId: runId + '-12' }),
    restore: () => { restored += 1; delete world.__knoxxControlledCore; } } };
  assert.deepEqual(restoreOwnedAdapter({ runId }, world), { restored: true });
  assert.equal(restored, 1);
  assert.deepEqual(restoreOwnedAdapter({ runId }, world), { restored: false });
  const foreign = { __knoxxControlledCore: { report: () => ({ runId: 'foreign-run-12' }), restore: () => { restored += 1; } } };
  assert.throws(() => restoreOwnedAdapter({ runId }, foreign), /ownership/);
  assert.equal(restored, 1);
});

test('browser process environment excludes tour, provider and account credentials', async () => {
  const { browserEnvironment } = await browser();
  const env = browserEnvironment({ PATH: '/owned/bin', TMPDIR: '/tmp/owned', KNOXX_API_KEY: 'SECRET',
    OPENAI_API_KEY: 'SECRET', GH_TOKEN: 'SECRET', GITHUB_TOKEN: 'SECRET', HTTP_PROXY: 'SECRET', HOME: '/ambient' });
  assert.equal(env.PATH, '/owned/bin'); assert.equal(env.TMPDIR, '/tmp/owned');
  assert.equal(env.HTTP_PROXY, ''); assert.ok(!JSON.stringify(env).includes('SECRET'));
  assert.equal(Object.hasOwn(env, 'HOME'), false);
});

async function observerReleaseSchedule(earlyB, committed = { value: 'B', attribute: 'B' }) {
  const { installDeferred } = await deferred(); const { world, forwarded } = inertWorld(); let observed;
  world.document.querySelector = () => ({ value: 'B', defaultValue: committed.value, getAttribute: () => committed.attribute });
  world.MutationObserver = class { constructor(fn) { observed = fn; } observe() {} disconnect() {} };
  const adapter = installDeferred({ runId, origin, steps: [step('A-inventory'),
    step('A-manifest', { path: '/api/translations/export/manifest' }), step('B-inventory', { hold: false })],
    requiredOrder: ['commit:B', 'release:A-inventory', 'release:A-manifest', 'request:B-inventory'] }, world);
  const owned = [world.fetch('/api/publications/translations/reviews'), world.fetch('/api/translations/export/manifest')];
  const settled = Promise.allSettled(owned);
  try {
    adapter.watchCommit('B', { project: 'B', committedProject: 'B', absent: ['OLD-A'], formAbsent: true },
      { release: ['A-inventory', 'A-manifest'], beforeRequest: 'B-inventory' });
    if (earlyB) await world.fetch('/api/publications/translations/reviews');
    observed();
    const afterObserver = adapter.report();
    // Read before awaiting: old v5 leaves both A handles pending, so the causal
    // positive assertion fails directly rather than manufacturing a timeout.
    if (!earlyB && afterObserver.pending.length === 0) {
      await settled; await world.fetch('/api/publications/translations/reviews');
    }
    let complete = false;
    try { adapter.assertComplete(); complete = true; } catch { /* Explicit refusal returned as test evidence. */ }
    return { afterObserver, complete, forwarded: forwarded() };
  } finally { adapter.restore(); await settled; }
}

test('observer B checkpoint releases both A handles before the first B request', async () => {
  const proof = await observerReleaseSchedule(false);
  assert.deepEqual(proof.afterObserver.pending, []);
  assert.equal(proof.complete, true); assert.equal(proof.forwarded, 0);
  assert.deepEqual(proof.afterObserver.records.slice(-3), ['commit:B', 'release:A-inventory', 'release:A-manifest']);
});

test('B request before observer delivery refuses timing credit and retains A handles', async () => {
  const proof = await observerReleaseSchedule(true);
  assert.equal(proof.afterObserver.failed, true); assert.equal(proof.complete, false);
  assert.deepEqual(proof.afterObserver.pending, ['A-inventory', 'A-manifest']);
  assert.equal(proof.afterObserver.records.some(x => x.startsWith('release:')), false);
  assert.equal(proof.forwarded, 0);
});

test('live input B with old defaultValue A cannot release A responses', async () => {
  const proof = await observerReleaseSchedule(false, { value: 'A', attribute: 'B' });
  assert.equal(proof.afterObserver.failed, true); assert.equal(proof.complete, false);
  assert.deepEqual(proof.afterObserver.pending, ['A-inventory', 'A-manifest']);
  assert.equal(proof.afterObserver.records.some(x => x.startsWith('release:')), false);
});

test('live input B with old value attribute A cannot release A responses', async () => {
  const proof = await observerReleaseSchedule(false, { value: 'B', attribute: 'A' });
  assert.equal(proof.afterObserver.failed, true); assert.equal(proof.complete, false);
  assert.deepEqual(proof.afterObserver.pending, ['A-inventory', 'A-manifest']);
  assert.equal(proof.afterObserver.records.some(x => x.startsWith('release:')), false);
});


test('native CLI URL data object reaches the exact owned-page guard', async () => {
  const { assertOwnedCLIURL } = await browser();
  // agent-browser handleUrl -> successResponse -> cliFor's existing data unwrap.
  const native = { id: 'owned-url', success: true, data: { url: origin + '/cms' } };
  const value = native.data?.result ?? native.data ?? native;
  assert.doesNotThrow(() => assertOwnedCLIURL(value, origin + '/cms'));
});

test('owned-page URL guard refuses wrong native types, missing data and every other URL', async () => {
  const { assertOwnedCLIURL } = await browser();
  for (const value of [null, [], origin + '/cms', {}, { url: null }, { url: 1 }, { url: '' },
    { url: origin + '/translations' }, { url: 'https://foreign.invalid/cms' },
    { url: origin + '/cms?changed=1' }, Object.create({ url: origin + '/cms' })]) {
    assert.throws(() => assertOwnedCLIURL(value, origin + '/cms'));
  }
});


const backgroundContracts = [
  ['existing CMS recent10', '/api/memory/sessions?limit=10', { rows: [], total: 0, has_more: false }],
  ['mounted chat recent20', '/api/memory/sessions?limit=20&excludeActorIds=eta-mu', { rows: [], total: 0, has_more: false }],
  ['mounted chat docs browse', '/api/ingestion/browse?path=docs', { workspace_root: '/synthetic-core', current_path: 'docs', entries: [] }],
  ['owned public chat status', `/api/knoxx/session/status?session_id=${runId}-cms-session&conversation_id=${runId}-cms-conversation`,
    { session_id: runId + '-cms-session', conversation_id: runId + '-cms-conversation', status: 'not_found',
      has_active_stream: false, can_send: true, reason: 'No session state found. Ready for new turn.' }],
];
for (const [name, url, expected] of backgroundContracts) test(name + ' is synthetic at both actual boundaries', async () => {
  const { createNetworkGuard } = await browser(); const { backgroundResponses } = await scenarios();
  const { installDeferred } = await deferred(); const background = backgroundResponses(runId);
  const guard = createNetworkGuard({ ...config, background }); let body, aborted = 0, nativeCalls = 0;
  await guard.route({ request: () => ({ method: () => 'GET', url: () => origin + url, postData: () => null }),
    fulfill: async result => { body = JSON.parse(result.body); }, abort: async () => { aborted += 1; },
    fetch: async () => { nativeCalls += 1; throw Error('uncontrolled'); } });
  const { world, forwarded } = inertWorld(); const adapter = installDeferred({ runId, origin, steps: [], background }, world);
  try {
    const windowBody = await world.fetch(url).then(result => result.json(), () => null);
    assert.deepEqual({ aborted, body, windowBody, nativeCalls, forwarded: forwarded() },
      { aborted: 0, body: expected, windowBody: expected, nativeCalls: 0, forwarded: 0 });
  } finally { adapter.restore(); }
});

test('background fixtures refuse every non-GET, body, fragment and unowned query at both boundaries', async () => {
  const { createNetworkGuard } = await browser(); const { backgroundResponses } = await scenarios();
  const { installDeferred } = await deferred(); const background = backgroundResponses(runId);
  const guard = createNetworkGuard({ ...config, background }); const outcomes = []; let nativeCalls = 0;
  const { world, forwarded } = inertWorld(); const adapter = installDeferred({ runId, origin, steps: [], background }, world);
  const status = backgroundContracts[3][1];
  try {
    for (const [method, url, body] of [['HEAD', '/api/config'], ['POST', '/api/config'], ['GET', '/api/config', '{}'],
      ['GET', '/api/config#fragment'], ['GET', '/api/config?extra=1'], ['GET', status.replace('-cms-session', '-foreign-session')],
      ['GET', status.replace('-cms-conversation', '-foreign-conversation')], ['GET', status + '&session_id=duplicate'],
      ['GET', '/api/ingestion/browse?path=..%2Fprivate'], ['GET', 'https://foreign.invalid/api/config']]) {
      let aborted = false, fulfilled = false;
      await guard.route({ request: () => ({ method: () => method, url: () => new URL(url, origin).href, postData: () => body ?? null }),
        abort: async () => { aborted = true; }, fulfill: async () => { fulfilled = true; },
        fetch: async () => { nativeCalls += 1; throw Error('uncontrolled'); } });
      const refused = await world.fetch(url, { method, ...(body === undefined ? {} : { body }) }).then(() => false, () => true);
      outcomes.push({ aborted, fulfilled, refused });
    }
    assert.deepEqual(outcomes, Array.from({ length: 10 }, () => ({ aborted: true, fulfilled: false, refused: true })));
    assert.equal(nativeCalls, 0); assert.equal(forwarded(), 0);
    assert.equal(guard.decide({ method: 'HEAD', url: origin + '/assets/actual.js' }), 'continue');
  } finally { adapter.restore(); }
});


function publicChatWorld(values = {}) {
  const data = new Map(Object.entries(values)); let writes = 0;
  return { data, writes: () => writes, location: { origin }, localStorage: {
    getItem: key => data.get(key) ?? null, setItem: (key, value) => { writes += 1; data.set(key, value); } } };
}
test('owned public chat initialization preserves later app snapshot bytes and refuses foreign origin access', async () => {
  const { installOwnedChatStorage } = await browser(); const world = publicChatWorld();
  assert.equal(installOwnedChatStorage(config, world), true); assert.equal(world.writes(), 2);
  const sessionId = runId + '-cms-session', conversationId = runId + '-cms-conversation';
  const key = 'knoxx_cms_chat_session_state:' + sessionId;
  assert.deepEqual(JSON.parse(world.data.get(key)), { sessionId, conversationId, activeActorId: 'cms_chat',
    messages: [], latestRun: null, runtimeEvents: [], isSending: false });
  const updated = JSON.stringify({ sessionId, conversationId, messages: [{ content: 'owned synthetic text' }], selectedModel: 'synthetic' });
  world.data.set(key, updated); assert.equal(installOwnedChatStorage(config, world), true);
  assert.equal(world.data.get(key), updated); assert.equal(world.writes(), 2);
  assert.equal(installOwnedChatStorage(config, { location: { origin: 'https://foreign.invalid' },
    get localStorage() { throw Error('foreign storage must never be inspected'); } }), false);
});
test('foreign public chat identities or malformed snapshots refuse before any write', async () => {
  const { installOwnedChatStorage } = await browser(); const key = 'knoxx_cms_chat_session_state:' + runId + '-cms-session';
  for (const values of [{ knoxx_cms_chat_session_id: 'foreign' }, { [key]: '{malformed' }, { [key]: '[]' },
    { [key]: JSON.stringify({ sessionId: 'foreign', conversationId: runId + '-cms-conversation' }) },
    { [key]: JSON.stringify({ sessionId: runId + '-cms-session', conversationId: 'foreign' }) }]) {
    const world = publicChatWorld(values); assert.throws(() => installOwnedChatStorage(config, world), /core-chat-/);
    assert.equal(world.writes(), 0); assert.deepEqual(Object.fromEntries(world.data), values);
  }
});


test('failure diagnostics retain finite stage and current case while every original value stays private', async () => {
  const { safeDiagnostic } = await browser(); const secret = 'NEVER-RETAIN-DIAGNOSTIC-SECRET';
  const failure = Object.assign(Error(secret), { code: 'ERR_ASSERTION', actual: secret, expected: secret, stack: secret });
  const progress = { stage: 'scenarios', caseNumber: 1, caseName: 'legacy-segment:project-success', completedCases: 0 };
  assert.deepEqual(safeDiagnostic(failure, progress), { category: 'core-browser-failed', ...progress,
    errorName: 'Error', errorCode: 'ERR_ASSERTION' });
  const unknown = { category: secret, name: secret, code: secret, message: secret, stack: secret, actual: secret, expected: secret };
  assert.deepEqual(safeDiagnostic(unknown, { stage: secret, caseNumber: 58, caseName: secret, completedCases: 58 }),
    { category: 'core-browser-failed' });
  const accessor = Object.fromEntries(['category', 'name', 'code', 'message', 'stack'].map(key => [key, undefined]));
  for (const key of Object.keys(accessor)) Object.defineProperty(accessor, key, { get() { throw Error(secret); } });
  assert.deepEqual(safeDiagnostic(accessor, progress), { category: 'core-browser-failed', ...progress });
  assert.ok(!JSON.stringify(safeDiagnostic(failure, progress)).includes(secret));
});

test('scenario failure reports its first exact case before navigation and still restores without replacing primary', async () => {
  const { runCoreScenarios } = await scenarios(); const { safeDiagnostic, closeOwned } = await browser();
  const primary = Error('PRIVATE-PRIMARY'), observed = [], calls = []; let first = true;
  await assert.rejects(runCoreScenarios({ onCase: value => observed.push(value), cli: async (...args) => {
    calls.push(args[0]); if (first) { first = false; throw primary; } return true;
  } }), error => error === primary);
  assert.deepEqual(observed, [{ stage: 'scenarios', caseNumber: 1, caseName: 'legacy-segment:project-success', completedCases: 0 }]);
  assert.deepEqual(calls, ['eval', 'eval']);
  const diagnostic = safeDiagnostic(primary, observed[0]), closed = [];
  const result = await closeOwned([{ name: 'first', close: async () => { closed.push('first'); throw Error('PRIVATE-CLEANUP'); } },
    { name: 'second', close: async () => { closed.push('second'); } }], diagnostic);
  assert.strictEqual(result.primary, diagnostic); assert.deepEqual(closed, ['first', 'second']);
  assert.deepEqual(result.cleanupErrors, ['first']); assert.equal(result.primary.caseNumber, 1);
  assert.ok(!JSON.stringify(result).includes('PRIVATE'));
});


for (const value of ['Error', 'null']) test(`scenario restore double failure retains original ${value} identity and fixed cleanup name`, async () => {
  const { runCoreScenarios } = await scenarios(); const { safeDiagnostic, closeOwned } = await browser();
  const primary = value === 'null' ? null : Error('PRIVATE-PRIMARY');
  const secondary = Error('PRIVATE-RESTORE'), cleanupNames = []; let calls = 0, thrown = false, actual;
  try {
    await runCoreScenarios({ onCleanupError: name => { cleanupNames.push(name); throw Error('PRIVATE-OBSERVER'); },
      cli: async () => { calls += 1; throw calls === 1 ? primary : secondary; } });
  } catch (error) { thrown = true; actual = error; }
  assert.equal(thrown, true); assert.strictEqual(actual, primary); assert.equal(calls, 2);
  assert.deepEqual(cleanupNames, ['scenario-adapter-restore']);
  const diagnostic = safeDiagnostic(actual, { stage: 'scenarios', caseNumber: 1,
    caseName: 'legacy-segment:project-success', completedCases: 0 });
  const cleanup = await closeOwned([{ name: 'remaining-browser', close: async () => {} }], diagnostic);
  assert.strictEqual(cleanup.primary, diagnostic); assert.equal(cleanup.cleanupComplete, true);
  assert.ok(!JSON.stringify({ diagnostic, cleanupNames, cleanup }).includes('PRIVATE'));
});


// Capture the actual first projectCase configuration through the real public
// scenario entry; no copied requiredOrder/checkpoint or new product test seam.
async function firstProjectCaseConfiguration() {
  const { runInNewContext } = await import('node:vm');
  const { runCoreScenarios } = await scenarios();
  const stopped = Error('owned-first-project-configuration-captured'); let captured;
  const world = { document: { querySelectorAll: () => [
    { getAttribute: () => '/cms', click() {} },
  ] }, __capture(value) { captured = JSON.parse(JSON.stringify(value)); throw stopped; } };
  world.window = world;
  await assert.rejects(runCoreScenarios({ config: { ...config, evidenceDir: '/inert-no-write' },
    installSource: 'value => globalThis.__capture(value)',
    cli: async (command, source) => {
      assert.equal(command, 'eval'); return runInNewContext(source, world);
    } }), error => error === stopped);
  assert.ok(captured); return captured;
}

async function projectRequestObserverSchedule({ staleAtRequest = false, earlyA = false } = {}) {
  const configuration = await firstProjectCaseConfiguration();
  const { installDeferred } = await deferred(); const { world, forwarded } = inertWorld();
  const inventory = configuration.steps.find(value => value.id === 'B-inventory');
  const rule = inventory.checkpoint; let observed, current = 'A';
  world.document.querySelector = () => ({ value: current, defaultValue: current,
    getAttribute: () => current });
  world.MutationObserver = class { constructor(fn) { observed = fn; } observe() {} disconnect() {} };
  const adapter = installDeferred(configuration, world), outstanding = [];
  const request = value => {
    const query = new URLSearchParams(value.query).toString();
    const promise = world.fetch(value.path + (query ? '?' + query : ''),
      { method: value.method, ...(Object.hasOwn(value, 'body') ? { body: JSON.stringify(value.body) } : {}) });
    outstanding.push(promise); return promise;
  };
  try {
    for (const value of configuration.steps.filter(value => value.id.startsWith('A-'))) await request(value);
    const mutation = request(configuration.steps.find(value => value.id === 'mutation-A'));
    adapter.watchCommit('B', rule);
    // Negative control completes A while the DOM still identifies old A.
    if (earlyA) { adapter.release('mutation-A', 'success'); await mutation; }
    current = rule.project;
    world.document.body.innerText = staleAtRequest ? rule.absent[0] : '';
    const b = request(inventory).then(() => true, () => false);
    const manifest = request(configuration.steps.find(value => value.id === 'B-manifest'));
    // Reproduce actual v4: the request checkpoint runs before observer delivery.
    // A stale request is sticky even if the later observer sees a clean DOM.
    world.document.body.innerText = ''; observed();
    if (!earlyA) { adapter.release('mutation-A', 'success'); await mutation; }
    const beforeBRelease = adapter.report();
    if (beforeBRelease.pending.includes('B-inventory')) adapter.release('B-inventory', 'success');
    adapter.release('B-manifest', 'success'); await Promise.all([b, manifest]);
    for (const value of configuration.steps.filter(value => value.id.startsWith('B-') && !value.hold)) await request(value);
    let complete = false;
    try { adapter.assertComplete(); complete = true; } catch { /* Retain explicit contract refusal. */ }
    return { complete, beforeBRelease, forwarded: forwarded() };
  } finally { adapter.restore(); await Promise.allSettled(outstanding); }
}

test('project case accepts committed clean B request before observer, both before A completion', async () => {
  const proof = await projectRequestObserverSchedule();
  assert.equal(proof.complete, true);
  assert.deepEqual(proof.beforeBRelease.checkpoints.map(value => value.ok), [true, true]);
  assert.deepEqual(proof.beforeBRelease.pending, ['B-inventory', 'B-manifest']);
  const records = proof.beforeBRelease.records;
  assert.ok(records.indexOf('request:B-inventory') < records.indexOf('commit:B'));
  assert.ok(records.indexOf('commit:B') < records.indexOf('release:mutation-A'));
  assert.equal(proof.forwarded, 0);
});

test('project case refuses stale A DOM at B request even when later observer is clean', async () => {
  const proof = await projectRequestObserverSchedule({ staleAtRequest: true });
  assert.equal(proof.complete, false);
  assert.deepEqual(proof.beforeBRelease.checkpoints.map(value => value.ok), [false, true]);
  assert.equal(proof.beforeBRelease.failed, true); assert.equal(proof.forwarded, 0);
});

test('project case refuses A completion before committed B even when request and observer later pass', async () => {
  const proof = await projectRequestObserverSchedule({ earlyA: true });
  assert.equal(proof.complete, false);
  assert.deepEqual(proof.beforeBRelease.checkpoints.map(value => value.ok), [true, true]);
  const records = proof.beforeBRelease.records;
  assert.ok(records.indexOf('release:mutation-A') < records.indexOf('request:B-inventory'));
  assert.equal(proof.forwarded, 0);
});


// Synthetic public-runner fixture: no Chromium, app, HTTP authentication or DB.
// The two clients share only the modeled native download directory. Actual
// copyFile exercises the same missing-original-artifact boundary as Playwright.
async function inertPublicDownloadOwner({ saveFailure, verifyMissing = false,
  sendFailure, detachFailure } = {}) {
  const fs = await import('node:fs/promises'), sync = await import('node:fs');
  const path = await import('node:path'), os = await import('node:os');
  const { createHash, randomUUID } = await import('node:crypto');
  const { EventEmitter } = await import('node:events');
  const { createServer: createPort } = await import('node:net');
  const root = await fs.mkdtemp(path.join(os.tmpdir(), 'cms-owned-download-unit-'));
  const key = randomUUID(), evidence = path.join(root, 'evidence');
  const implicit = path.join(root, 'implicit-owner'), remote = path.join(root, 'second-CDP');
  const stateFile = path.join(root, 'synthetic-state.json');
  const text = 'controlled public export unit bytes\n', filename = 'controlled-translations.jsonl';
  const getState = () => JSON.parse(sync.readFileSync(stateFile, 'utf8'));
  const change = fn => { const state = getState(); fn(state); sync.writeFileSync(stateFile, JSON.stringify(state)); };
  const event = value => change(state => state.events.push(value));
  await fs.mkdir(implicit); await fs.mkdir(remote);
  await fs.writeFile(stateFile, JSON.stringify({ events: [], destination: implicit }), { mode: 0o600 });
  let launchOptions, messageReads = 0;
  const sentinel = saveFailure === 'null' ? null : Object.freeze(Object.defineProperties(Error(), {
    message: { get() { messageReads += 1; throw Error('SYNTHETIC-PRIVATE'); } },
    cause: { value: Object.freeze(Object.assign(Error('SYNTHETIC-CAUSE'), { code: 'ENOENT' })) },
  }));
  const page = new EventEmitter();
  page.url = () => 'about:blank'; page.goto = async () => {};
  page.evaluate = async (fn, argument) => {
    if (typeof argument === 'string') change(state => { state.nonce = argument; });
    if (fn.toString().includes("fetch('/api/auth/context'")) return { status: 200, orgId: 'synthetic-org', email: 'synthetic@unit.invalid' };
    return { restored: false };
  };
  const originalOn = page.on;
  page.on = function (name, listener) {
    originalOn.call(this, name, listener);
    if (name === 'download') {
      event('download-listener');
      sync.writeFileSync(path.join(getState().destination, 'synthetic-guid'), text, { mode: 0o600 });
      this.emit('download', {
        suggestedFilename: () => filename,
        saveAs: async target => {
          if (saveFailure) throw sentinel;
          await fs.copyFile(path.join(launchOptions.downloadsPath ?? implicit, 'synthetic-guid'), target);
          if (verifyMissing) await fs.unlink(target);
        },
      });
    }
    return this;
  };
  const context = { pages: () => [page], route: async () => {}, routeWebSocket: async () => {},
    addInitScript: async () => {}, setExtraHTTPHeaders: async () => {},
    close: async () => event('context-close'), browser: () => nativeBrowser };
  const nativeBrowser = { contexts: () => [context], newBrowserCDPSession: async () => ({
    send: async (method, parameters) => {
      assert.equal(method, 'Browser.setDownloadBehavior');
      event('claim');
      if (sendFailure) throw sendFailure;
      assert.equal(parameters.behavior, 'allowAndName'); assert.equal(parameters.eventsEnabled, true);
      change(state => { state.destination = parameters.downloadPath; state.claimed = parameters.downloadPath; });
    },
    detach: async () => { event('detach'); if (detachFailure) throw detachFailure; },
  }) };
  const registry = globalThis.__cmsSyntheticDownloadOwners ??= new Map();
  registry.set(key, { launch: async (profile, options) => { launchOptions = options; return context; } });
  const moduleFile = path.join(root, 'synthetic-playwright.mjs');
  await fs.writeFile(moduleFile, `export const chromium = { launchPersistentContext: globalThis.__cmsSyntheticDownloadOwners.get(${JSON.stringify(key)}).launch };`);
  const executable = path.join(root, 'synthetic-cli.cjs');
  await fs.writeFile(executable, `#!${process.execPath}\nconst fs=require('node:fs');const file=${JSON.stringify(stateFile)};const a=process.argv.slice(2),op=a[5];const state=JSON.parse(fs.readFileSync(file,'utf8'));let value;let success=true;if(op==='eval'&&a[6]==='window.__coreOwner'){state.events.push('nonce');value=state.nonce;}else if(op==='get'){state.events.push('url');value={url:${JSON.stringify(origin + '/cms')}};}else if(op==='set'){state.events.push('media');state.destination=${JSON.stringify(remote)};value=true;}else if(op==='close'){state.events.push('cli-close');value=true;}else if(op==='eval'&&a[6].includes('const a=window.__knoxxControlledCore')){value=true;}else{state.events.push('scenario-stop');success=false;}fs.writeFileSync(file,JSON.stringify(state));process.stdout.write(JSON.stringify({success,data:{result:value}}));\n`, { mode: 0o700 });
  const source = path.join(root, 'synthetic-source');
  await fs.mkdir(path.join(source, 'backend/dist'), { recursive: true });
  await fs.mkdir(path.join(source, 'frontend/dist'), { recursive: true });
  const server = path.join(source, 'backend/dist/server.js'), browsers = path.join(root, 'browsers.json');
  await fs.writeFile(server, '// inert unit artifact, never imported\n'); await fs.writeFile(browsers, '{}');
  const sha = value => createHash('sha256').update(value).digest('hex');
  const token = path.join(root, 'synthetic-key'); await fs.writeFile(token, 'SYNTHETIC-NOT-A-CREDENTIAL-123456', { mode: 0o600 });
  const sourceCommit = 'a'.repeat(40), project = 'synthetic-project';
  const preflight = path.join(root, 'synthetic-preflight.json'), writer = path.join(root, 'synthetic-writer.json');
  await fs.writeFile(preflight, JSON.stringify({ source_commit: sourceCommit, run_id: runId, org_id: 'synthetic-org',
    project, sft_anonymous_status: 401, dispatch_anonymous_status: 401, state_unchanged: true, state_sha256: 'b'.repeat(64) }));
  await fs.writeFile(writer, JSON.stringify({ sourceCommit, normalExit: 0, calls: { queue: 0, agent: 0, eventTurn: 0 }, actualRouteGuards: true }));
  const port = createPort(); await new Promise(resolve => port.listen(0, '127.0.0.1', resolve));
  const cdpPort = port.address().port; await new Promise(resolve => port.close(resolve));
  const { runBrowserProof } = await browser();
  try {
    await assert.rejects(runBrowserProof({ ...config, sourceRoot: source, sourceCommit, evidenceDir: evidence,
      profileDir: path.join(evidence, 'profile'), cdpPort, orgId: 'synthetic-org', project,
      email: 'synthetic@unit.invalid', orgSlug: 'synthetic-org', apiKeyFile: token,
      playwrightEntry: moduleFile, chromiumExecutable: '/inert-no-browser', agentBrowserExecutable: executable,
      frozenFiles: [{ path: server, sha256: sha(await fs.readFile(server)) }, { path: browsers, sha256: sha(await fs.readFile(browsers)) }],
      preflight: { path: preflight, sha256: sha(await fs.readFile(preflight)) },
      writerProof: { path: writer, sha256: sha(await fs.readFile(writer)) },
    }), /core-browser-proof-failed/);
    const proof = JSON.parse(await fs.readFile(path.join(evidence, 'core-browser-proof.json'), 'utf8'));
    const copy = await fs.readFile(path.join(evidence, 'owned-export-0.jsonl')).catch(() => null);
    return { proof, state: getState(), ownerPath: launchOptions.downloadsPath,
      expectedPath: path.join(evidence, 'native-downloads'), copied: copy?.toString(), text,
      messageReads, originalCauseIdentity: sentinel?.cause };
  } finally { registry.delete(key); await fs.rm(root, { recursive: true, force: true }); }
}

test('public inert owner reclaims exact original download path after second CDP attach', async () => {
  const result = await inertPublicDownloadOwner();
  assert.equal(result.proof.cleanupComplete, true);
  assert.equal(result.copied, result.text);
  assert.equal(result.ownerPath, result.expectedPath); assert.equal(result.state.claimed, result.ownerPath);
  const events = result.state.events;
  assert.ok(events.indexOf('nonce') < events.indexOf('url'));
  assert.ok(events.indexOf('url') < events.indexOf('media'));
  assert.ok(events.indexOf('media') < events.indexOf('claim'));
  assert.ok(events.indexOf('claim') < events.indexOf('download-listener'));
  assert.ok(events.indexOf('download-listener') < events.indexOf('scenario-stop'));
  assert.ok(events.includes('detach') && events.includes('cli-close') && events.includes('context-close'));
});

test('public inert owner preserves send failure and attempts detach CLI and context cleanup', async () => {
  const cause = Object.freeze(Error('SYNTHETIC-PRIVATE-CAUSE'));
  const primary = Object.freeze(Object.assign(TypeError('SYNTHETIC-PRIVATE-PRIMARY'), { cause }));
  const result = await inertPublicDownloadOwner({ sendFailure: primary, detachFailure: Error('SYNTHETIC-PRIVATE-SECONDARY') });
  assert.equal(result.proof.primary.stage, 'cli-downloads'); assert.equal(result.proof.primary.errorName, 'TypeError');
  assert.deepEqual(result.proof.cleanupErrors, ['download-transport']);
  assert.ok(result.state.events.includes('detach') && result.state.events.includes('cli-close') && result.state.events.includes('context-close'));
  assert.equal(primary.cause, cause); assert.ok(!JSON.stringify(result.proof).includes('SYNTHETIC-PRIVATE'));
});

test('public inert owner retains safe save-as and cause metadata without reading raw messages', async () => {
  const result = await inertPublicDownloadOwner({ saveFailure: 'error' });
  assert.deepEqual(result.proof.downloadFailures, [{ index: 0, stage: 'save-as', thrownKind: 'object',
    primary: { category: 'core-browser-failed', errorName: 'Error' },
    cause: { category: 'core-browser-failed', errorName: 'Error', errorCode: 'ENOENT' } }]);
  assert.deepEqual(result.proof.cleanupErrors, ['downloads']); assert.equal(result.messageReads, 0);
  assert.equal(result.originalCauseIdentity.code, 'ENOENT'); assert.ok(!JSON.stringify(result.proof).includes('SYNTHETIC-'));
});

test('public inert owner retains null download rejection without replacing it or claiming cleanup', async () => {
  const result = await inertPublicDownloadOwner({ saveFailure: 'null' });
  assert.deepEqual(result.proof.downloadFailures, [{ index: 0, stage: 'save-as', thrownKind: 'null',
    primary: { category: 'core-browser-failed' } }]);
  assert.deepEqual(result.proof.cleanupErrors, ['downloads']); assert.equal(result.proof.cleanupComplete, false);
});

test('public inert owner distinguishes saved-copy verification absence from save-as failure', async () => {
  const result = await inertPublicDownloadOwner({ verifyMissing: true });
  assert.deepEqual(result.proof.downloadFailures, [{ index: 0, stage: 'verify-file', thrownKind: 'object',
    primary: { category: 'core-browser-failed', errorName: 'Error', errorCode: 'ENOENT' } }]);
  assert.deepEqual(result.proof.cleanupErrors, ['downloads']); assert.equal(result.copied, undefined);
});


// Capture the existing settled runner's actual emitted data, stopping at its
// observer registration. No copied checkpoint/order or preceding53 schedules.
async function settledFeedbackConfiguration() {
  const { runInNewContext } = await import('node:vm');
  const { settledFeedbackCase } = await scenarios();
  const stopped = Error('owned-settled-configuration-captured'); let configuration, watch, download;
  const world = { __capture(value) {
    configuration = JSON.parse(JSON.stringify(value));
    world.__knoxxControlledCore = {
      report: () => ({ runId: configuration.runId, active: true }),
      release: () => {},
      watchCommit(id, rule, actions) { watch = { id, rule, actions }; throw stopped; },
    };
  } };
  world.window = world;
  await assert.rejects(settledFeedbackCase({ config: { ...config, evidenceDir: '/inert-no-write' },
    installSource: 'value => globalThis.__capture(value)', guardReport: () => ({ phase: 'controlled' }),
    downloadCount: () => 0, checkDownload: async (...args) => { download = args; },
    cli: async (command, source) => {
      assert.equal(command, 'eval');
      if (source.includes('globalThis.__capture') || source.startsWith('window.__knoxxControlledCore.'))
        return runInNewContext(source, world);
      return true; // DOM/steady-state calls are inert; this fixture captures configuration only.
    },
  }, 'success', 54), error => error === stopped);
  assert.ok(configuration && watch && download);
  return { configuration, watch: JSON.parse(JSON.stringify(watch)) };
}

async function settledRequestObserverSchedule({ staleAtRequest, observer = 'clean', earlyRelease = false } = {}) {
  const { configuration, watch } = await settledFeedbackConfiguration();
  const { installDeferred } = await deferred(); const { world, forwarded } = inertWorld();
  const inventory = configuration.steps.find(value => value.id === 'settled-B-inventory');
  let current = configuration.steps.find(value => value.id === 'A-inventory').response.json.project;
  let formCount = 1, observed;
  world.document.querySelector = () => ({ value: current, defaultValue: current, getAttribute: () => current });
  world.document.querySelectorAll = () => Array.from({ length: formCount }, () => ({}));
  world.MutationObserver = class { constructor(fn) { observed = fn; } observe() {} disconnect() {} };
  const adapter = installDeferred(configuration, world), outstanding = [];
  const request = value => {
    const query = new URLSearchParams(value.query).toString();
    const promise = world.fetch(value.path + (query ? '?' + query : ''), { method: value.method });
    outstanding.push(promise); return promise;
  };
  try {
    for (const value of configuration.steps.filter(value => value.id.startsWith('A-'))) await request(value);
    const exported = request(configuration.steps.find(value => value.id === 'settled-export'));
    adapter.release('settled-export', 'success'); await exported;
    adapter.watchCommit(watch.id, watch.rule, watch.actions);
    current = watch.rule.project; formCount = staleAtRequest === 'form' ? 1 : 0;
    const staleIndex = { title: 0, feedback: 1, translation: 2 }[staleAtRequest];
    world.document.body.innerText = staleIndex === undefined ? '' : watch.rule.absent[staleIndex];
    const b = request(inventory).then(() => true, () => false);
    const manifest = request(configuration.steps.find(value => value.id === 'settled-B-manifest'));
    if (earlyRelease) {
      if (adapter.report().pending.includes('settled-B-inventory')) adapter.release('settled-B-inventory', 'success');
      adapter.release('settled-B-manifest', 'success');
    }
    // A request-time refusal is sticky even when the later observer is clean.
    world.document.body.innerText = observer === 'stale' ? watch.rule.absent[1] : ''; formCount = 0;
    if (observer !== 'missing') observed();
    const beforeBRelease = adapter.report();
    if (!earlyRelease) {
      if (adapter.report().pending.includes('settled-B-inventory')) adapter.release('settled-B-inventory', 'success');
      adapter.release('settled-B-manifest', 'success');
    }
    await Promise.all([b, manifest]);
    for (const value of configuration.steps.filter(value => value.id.startsWith('settled-B-') && !value.hold)) await request(value);
    let complete = false;
    try { adapter.assertComplete(); complete = true; } catch { /* Retain explicit contract refusal. */ }
    return { complete, beforeBRelease, final: adapter.report(), forwarded: forwarded() };
  } finally { adapter.restore(); await Promise.allSettled(outstanding); }
}

test('settled feedback accepts clean committed B request before observer while B responses stay held', async () => {
  const proof = await settledRequestObserverSchedule();
  assert.equal(proof.complete, true);
  assert.deepEqual(proof.beforeBRelease.checkpoints.map(value => value.ok), [true, true]);
  assert.deepEqual(proof.beforeBRelease.pending, ['settled-B-inventory', 'settled-B-manifest']);
  const records = proof.final.records;
  assert.ok(records.indexOf('request:settled-B-inventory') < records.indexOf('commit:settled-B'));
  assert.ok(records.indexOf('commit:settled-B') < records.indexOf('release:settled-B-inventory'));
  assert.equal(proof.forwarded, 0);
});

test('settled feedback refuses stale A title feedback translation or form at B request despite later clean observer', async () => {
  for (const staleAtRequest of ['title', 'feedback', 'translation', 'form']) {
    const proof = await settledRequestObserverSchedule({ staleAtRequest });
    assert.equal(proof.complete, false);
    assert.deepEqual(proof.beforeBRelease.checkpoints.map(value => value.ok), [false, true]);
    assert.equal(proof.beforeBRelease.failed, true); assert.equal(proof.forwarded, 0);
  }
});

test('settled feedback retains missing stale observer and early held-response release refusals', async () => {
  for (const options of [{ observer: 'missing' }, { observer: 'stale' }, { earlyRelease: true }]) {
    const proof = await settledRequestObserverSchedule(options);
    assert.equal(proof.complete, false); assert.equal(proof.forwarded, 0);
    if (options.observer === 'stale') assert.equal(proof.beforeBRelease.failed, true);
  }
});
