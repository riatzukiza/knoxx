// GPL-3.0-or-later. Execute the actual workflow finalizer in a disposable directory.
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { chmodSync, mkdirSync, mkdtempSync, readFileSync, rmSync, copyFileSync, writeFileSync } from 'node:fs';
import { tmpdir } from 'node:os';
import path from 'node:path';
import test from 'node:test';
import { fileURLToPath } from 'node:url';

const root = fileURLToPath(new URL('../../../', import.meta.url));
const workflow = readFileSync(path.join(root, '.github/workflows/sandbox-bundle.yml'), 'utf8');
const step = workflow.split('      - name: Write bundle metadata and checksums\n')[1]?.split('\n      - name:')[0];
assert(step, 'actual finalization step must exist');
const script = step.split('        run: |\n')[1]?.split('\n').map(line => {
  assert(!line.trim() || line.startsWith('          '), 'workflow script indentation changed');
  return line.slice(10);
}).join('\n');
assert(script, 'actual shell script must exist');

for (const observed of ['present', 'absent', 'unreadable', 'empty', 'invalid']) {
  test(`sandbox manifest uses observed checkout revision; ${observed} metadata`, t => {
    const directory = mkdtempSync(path.join(tmpdir(), 'knoxx-manifest-'));
    t.after(() => rmSync(directory, { recursive: true, force: true }));
    const out = path.join(directory, 'bundle');
    const bin = path.join(directory, 'bin');
    mkdirSync(path.join(out, 'metadata'), { recursive: true });
    mkdirSync(bin);
    mkdirSync(path.join(directory, 'scripts'));
    copyFileSync(path.join(root, 'scripts/sandbox-clj-kondo.mjs'), path.join(directory, 'scripts/sandbox-clj-kondo.mjs'));
    for (const tool of ['pnpm', 'clj-kondo', 'java', 'clojure']) {
      const file = path.join(bin, tool);
      writeFileSync(file, '#!/bin/sh\nprintf "isolated version fixture\\n"\n');
      chmodSync(file, 0o755);
    }
    const revision = '1234567890123456789012345678901234567890';
    const pin = 'abcdefabcdefabcdefabcdefabcdefabcdefabcd';
    const revisionFile = path.join(out, 'metadata/openplanner-revision.txt');
    if (observed === 'present') writeFileSync(revisionFile, `${revision}\n`);
    if (observed === 'empty') writeFileSync(revisionFile, '');
    if (observed === 'invalid') writeFileSync(revisionFile, 'not-a-commit\n');
    if (observed === 'unreadable') mkdirSync(revisionFile); // cat fails even under a privileged test runner.
    const outcomes = Object.fromEntries([...step.matchAll(/^          ([A-Z_]+_OUTCOME):/gm)].map(match => [match[1], 'skipped']));
    const result = spawnSync('bash', ['-c', script], {
      cwd: directory, encoding: 'utf8',
      env: { PATH: `${bin}:${process.env.PATH}`, OUT: out, RUNNER_TEMP: directory,
        GITHUB_REPOSITORY: 'riatzukiza/knoxx', GITHUB_SHA: 'fixture-head', GITHUB_REF: 'fixture-ref',
        OPENPLANNER_REF: pin, ...outcomes },
    });
    assert.equal(result.status, 0, result.stderr);
    const manifest = JSON.parse(readFileSync(path.join(out, 'manifest.json'), 'utf8'));
    assert.equal(manifest.openplanner_revision, observed === 'present' ? revision : pin);
    assert.equal(manifest.openplanner_requested_revision, pin);
    assert.equal(manifest.openplanner_revision_source, observed === 'present' ? 'observed-checkout' : 'requested-pin-fallback');
    assert.equal(manifest.outcomes.build_openplanner, 'skipped');
    assert.match(readFileSync(path.join(out, 'SHA256SUMS'), 'utf8'), /\.\/manifest\.json/);
  });
}
