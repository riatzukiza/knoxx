#!/usr/bin/env bash
# SPDX-License-Identifier: GPL-3.0-or-later
# Pin-specific source/bootstrap boundary. stdout is the verified build path only.
set -euo pipefail
sdk_script="$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd -P)/$(basename -- "${BASH_SOURCE[0]}")"
sdk_node="$(command -v node)"
exec env -i PATH="$PATH" JAVA_HOME="${JAVA_HOME:-/usr/lib/jvm/java-21-openjdk-amd64}" \
  "$sdk_node" --input-type=module - "$sdk_script" "$@" <<'NODE'
import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { createHash } from 'node:crypto';
import * as fs from 'node:fs/promises';
import { openSync, writeSync, fsyncSync, closeSync } from 'node:fs';
import { dirname, join, resolve, relative, sep } from 'node:path';
const [self, mode, ...args] = process.argv.slice(2);
const pin = '07085d6557b75834ce6f50e6c54b8ca47e1c7c08';
const tree = '1dc69c04c8e832661d8062c15ee24a853f135d35';
const selectedHash = '3f67946839edd484db2973dd19b2eb822e41e10ada9ac878bb2d24468839aece';
const roots = ['packages/openplanner-sdk', 'packages/stores/document-hydration',
  'packages/stores/cache', 'package.json', 'pnpm-workspace.yaml', 'pnpm-lock.yaml'];
const sha = (bytes) => createHash('sha256').update(bytes).digest('hex');
const options = {};
for (let i = 0; i < args.length; i += 2) {
  assert.ok(['--source', '--run-root', '--evidence', '--bundle'].includes(args[i]), 'unknown SDK option');
  assert.ok(args[i + 1] && !Object.hasOwn(options, args[i]), 'one value per SDK option');
  options[args[i]] = resolve(args[i + 1]);
}
assert.ok(['prepare', 'restore', 'verify'].includes(mode), 'SDK mode must be prepare, restore or verify');
const run = options['--run-root'];
assert.ok(run && run !== '/', 'an initially absent owned run root is required');
const evidence = options['--evidence'] ?? join(run, 'evidence');
const state = { source_revision: pin, source_tree: tree, selected_inventory_sha256: selectedHash,
  outcome: 'failure', install: 'not-run', build: 'not-run', interrupted: null, commands: [] };
const children = new Set();
let interrupted = null;
function signalChildren(signal) {
  interrupted ??= signal;
  for (const child of children) child.stop(signal);
}
process.once('SIGINT', () => signalChildren('SIGINT'));
process.once('SIGTERM', () => signalChildren('SIGTERM'));

