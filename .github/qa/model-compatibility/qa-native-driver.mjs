// PREP ONLY. Future execution requires source/resource admission and the existing
// owned-process supervisor with a HARD 15000ms cap, plus normal owned cleanup.
// This observes one genuine native fetch, preserving its input/init/response.
// It neither provides a fake provider nor changes the component's 30000ms timer.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import path from 'node:path';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';

const [tier, evidenceArg, originArg] = process.argv.slice(2);
assert.equal(process.argv.length, 5);
assert.ok(tier === 'hot' || tier === 'compact');
const allowedEnv = new Set(['PATH', 'HOME', 'LANG', 'LC_ALL', 'OLLAMA_BASE_URL',
  'EMBED_PROVIDER_BASE_URL', 'EMBED_PROVIDER_MODEL', 'EMBED_PROVIDER_COMPACT_MODEL',
  'EMBED_PROVIDER_DIMENSIONS', 'OPENPLANNER_DATA_DIR', 'EMBED_PROVIDER_CACHE_PATH']);
assert.ok(Object.keys(process.env).every(key => allowedEnv.has(key)), 'env-i allowlist required');
const origin = new URL(originArg);
assert.equal(origin.protocol, 'http:');
assert.equal(origin.port, '11434');
assert.equal(origin.href, `${origin.origin}/`);
assert.equal(process.env.OLLAMA_BASE_URL, origin.origin);
assert.equal(process.env.EMBED_PROVIDER_BASE_URL, origin.origin);
assert.equal(process.env.EMBED_PROVIDER_MODEL, 'qwen3-embedding:8b');
assert.equal(process.env.EMBED_PROVIDER_COMPACT_MODEL, 'qwen3-embedding:8b');
assert.equal(process.env.EMBED_PROVIDER_DIMENSIONS, '1024');
assert.ok(path.isAbsolute(process.env.OPENPLANNER_DATA_DIR || ''));
assert.ok(path.isAbsolute(process.env.EMBED_PROVIDER_CACHE_PATH || ''));
const evidence = path.resolve(evidenceArg);
assert.equal(evidence, evidenceArg);
const parent = path.dirname(evidence);
assert.equal(fs.realpathSync(parent), parent);
assert.equal(fs.statSync(parent).uid, process.getuid());
fs.mkdirSync(evidence, {mode: 0o700}); // Refuse any existing directory/link.
const save = (name, bytes) => fs.writeFileSync(path.join(evidence, name), bytes,
  {flag: 'wx', mode: 0o600});
const sha = bytes => crypto.createHash('sha256').update(bytes).digest('hex');
const qaRoot = path.dirname(fileURLToPath(import.meta.url));
const sdkRoot = path.dirname(qaRoot); // SDK-package/.qa-live-embedding only.
let calls = 0;
let completedResponses = 0;
let primary;
let result;
const originalFetch = globalThis.fetch;
const nativeFetch = originalFetch.bind(globalThis);
const expectedBody = {model: 'qwen3-embedding:8b', dimensions: 1024,
  input: ['knoxx deployment embedding probe']};
const observedFetch = async (input, init = {}) => {
  calls += 1;
  assert.equal(calls, 1, 'One request only; no retries, warm-up or parallel provider');
  const url = new URL(String(input));
  assert.equal(url.href, `${origin.origin}/v1/embeddings`);
  assert.equal(init.method, 'POST');
  assert.equal(init.redirect, 'error');
  assert.ok(init.signal, 'Original checked-provider deadline signal required');
  const headers = new Headers(init.headers);
  assert.ok(!headers.has('authorization') && !headers.has('cookie'));
  assert.equal(headers.get('content-type'), 'application/json');
  assert.equal(typeof init.body, 'string');
  assert.deepEqual(JSON.parse(init.body), expectedBody);
  save('request.raw.json', Buffer.from(init.body));
  save('request.metadata.json', JSON.stringify({tier, startedAtUTC: new Date().toISOString(),
    model: expectedBody.model, dimensions: 1024, inputCount: 1,
    bytes: Buffer.byteLength(init.body), sha256: sha(init.body),
    authorizationAbsent: true, cookieAbsent: true, originalSignalPreserved: true}, null, 2) + '\n');
  const started = performance.now();
  const response = await nativeFetch(input, init);
  const reader = response.clone().body?.getReader();
  const chunks = [];
  let length = 0;
  while (reader) {
    const {done, value} = await reader.read();
    if (done) break;
    length += value.byteLength;
    if (length > 1048576) {
      void Promise.allSettled([reader.cancel(), response.body.cancel()]);
      throw new Error('Native response exceeds 1MiB evidence cap');
    }
    chunks.push(Buffer.from(value));
  }
  const bytes = Buffer.concat(chunks, length);
  save('response.raw.json', bytes);
  save('response.metadata.json', JSON.stringify({status: response.status,
    elapsedMs: performance.now() - started, bytes: bytes.length, sha256: sha(bytes)}, null, 2) + '\n');
  completedResponses += 1;
  return response;
};
try {
  const modules = {};
  for (const name of ['config', 'embedding-runtime']) {
    const url = import.meta.resolve(`@open-hax/openplanner-sdk/${name}`);
    const actual = fs.realpathSync(fileURLToPath(url));
    const expected = path.join(sdkRoot, 'dist', `${name}.js`);
    assert.equal(actual, expected, 'Genuine pinned SDK self-export required');
    const bytes = fs.readFileSync(actual);
    modules[name] = {file: actual, bytes: bytes.length, sha256: sha(bytes)};
  }
  save('actual-sdk-module-resolution.json', JSON.stringify(modules, null, 2) + '\n');
  globalThis.fetch = observedFetch;
  const entry = await import('./dist/entry.js');
  assert.equal(globalThis.fetch, observedFetch, 'No loader may replace native observation');
  result = await entry.runCheckedEmbeddingQa(tier);
  assert.equal(calls, 1);
  assert.equal(completedResponses, 1);
  assert.equal(result.tier, tier);
  assert.equal(result['selected-model'], expectedBody.model);
  assert.equal(result['actual-sdk-hot-model'], expectedBody.model);
  assert.equal(result['actual-sdk-compact-model'], expectedBody.model);
  assert.equal(result['vector-count'], 1);
  assert.deepEqual(result.widths, [1024]);
  const vectorBytes = Buffer.from(JSON.stringify(result.vectors));
  assert.ok(vectorBytes.length <= 1048576);
  save('vectors.private.json', vectorBytes);
  const {vectors, ...metadata} = result;
  save('result.metadata.json', JSON.stringify({...metadata,
    vectorBytes: vectorBytes.length, vectorSHA256: sha(vectorBytes)}, null, 2) + '\n');
} catch (error) {
  primary = {name: error.name, message: error.message};
  try {
    const stack = error.stack;
    if (typeof stack === 'string') {
      primary.stack = stack.slice(0, 8192);
      primary.stackTruncated = stack.length > 8192;
    }
  } catch {
    primary.stackUnavailable = true; // A diagnostic must not replace the primary.
  }
  process.exitCode = 1;
} finally {
  globalThis.fetch = originalFetch;
  save('outcome.json', JSON.stringify({tier, success: !primary, primary,
    calls, completedResponses, originalFetchRestored: globalThis.fetch === originalFetch,
    providerTimerUnchangedMs: 30000, requiredOwningProcessCapMs: 15000,
    note: 'The owning controller must retain actual timeout/signals/cleanup; this file cannot prove external deadline admission.'}, null, 2) + '\n');
}
