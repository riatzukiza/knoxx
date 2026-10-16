/** Source-shaped synthetic wires and normal-DOM core schedules; no draft ownership. */
import assert from 'node:assert/strict';

export const entrySpecifications = Object.freeze([
  { id: 'legacy-segment', runner: 'segment', label: 'Submit review', legacy: true, legacyOnly: true },
  { id: 'resource-split', runner: 'split', label: 'Approve split' },
  { id: 'resource-bulk', runner: 'bulk', label: 'Approve All' },
  { id: 'legacy-document', runner: 'document', label: 'Approve All', legacy: true, legacyOnly: true },
  { id: 'whole-output', runner: 'approval', label: 'Approve whole output' },
  { id: 'dispatch', runner: 'dispatch', label: 'Dispatch', legacy: true },
  { id: 'retry', runner: 'dispatch', label: 'Retry', legacy: true },
]);
const scores = { adequacy: 'good', fluency: 'good', terminology: 'correct', risk: 'safe' };
const reviewsPath = '/api/publications/translations/reviews';

export function makeWireFixture(runId, side, entryId) {
  const entry = entrySpecifications.find(x => x.id === entryId); assert.ok(entry);
  const root = `${runId}/${side}`; const project = `${runId}-${side}`;
  const document = `${root}/document`, garden = `${root}/garden`, publication = `${root}/publication`;
  const title = `Controlled ${runId} ${side}`;
  const splits = [0, 1].map(i => ({ split_id: `${root}/split${i}`, split_index: i,
    source_text: `Controlled source ${side}${i}`, candidate_text: `Controlled translation ${side}${i}`,
    review_status: entry.runner === 'approval' ? 'approved' : 'in-review' }));
  const row = { document, garden, publication, locale: 'es', project, title, source_locale: 'en',
    revision: `${root}-source`, translation_revision: `${root}-translation`, translated_at: '2026-10-16T00:00:00Z',
    approved: false, work_state: 'ready', allowed_actions: [] };
  if (!entry.legacy) row.split_review = { candidate_set_id: `${root}/candidate`, manifest_id: `${root}/manifest`, splits };
  if (entry.runner === 'dispatch') {
    delete row.translation_revision; delete row.translated_at;
    row.work_state = entryId === 'retry' ? 'failed' : 'missing'; row.allowed_actions = [entryId];
  }
  const summary = { document_id: document, garden_id: garden, project, target_lang: 'es', title,
    source_lang: 'en', approved: 0, total_segments: 2, overall_status: 'pending_review' };
  const detail = { document: { id: document, title, source_lang: 'en' }, target_lang: 'es',
    summary: { total_segments: 2, approved: 0, overall_status: 'pending_review' },
    segments: splits.map((x, i) => ({ id: `${root}/legacy${i}`, segment_index: i, status: 'pending',
      source_lang: 'en', target_lang: 'es', source_text: x.source_text, translated_text: x.candidate_text, label_count: 0 })) };
  const scope = { project, garden_id: garden };
  const documentPath = `/api/translations/documents/${encodeURIComponent(document)}/es`;
  const mutation = mutationFor(entry, { row, splits, documentPath, detail, scope });
  return { project, document, garden, publication, title, row, summary, detail, documentPath, mutation, legacyOnly: Boolean(entry.legacyOnly),
    followup: entry.runner === 'approval' ? { method: 'POST', path: '/api/publications/reconcile',
      body: { publicationId: publication }, response: { status: 200, json: { type: 'publication/materialized' } } } : null };
}

