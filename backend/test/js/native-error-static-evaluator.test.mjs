import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync, symlinkSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';
import { collectNativeErrorEvaluator, nativeErrorPin, requireNativeErrorEvaluator,
  restoreNativeErrorEvaluator } from '../../../scripts/native-error-static-evaluator.mjs';
import { pin } from '../../../scripts/sandbox-clj-kondo.mjs';

/** Own a disposable directory for malformed tool inputs; never install fixture tools. */
function fixture(t) {
  const directory = mkdtempSync(path.join(tmpdir(), 'knoxx-native-error-tool-'));
  t.after(() => rmSync(directory, { recursive: true, force: true }));
  return directory;
}

test('native error evaluator is explicit and independent of the unchanged primary profile', () => {
  assert.equal(pin.version, '2025.07.28');
  assert.equal(nativeErrorPin.version, '2026.08.04');
  for (const input of [undefined, '', 'clj-kondo', './clj-kondo']) {
    assert.throws(() => requireNativeErrorEvaluator(input), /must select an absolute verified auxiliary executable/);
  }
});

test('missing selected executable fails without looking for a PATH tool', t => {
  assert.throws(() => requireNativeErrorEvaluator(path.join(fixture(t), 'absent')), /ENOENT/);
});

test('claimed version cannot authorize different executable bytes or a symlink', t => {
  const directory = fixture(t);
  const binary = path.join(directory, 'clj-kondo');
  writeFileSync(binary, '#!/bin/sh\necho "clj-kondo v2026.08.04"\n', { mode: 0o755 });
  assert.throws(() => requireNativeErrorEvaluator(binary), /binary checksum mismatch/);
  const alias = path.join(directory, 'alias');
  symlinkSync(binary, alias);
  assert.throws(() => requireNativeErrorEvaluator(alias), /regular executable file/);
});

test('foreign host fails before opening any executable', () => {
  for (const host of [{ platform: 'linux', arch: 'arm64' }, { platform: 'darwin', arch: 'x64' }]) {
    assert.throws(() => requireNativeErrorEvaluator('/absent', host), /requires linux\/x64/);
  }
});

test('restoration and its CLI refuse missing auxiliary archive despite an installed primary tool', t => {
  const directory = fixture(t);
  assert.throws(() => restoreNativeErrorEvaluator(directory, path.join(directory, 'tools')), /ENOENT/);
  const script = fileURLToPath(new URL('../../../scripts/native-error-static-evaluator.mjs', import.meta.url));
  const result = spawnSync(process.execPath, [script, 'restore', directory, path.join(directory, 'tools')],
    { encoding: 'utf8', timeout: 10_000 });
  assert.equal(result.status, 1);
  assert.match(result.stderr, /ENOENT/);
  assert.equal(result.stdout, '');
  assert(!existsSync(path.join(directory, 'tools')));
});

test('auxiliary collection preserves download failure instead of writing a success artifact', async t => {
  const directory = path.join(fixture(t), 'bundle');
  t.mock.method(globalThis, 'fetch', async () => new Response('unavailable', { status: 503 }));
  await assert.rejects(collectNativeErrorEvaluator(directory), /download returned 503/);
  assert(!existsSync(directory));
});

test('auxiliary collection rejects a corrupt archive before storing any profile', async t => {
  const directory = path.join(fixture(t), 'bundle');
  t.mock.method(globalThis, 'fetch', async () => new Response('tampered archive'));
  await assert.rejects(collectNativeErrorEvaluator(directory), /archive checksum mismatch/);
  assert(!existsSync(directory));
});
