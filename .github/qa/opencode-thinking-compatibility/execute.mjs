// QA-only executor. No main is evaluated by node --check.
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import { createHash } from 'node:crypto';
import { spawnSync } from 'node:child_process';
import { isDeepStrictEqual } from 'node:util';
import { pathToFileURL } from 'node:url';

const [runRoot, gateFile] = process.argv.slice(2);
const sha = b => createHash('sha256').update(b).digest('hex');
const check = (v, why) => { if (!v) throw new Error(why); };
const physical = p => { const s = fs.lstatSync(p); check(!s.isSymbolicLink() && fs.realpathSync(p) === p, `Nonphysical path: ${p}`); return s; };
const write = (p, value) => fs.writeFileSync(p, JSON.stringify(value, null, 2) + '\n', { flag: 'wx', mode: 0o600 });
let gate, evidence, summary = { status: 'failed', primary: null, secondary: [], cases: [] };
let sequence = 0, discovery, catalogSHA, binarySHA, sourceRows;
const workDirectories = [];
function cleanEnvironment(extra = {}) {
  return { PATH: gate.pathEnvironment, LANG: 'C.UTF-8', LC_ALL: 'C.UTF-8', TZ: 'UTC', TMPDIR: `${runRoot}/tmp`, ...extra };
}
function native(label, argv, env, cwd, timeout, output = 1048576) {
  const prefix = `${evidence}/${String(++sequence).padStart(2, '0')}-${label}`;
  const command = [gate.python, gate.bundle + '/owned-process.py', '--timeout-ms', String(timeout),
    '--max-output-bytes', String(output), '--cleanup-ms', '10000', '--', ...argv];
  write(prefix + '.intent.json', { command, cwd, timeoutMs: timeout, observedStartUTC: new Date().toISOString() });
  const r = spawnSync(command[0], command.slice(1), { env, cwd, encoding: 'utf8', maxBuffer: output * 8 + 1048576, timeout: timeout + 25000 });
  fs.writeFileSync(prefix + '.native.json', r.stdout ?? '', { flag: 'wx', mode: 0o600 });
  fs.writeFileSync(prefix + '.outer.stderr', r.stderr ?? '', { flag: 'wx', mode: 0o600 });
  check(!r.error && r.status === 0 && !r.signal && r.stderr === '', `Native outer failure/closure UNKNOWN: ${label}`);
  const c = JSON.parse(r.stdout);
  check(c['exit-code'] === 0 && c.termination === 'exited' && c['cleanup-complete?'] === true && c['capture-truncated?'] === false && c['timed-out?'] === false,
    `Native child failed/refused/incomplete: ${label}`);
  return c;
}
function git(args) {
  const r = spawnSync(gate.git, ['-C', gate.checkout, ...args], { env: cleanEnvironment({ GIT_OPTIONAL_LOCKS: '0' }), encoding: 'utf8', timeout: 10000, maxBuffer: 1048576 });
  check(!r.error && r.status === 0 && !r.signal && r.stderr === '', `Source metadata failure: git ${args[0]}`);
  return r.stdout.trim();
}
function guard() {
  check(git(['rev-parse', 'HEAD']) === gate.qaHead && git(['rev-parse', 'HEAD^{tree}']) === gate.qaTree, 'QA HEAD/tree changed');
  check(git(['status', '--porcelain=v1', '-z', '--untracked-files=all']) === '', 'QA checkout dirty');
  for (const row of sourceRows) {
    check(physical(row.path).isFile() && sha(fs.readFileSync(row.path)) === row.sha256, `Source/tool changed: ${row.path}`);
  }
  for (const cwd of workDirectories) {
    check(physical(cwd).isDirectory() && fs.readdirSync(cwd).length === 0, `Empty read-only case work directory changed: ${cwd}`);
  }
  if (discovery) {
    check(sha(fs.readFileSync(discovery.file)) === discovery.sha256, 'Discovery proof changed');
    check(sha(fs.readFileSync(discovery.proof.catalog_source.path)) === catalogSHA, 'Catalog changed');
    check(sha(fs.readFileSync(discovery.proof.binary.real_path)) === binarySHA, 'Selected executable changed');
  }
}
try {
  check(process.platform === 'linux' && process.arch === 'x64' && process.getuid() !== 0, 'Fresh nonroot LinuxAMD64 worker required');
  check(path.isAbsolute(runRoot) && physical(runRoot).isDirectory() && physical(runRoot).uid === process.getuid(), 'Owned physical run root required');
  check(physical(gateFile).isFile(), 'Physical gate required');
  gate = JSON.parse(fs.readFileSync(gateFile));
  check(gate.schema === 'root-reviewed-native-thinking-probe/v1' && gate.repository === 'riatzukiza/knoxx' &&
    gate.ref === 'refs/heads/qa/opencode-thinking-compatibility-20261017' && gate.event === 'push' && gate.runRoot === runRoot, 'Exact one-branch QA scope refused');
  sourceRows = gate.sourceRows;
  evidence = `${runRoot}/evidence`; fs.mkdirSync(evidence, { mode: 0o700 });
  fs.mkdirSync(`${runRoot}/tmp`, { mode: 0o700 });
  guard();
  for (const p of [path.join(os.homedir(), '.opencode'), path.join(os.homedir(), '.agents'), path.join(os.homedir(), 'AGENTS.md')])
    check(!fs.existsSync(p), `Unexpected ordinary worker home overlay: ${p}`);
  const resources = { platform: process.platform, arch: process.arch, uid: process.getuid(), gid: process.getgid(), homeObserved: os.homedir(),
    freeMemory: os.freemem(), diskAvailable: Number(fs.statfsSync(runRoot).bavail) * Number(fs.statfsSync(runRoot).bsize),
    memoryPSI: fs.readFileSync('/proc/pressure/memory', 'utf8'), observedUTC: new Date().toISOString() };
  write(`${evidence}/worker-metadata.json`, resources);
  check(resources.freeMemory >= 536870912 && resources.diskAvailable >= 1073741824, 'Fresh client tool resource admission refused');
  const { discoverFreeMiMoFlash, parseOpenCodeModelCatalog, selectFreeMiMoFlash } = await import(pathToFileURL(gate.bundle + '/discovery.mjs'));
  const { prepareCase } = await import(pathToFileURL(gate.bundle + '/probe-case.mjs'));
  const discoveryDirectory = `${evidence}/discovery`; fs.mkdirSync(discoveryDirectory, { mode: 0o700 });
  discovery = await discoverFreeMiMoFlash({ evidenceDirectory: discoveryDirectory, requested: '', expectedVersion: '1.18.31',
    binary: gate.binary, environment: cleanEnvironment(), inferenceConfiguration: null });
  binarySHA = discovery.proof.binary.sha256; catalogSHA = discovery.proof.catalog_source.sha256;
  check(binarySHA === 'f9dab32248695e9ebd56b16a1921798fd85112cf5a69c7dfd0cabc1e17be4a11', 'Pinned hosted1.18.31 executable hash differs');
  const baseline = discovery.proof.selected_row;
  const raw = JSON.parse(fs.readFileSync(discovery.proof.catalog_source.path));
  // Native parameter support is a separate capability precondition AFTER the unchanged dynamic latest selector.
  // No fallback to an older model is allowed if a newly selected native model is outside the inspected primary docs.
  const documented = ['xiaomi/mimo-v2.6-flash', 'xiaomi/mimo-v2.6-pro', 'xiaomi/mimo-v2.6-pro-ultraspeed', 'xiaomi/mimo-v2.5-pro', 'xiaomi/mimo-v2.5'];
  check(documented.includes(raw.opencode.models[baseline.id].canonical_model_id), 'Latest selected native model lacks inspected primary toggle support');
  check(!Object.hasOwn(baseline.options, 'thinking') && !Object.hasOwn(baseline.options, 'reasoningEffort'), 'Unexpected existing model reasoning allocation');
  fs.mkdirSync(`${runRoot}/cases`, { mode: 0o700 });
  for (const mode of ['disabled', 'enabled']) {
    guard();
    const c = prepareCase({ discovery, mode, binary: discovery.proof.binary.real_path, runRoot,
      catalogPath: discovery.proof.catalog_source.path, pathEnvironment: gate.pathEnvironment });
    fs.mkdirSync(c.root, { mode: 0o700 });
    for (const leaf of ['work', 'xdg-config', 'xdg-data', 'xdg-cache', 'xdg-state', 'tmp']) fs.mkdirSync(`${c.root}/${leaf}`, { mode: 0o700 });
    workDirectories.push(c.cwd);
    check(fs.readdirSync(c.cwd).length === 0, `Case work directory is not empty: ${mode}`);
    write(`${c.root}/config.json`, c.config); const configSHA = sha(fs.readFileSync(`${c.root}/config.json`));
    write(`${evidence}/${mode}-case-source-projection.json`, { ...c, environment: Object.keys(c.environment), configSHA, requestProjectionIsWireCapture: false });
    const registration = JSON.parse(native(`${mode}-native-agent`, c.registrationArgv, c.environment, c.cwd, 30000).output);
    const visibleTools = Object.entries(registration.tools).filter(([, value]) => value === true).map(([id]) => id).sort();
    check(registration.name === 'build' && registration.native === true && registration.steps === 1 &&
      isDeepStrictEqual(visibleTools, ['bash', 'glob', 'grep', 'read']), `Native read-only tool registration differs: ${mode}`);
    guard();
    const model = selectFreeMiMoFlash(parseOpenCodeModelCatalog(Buffer.from(native(`${mode}-configured-models`, c.modelsArgv, c.environment, c.cwd, 30000).output)));
    const expected = { ...baseline, options: { ...baseline.options, thinking: { type: mode } } };
    check(isDeepStrictEqual(model, expected), `Effective model source/options differ: ${mode}`);
    const capture = native(`${mode}-OpenCode`, c.inferenceArgv, c.environment, c.cwd, 90000);
    const events = capture.output.split(/\r?\n/).filter(x => x.trim()).map(x => JSON.parse(x));
    check(events.every(x => ['step_start', 'step_finish', 'text'].includes(x.type)), `Unexpected error/tool/retry/event: ${mode}`);
    const starts = events.filter(x => x.type === 'step_start'), ends = events.filter(x => x.type === 'step_finish');
    const sessions = new Set(events.map(x => x.sessionID));
    const text = events.filter(x => x.type === 'text').map(x => x.part.text).join('').trim();
    check(sessions.size === 1 && starts.length === 1 && ends.length === 1 && ends[0].part.reason === 'stop' && text === '542', `Visible native compatibility failed: ${mode}`);
    const usage = ends[0].part.tokens, cost = ends[0].part.cost;
    check(cost === 0, `Unexpected reported cost: ${mode}`);
    const valid = v => typeof v === 'number' && Number.isFinite(v) && v >= 0;
    const usageKnown = valid(usage?.output) && valid(usage?.reasoning);
    if (usageKnown) check(usage.output + usage.reasoning <= 512, `Reported generated tokens exceed requested cap: ${mode}`);
    check(sha(fs.readFileSync(`${c.root}/config.json`)) === configSHA, `Temporary config changed: ${mode}`);
    summary.cases.push({ mode, oneVisibleStep: true, finish: ends[0].part.reason, visibleText: text, tokens: usage ?? null, cost,
      usageKnown, nativeAgent: registration.native, visibleToolIDs: visibleTools, nativeExit: capture['exit-code'], termination: capture.termination, cleanupComplete: capture['cleanup-complete?'], captureTruncated: capture['capture-truncated?'], timedOut: capture['timed-out?'],
      wireRequestCaptured: false, transportRetryCount: 'not independently observed; stock client behavior is unchanged and bounded by90s' });
    guard();
  }
  const [off, on] = summary.cases;
  summary.status = off.usageKnown && on.usageKnown && off.tokens.reasoning === 0 && on.tokens.reasoning > 0
    ? 'native-compatibility-and-usage-contrast-observed' : 'native-compatibility-observed-toggle-efficacy-UNKNOWN-or-not-demonstrated';
} catch (e) { summary.primary = { name: e.name, message: e.message, stack: String(e.stack).slice(0, 4096) }; }
finally {
  if (gate && sourceRows) try { guard(); } catch (e) { summary.secondary.push({ phase: 'postflight', message: e.message }); }
  if (summary.secondary.length) summary.status = 'failed';
  summary.observedFinishedUTC = new Date().toISOString();
  if (evidence) write(`${evidence}/result.json`, summary);
  console.log(JSON.stringify(summary));
  process.exitCode = summary.status === 'native-compatibility-and-usage-contrast-observed' && !summary.primary && summary.secondary.length === 0 ? 0 : 1;
}
