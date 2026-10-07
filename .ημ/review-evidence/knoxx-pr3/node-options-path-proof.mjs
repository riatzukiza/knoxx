// SPDX-License-Identifier: GPL-3.0-or-later
// Exercise the verifier's actual environment construction and Node's option
// parser. Compilation is stopped at the spawn boundary; no server is contacted.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const script = fs.readFileSync(path.join(root, 'scripts/verify-native-music.mjs'), 'utf8');
const start = script.indexOf('function sourceProof() {');
const end = script.indexOf('// The same bounded script', start);
assert.ok(start >= 0 && end > start, 'locate the actual verifier sourceProof');
const directory = fs.mkdtempSync(path.join(os.tmpdir(), 'knoxx options '));
try {
  const existing = path.join(directory, 'existing preload.cjs');
  fs.writeFileSync(existing, 'process.__knoxxExistingPreload = true;\n');
  const prior = `--no-warnings --require ${JSON.stringify(existing)}`;
  for (const name of ['checkout with spaces', 'checkout with "quotes" and \\backslashes']) {
    const backend = path.join(directory, name, 'backend');
    fs.mkdirSync(path.join(backend, 'scripts'), { recursive: true });
    fs.copyFileSync(path.join(root, 'backend/scripts/shadow-test-error-guard.cjs'),
      path.join(backend, 'scripts/shadow-test-error-guard.cjs'));
    const stop = new Error('captured compile spawn');
    let options;
    const proof = vm.runInNewContext(`(${script.slice(start, end).trim()})`, {
      command: () => '', console: { log() {} }, root: path.dirname(backend), backend,
      revision: 'environment-fixture-only', path, process: { env: { NODE_OPTIONS: prior }, execPath: process.execPath },
      require: { resolve: () => 'compile-not-executed' },
      spawnSync: (_program, _args, config) => { options = config.env.NODE_OPTIONS; throw stop; },
    });
    assert.throws(proof, error => error === stop);
    assert.ok(options.startsWith(`${prior} `), 'preserve all existing NODE_OPTIONS');
    const env = { ...process.env, NODE_OPTIONS: options };
    const loaded = spawnSync(process.execPath, ['-e',
      'console.log(JSON.stringify({existing:process.__knoxxExistingPreload,guarded:Boolean(process[Symbol.for("open-hax.shadow-test-error-guard")])}))'],
    { env, encoding: 'utf8', timeout: 10000 });
    assert.equal(loaded.status, 0, `Node must load the complete guard path: ${loaded.stderr}`);
    assert.deepEqual(JSON.parse(loaded.stdout), { existing: true, guarded: true });
    const fatal = spawnSync(process.execPath, ['-e',
      'Promise.reject(new Error("intentional guard proof"));process.exit(0)'],
    { env, encoding: 'utf8', timeout: 10000 });
    assert.equal(fatal.status, 1, 'guard must still reject a late failure after exit(0)');
    assert.match(fatal.stderr, /shadow-test-guard.*FATAL unhandled rejection/);
    console.log(`PASS ${name}: both preloads load, prior options survive, late rejection exits1`);
  }
} finally {
  fs.rmSync(directory, { recursive: true, force: true });
}
