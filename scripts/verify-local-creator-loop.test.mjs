// SPDX-License-Identifier: GPL-3.0-or-later
// Actual served-mode shell execution with a dedicated source tree and fake Docker.
// The fixture never reaches the system Docker executable, HTTP or a backend.
import assert from 'node:assert/strict';
import { mkdtempSync, mkdirSync, writeFileSync, readFileSync, readdirSync, copyFileSync, unlinkSync,
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

function seedModules(source, destination, modules, relative = '', namespace = 'knoxx.backend') {
  for (const entry of readdirSync(source, { withFileTypes: true })) {
    const file = path.join(relative, entry.name);
    if (entry.isDirectory()) {
      seedModules(path.join(source, entry.name), destination, modules, file, namespace);
    } else if (entry.isFile() && /\.(cljs|cljc)$/.test(file)) {
      const target = path.join(destination, file);
      mkdirSync(path.dirname(target), { recursive: true });
      copyFileSync(path.join(source, entry.name), target);
      utimesSync(target, 1_600_000_000, 1_600_000_000);
      const module = path.join(modules, `${namespace}.${file.replaceAll('/', '.').replace(/\.(cljs|cljc)$/, '.js')}`);
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

const cases = [
  ...['infra/character/authority.cljs', 'infra/agent/turn_startup.cljs', 'domain/node/crypto.cljs']
    .map(feature => ({ feature, title: `served verification refuses a stale ${feature} before Docker exec` })),
  { feature: 'markup.cljs', shared: true, title: 'served verification refuses stale emitted shared markup before Docker exec' },
  { missing: 'infra/character/authority.cljs', title: 'dynamic inventory retains mandatory feature-module presence refusal' },
  { title: 'fresh emitted backend/shared source controls pass matching served bytes' },
];

for (const { feature, shared, missing, title } of cases) {
  test(title, () => {
    const root = mkdtempSync(path.join(tmpdir(), 'knoxx-served-freshness-'));
    try {
      const fixtures = path.join(root, 'scripts');
      const bin = path.join(root, 'fixture-bin');
      const modules = path.join(root, 'backend/dist/cljs-runtime');
      for (const directory of [fixtures, bin, modules]) mkdirSync(directory, { recursive: true });
      copyFileSync(path.join(scripts, 'verify-local-creator-loop.sh'), path.join(fixtures, 'verify-local-creator-loop.sh'));
      seedModules(sourceRoot, path.join(root, 'backend/src/cljs/knoxx/backend'), modules);
      seedModules(path.join(repository, 'shared/src/cljs/open_hax/uxx'),
        path.join(root, 'shared/src/cljs/open_hax/uxx'), modules, '', 'open_hax.uxx');
      if (feature) {
        const stale = path.join(root, shared ? 'shared/src/cljs/open_hax/uxx' : 'backend/src/cljs/knoxx/backend', feature);
        utimesSync(stale, 1_800_000_000, 1_800_000_000);
      }
      if (missing) unlinkSync(path.join(modules,
        `knoxx.backend.${missing.replaceAll('/', '.').replace(/\.(cljs|cljc)$/, '.js')}`));
      writeFileSync(path.join(root, 'backend/dist/server.js'), '// fixture, never executed\n');
      git(root, 'init', '--quiet');
      git(root, 'add', '--', 'scripts', 'backend', 'shared');
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
      if (!feature && !missing) {
        assert.equal(result.status, 0, result.stdout + result.stderr);
        assert.match(result.stdout, /source freshness checked \d+ emitted modules/);
        assert.match(readFileSync(trace, 'utf8'), /^exec /m);
      } else {
        assert.equal(result.status, 2, result.stdout + result.stderr);
        const module = `${shared ? 'open_hax.uxx' : 'knoxx.backend'}.${(feature ?? missing)
          .replaceAll('/', '.').replace(/\.(cljs|cljc)$/, '.js')}`;
        assert.match(result.stderr, new RegExp(`${missing ? 'required feature module is absent from compiled server' :
          'compiled module predates its source'}: ${module.replaceAll('.', '\\.')}[;\n]`));
        assert.doesNotMatch(readFileSync(trace, 'utf8'), /^exec /m,
          'Source freshness and mandatory presence refuse before container content operations');
      }
    } finally {
      rmSync(root, { recursive: true, force: true, maxRetries: 5, retryDelay: 100 });
    }
  });
}
