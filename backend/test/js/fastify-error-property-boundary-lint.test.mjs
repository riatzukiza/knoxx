import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtempSync, rmSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';
import test from 'node:test';
import { requireNativeErrorEvaluator } from '../../../scripts/native-error-static-evaluator.mjs';

const backend = fileURLToPath(new URL('../../', import.meta.url));
const owners = ['extern/fastify.cljs', 'infra/http.cljs', 'infra/routes/mcp/params.cljs']
  .map(path => resolve(backend, 'src/cljs/knoxx/backend', path));

/** Analyze real error owners and a disposable caller with the unchanged backend rules. */
function lintCaller(t, body, sourceFiles = owners, evaluator = 'clj-kondo') {
  const directory = mkdtempSync(join(tmpdir(), 'knoxx-fastify-error-lint-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  const fixture = join(directory, 'error_caller.cljs');
  writeFileSync(fixture, body);
  const result = spawnSync(evaluator, [
    '--config-dir', resolve(backend, '.clj-kondo'),
    '--cache-dir', join(directory, 'cache'),
    '--config', '{:output {:format :json}}',
    '--lint', ...sourceFiles, fixture,
    '--fail-level', 'warning',
  ], { cwd: backend, encoding: 'utf8', timeout: 30_000 });
  assert.ifError(result.error);
  assert.equal(result.signal, null, result.stderr);
  return { ...result, findings: JSON.parse(result.stdout).findings };
}

test('genuine HTTP and MCP exceptions are statically admitted by their named Fastify boundary', t => {
  const body = `(ns error-caller
  (:require [knoxx.backend.extern.fastify :as fastify]
            [knoxx.backend.infra.http :as http]
            [knoxx.backend.infra.routes.mcp.params :as params]))
(defn error-projection []
  (let [ordinary (http/http-error 403 "scope_denied" "Denied")
        oauth (params/http-error 400 "invalid_request" "Invalid" {:attempt 1})
        native-data (ex-info "native data" {:attempt 2})]
    {:ordinary-status (fastify/error-status ordinary 500)
     :ordinary-code (fastify/error-code ordinary)
     :oauth-status (fastify/error-status oauth 500)
     :oauth-code (fastify/error-code oauth)
     :native-message (fastify/error-message native-data)
     :native-log (fastify/log-unclassified-failure! "error-probe" native-data)}))
`;
  const auxiliary = requireNativeErrorEvaluator(process.env.KNOXX_NATIVE_ERROR_KONDO);
  for (const evaluator of ['clj-kondo', auxiliary.binary]) {
    const result = lintCaller(t, body, owners, evaluator);
    assert.deepEqual(result.findings, [], result.stdout);
    assert.equal(result.status, 0, result.stderr);
  }
});

test('the same unchanged rules still reject array operations on genuine throwables', t => {
  const auxiliary = requireNativeErrorEvaluator(process.env.KNOXX_NATIVE_ERROR_KONDO);
  const result = lintCaller(t, `(ns error-caller)
(defn genuine-array-mismatch [] (aget (ex-info "boom" {}) "statusCode"))
`, [], auxiliary.binary);
  assert.equal(result.findings.length, 1, result.stdout);
  assert.equal(result.findings[0].type, 'type-mismatch');
  assert.equal(result.findings[0].level, 'error');
  assert.match(result.findings[0].message, /Expected: array, received: throwable/);
  assert.notEqual(result.status, 0);
});
