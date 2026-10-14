// SPDX-License-Identifier: GPL-3.0-or-later
// Actual served-mode shell execution with a dedicated source tree and fake Docker.
// The fixture never reaches the system Docker executable, HTTP or a backend.
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, copyFileSync,
  chmodSync, utimesSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { spawnSync } from 'node:child_process';
import { createHash } from 'node:crypto';
import test from 'node:test';

const repository = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '..');
const scripts = path.join(repository, 'scripts');
const sourceRoot = path.join(repository, 'backend/src/cljs/knoxx/backend');

function git(root, ...args) {
  const result = spawnSync('git', ['-C', root, ...args], { encoding: 'utf8' });
  assert.equal(result.status, 0, result.stderr);
  return result.stdout.trim();
}

function seedModules(source, destination, modules, relative = '') {
  for (const entry of readdirSync(source, { withFileTypes: true })) {
    const file = path.join(relative, entry.name);
    if (entry.isDirectory()) {
      seedModules(path.join(source, entry.name), destination, modules, file);
    } else if (entry.isFile() && /\.(cljs|cljc)$/.test(file)) {
      const target = path.join(destination, file);
      mkdirSync(path.dirname(target), { recursive: true });
      copyFileSync(path.join(source, entry.name), target);
      utimesSync(target, 1_600_000_000, 1_600_000_000);
      const module = path.join(modules, `knoxx.backend.${file.replaceAll('/', '.').replace(/\.(cljs|cljc)$/, '.js')}`);
      writeFileSync(module, '// dedicated compiled-content fixture\n');
      utimesSync(module, 1_700_000_000, 1_700_000_000);
    }
  }
}

function compiledInventory(root, relative = '', rows = []) {
  for (const entry of readdirSync(path.join(root, relative), { withFileTypes: true })) {
    const file = path.join(relative, entry.name);
    if (entry.isDirectory()) compiledInventory(root, file, rows);
    else if (entry.isFile() && file.endsWith('.js')) {
      rows.push([file, createHash('sha256').update(readFileSync(path.join(root, file))).digest('hex')]);
    }
  }
  return rows.sort((a, b) => a[0].localeCompare(b[0]));
}

for (const feature of ['infra/character/authority.cljs', 'infra/agent/turn_startup.cljs', 'domain/node/crypto.cljs']) {
  test(`served verification refuses a stale ${feature} before Docker exec`, () => {
    const root = mkdtempSync(path.join(tmpdir(), 'knoxx-served-freshness-'));
    try {
      const fixtures = path.join(root, 'scripts');
      const bin = path.join(root, 'fixture-bin');
      const modules = path.join(root, 'backend/dist/cljs-runtime');
      for (const directory of [fixtures, bin, modules]) mkdirSync(directory, { recursive: true });
      copyFileSync(path.join(scripts, 'verify-local-creator-loop.sh'), path.join(fixtures, 'verify-local-creator-loop.sh'));
      seedModules(sourceRoot, path.join(root, 'backend/src/cljs/knoxx/backend'), modules);
      const stale = path.join(root, 'backend/src/cljs/knoxx/backend', feature);
      utimesSync(stale, 1_800_000_000, 1_800_000_000);
      writeFileSync(path.join(root, 'backend/dist/server.js'), '// fixture, never executed\n');
      git(root, 'init', '--quiet');
      git(root, 'add', '--', 'scripts', 'backend');
      git(root, '-c', 'commit.gpgSign=false', '-c', 'user.name=Fixture', '-c', 'user.email=fixture@example.invalid',
        'commit', '--quiet', '-m', 'Dedicated freshness fixture');
      const head = git(root, 'rev-parse', 'HEAD');
      const trace = path.join(root, 'docker-calls');
      const served = path.join(root, 'same-stale-served-inventory.json');
      writeFileSync(served, JSON.stringify(compiledInventory(path.join(root, 'backend/dist'))));
      const docker = path.join(bin, 'docker');
      writeFileSync(docker, `#!/bin/sh\nprintf '%s\\n' "$*" >> '${trace}'\ncase "$*" in\n*State.Running*) printf 'true\\n';;\n*org.opencontainers.image.revision*) printf '${head}\\n';;\nexec*) cat '${served}';;\n*) exit 2;;\nesac\n`);
      chmodSync(docker, 0o755);
      const result = spawnSync('bash', [path.join(fixtures, 'verify-local-creator-loop.sh'), '--served'], {
        encoding: 'utf8', env: { ...process.env, PATH: `${bin}${path.delimiter}${process.env.PATH}` },
        timeout: 30_000,
      });
      assert.equal(result.status, 2, result.stdout + result.stderr);
      const module = `knoxx.backend.${feature.replaceAll('/', '.').replace(/\.(cljs|cljc)$/, '.js')}`;
      assert.match(result.stderr, new RegExp(`compiled module predates its source: ${module.replaceAll('.', '\\.')}[;]`));
      assert.doesNotMatch(readFileSync(trace, 'utf8'), /^exec /m,
        'Source freshness fails before any compiled-content or behavioral container operation');
    } finally {
      rmSync(root, { recursive: true, force: true });
    }
  });
}