async function ownedParent(path) {
  const parent = dirname(path);
  assert.equal(await fs.realpath(parent), parent, 'SDK parent must not traverse symlinks');
  const stat = await fs.lstat(parent);
  assert.ok(stat.isDirectory() && stat.uid === process.getuid(), 'SDK parent must be owned');
}
async function makeFresh(path) {
  await ownedParent(path);
  await fs.mkdir(path, { mode: 0o700 });
}
async function makeEvidence() {
  const relation = relative(run, evidence);
  assert.ok(relation !== '' && relation !== '..', 'evidence must not overwrite the run root or its parent');
  if (relation.startsWith(`..${sep}`)) { await ownedParent(evidence); await fs.mkdir(evidence, { mode: 0o700 }); }
  else await fs.mkdir(evidence, { recursive: true, mode: 0o700 });
  const stat = await fs.lstat(evidence);
  assert.ok(stat.isDirectory() && stat.uid === process.getuid() && !stat.isSymbolicLink());
  assert.equal(await fs.realpath(evidence), evidence);
}
async function command(argv, { cwd = run, env, log = null, bytes = false, timeout = 15 * 60_000 } = {}) {
  if (interrupted) throw new Error(`SDK interrupted by ${interrupted}`);
  const child = spawn(argv[0], argv.slice(1), { cwd, env, detached: true, stdio: ['ignore', 'pipe', 'pipe'] });
  const output = [], errors = [];
  let hard, escalation, record, timedOut = false, stopRequested = false, groupKillSent = false;
  const stop = (signal) => {
    stopRequested = true;
    if (!child.pid) return;
    try { process.kill(-child.pid, signal); } catch (error) { if (error.code !== 'ESRCH') throw error; }
    escalation ??= new Promise((done) => { hard = setTimeout(() => {
      try { process.kill(-child.pid, 'SIGKILL'); groupKillSent = true; }
      catch (error) { if (error.code !== 'ESRCH') throw error; }
      done();
    }, 2000); });
  };
  const handle = { stop }; children.add(handle);
  const timer = setTimeout(() => { timedOut = true; stop('SIGTERM'); }, timeout);
  let fd;
  try {
    if (log) fd = openSync(log, 'ax', 0o600);
    child.stdout.on('data', (chunk) => { output.push(chunk); if (log) { process.stderr.write(chunk); writeSync(fd, chunk); } });
    child.stderr.on('data', (chunk) => { errors.push(chunk); if (log) { process.stderr.write(chunk); writeSync(fd, chunk); } });
    const result = await new Promise((done, reject) => {
      child.once('error', reject); child.once('close', (code, signal) => done({ code, signal }));
    });
    const stdout = Buffer.concat(output), stderr = Buffer.concat(errors);
    record = { argv, code: result.code, signal: result.signal, timed_out: timedOut,
      stop_requested: stopRequested, stdout_bytes: stdout.length, stdout_sha256: sha(stdout),
      stderr_bytes: stderr.length, stderr_sha256: sha(stderr) };
    state.commands.push(record);
    if (result.code !== 0 || result.signal || timedOut || interrupted) {
      throw new Error(`SDK command failed: ${argv[0]} exit=${result.code} signal=${result.signal} timeout=${timedOut} interrupted=${interrupted ?? 'none'}`);
    }
    return bytes ? stdout : stdout.toString('utf8').trim();
  } finally {
    clearTimeout(timer);
    // A closed leader can leave live descendants with closed stdio. Never cancel
    // escalation on leader close: retain ownership of its process group until KILL.
    if (stopRequested) await escalation;
    clearTimeout(hard); children.delete(handle);
    if (record) record.owned_group_kill_sent = groupKillSent;
    if (fd !== undefined) { fsyncSync(fd); closeSync(fd); }
  }
}
async function environment() {
  const home = join(run, 'home'), config = join(run, 'config');
  await fs.mkdir(home, { mode: 0o700 }); await fs.mkdir(config, { mode: 0o700 });
  const user = join(config, 'user.npmrc'), global = join(config, 'global.npmrc');
  await fs.writeFile(user, '', { flag: 'wx', mode: 0o600 });
  await fs.writeFile(global, '', { flag: 'wx', mode: 0o600 });
  assert.notEqual((await fs.stat(user)).ino, (await fs.stat(global)).ino);
  return { PATH: process.env.PATH, HOME: home, XDG_CONFIG_HOME: config,
    XDG_CACHE_HOME: join(run, 'cache'), PNPM_HOME: join(run, 'pnpm-home'),
    CI: 'true', JAVA_HOME: process.env.JAVA_HOME,
    NPM_CONFIG_USERCONFIG: user, NPM_CONFIG_GLOBALCONFIG: global,
    NPM_CONFIG_REGISTRY: 'https://registry.npmjs.org',
    GIT_CONFIG_GLOBAL: '/dev/null', GIT_CONFIG_NOSYSTEM: '1', GIT_TERMINAL_PROMPT: '0' };
}
function safePath(path) {
  assert.ok(path && !path.startsWith('/') && !path.split('/').some((p) => !p || p === '.' || p === '..'));
  assert.ok(!/[\x00-\x20\\]/.test(path), 'SDK relative paths must be unambiguous');
  return path;
}
async function inventory(directory, paths) {
  const lines = [];
  for (const path of [...paths].sort()) {
    const file = join(directory, safePath(path)); const stat = await fs.lstat(file);
    assert.ok(stat.isFile() && !stat.isSymbolicLink() && stat.nlink === 1, 'SDK source is regular and unaliased');
    assert.equal(await fs.realpath(file), file, 'SDK source path must stay within its owned tree');
    lines.push(`${sha(await fs.readFile(file))}  ${path}\n`);
  }
  const wire = lines.join('');
  assert.equal(paths.length, 47); assert.equal(sha(wire), selectedHash, 'immutable selected source inventory');
  return wire;
}
async function verifyOnlySelected(directory, paths) {
  const actual = [];
  async function walk(path, prefix = '') {
    for (const entry of await fs.readdir(path, { withFileTypes: true })) {
      const relativePath = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isDirectory()) await walk(join(path, entry.name), relativePath);
      else { assert.ok(entry.isFile(), 'no source symlink or special file'); actual.push(relativePath); }
    }
  }
  await walk(directory);
  assert.deepEqual(actual.sort(), [...paths].sort(), 'only selected source exists before lifecycle');
  await inventory(directory, paths);
}
async function initialSource(snapshot, env) {
  const source = options['--source']; assert.ok(source, 'SDK prepare requires source');
  const git = (...argv) => command(['git', '--no-replace-objects', '-C', source, ...argv], { env });
  assert.equal(await git('rev-parse', 'HEAD'), pin, 'exact SDK checkout');
  assert.equal(await git('rev-parse', `${pin}^{tree}`), tree, 'exact SDK tree');
  const raw = await git('ls-tree', '-r', '-z', pin, '--', ...roots);
  const paths = [];
  for (const entry of raw.split('\0').filter(Boolean)) {
    const match = /^100644 blob ([0-9a-f]{40})\t(.+)$/.exec(entry);
    assert.ok(match, 'selected SDK input must be a non-executable Git blob');
    const [, blob, path] = match; safePath(path);
    const bytes = await command(['git', '--no-replace-objects', '-C', source, 'cat-file', 'blob', blob], { env, bytes: true });
    await fs.mkdir(dirname(join(snapshot, path)), { recursive: true });
    await fs.writeFile(join(snapshot, path), bytes, { flag: 'wx', mode: 0o644 }); paths.push(path);
  }
  assert.equal(await git('rev-parse', 'HEAD'), pin, 'SDK source did not move');
  const all = (await git('ls-tree', '-r', '--name-only', pin)).split('\n');
  const excluded = all.filter((path) => !paths.includes(path)).sort();
  state.excluded_relative_paths = excluded;
  state.excluded_inventory_sha256 = sha(excluded.map((path) => `${path}\n`).join(''));
  return paths.sort();
}
async function restoredSource(snapshot, env) {
  const bundle = options['--bundle']; assert.ok(bundle, 'SDK restore requires bundle');
  const original = JSON.parse(await fs.readFile(join(bundle, 'source.json'), 'utf8'));
  assert.equal(original.source_revision, pin); assert.equal(original.source_tree, tree);
  assert.equal(sha(await fs.readFile(self)), original.helper_sha256, 'retained helper checksum');
  assert.equal(original.selected_inventory_sha256, selectedHash);
  const archive = join(bundle, 'source.tar.gz');
  assert.equal(sha(await fs.readFile(archive)), original.source_archive_sha256, 'source archive checksum');
  const names = (await command(['tar', '-tzf', archive], { env })).split('\n');
  assert.equal(names.length, 47); assert.equal(new Set(names).size, 47);
  const verbose = (await command(['tar', '-tvzf', archive, '--numeric-owner'], { env })).split('\n');
  assert.equal(verbose.length, 47); assert.ok(verbose.every((line) => line.startsWith('-rw-r--r-- ')), 'no archive links, directories or special files');
  for (const path of names) {
    safePath(path);
    assert.ok(original.selected_relative_paths.includes(path), 'only declared archive paths');
    const bytes = await command(['tar', '-xOzf', archive, '--', path], { env, bytes: true });
    await fs.mkdir(dirname(join(snapshot, path)), { recursive: true });
    await fs.writeFile(join(snapshot, path), bytes, { flag: 'wx', mode: 0o644 });
  }
  state.excluded_relative_paths = original.excluded_relative_paths;
  assert.ok(Array.isArray(state.excluded_relative_paths));
  state.excluded_inventory_sha256 = sha(state.excluded_relative_paths.map((path) => `${path}\n`).join(''));
  assert.equal(state.excluded_inventory_sha256, original.excluded_inventory_sha256);
  return names.sort();
}
let created = false;
try {
  assert.match(process.version, /^v24\./, 'SDK requires Node24');
  await makeFresh(run); created = true; await makeEvidence();
  const env = await environment();
  state.node_version = process.version;
  state.pnpm_version = await command(['pnpm', '--version'], { env });
  assert.equal(state.pnpm_version, '10.20.0', 'SDK requires pnpm10.20.0');
  const javaVersion = await command(['/bin/bash', '-c', 'java -version 2>&1'], { env });
  assert.match(javaVersion, /(?:openjdk|java) version "21[.\"]/); state.java_version = javaVersion;
  if (mode === 'verify') {
    await command([process.execPath, '--test', join(dirname(self), '../backend/test/integration/sandbox-openplanner-sdk-bootstrap.test.mjs')],
      { cwd: resolve(dirname(self), '..'), env: { ...env,
        KNOXX_SANDBOX_SDK_SOURCE: options['--source'], KNOXX_SANDBOX_SDK_RUN_ROOT: run }, log: join(evidence, 'verify.log'), timeout: 45 * 60_000 });
    state.outcome = 'success';
  } else {
    const snapshot = join(run, 'selected-source'), filtered = join(run, 'filtered-build');
    await fs.mkdir(snapshot); await fs.mkdir(filtered);
    const paths = mode === 'prepare' ? await initialSource(snapshot, env) : await restoredSource(snapshot, env);
    state.selected_relative_paths = paths;
    const wire = await inventory(snapshot, paths); await fs.writeFile(join(evidence, 'source.sha256'), wire);
    const archive = join(evidence, 'source.tar.gz');
    await command(['tar', '-czf', archive, '-C', snapshot, '--', ...paths], { env });
    state.source_archive_sha256 = sha(await fs.readFile(archive));
    await fs.copyFile(self, join(evidence, 'build-sandbox-openplanner-sdk.sh'));
    state.helper_sha256 = sha(await fs.readFile(self));
    await fs.writeFile(join(evidence, 'source.json'), JSON.stringify(state, null, 2) + '\n');
    for (const path of paths) { await fs.mkdir(dirname(join(filtered, path)), { recursive: true }); await fs.copyFile(join(snapshot, path), join(filtered, path)); }
    await verifyOnlySelected(filtered, paths);
    state.before_lifecycle = { selected_source_verified: true, credential_env_names_present: [],
      allowed_env_names: Object.keys(env).sort(), forbidden_source_paths_absent: true };
    await fs.writeFile(join(evidence, 'before-lifecycle.json'), JSON.stringify(state.before_lifecycle, null, 2) + '\n');
    const install = ['pnpm', '-C', filtered, '--filter', '@open-hax/openplanner-sdk...', 'install', '--frozen-lockfile'];
    state.install = 'failure'; await command(install, { env, log: join(evidence, 'install.log') }); state.install = 'success';
    state.build = 'failure'; await command(['pnpm', '-C', filtered, '--filter', '@open-hax/openplanner-sdk...', 'run', 'build'],
      { env, log: join(evidence, 'build.log') }); state.build = 'success';
    const exports = ['packages/openplanner-sdk/dist/search-core.js', 'packages/stores/document-hydration/dist/index.js', 'packages/stores/cache/dist/index.js'];
    state.generated_exports = [];
    for (const path of exports) { const file = join(filtered, path); const stat = await fs.lstat(file); assert.ok(stat.isFile() && !stat.isSymbolicLink()); state.generated_exports.push({ path, sha256: sha(await fs.readFile(file)) }); }
    if (interrupted) throw new Error(`SDK interrupted by ${interrupted}`);
    state.filtered_tree = filtered; state.outcome = 'success';
  }
} catch (error) {
  state.error = error.message; process.stderr.write(`SDK preparation failed: ${error.message}\n`);
  process.exitCode = interrupted === 'SIGINT' ? 130 : interrupted === 'SIGTERM' ? 143 : 1;
} finally {
  state.interrupted = interrupted;
  if (created) {
    try { await fs.writeFile(join(evidence, 'outcome.json'), JSON.stringify(state, null, 2) + '\n'); }
    catch (error) { process.stderr.write(`SDK outcome retention failed: ${error.message}\n`); process.exitCode ||= 1; }
  }
}
if (state.outcome === 'success' && !process.exitCode && mode !== 'verify') process.stdout.write(`${state.filtered_tree}\n`);
NODE
