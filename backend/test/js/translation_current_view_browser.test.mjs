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