function mutationFor(entry, w) {
  const base = { method: 'POST', response: { status: 200, json: { status: 'recorded', ok: true } } };
  if (entry.runner === 'segment') return { ...base, path: `/api/translations/segments/${encodeURIComponent(w.detail.segments[0].id)}/labels`,
    body: { ...scores, overall: 'approve', ...w.scope } };
  if (entry.runner === 'document') return { ...base, path: w.documentPath + '/review',
    body: { overall: 'approve', ...w.scope }, response: { status: 200, json: { segments_reviewed: 2 } } };
  if (entry.runner === 'split') return { ...base, path: reviewsPath,
    body: { candidate_set_id: w.row.split_review.candidate_set_id, split_id: w.splits[0].split_id, status: 'approved', ...scores } };
  if (entry.runner === 'bulk') return { ...base, path: reviewsPath + '/bulk',
    body: { candidate_set_id: w.row.split_review.candidate_set_id, status: 'approved', ...scores } };
  if (entry.runner === 'approval') return { ...base, path: '/api/publications/translations/approvals',
    body: Object.fromEntries(['document', 'garden', 'locale', 'revision', 'translation_revision'].map(k => [k, w.row[k]])) };
  return { ...base, path: '/api/publications/translations/dispatch', body: { publication: w.row.publication },
    response: { status: 200, json: { dispatched: [{ outcome: 'dispatch/accepted' }] } } };
}

const get = (id, pathname, query, json, extra = {}) => ({ id, method: 'GET', path: pathname,
  query, response: { status: 200, json }, ...extra });
const manifest = { languages: { es: { approved: 0, total_segments: 2 }, fr: { approved: 0, total_segments: 2 } } };
export function initialSteps(wire, prefix, { hold = false, checkpoint } = {}) {
  return [get(prefix + '-inventory', reviewsPath, [], { project: wire.project, reviews: wire.legacyOnly ? [] : [wire.row] }, { hold, checkpoint }),
    get(prefix + '-legacy', '/api/translations/documents', [['project', wire.project]], { documents: [wire.summary], total: 1 }),
    get(prefix + '-manifest', '/api/translations/export/manifest', [['project', wire.project]], manifest, { hold })];
}
function detailStep(wire, id, checkpoint) {
  return get(id, wire.documentPath, [['project', wire.project], ['garden_id', wire.garden]], wire.detail, { checkpoint });
}
function bootSteps(wire, prefix = 'A') {
  return [get(prefix + '-adopt-project', reviewsPath, [], { project: wire.project, reviews: [] }),
    get(prefix + '-initial-manifest', '/api/translations/export/manifest', [['project', 'devel']], manifest),
    ...initialSteps(wire, prefix), ...(wire.row.split_review ? [] : [detailStep(wire, prefix + '-detail')])];
}

/** Only fixed read-only background responses, with exact source-observed queries. */
export function backgroundResponses(runId) {
  assert.match(runId, /^[a-zA-Z0-9_-]{8,80}$/);
  const sessionId = runId + '-cms-session', conversationId = runId + '-cms-conversation';
  const statusPath = '/api/knoxx/session/status?' + new URLSearchParams({ session_id: sessionId, conversation_id: conversationId });
  const entries = [
    ['/api/config', { default_role: 'executive', default_actor_id: 'cms_chat' }],
    ['/api/proxx/health', { reachable: false, configured: false }], ['/api/proxx/models', { models: [] }],
    ['/api/ingestion/browse?path=.', { workspace_root: '/synthetic-core', current_path: '.', entries: [] }],
    ['/api/ingestion/browse?path=docs', { workspace_root: '/synthetic-core', current_path: 'docs', entries: [] }], ['/api/ingestion/sources', []],
    ['/api/ingestion/file?path=contracts/cms-templates.edn', { content: '' }],
    ['/api/memory/sessions?limit=10', { rows: [], total: 0, has_more: false }],
    ['/api/memory/sessions?limit=12', { rows: [], total: 0, has_more: false }],
    ['/api/memory/sessions?limit=20&excludeActorIds=eta-mu', { rows: [], total: 0, has_more: false }],
    [statusPath, { session_id: sessionId, conversation_id: conversationId, status: 'not_found',
      has_active_stream: false, can_send: true, reason: 'No session state found. Ready for new turn.' }],
    ['/api/cms/publications/documents', { documents: [], gardens: [] }],
  ];
  for (const actor of ['cms_chat', 'chat_primary']) {
    entries.push([`/api/knoxx/agents/catalog?actor=${actor}`, { actor_id: actor, agents: [], actors: [] }]);
    entries.push([`/api/tools/catalog?role=executive&actor=${actor}`, { tools: [] }]);
  }
  return Object.fromEntries(entries);
}

