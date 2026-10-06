// SPDX-License-Identifier: GPL-3.0-or-later
// Replay the actual verifier's directory expression and container-side script.
// Native synthesis and filesystem operations are real; no server is contacted.
import assert from 'node:assert/strict';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import vm from 'node:vm';
import { spawnSync } from 'node:child_process';
import { fileURLToPath } from 'node:url';

const root = path.resolve(path.dirname(fileURLToPath(import.meta.url)), '../../..');
const source = fs.readFileSync(path.join(root, 'scripts/verify-native-music.mjs'), 'utf8');
const live = source.slice(source.indexOf('async function liveProof('));
const expression = live.match(/const directory = ([^\n]+);/)[1];
const scriptMatch = source.match(/const fixtureDirectoryScript = (`[\s\S]*?`);/);
const helperStart = source.indexOf('function fixtureDirectory(');
const helperEnd = source.indexOf('\n}\n', helperStart) + 2;
const temp = fs.mkdtempSync(path.join(os.tmpdir(), 'knoxx music-root '));
try {
  const workspace = path.join(temp, 'workspace');
  const library = path.join(temp, 'music library');
  fs.mkdirSync(workspace); fs.mkdirSync(library);
  const spec = path.join(temp, 'spec.json');
  fs.writeFileSync(spec, JSON.stringify({ bpm: 120, duration: 0.5, tracks: [{
    instrument: 'synth', waveform: 'sine', notes: [{ note: 'A4', time: 0, duration: 0.25 }],
  }] }));
  for (const [name, override, expectedRoot] of [
    ['absolute override', `  ${library}  `, library],
    ['relative override', 'music library', library],
    ['no override', undefined, path.join(workspace, 'Music')],
    ['blank override', '   ', path.join(workspace, 'Music')],
  ]) {
    const relativeDirectory = `Music/generated/.verify-native-music-${name.replaceAll(' ', '-')}`;
    const expected = path.join(expectedRoot, relativeDirectory.slice('Music/'.length));
    const actualRoot = override?.trim() ? library : workspace;
    const env = { ...process.env };
    if (override === undefined) delete env.KNOXX_MUSIC_LIBRARY_ROOT;
    else env.KNOXX_MUSIC_LIBRARY_ROOT = override;
    const fixtureDirectoryScript = scriptMatch ? vm.runInNewContext(scriptMatch[1]) : undefined;
    const command = (program, args) => {
      assert.equal(program, 'docker');
      assert.deepEqual(Array.from(args.slice(0, 5)), ['exec', 'fixture-container', 'node', '-e', fixtureDirectoryScript]);
      const result = spawnSync(process.execPath, ['-e', args[4], ...args.slice(5)], {
        cwd: temp, env, encoding: 'utf8', timeout: 10000,
      });
      assert.equal(result.status, 0, result.stderr);
      return result.stdout;
    };
    const fixtureDirectory = helperStart < 0 ? undefined : vm.runInNewContext(
      `(${source.slice(helperStart, helperEnd)})`, { command, fixtureDirectoryScript, assert, path, pass() {} },
    );
    const directory = vm.runInNewContext(expression, {
      path, workspace, relativeDirectory, fixtureDirectory, container: 'fixture-container',
      mounts: [{ Type: 'bind', RW: true, Destination: actualRoot }],
    });
    assert.equal(directory, expected, `${name}: verifier read/cleanup must follow the backend Music alias`);
    if (name === 'absolute override') {
      for (const mounts of [[], [{ Type: 'bind', RW: false, Destination: actualRoot }]]) {
        assert.throws(() => vm.runInNewContext(expression, {
          path, workspace, relativeDirectory, fixtureDirectory, container: 'fixture-container', mounts,
        }), /effective music root must be inside writable durable storage/);
      }
      console.log('PASS missing/read-only mount: rejected before generation or cleanup');
    }
    const output = path.join(expected, 'proof.wav');
    const generated = spawnSync(process.execPath, [path.join(root, 'backend/scripts/synthesize-music.mjs'), spec, output], {
      encoding: 'utf8', timeout: 20000,
    });
    assert.equal(generated.status, 0, generated.stderr);
    const wav = fs.readFileSync(path.join(directory, 'proof.wav'));
    assert.equal(wav.toString('ascii', 0, 4), 'RIFF');
    assert.equal(wav.toString('ascii', 8, 12), 'WAVE');
    assert.ok(wav.subarray(44).some(byte => byte !== 0));
    fs.rmSync(directory, { recursive: true, force: true });
    assert.equal(fs.existsSync(expected), false, 'the same dedicated directory is cleaned');
    assert.ok(fs.existsSync(workspace) && fs.existsSync(library), 'roots survive fixture cleanup');
    console.log(`PASS ${name}: actual path, native WAV read and dedicated cleanup`);
  }
} finally {
  fs.rmSync(temp, { recursive: true, force: true });
}
