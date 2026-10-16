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
        continue: async () => { await fetch(request.url(), { method }); } });
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

test('required pre-first-B order is recorded and rejects a late A completion', async () => {
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
