import { createHash } from 'node:crypto';
import { execFileSync } from 'node:child_process';
import { copyFileSync, lstatSync, mkdirSync, readFileSync, writeFileSync } from 'node:fs';
import path from 'node:path';
import { fileURLToPath } from 'node:url';
import { restoreArchive } from './sandbox-clj-kondo.mjs';

// An auxiliary test evaluator, independent of the primary CI/source-lint pin.
// Archive digest is from the official v2026.08.04 checksum; binary digest is
// derived from that verified archive. Neither PATH nor bundle metadata is a pin.
export const nativeErrorPin = Object.freeze({
  version: '2026.08.04', platform: 'linux', arch: 'x64',
  archive: 'clj-kondo-2026.08.04-linux-amd64.zip',
  sha256: 'dd3bb3ec91bcb838e9bbefc9ba9968b69a8fe2a625fa67e8b0485605604e61d1',
  binarySha256: '6c2b00ea3a4bc707e570be995c51c718fb19cd098d4485ab86d9abfcc209623e',
});
const script = fileURLToPath(import.meta.url);
const digest = bytes => createHash('sha256').update(bytes).digest('hex');
const profile = bundle => path.resolve(bundle, 'toolchain', 'native-error-static');

/** Require the separately pinned evaluator's supported host before using a tool. */
function requireHost(host) {
  if (host.platform !== nativeErrorPin.platform || host.arch !== nativeErrorPin.arch) {
    throw new Error(`Native error evaluator requires linux/x64; got ${host.platform}/${host.arch}`);
  }
}

/**
 * Validate the mandatory caller-selected executable, its bytes and actual version.
 * Returns a tool descriptor; never searches PATH or installs a fallback.
 */
export function requireNativeErrorEvaluator(binary, host = process) {
  requireHost(host);
  if (typeof binary !== 'string' || !path.isAbsolute(binary)) {
    throw new Error('KNOXX_NATIVE_ERROR_KONDO must select an absolute verified auxiliary executable; use native-error-static-evaluator.mjs restore BUNDLE DESTINATION');
  }
  if (!lstatSync(binary).isFile()) throw new Error('Native error evaluator must be a regular executable file');
  const sha256 = digest(readFileSync(binary));
  if (sha256 !== nativeErrorPin.binarySha256) throw new Error('Native error evaluator binary checksum mismatch');
  const version = execFileSync(binary, ['--version'], { encoding: 'utf8', timeout: 10_000 }).trim();
  if (version !== `clj-kondo v${nativeErrorPin.version}`) {
    throw new Error(`Unexpected native error evaluator version: ${version}`);
  }
  return { binary, sha256, version };
}

/** Download only the auxiliary official release archive, verifying before storage. */
export async function collectNativeErrorEvaluator(bundle) {
  requireHost(process);
  const url = `https://github.com/clj-kondo/clj-kondo/releases/download/v${nativeErrorPin.version}/${nativeErrorPin.archive}`;
  const response = await fetch(url, { signal: AbortSignal.timeout(120_000) });
  if (!response.ok) throw new Error(`Native error evaluator download returned ${response.status}`);
  const bytes = Buffer.from(await response.arrayBuffer());
  if (digest(bytes) !== nativeErrorPin.sha256) throw new Error('Native error evaluator archive checksum mismatch');
  const directory = profile(bundle);
  mkdirSync(directory, { recursive: true });
  writeFileSync(path.join(directory, nativeErrorPin.archive), bytes);
  writeFileSync(path.join(directory, 'clj-kondo.json'), `${JSON.stringify(nativeErrorPin, null, 2)}\n`);
  copyFileSync(script, path.resolve(bundle, 'toolchain', path.basename(script)));
  copyFileSync(fileURLToPath(new URL('./sandbox-clj-kondo.mjs', import.meta.url)),
    path.resolve(bundle, 'toolchain', 'sandbox-clj-kondo.mjs'));
}

/** Restore a verified archive using the existing owner; validate the actual binary. */
export function restoreNativeErrorEvaluator(bundle, destination) {
  const bin = restoreArchive(path.join(profile(bundle), nativeErrorPin.archive), destination, nativeErrorPin);
  return requireNativeErrorEvaluator(path.join(bin, 'clj-kondo')).binary;
}

/** Dispatch the fixed auxiliary profile; no arbitrary version/hash override is accepted. */
async function main([action, input, destination, ...extra]) {
  if (extra.length) throw new Error('Unexpected native error evaluator arguments');
  if (action === 'describe' && !input && !destination) {
    console.log(JSON.stringify({ ...nativeErrorPin,
      archive: `toolchain/native-error-static/${nativeErrorPin.archive}`,
      restore_script: 'toolchain/native-error-static-evaluator.mjs',
      input: 'KNOXX_NATIVE_ERROR_KONDO' }));
  } else if (action === 'collect' && input && !destination) {
    await collectNativeErrorEvaluator(input);
  } else if (action === 'restore' && input && destination) {
    console.log(restoreNativeErrorEvaluator(input, destination));
  } else if (action === 'verify' && input && !destination) {
    console.log(JSON.stringify(requireNativeErrorEvaluator(input)));
  } else {
    throw new Error('Usage: native-error-static-evaluator.mjs describe | collect BUNDLE | restore BUNDLE DESTINATION | verify ABSOLUTE_EXECUTABLE');
  }
}

if (process.argv[1] && path.resolve(process.argv[1]) === script) {
  main(process.argv.slice(2)).catch(error => { console.error(error.message); process.exitCode = 1; });
}