const js = value => JSON.stringify(value);
function domAction(kind, value) {
  if (kind === 'project') return `(() => { const n=document.querySelector('input[placeholder="devel"]'); if(!n)throw Error('core-project-missing'); Object.getOwnPropertyDescriptor(HTMLInputElement.prototype,'value').set.call(n,${js(value)}); n.dispatchEvent(new Event('input',{bubbles:true})); return true; })()`;
  if (kind === 'route') return `(() => { const n=[...document.querySelectorAll('a')].find(x=>x.getAttribute('href')===${js(value)}); if(!n)throw Error('core-router-link-missing'); n.click(); return true; })()`;
  return `(() => { const n=[...document.querySelectorAll('button')].find(x=>x.textContent.trim()===${js(value)}); if(!n||n.disabled)throw Error('core-action-unavailable'); n.click(); return true; })()`;
}
async function evaluate(io, source) { return await io.cli('eval', source); }
async function adapter(io, method, ...args) {
  return await evaluate(io, `window.__knoxxControlledCore.${method}(${args.map(js).join(',')})`);
}
async function waitDom(io, predicate, label) {
  // Polling is permitted only for steady state, never first-render/order credit.
  for (let i = 0; i < 80; i += 1) {
    if (await evaluate(io, `Boolean(${predicate})`)) return;
    await new Promise(resolve => setTimeout(resolve, 50));
  }
  throw Error('core-steady-state-missing-' + label);
}
async function openWork(io, wire) {
  await waitDom(io, `[...document.querySelectorAll('aside button')].some(x=>x.textContent.includes(${js(wire.title)}))`, 'row');
  await evaluate(io, `(() => { const n=[...document.querySelectorAll('aside button')].find(x=>x.textContent.includes(${js(wire.title)})); n.click();return true; })()`);
  await waitDom(io, `document.body.innerText.includes(${js(wire.detail.segments[0].translated_text)})`, 'detail');
  await evaluate(io, `(() => { const n=[...document.querySelectorAll('button')].find(x=>x.textContent.includes(${js(wire.detail.segments[0].translated_text)})); if(!n)throw Error('core-segment-control-missing');n.click();return true; })()`);
}
async function install(io, config) {
  await evaluate(io, `(${io.installSource})(${js({ ...config, background: backgroundResponses(io.config.runId) })}); true`);
  const report = await adapter(io, 'report'); assert.equal(report.runId, config.runId);
  assert.equal(report.active, true); assert.equal(io.guardReport().phase, 'controlled');
}
async function finish(io, shot) {
  const report = await adapter(io, 'assertComplete');
  await io.cli('screenshot', shot); await adapter(io, 'restore'); return report;
}
async function releaseMutation(io, prefix, wire, mode) {
  await adapter(io, 'release', prefix, mode);
  if (mode === 'success' && wire.followup) {
    await waitDom(io, `window.__knoxxControlledCore.report().pending.includes(${js(prefix + '-reconcile')})`, 'original-reconcile');
    await adapter(io, 'release', prefix + '-reconcile', 'success');
  }
}
function mutationSteps(wire, prefix, mode = 'success') {
  return [{ id: prefix, ...wire.mutation, query: [], hold: true },
    ...(wire.followup && mode === 'success' ? [{ id: prefix + '-reconcile', ...wire.followup, query: [], hold: true }] : [])];
}

async function projectCase(io, entry, mode, number) {
  const runId = `${io.config.runId}-${number}`;
  const a = makeWireFixture(runId, 'A', entry.id), b = makeWireFixture(runId, 'B', entry.id);
  const steps = [...bootSteps(a), ...mutationSteps(a, 'mutation-A', mode),
    ...initialSteps(b, 'B', { hold: true, checkpoint: { project: b.project, committedProject: b.project, absent: [a.title, a.detail.segments[0].translated_text], formAbsent: true } })];
  // The held inventory is essential; the unrelated project manifest is released independently.
  await install(io, { runId, origin: io.config.origin, steps, requiredCheckpoints: ['B-inventory'],
    requiredOrder: ['commit:B', 'release:mutation-A'] });
  await evaluate(io, domAction('route', '/translations')); await openWork(io, a);
  await evaluate(io, domAction('button', entry.label));
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('mutation-A')`, 'mutation-A');
  await adapter(io, 'watchCommit', 'B', { project: b.project, committedProject: b.project, absent: [a.title, a.detail.segments[0].translated_text], formAbsent: true });
  await evaluate(io, domAction('project', b.project));
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('B-inventory')`, 'held-B');
  const before = await adapter(io, 'report');
  await releaseMutation(io, 'mutation-A', a, mode);
  const after = await adapter(io, 'report');
  assert.equal(after.records.filter(x => x === 'request:B-inventory').length, 1);
  assert.ok(after.pending.includes('B-inventory')); assert.ok(before.pending.includes('B-inventory'));
  await adapter(io, 'release', 'B-inventory', 'success'); await adapter(io, 'release', 'B-manifest', 'success');
  await waitDom(io, `document.body.innerText.includes(${js(b.title)})`, 'B-installed');
  return await finish(io, `${io.config.evidenceDir}/${number}-${entry.id}-${mode}.png`);
}

