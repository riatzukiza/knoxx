// PREP ONLY. No standalone invocation. Loaded only by the reviewed, env-isolated Services probe.
'use strict';
const fs = require('node:fs');
const path = require('node:path');
const crypto = require('node:crypto');
const evidence = process.env.PROBE_EVIDENCE_DIR;
if (!path.isAbsolute(evidence || '') || fs.realpathSync(evidence) !== evidence || fs.statSync(evidence).uid !== process.getuid() || fs.readdirSync(evidence).length !== 0) throw new Error('Unexpected owned empty evidence directory');
const expectedOrigin = 'http://192.168.239.2:11434';
const expectedPaths = ['/api/tags', '/api/chat', '/v1/chat/completions', '/v1/embeddings'];
const nativeFetch = globalThis.fetch.bind(globalThis);
let calls = 0;
const sha = (bytes) => crypto.createHash('sha256').update(bytes).digest('hex');
const save = (name, bytes) => fs.writeFileSync(path.join(evidence, name), bytes, {flag: 'wx', mode: 0o600});
globalThis.fetch = async (input, init = {}) => {
  const ordinal = calls++;
  const url = new URL(String(input));
  if (ordinal >= expectedPaths.length || url.origin !== expectedOrigin || url.pathname !== expectedPaths[ordinal] || url.search || url.hash) throw new Error('Unexpected probe request');
  const method = init.method || 'GET';
  if (method !== (ordinal === 0 ? 'GET' : 'POST')) throw new Error('Unexpected probe method');
  const headers = new Headers(init.headers);
  if (headers.has('authorization') || headers.has('cookie') || !init.signal) throw new Error('Unexpected probe credentials or missing deadline');
  const body = ordinal === 0 ? '' : init.body;
  if (typeof body !== 'string') throw new Error('Probe body is not exact JSON text');
  const parsed = body ? JSON.parse(body) : null;
  if (ordinal === 3 && JSON.stringify(parsed) !== JSON.stringify({model: 'qwen3-embedding:8b', input: ['knoxx deployment embedding probe'], dimensions: 1024})) throw new Error('Embedding request differs from the reviewed full-input contract');
  const stem = String(ordinal + 1).padStart(2, '0');
  save(stem + '-request.raw.json', Buffer.from(body));
  save(stem + '-request.metadata.json', JSON.stringify({url: url.href, method, startedAtUTC: new Date().toISOString(), bodyBytes: Buffer.byteLength(body), bodySHA256: sha(body), body: parsed, authorizationAbsent: true, cookieAbsent: true, signalPresent: true}, null, 2) + '\n');
  const started = performance.now();
  const response = await nativeFetch(input, init);
  const reader = response.clone().body.getReader();
  const chunks = [];
  let totalBytes = 0;
  for (;;) {
    const {done, value} = await reader.read();
    if (done) break;
    totalBytes += value.byteLength;
    if (totalBytes > 1048576) {
      // Cancel both tee branches without waiting on one branch before the other.
      const cancellations = [reader.cancel(), response.body.cancel()];
      void Promise.allSettled(cancellations);
      throw new Error('Provider response exceeds private evidence bound');
    }
    chunks.push(Buffer.from(value));
  }
  const bytes = Buffer.concat(chunks, totalBytes);
  save(stem + '-response.raw.json', bytes);
  const metadata = {status: response.status, elapsedMs: performance.now() - started, bodyBytes: bytes.length, bodySHA256: sha(bytes)};
  if (ordinal === 3) {
    const reply = JSON.parse(bytes);
    const row = reply.data && reply.data[0];
    metadata.exactEmbedding = Boolean(reply.model === 'qwen3-embedding:8b' && reply.data.length === 1 && row.index === 0 && Array.isArray(row.embedding) && row.embedding.length === 1024 && row.embedding.every(x => typeof x === 'number' && Number.isFinite(x)));
  }
  save(stem + '-response.metadata.json', JSON.stringify(metadata, null, 2) + '\n');
  return response;
};
process.on('exit', () => {
  if (calls !== 4) process.exitCode = 1;
});
