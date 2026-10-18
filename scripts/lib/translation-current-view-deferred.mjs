/** Session-local public fetch/DOM fixture. Serializable; no application/private namespace. */
export function installDeferred(config, world = globalThis) {
  const fail = category => { throw Error(category); };
  const key = '__knoxxControlledCore';
  if (world[key]) fail('core-adapter-owned');
  if (!/^[a-zA-Z0-9_-]{8,80}$/.test(config.runId)) fail('core-run-invalid');
  if (new URL(config.origin).origin !== world.location.origin) fail('core-origin-invalid');
  const original = world.fetch;
  const pending = new Map(), consumed = new Set(), records = [], checkpoints = [], commits = new Map(), background = [];
  let active = true, failed = false, completion, firstFailure;
  function markFailure(category, id) {
    failed = true;
    if (!firstFailure) firstFailure = { category, ...(id === undefined ? {} : { id }) };
  }
  const stable = value => {
    if (Array.isArray(value)) return '[' + value.map(stable).join(',') + ']';
    if (value && typeof value === 'object') return '{' + Object.keys(value).sort().map(k => JSON.stringify(k) + ':' + stable(value[k])).join(',') + '}';
    return JSON.stringify(value);
  };
  function checkedSteps(values) {
    const ids = new Set();
    for (const value of values) {
      if (!value || typeof value.id !== 'string' || ids.has(value.id) || !['GET', 'POST'].includes(value.method)) fail('core-step-invalid');
      if (!value.path.startsWith('/api/') || !Array.isArray(value.query) || !value.response) fail('core-step-invalid');
      if (!Number.isInteger(value.response.status) || value.response.status < 200 || value.response.status > 599) fail('core-response-invalid');
      ids.add(value.id);
    }
    return JSON.parse(JSON.stringify(values));
  }
  let steps = checkedSteps(config.steps);
  function matches(rule) {
    const body = world.document.body?.innerText ?? '';
    const input = world.document.querySelector('input[placeholder="devel"]');
    const project = input?.value;
    const committed = rule.committedProject === undefined ||
      (input?.defaultValue === rule.committedProject && input?.getAttribute('value') === rule.committedProject);
    return committed && (rule.project === undefined || project === rule.project)
      && (rule.absent ?? []).every(x => !body.includes(x))
      && (rule.present ?? []).every(x => body.includes(x))
      && (!rule.formAbsent || world.document.querySelectorAll('textarea').length === 0);
  }
  function checkpoint(step) {
    const rule = step.checkpoint;
    if (!rule) return;
    const ok = matches(rule);
    checkpoints.push({ id: step.id, ok });
    if (!ok) { markFailure('core-first-render-checkpoint', step.id); fail('core-first-render-checkpoint'); }
  }
  function response(step) {
    const body = Object.hasOwn(step.response, 'text') ? step.response.text : JSON.stringify(step.response.json);
    return new world.Response(body, { status: step.response.status,
      headers: { 'Content-Type': Object.hasOwn(step.response, 'text') ? 'application/x-ndjson' : 'application/json' } });
  }
  function reject(category) { markFailure(category); return Promise.reject(Error(category)); }
  function fetchControlled(input, init = {}) {
    if (!active) return reject('core-adapter-inactive');
    if (typeof input !== 'string' || typeof init !== 'object' || init === null) return reject('core-request-invalid');
    let url, body;
    try {
      url = new world.URL(input, config.origin);
      body = init.body === undefined ? undefined : JSON.parse(init.body);
    } catch { return reject('core-request-invalid'); }
    if (url.origin !== config.origin || url.hash) return reject('core-request-outside-owner');
    const method = init.method ?? 'GET';
    if (method === 'GET' && body === undefined && Object.hasOwn(config.background ?? {}, url.pathname + url.search)) {
      background.push(url.pathname + url.search);
      return Promise.resolve(new world.Response(JSON.stringify(config.background[url.pathname + url.search]),
        { status: 200, headers: { 'Content-Type': 'application/json' } }));
    }
    const query = [...url.searchParams.entries()].sort((a, b) => stable(a).localeCompare(stable(b)));
    const step = steps.find(x => !consumed.has(x.id) && x.method === method && x.path === url.pathname
      && stable([...x.query].sort((a, b) => stable(a).localeCompare(stable(b)))) === stable(query)
      && stable(x.body) === stable(body));
    if (!step) return reject('core-request-unexpected');
    consumed.add(step.id); records.push('request:' + step.id);
    try { checkpoint(step); } catch (error) { return Promise.reject(error); }
    if (!step.hold) return Promise.resolve(response(step));
    return new Promise((resolve, deny) => { pending.set(step.id, { step, resolve, deny }); });
  }
  function ordered() {
    const order = config.requiredOrder ?? []; let index = -1;
    return order.every(value => { index = records.indexOf(value, index + 1); return index >= 0; });
  }
  function report() {
    return { runId: config.runId, active, failed, pending: [...pending.keys()], consumed: [...consumed],
      records: [...records], background: [...background], checkpoints: checkpoints.map(x => ({ ...x })),
      ...(completion ? { completion: JSON.parse(JSON.stringify(completion)) } : {}) };
  }
  const api = {
    report,
    watchCommit(id, rule, actions = {}) {
      if (commits.has(id) || typeof world.MutationObserver !== 'function') fail('core-commit-observer-invalid');
      const releases = actions.release ?? [];
      if (!Array.isArray(releases) || new Set(releases).size !== releases.length
          || releases.some(x => typeof x !== 'string' || !steps.some(y => y.id === x))
          || (actions.beforeRequest && !steps.some(x => x.id === actions.beforeRequest))) fail('core-commit-actions-invalid');
      const state = { ok: null, observation: 'first-mutation-observer-delivery', observer: null };
      state.observer = new world.MutationObserver(() => {
        if (state.ok !== null) return;
        state.observer.disconnect();
        // Never release A from an event-return marker. This callback must first
        // observe B's DOM/source rule, both owned A handles and no B request.
        // Identify only the first false conjunct, preserving its short-circuit evaluation.
        let category = 'core-commit-rule';
        state.ok = matches(rule)
          && (category = 'core-commit-release-not-pending', releases.every(x => pending.has(x)))
          && (category = 'core-commit-request-already-started',
            !actions.beforeRequest || !records.includes('request:' + actions.beforeRequest));
        records.push('commit:' + id);
        checkpoints.push({ id: 'commit:' + id, ok: state.ok, observation: state.observation });
        if (!state.ok) markFailure(category, id);
        else for (const release of releases) api.release(release, 'success');
      });
      commits.set(id, state); state.observer.observe(world.document.body, { childList: true, subtree: true, characterData: true, attributes: true, attributeFilter: ['value'] });
    },
    release(id, mode) {
      const value = pending.get(id); if (!value || !['success', 'error'].includes(mode)) fail('core-release-not-owned');
      pending.delete(id); records.push('release:' + id);
      if (mode === 'error') value.deny(Error('Controlled original operation rejection'));
      else value.resolve(response(value.step));
    },
    markBoundary(id) { records.push('boundary:' + id); },
    append(values) {
      const additions = checkedSteps(values);
      if (additions.some(x => steps.some(y => x.id === y.id))) fail('core-step-duplicate');
      steps = steps.concat(additions);
    },
    assertComplete() {
      // Synchronous pre-assertion snapshot; bounded copies never become scheduler authority.
      const details = pending.size <= 64 && records.length <= 256 && checkpoints.length <= 64
        ? { pending: [...pending.keys()], records: [...records], checkpoints: checkpoints.map(x => ({ ...x })),
          ...(firstFailure ? { firstFailure: { ...firstFailure } } : {}) } : undefined;
      completion = { failed, pendingCount: pending.size, consumedCount: consumed.size, stepCount: steps.length,
        ...(details ? { details } : {}) };
      if (failed || pending.size || consumed.size !== steps.length) fail('core-incomplete');
      if (!ordered()) fail('core-required-order-missing');
      if ((config.requiredCheckpoints ?? []).some(id => !checkpoints.some(x => x.id === id && x.ok))) fail('core-checkpoint-missing');
      if ([...commits.values()].some(x => x.ok !== true)) fail('core-first-commit-missing');
      return { ...report(), ordered: true };
    },
    restore() {
      if (world.fetch !== fetchControlled || world[key] !== api) fail('core-adapter-ownership-lost');
      active = false;
      for (const state of commits.values()) state.observer.disconnect();
      for (const value of pending.values()) value.deny(Error('core-owned-cleanup'));
      pending.clear(); world.fetch = original; delete world[key];
    },
  };
  world.fetch = fetchControlled; world[key] = api; return api;
}