async function unmountCase(io, entry, mode, number) {
  const runId = `${io.config.runId}-${number}`; const a = makeWireFixture(runId, 'A', entry.id);
  await install(io, { runId, origin: io.config.origin, steps: [...bootSteps(a), ...mutationSteps(a, 'unmount-A', mode)] });
  await evaluate(io, domAction('route', '/translations')); await openWork(io, a);
  await evaluate(io, domAction('button', entry.label));
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('unmount-A')`, 'unmount-operation');
  await evaluate(io, domAction('route', '/cms'));
  await waitDom(io, `!document.body.innerText.includes('Translation Review')`, 'router-unmounted');
  const before = await adapter(io, 'report'); await releaseMutation(io, 'unmount-A', a, mode);
  const after = await adapter(io, 'report');
  assert.equal(after.records.filter(x => x.startsWith('request:')).length,
    before.records.filter(x => x.startsWith('request:')).length + (mode === 'success' && a.followup ? 1 : 0));
  assert.equal(await evaluate(io, `document.body.innerText.includes('Controlled original operation rejection')`), false);
  return await finish(io, `${io.config.evidenceDir}/${number}-${entry.id}-unmount-${mode}.png`);
}

async function preFirstBCase(io, number) {
  const runId = `${io.config.runId}-${number}`;
  const a = makeWireFixture(runId, 'A', 'resource-split'), b = makeWireFixture(runId, 'B', 'resource-split');
  const steps = bootSteps(a); steps.find(x => x.id === 'A-inventory').hold = true;
  steps.find(x => x.id === 'A-manifest').hold = true;
  // No A legacy response is consumed when its old generation is invalidated.
  const filtered = steps.filter(x => x.id !== 'A-legacy');
  await install(io, { runId, origin: io.config.origin, steps: [...filtered, ...initialSteps(b, 'B')],
    requiredOrder: ['commit:B', 'release:A-inventory', 'release:A-manifest', 'request:B-inventory'] });
  await evaluate(io, domAction('route', '/translations'));
  await waitDom(io, `['A-inventory','A-manifest'].every(x=>window.__knoxxControlledCore.report().pending.includes(x))`, 'old-A-held');
  // The first public observer delivery must see B before A is released and
  // before B's first request; an unattainable window fails without fallback.
  // This records the observed order, not a generic React pre-passive guarantee.
  await adapter(io, 'watchCommit', 'B', { project: b.project, committedProject: b.project,
    absent: [a.title, a.detail.segments[0].translated_text], formAbsent: true },
    { release: ['A-inventory', 'A-manifest'], beforeRequest: 'B-inventory' });
  await evaluate(io, domAction('project', b.project));
  await waitDom(io, `document.body.innerText.includes(${js(b.title)})`, 'ordered-B');
  return await finish(io, `${io.config.evidenceDir}/${number}-experimental-pre-first-B.png`);
}

function refreshSteps(wire, prefix, entry) {
  const result = initialSteps(wire, prefix).filter(x => !x.id.endsWith('-manifest'));
  if (['segment', 'document'].includes(entry.runner)) result.push(detailStep(wire, prefix + '-direct-detail'));
  return result;
}
async function remountOwnerCase(io, entry, mode, number) {
  const runId = `${io.config.runId}-${number}`;
  const a = makeWireFixture(runId, 'A', entry.id), b = makeWireFixture(runId, 'B', entry.id);
  await install(io, { runId, origin: io.config.origin, steps: [...bootSteps(a),
    ...mutationSteps(a, 'old-A', mode), ...bootSteps(b, 'B'), ...mutationSteps(b, 'current-B'), ...refreshSteps(b, 'fresh-B', entry)] });
  await evaluate(io, domAction('route', '/translations')); await openWork(io, a);
  await evaluate(io, domAction('button', entry.label));
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('old-A')`, 'old-A');
  await evaluate(io, domAction('route', '/cms'));
  await waitDom(io, `!document.body.innerText.includes('Translation Review')`, 'unmounted');
  await evaluate(io, domAction('route', '/translations')); await openWork(io, b);
  await evaluate(io, domAction('button', entry.label));
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('current-B')`, 'current-B');
  await releaseMutation(io, 'old-A', a, mode);
  assert.equal(await evaluate(io, `[...document.querySelectorAll('button')].some(x=>x.textContent.trim()===${js(entry.label)}&&x.disabled)`), true);
  assert.ok((await adapter(io, 'report')).pending.includes('current-B'));
  await releaseMutation(io, 'current-B', b, 'success');
  await waitDom(io, `window.__knoxxControlledCore.report().consumed.includes('fresh-B-legacy')`, 'fresh-B');
  return await finish(io, `${io.config.evidenceDir}/${number}-${entry.id}-remount-${mode}.png`);
}
async function filterCase(io, mode, number) {
  const runId = `${io.config.runId}-${number}`, a = makeWireFixture(runId, 'A', 'resource-split');
  const filtered = [get('filter-inventory', reviewsPath, [], { project: a.project, reviews: [a.row] },
    { hold: true, checkpoint: { project: a.project, absent: [a.title, a.detail.segments[0].translated_text], formAbsent: true } }),
    get('filter-legacy', '/api/translations/documents', [['project', a.project], ['target_lang', 'fr']], { documents: [], total: 0 })];
  await install(io, { runId, origin: io.config.origin, steps: [...bootSteps(a), ...mutationSteps(a, 'filter-old-A', mode), ...filtered], requiredCheckpoints: ['filter-inventory'] });
  await evaluate(io, domAction('route', '/translations')); await openWork(io, a);
  await evaluate(io, domAction('button', 'Approve split'));
  await evaluate(io, filterAction('fr'));
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('filter-inventory')`, 'fr-held');
  await releaseMutation(io, 'filter-old-A', a, mode);
  assert.ok((await adapter(io, 'report')).pending.includes('filter-inventory'));
  await adapter(io, 'release', 'filter-inventory', 'success');
  await waitDom(io, `window.__knoxxControlledCore.report().consumed.includes('filter-legacy')`, 'filter-loaded');
  assert.equal(await evaluate(io, `document.body.innerText.includes(${js(a.title)})`), false);
  return await finish(io, `${io.config.evidenceDir}/${number}-first-filter-render-${mode}.png`);
}
function filterAction(value) {
  return `(() => { const n=document.querySelector('select');if(!n)throw Error('core-filter-missing');Object.getOwnPropertyDescriptor(HTMLSelectElement.prototype,'value').set.call(n,${js(value)});n.dispatchEvent(new Event('change',{bubbles:true}));return true; })()`;
}
async function selectedWorkCase(io, number) {
  const runId = `${io.config.runId}-${number}`, a = makeWireFixture(runId, 'A', 'legacy-document');
  const b = makeWireFixture(runId, 'B', 'legacy-document'); b.project = a.project; b.row.project = a.project; b.summary.project = a.project;
  const steps = bootSteps(a);
  steps.find(x => x.id === 'A-legacy').response.json.documents.push(b.summary);
  const next = detailStep(b, 'selected-B-detail', { project: a.project,
    absent: [a.detail.segments[0].translated_text], formAbsent: true }); next.hold = true;
  await install(io, { runId, origin: io.config.origin, steps: [...steps, ...mutationSteps(a, 'selected-old-A'), next,
    get('selected-refresh-inventory', reviewsPath, [], { project: a.project, reviews: [] }),
    get('selected-refresh-legacy', '/api/translations/documents', [['project', a.project]], { documents: [a.summary, b.summary], total: 2 })], requiredCheckpoints: ['selected-B-detail'] });
  await evaluate(io, domAction('route', '/translations')); await openWork(io, a);
  await evaluate(io, domAction('button', 'Approve All'));
  await evaluate(io, `(() => {const n=[...document.querySelectorAll('aside button')].find(x=>x.textContent.includes(${js(b.title)}));if(!n)throw Error('core-B-row-missing');n.click();return true;})()`);
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('selected-B-detail')`, 'B-detail');
  await releaseMutation(io, 'selected-old-A', a, 'success');
  assert.ok((await adapter(io, 'report')).pending.includes('selected-B-detail'));
  await adapter(io, 'release', 'selected-B-detail', 'success');
  await waitDom(io, `document.body.innerText.includes(${js(b.detail.segments[0].translated_text)})`, 'B-detail-installed');
  return await finish(io, `${io.config.evidenceDir}/${number}-first-selected-detail.png`);
}
function filterSteps(wire, prefix, locale, extra = {}) {
  return [get(prefix + '-inventory', reviewsPath, [], { project: wire.project, reviews: [wire.row] }, extra),
    get(prefix + '-legacy', '/api/translations/documents', [['project', wire.project], ['target_lang', locale]],
      { documents: locale === 'es' ? [wire.summary] : [], total: locale === 'es' ? 1 : 0 })];
}
async function exportCase(io, mode, boundary, number) {
  const runId = `${io.config.runId}-${number}`, a = makeWireFixture(runId, 'A', 'resource-split');
  const b = makeWireFixture(runId, 'B', 'resource-split'), locale = ['locale', 'aba'].includes(boundary);
  const text = `controlled-export-${runId}\n`;
  const exportStep = { id: 'export-A', method: 'GET', path: '/api/translations/export/sft',
    query: [['project', a.project], ['include_corrected', 'true'], ...(locale ? [['target_lang', 'es']] : [])],
    hold: true, response: { status: 200, text } };
  const steps = [...bootSteps(a), ...(locale ? filterSteps(a, 'initial-es', 'es') : []), exportStep,
    ...(boundary === 'project' ? initialSteps(b, 'export-B') : []),
    ...(locale ? filterSteps(a, 'changed-fr', 'fr') : []),
    ...(boundary === 'aba' ? filterSteps(a, 'returned-es', 'es') : [])];
  await install(io, { runId, origin: io.config.origin, steps });
  await evaluate(io, domAction('route', '/translations'));
  await waitDom(io, `document.body.innerText.includes(${js(a.title)})`, 'export-ready');
  if (locale) {
    await evaluate(io, filterAction('es'));
    await waitDom(io, `window.__knoxxControlledCore.report().consumed.includes('initial-es-legacy')`, 'initial-es');
  }
  const downloads = io.downloadCount(); await evaluate(io, domAction('button', 'Export SFT'));
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('export-A')`, 'export-held');
  if (boundary === 'project') {
    await evaluate(io, domAction('project', b.project));
    await waitDom(io, `document.body.innerText.includes(${js(b.title)})`, 'export-B');
  } else if (locale) {
    await evaluate(io, filterAction('fr'));
    await waitDom(io, `window.__knoxxControlledCore.report().consumed.includes('changed-fr-legacy')`, 'changed-fr');
    if (boundary === 'aba') {
      await evaluate(io, filterAction('es'));
      await waitDom(io, `window.__knoxxControlledCore.report().consumed.includes('returned-es-legacy')`, 'returned-es');
    }
  } else {
    await evaluate(io, domAction('route', '/cms'));
    await waitDom(io, `!document.body.innerText.includes('Translation Review')`, 'export-unmounted');
  }
  const before = (await adapter(io, 'report')).records.length;
  await adapter(io, 'release', 'export-A', mode);
  if (mode === 'success') await io.checkDownload(downloads, `${a.project}-${locale ? 'es' : 'all'}-translations.jsonl`, text);
  else await io.noDownload(downloads);
  const proof = await adapter(io, 'report');
  assert.equal(proof.records.length, before + 1, 'Export completion must not refetch or mutate');
  assert.equal(await evaluate(io, `document.body.innerText.includes('SFT export downloaded.')||document.body.innerText.includes('Controlled original operation rejection')`), false);
  return await finish(io, `${io.config.evidenceDir}/${number}-export-${boundary}-${mode}.png`);
}
export async function settledFeedbackCase(io, mode, number) {
  const runId = `${io.config.runId}-${number}`, a = makeWireFixture(runId, 'A', 'resource-split');
  const b = makeWireFixture(runId, 'B', 'resource-split'), message = mode === 'success'
    ? 'SFT export downloaded.' : 'Controlled original operation rejection';
  const text = `settled-${runId}\n`;
  const boundary = { project: b.project, committedProject: b.project,
    absent: [a.title, message, a.detail.segments[0].translated_text], formAbsent: true };
  await install(io, { runId, origin: io.config.origin, steps: [...bootSteps(a),
    { id: 'settled-export', method: 'GET', path: '/api/translations/export/sft', query: [['project', a.project], ['include_corrected', 'true']],
      hold: true, response: { status: 200, text } }, ...initialSteps(b, 'settled-B', { hold: true, checkpoint: boundary })],
    requiredCheckpoints: ['settled-B-inventory'],
    requiredOrder: ['commit:settled-B', 'release:settled-B-inventory'] });
  await evaluate(io, domAction('route', '/translations')); await openWork(io, a);
  const downloads = io.downloadCount(); await evaluate(io, domAction('button', 'Export SFT'));
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('settled-export')`, 'settled-export');
  await adapter(io, 'release', 'settled-export', mode);
  if (mode === 'success') await io.checkDownload(downloads, `${a.project}-all-translations.jsonl`, text);
  else await io.noDownload(downloads);
  await waitDom(io, `document.body.innerText.includes(${js(message)})`, 'feedback-installed');
  await adapter(io, 'watchCommit', 'settled-B', boundary);
  await evaluate(io, domAction('project', b.project));
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('settled-B-inventory')`, 'settled-B');
  await adapter(io, 'release', 'settled-B-inventory', 'success'); await adapter(io, 'release', 'settled-B-manifest', 'success');
  await waitDom(io, `document.body.innerText.includes(${js(b.title)})`, 'settled-B-installed');
  return await finish(io, `${io.config.evidenceDir}/${number}-settled-feedback-${mode}.png`);
}
async function omissionCase(io, number) {
  const runId = `${io.config.runId}-${number}`, a = makeWireFixture(runId, 'A', 'resource-split');
  const b = makeWireFixture(runId, 'B', 'dispatch'); b.row.project = a.project;
  const entry = entrySpecifications.find(x => x.id === 'resource-split');
  await install(io, { runId, origin: io.config.origin, steps: [...bootSteps(a), ...mutationSteps(a, 'omit-mutation'),
    get('omitted-inventory', reviewsPath, [], { project: a.project, reviews: [b.row] }, { hold: true }),
    get('omitted-legacy', '/api/translations/documents', [['project', a.project]], { documents: [], total: 0 })] });
  await evaluate(io, domAction('route', '/translations')); await openWork(io, a);
  await evaluate(io, domAction('button', entry.label)); await adapter(io, 'release', 'omit-mutation', 'success');
  await waitDom(io, `window.__knoxxControlledCore.report().pending.includes('omitted-inventory')`, 'omitted-inventory');
  await adapter(io, 'watchCommit', 'omitted', { project: a.project, absent: [a.title, a.detail.segments[0].translated_text, 'Split 0 approved.'], present: [b.title], formAbsent: true });
  await adapter(io, 'release', 'omitted-inventory', 'success');
  await waitDom(io, `window.__knoxxControlledCore.report().records.includes('commit:omitted')`, 'omitted-first-observed-commit');
  const before = (await adapter(io, 'report')).records.length;
  await evaluate(io, `(() => { const n=[...document.querySelectorAll('button')].find(x=>['Submit review','Approve split','Approve All'].includes(x.textContent.trim())); if(n&&!n.disabled)n.click(); return true; })()`);
  await new Promise(resolve => setTimeout(resolve, 100));
  assert.equal((await adapter(io, 'report')).records.length, before);
  // This is the first observer delivery, not a claim that an arbitrary browser
  // guarantees MutationObserver precedes all React passive effects.
  return await finish(io, `${io.config.evidenceDir}/${number}-inventory-omission.png`);
}

/** Execute actual normal UI controls. Any absent checkpoint/ordering fails this proof. */
export async function runCoreScenarios(io) {
  const results = []; let number = 0, primaryThrown = false, primaryError;
  try {
    for (const entry of entrySpecifications) {
      for (const mode of ['success', 'error']) {
        io.onCase?.({ stage: 'scenarios', caseNumber: number + 1, caseName: `${entry.id}:project-${mode}`, completedCases: results.length });
        await evaluate(io, domAction('route', '/cms'));
        results.push({ entry: entry.id, case: 'project-' + mode, proof: await projectCase(io, entry, mode, ++number) });
        io.onCase?.({ stage: 'scenarios', caseNumber: number + 1, caseName: `${entry.id}:unmount-${mode}`, completedCases: results.length });
        await evaluate(io, domAction('route', '/cms'));
        results.push({ entry: entry.id, case: 'unmount-' + mode, proof: await unmountCase(io, entry, mode, ++number) });
        io.onCase?.({ stage: 'scenarios', caseNumber: number + 1, caseName: `${entry.id}:remount-${mode}`, completedCases: results.length });
        await evaluate(io, domAction('route', '/cms'));
        results.push({ entry: entry.id, case: 'remount-' + mode, proof: await remountOwnerCase(io, entry, mode, ++number) });
      }
    }
    for (const mode of ['success', 'error']) {
      io.onCase?.({ stage: 'scenarios', caseNumber: number + 1, caseName: 'first-filter-render-' + mode, completedCases: results.length });
      await evaluate(io, domAction('route', '/cms'));
      results.push({ case: 'first-filter-render-' + mode, proof: await filterCase(io, mode, ++number) });
    }
    io.onCase?.({ stage: 'scenarios', caseNumber: number + 1, caseName: 'first-selected-detail', completedCases: results.length });
    await evaluate(io, domAction('route', '/cms'));
    results.push({ case: 'first-selected-detail', proof: await selectedWorkCase(io, ++number) });
    for (const mode of ['success', 'error']) for (const boundary of ['project', 'locale', 'aba', 'unmount']) {
      io.onCase?.({ stage: 'scenarios', caseNumber: number + 1, caseName: `export-${boundary}-${mode}`, completedCases: results.length });
      await evaluate(io, domAction('route', '/cms'));
      results.push({ case: `export-${boundary}-${mode}`, proof: await exportCase(io, mode, boundary, ++number) });
    }
    for (const mode of ['success', 'error']) {
      io.onCase?.({ stage: 'scenarios', caseNumber: number + 1, caseName: 'settled-feedback-' + mode, completedCases: results.length });
      await evaluate(io, domAction('route', '/cms'));
      results.push({ case: 'settled-feedback-' + mode, proof: await settledFeedbackCase(io, mode, ++number) });
    }
    io.onCase?.({ stage: 'scenarios', caseNumber: number + 1, caseName: 'inventory-omission-first-observer', completedCases: results.length });
    await evaluate(io, domAction('route', '/cms'));
    results.push({ case: 'inventory-omission-first-observer', proof: await omissionCase(io, ++number) });
    io.onCase?.({ stage: 'scenarios', caseNumber: number + 1, caseName: 'experimental-pre-first-B', completedCases: results.length });
    await evaluate(io, domAction('route', '/cms'));
    results.push({ case: 'experimental-pre-first-B', proof: await preFirstBCase(io, ++number) });
    return results;
  } catch (error) {
    primaryThrown = true; primaryError = error; throw error;
  } finally {
    try {
      await evaluate(io, `(() => { const a=window.__knoxxControlledCore;if(a)a.restore();return true; })()`);
    } catch (cleanupError) {
      // A diagnostic observer cannot replace the original thrown value either.
      try { io.onCleanupError?.('scenario-adapter-restore'); }
      finally { throw primaryThrown ? primaryError : cleanupError; }
    }
  }
}
