"""PREP ONLY. Glue existing reviewed phases; no standalone execution authority.

Root must bind one exact public hosted profile and run this under the frozen
outer supervisor. No board, resource-policy, SDK or provider implementation here.
"""
from pathlib import Path
import argparse
import datetime
import hashlib
import json
import os
import re
import shutil
import stat
import subprocess
import sys
import time

NOW = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()
SCOPE = 'ONE hosted exact0332 full-runtime7fb64b cold-services-and-two-real-checked-SDK profile'
ROLES = ['metadata', 'source-only-clones', 'pre-acquisition', 'exact-public-acquisition',
         'pre-sdk', 'normal-sdk-and-QA-compile', 'owning-static-closure', 'measure']


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for b in iter(lambda: f.read(1048576), b''): h.update(b)
    return h.hexdigest()


def descriptor(path, base=None):
    return {('relative' if base else 'path'): str(path.relative_to(base) if base else path),
            'bytes': path.stat().st_size, 'sha256': sha(path)}


def write(path, data):
    with path.open('x') as f: json.dump(data, f, indent=2); f.write('\n')


def physical(path):
    p = Path(path)
    assert p.is_absolute() and p.resolve(strict=True) == p and p.is_dir()
    return p


class Composition:
    def __init__(self, gate_path):
        self.source_bundle = Path(__file__).resolve().parent
        self.gate = json.loads(Path(gate_path).read_text())
        assert self.gate['admitted'] is True and self.gate['scope'] == SCOPE
        assert self.gate['roles'] == ROLES and self.gate['programSHA256'] == sha(__file__)
        assert 0 <= time.time() - self.gate['admittedAtUnix'] <= 120
        manifest_path = self.source_bundle / 'composition-inputs.json'
        assert sha(manifest_path) == self.gate['manifestSHA256']
        self.manifest = json.loads(manifest_path.read_text())
        self.job = {k: os.environ[k] for k in ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB', 'GITHUB_SHA']}
        assert self.job == self.gate['job']
        self.uid, self.gid = os.geteuid(), os.getegid()
        assert self.uid > 0 and self.gid > 0
        temp = physical(os.environ['RUNNER_TEMP']); assert temp.stat().st_uid == self.uid
        token = self.job['GITHUB_RUN_ID'] + '-' + self.job['GITHUB_RUN_ATTEMPT']
        assert re.fullmatch(r'[0-9]+-[0-9]+', token)
        self.root = temp / ('openhax-model-compat-' + token)
        assert not self.root.exists() and not self.root.is_symlink()
        self.root.mkdir(mode=0o700)
        for leaf in ['metadata', 'bindings']: (self.root / leaf).mkdir(mode=0o700)
        self.evidence = self.root / 'metadata/composition'
        self.evidence.mkdir(mode=0o700)
        self.bundle = self.root / 'metadata/qa-bundle'
        self.bundle.mkdir(mode=0o700)
        for row in self.manifest['bundleFiles']:
            source = self.source_bundle / row['relative']
            assert source.stat().st_size == row['bytes'] and sha(source) == row['sha256']
            dest = self.bundle / row['relative']; dest.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
            shutil.copyfile(source, dest)
            assert sha(dest) == row['sha256']
        self.assets = json.loads((self.bundle / 'portable-assets.json').read_text())
        self.inputs = json.loads((self.bundle / 'source-inputs.json').read_text())
        self.python = str(Path(sys.executable).resolve(strict=True))
        self.clean = {'PATH': os.environ['PATH'], 'JAVA_HOME': os.environ['JAVA_HOME'], 'LANG': 'C.UTF-8',
                      'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_CONFIG_NOSYSTEM': '1', 'GIT_OPTIONAL_LOCKS': '0',
                      'GIT_TERMINAL_PROMPT': '0', 'GIT_ASKPASS': '/bin/false', 'SSH_ASKPASS': '/bin/false'}
        self.context = {k: os.environ[k] for k in ['RUNNER_TEMP', 'GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB', 'GITHUB_SHA',
                                                'GITHUB_WORKSPACE', 'GITHUB_WORKFLOW_REF', 'GITHUB_WORKFLOW_SHA']}
        self.calls = []; self.seq = 0
        self.out = {'schema': 'one-owned-hosted-profile-composition/v2', 'job': self.job, 'startedAtUTC': NOW(),
                    'rootGateSHA256': sha(Path(gate_path)), 'outcome': 'FAIL', 'completedRoles': []}

    def save(self, label, value): write(self.evidence / label, value)

    def observed_children(self):
        # Only this owning process's all-thread children; no host-wide scan.
        found = set()
        for task in Path('/proc/self/task').iterdir():
            found.update(int(x) for x in (task / 'children').read_text().split())
        rows = []
        for pid in sorted(found):
            try:
                fields = Path(f'/proc/{pid}/stat').read_text().rsplit(')', 1)[1].split()
                rows.append({'pid': pid, 'startTicks': int(fields[19]), 'state': fields[0]})
            except FileNotFoundError: pass
        return rows

    def native(self, label, argv, cap_ms, env=None, output_cap=8388608, cleanup_ms=10000, cwd=None):
        self.seq += 1; stem = f'{self.seq:03d}-{label}'
        self.save(stem + '-intent.json', {'atUTC': NOW(), 'argv': argv, 'capMs': cap_ms, 'cwd': str(cwd) if cwd else None})
        supervisor = self.bundle / 'owned-process-supervisor.py'
        assert sha(supervisor) == '348bd2f8451e0c7d232407cd0dcdd328698e896b80838e68e8681a3b7892941b'
        try:
            r = subprocess.run([self.python, str(supervisor), '--timeout-ms', str(cap_ms), '--max-output-bytes', str(output_cap),
                    '--cleanup-ms', str(cleanup_ms), '--', *argv], env=env or self.clean,
                    capture_output=True, timeout=cap_ms / 1000 + cleanup_ms / 1000 + 10, cwd=cwd)
        except subprocess.TimeoutExpired as e:
            (self.evidence / (stem + '-timeout-partial.stdout')).write_bytes(e.stdout or b'')
            (self.evidence / (stem + '-timeout-partial.stderr')).write_bytes(e.stderr or b'')
            self.save(stem + '-timeout.json', {'cleanup': 'UNKNOWN', 'owner': 'whole-composition frozen outer supervisor'})
            raise RuntimeError(label + ': outer timeout; cleanup UNKNOWN') from e
        capture = self.evidence / (stem + '-native.json'); capture.write_bytes(r.stdout)
        (self.evidence / (stem + '-adapter.stderr')).write_bytes(r.stderr)
        value = json.loads(r.stdout)
        observed = {'label': label, 'capture': descriptor(capture, self.root),
                    'adapterExit': r.returncode, 'adapterStderrBytes': len(r.stderr), 'value': value}
        self.calls.append(observed)
        assert r.returncode == 0 and not r.stderr and value['exit-code'] == 0
        assert value['termination'] == 'exited' and value['cleanup-complete?'] and not value['capture-truncated?']
        assert not self.observed_children(), 'A completed phase still has owned direct children'
        return observed

    def git(self, root, args):
        return ['/usr/bin/git', '-C', str(root), '-c', 'core.hooksPath=/dev/null', '-c', 'core.fsmonitor=false',
                '-c', 'gc.auto=0', '-c', 'maintenance.auto=false', '-c', 'credential.helper=',
                '-c', 'http.extraHeader=', '-c', 'http.followRedirects=false', '-c', 'protocol.allow=never',
                '-c', 'protocol.https.allow=always', *args]

    def qa_checkout(self, stage):
        # Root creates its gate only after native checkout observations. Repeat
        # them independently here before metadata, then again after all phases.
        expected = self.gate['qaCheckout']
        root = physical(os.environ['GITHUB_WORKSPACE'])
        assert str(root) == expected['path']
        top = self.native(stage + '-QA-top-level', self.git(root, ['rev-parse', '--show-toplevel']), 30000)['value']['output'].strip()
        assert physical(top) == root
        head = self.native(stage + '-QA-head', self.git(root, ['rev-parse', 'HEAD']), 30000)['value']['output'].strip()
        tree = self.native(stage + '-QA-tree', self.git(root, ['rev-parse', 'HEAD^{tree}']), 30000)['value']['output'].strip()
        status = self.native(stage + '-QA-status', self.git(root, ['status', '--porcelain=v1', '-z', '--untracked-files=all']), 30000)['value']['output']
        assert re.fullmatch(r'[0-9a-f]{40}', head) and re.fullmatch(r'[0-9a-f]{40}', tree)
        assert head == expected['head'] == self.job['GITHUB_SHA'] == expected['workflowSHA'] == os.environ['GITHUB_WORKFLOW_SHA']
        assert tree == expected['tree'] and status == ''
        assert hashlib.sha256(status.encode()).hexdigest() == expected['statusSHA256']
        workflow = expected['workflow']
        assert workflow['relative'] == '.github/workflows/ci.yml'
        path = root / workflow['relative']
        assert path.resolve(strict=True) == path and stat.S_ISREG(path.lstat().st_mode)
        assert path.stat().st_size == workflow['bytes'] and sha(path) == workflow['sha256']
        blob = self.native(stage + '-QA-workflow-blob', self.git(root, ['rev-parse', 'HEAD:' + workflow['relative']]), 30000)['value']['output'].strip()
        physical_blob = self.native(stage + '-QA-workflow-file', self.git(root, ['hash-object', '--no-filters', '--', str(path)]), 30000)['value']['output'].strip()
        assert blob == physical_blob == workflow['gitBlobOid']
        assert expected['bundleRelative'] == '.github/qa/model-compatibility'
        assert self.source_bundle == physical(root / expected['bundleRelative'])
        assert os.environ['GITHUB_WORKFLOW_REF'] == expected['workflowRef']
        assert expected['workflowRef'].startswith('riatzukiza/knoxx/' + workflow['relative'] + '@')
        metadata = self.gate['expectedMetadataContext']
        assert metadata['PILOT_SOURCE_SHA'] == metadata['PILOT_WORKFLOW_SHA'] == head
        assert metadata['PILOT_WORKFLOW_REF'] == expected['workflowRef']
        observed = {'path': str(root), 'head': head, 'tree': tree, 'statusSHA256': hashlib.sha256(status.encode()).hexdigest(),
                    'workflow': {**descriptor(path, root), 'gitBlobOid': blob}, 'bundleRelative': expected['bundleRelative'],
                    'workflowRef': os.environ['GITHUB_WORKFLOW_REF'], 'workflowSHA': os.environ['GITHUB_WORKFLOW_SHA']}
        assert observed == expected
        self.save(stage + '-actual-QA-checkout.json', observed)

    def clones(self):
        for role, leaf, url in [('Knoxx', 'source', 'https://github.com/riatzukiza/knoxx.git'),
                                ('SDK', 'sdk-source', 'https://github.com/open-hax/openplanner.git')]:
            target = self.root / leaf; target.mkdir(mode=0o700)
            rev = self.assets['sourceRoles'][role]['commit']
            self.native(leaf + '-init', self.git(target, ['init', '--template=', '--initial-branch=qa-source']), 30000)
            self.native(leaf + '-fetch', self.git(target, ['fetch', '--depth=1', '--no-tags', '--no-write-fetch-head',
                        '--no-recurse-submodules', url, rev]), 120000)
            self.native(leaf + '-checkout', self.git(target, ['checkout', '--detach', rev]), 30000)
            head = self.native(leaf + '-head', self.git(target, ['rev-parse', 'HEAD']), 30000)['value']['output'].strip()
            assert head == rev
            for row in self.assets['sourceRoles'][role]['files']:
                p = target / row['path']; assert p.stat().st_size == row['bytes'] and sha(p) == row['sha256']

    def actual_tool(self, name):
        path = Path(shutil.which(name, path=self.clean['PATH']) or '').resolve(strict=True)
        assert path.is_file()
        return descriptor(path)

    def binding(self, phase, build=None):
        tools = []
        node = self.actual_tool('node')
        ip = self.actual_tool('ip')  # Bind the resolved installed file, never its PATH alias.
        for path in [node['path'], self.python, '/usr/bin/docker', '/usr/bin/curl', ip['path'], '/usr/bin/git']:
            p = Path(path); mode = p.lstat().st_mode
            assert p.resolve(strict=True) == p and stat.S_ISREG(mode), {'toolPath': str(p), 'mode': oct(mode)}
            tools.append({**descriptor(p), **({'role': 'ip'} if path == ip['path'] else {})})
        roots = {'Knoxx': str(self.root / 'source'), 'SDK': str(self.root / 'sdk-source')}
        state = {}
        for role, path in roots.items():
            root = Path(path)
            head = self.native(phase + '-' + role + '-head', self.git(root, ['rev-parse', 'HEAD']), 30000)['value']['output'].strip()
            status = self.native(phase + '-' + role + '-status', self.git(root, ['status', '--porcelain=v1', '-z', '--untracked-files=all']), 30000)['value']['output']
            assert head == self.assets['sourceRoles'][role]['commit'] and status == ''
            state[role] = {'head': head, 'statusSHA256': hashlib.sha256(status.encode()).hexdigest()}
        info = self.native(phase + '-docker-data-root', ['/usr/bin/docker', '--host', 'unix:///var/run/docker.sock',
                    '--config', str(self.root / 'metadata/empty-docker-config'), 'info', '--format', '{{.DockerRootDir}}'], 30000)
        data = physical(info['value']['output'].strip())
        b = {'job': self.job, 'uid': self.uid, 'gid': self.gid, 'runnerTemp': os.environ['RUNNER_TEMP'],
             'root': str(self.root), 'bundleRoot': str(self.bundle), 'dockerDataRoot': str(data),
             'node': node['path'], 'python': self.python, 'ip': ip['path'], 'assetManifest': descriptor(self.bundle / 'portable-assets.json'),
             'sourceRoots': roots, 'sourceState': state, 'tools': tools}
        if build: b['buildClosure'] = build
        path = self.root / 'bindings' / (phase + '-binding.json'); write(path, b)
        return path, b

    def closure(self, phase):
        children = self.observed_children()
        native_ok = all(x['adapterExit'] == 0 and x['adapterStderrBytes'] == 0
                        and x['value']['termination'] == 'exited' and x['value']['exit-code'] == 0
                        and x['value']['cleanup-complete?'] and not x['value']['capture-truncated?'] for x in self.calls)
        receipt = {'job': self.job, 'closed': bool(native_ok and not children), 'activeConsumers': children,
                   'nativeEvidence': [x['capture'] for x in self.calls], 'observedAtUTC': NOW()}
        assert receipt['closed']
        path = self.bundle / (phase + '-actual-qa-closure.json'); write(path, receipt)
        return descriptor(path, self.bundle)

    def controller(self, phase, build=None):
        path, b = self.binding(phase, build)
        a = {'admitted': self.gate['admitted'] and phase in self.gate['roles'],
             'scope': 'ONE hosted normal-profile exact0332 original-probe plus hot then compact checked SDK',
             'phase': phase, 'programSHA256': sha(self.bundle / 'controller.py'),
             'bindingSHA256': sha(path), 'assetManifestSHA256': b['assetManifest']['sha256'],
             'job': self.job, 'admittedAtUnix': time.time(), 'qaClosure': self.closure(phase),
             'derivedWithinRootGate': self.out['rootGateSHA256']}
        admission = self.root / 'bindings' / (phase + '-admission.json'); write(admission, a)
        cap = 900000 if phase == 'measure' else 180000
        child = self.native(phase, [self.python, str(self.bundle / 'controller.py'), '--phase', phase,
                       '--binding', str(path), '--admission', str(admission)], cap,
                       {**self.clean, **self.context}, cleanup_ms=150000 if phase == 'measure' else 10000)
        receipt = self.root / ('controller-' + phase) / 'FINAL-outcome.json'
        actual = json.loads(receipt.read_text())
        assert actual['job'] == self.job and actual['finalSourceGuard'] == 'PASS'
        assert not actual.get('cleanupFailure') and not actual.get('sourcePostflightFailure')
        assert actual['outcome'].startswith('PASS-')
        return path, b, receipt, child

    def acquisition(self, binding, b, pre_receipt):
        a = {'admitted': self.gate['admitted'] and 'exact-public-acquisition' in self.gate['roles'],
             'scope': 'ONE exact public0332 full-runtime models and carrier acquisition',
             'programSHA256': sha(self.bundle / 'acquire-assets.py'), 'bindingSHA256': sha(binding),
             'assetManifestSHA256': b['assetManifest']['sha256'], 'job': self.job, 'admittedAtUnix': time.time(),
             'preAcquisitionReceipt': descriptor(pre_receipt),
             'acquisitionTools': [{'role': role, **self.actual_tool(role)} for role in ['tar', 'zstd']],
             'derivedWithinRootGate': self.out['rootGateSHA256']}
        assert len(a['acquisitionTools']) == 2 and {x['role'] for x in a['acquisitionTools']} == {'tar', 'zstd'}
        admission = self.root / 'bindings/acquisition-admission.json'; write(admission, a)
        self.native('exact-public-acquisition', [self.python, str(self.bundle / 'acquire-assets.py'),
                    '--binding', str(binding), '--admission', str(admission)], 1800000, {**self.clean, **self.context})
        actual = json.loads((self.root / 'acquisition-evidence/FINAL-acquisition-outcome.json').read_text())
        assert actual['outcome'] == 'PASS-exact-public-assets-acquired-once-only'

    def compile_and_close(self):
        whole = self.native('normal-sdk-and-QA-compile', [self.python, str(self.bundle / 'build-only.py')], 2700000,
                            {**self.clean, **self.context}, output_cap=65536)
        ev = self.root / 'build-evidence'
        state = json.loads((ev / 'FINAL-build-only-outcome.json').read_text())
        assert state['outcome'] == 'PASS-genuine-SDK-and-QA-compilation-only'
        sdk_capture = ev / 'SDK-prepare-native.json'; qa_capture = ev / 'QA-release-native.json'
        sdk = json.loads(sdk_capture.read_text()); qa = json.loads(qa_capture.read_text())
        sdk_warning_counts = re.findall(r'Build completed\. \([^\n]*, (\d+) warnings,', sdk['output'])
        qa_warning_counts = re.findall(r'\[:entry\] Build completed\. \([^\n]*, (\d+) warnings,', qa['output'])
        zero_warnings = bool(sdk_warning_counts and all(x == '0' for x in sdk_warning_counts) and qa_warning_counts == ['0'])
        assert zero_warnings, {'SDK': sdk_warning_counts, 'QA': qa_warning_counts}
        qa_root = self.root / 'sdk/filtered-build/packages/openplanner-sdk/.qa-live-embedding'
        lint_root = self.root / 'source/backend'
        lint_cache = self.root / 'metadata/owning-kondo-cache'
        lint_cache.mkdir(mode=0o700)
        lint = self.native('owning-entry-lint', ['clj-kondo', '--config-dir', str(lint_root / '.clj-kondo'),
                    '--cache-dir', str(lint_cache), '--lint', str(qa_root / 'src/knoxx/backend/extern/live_checked_embedding_qa.cljs'),
                    '--fail-level', 'warning'], 120000, cwd=lint_root)
        syntax = self.native('compiled-entry-node-syntax', [self.actual_tool('node')['path'], '--check', str(qa_root / 'dist/entry.js')], 30000)
        raw = json.loads((ev / 'actual-filtered-build-source-dependency-output-inventory.json').read_text())
        rows = []
        for row in raw:
            if 'symlink' in row:
                assert isinstance(row['targetExists'], bool)
                rows.append({'type': 'symlink', 'relative': row['path'], 'link': row['symlink'],
                             'resolvedRelativePath': row['resolvedRelativePath'], 'targetExists': row['targetExists']})
            else:
                rows.append({'type': 'file', 'relative': row['path'], 'bytes': row['bytes'], 'sha256': row['sha256']})
        output = self.root / 'metadata/actual-complete-built-output-manifest.json'; write(output, {'rows': rows})
        native_children = []
        for role, capture in [('normalSDKFrozenBuild', sdk_capture), ('privateQACompile', qa_capture),
                              ('owningLint', self.root / lint['capture']['relative']), ('nodeSyntax', self.root / syntax['capture']['relative'])]:
            value = json.loads(capture.read_text())
            native_children.append({'role': role, 'capture': descriptor(capture, self.root),
                    **{key: value[key] for key in ['termination', 'exit-code', 'cleanup-complete?', 'capture-truncated?']}})
        actual_sdk = state['actualSdkOutcome']
        children = self.observed_children()
        closed = all(x['termination'] == 'exited' and x['exit-code'] == 0 and x['cleanup-complete?']
                     and not x['capture-truncated?'] for x in native_children) and not children
        receipt = {'job': self.job, 'closed': bool(closed), 'activeConsumers': children,
                   'SDKSourceCommit': actual_sdk['source_revision'], 'KnoxxSourceCommit': self.inputs['sourceRevision'],
                   'sdkSelectedInventorySHA256': actual_sdk['selected_inventory_sha256'],
                   'qaSourceSHA256': sha(qa_root / 'src/knoxx/backend/extern/live_checked_embedding_qa.cljs'),
                   'zeroCompilerWarnings': zero_warnings, 'warningCountsFromActualNative': {'SDK': sdk_warning_counts, 'QA': qa_warning_counts},
                   'roles': [x['role'] for x in native_children], 'nativeChildren': native_children,
                   'wholeBuildNative': whole['capture'], 'outputManifest': descriptor(output, self.root)}
        assert receipt['closed']
        path = self.root / 'metadata/actual-sdk-QA-closed-build-receipt.json'; write(path, receipt)
        return descriptor(path, self.root)

    def execute(self):
        self.qa_checkout('preflight')
        # The whole job must call this metadata adapter exactly once, not reuse a
        # previous pilot or duplicate its same-job immutable measurement path.
        metadata_context = {k: os.environ[k] for k in self.manifest['metadataEnvironmentNames']}
        assert metadata_context == self.gate['expectedMetadataContext']
        env = {**self.clean, **self.context, **metadata_context}
        env['PILOT_ADAPTER_SHA256'] = sha(self.bundle / 'metadata.py')
        self.native('metadata', [self.python, str(self.bundle / 'metadata.py')], 120000, env)
        metadata = Path(os.environ['RUNNER_TEMP']) / ('hosted-worker-metadata-' + self.job['GITHUB_RUN_ID'] + '-' + self.job['GITHUB_RUN_ATTEMPT']) / 'measurement.json'
        observed = json.loads(metadata.read_text()); assert observed['classification'] == 'METADATA_THRESHOLDS_MET'
        shutil.copyfile(metadata, self.root / 'metadata/actual-same-job-worker-measurement.json')
        self.selected_tools = [self.actual_tool(x) for x in ['node', 'python3', 'java', 'pnpm', 'clojure', 'clj-kondo', 'docker', 'curl', 'git', 'ip', 'tar', 'zstd']]
        write(self.root / 'metadata/actual-selected-tool-content-descriptors.json', self.selected_tools)
        self.out['completedRoles'].append('metadata')
        self.clones(); self.out['completedRoles'].append('source-only-clones')
        binding, b, pre, _ = self.controller('pre-acquisition'); self.out['completedRoles'].append('pre-acquisition')
        self.acquisition(binding, b, pre); self.out['completedRoles'].append('exact-public-acquisition')
        self.controller('pre-sdk'); self.out['completedRoles'].append('pre-sdk')
        build = self.compile_and_close()
        self.out['completedRoles'] += ['normal-sdk-and-QA-compile', 'owning-static-closure']
        self.controller('measure', build); self.out['completedRoles'].append('measure')
        self.out['outcome'] = 'PASS-one-exact-profile-all-native-phases-and-owned-stop'

    def finish(self):
        # Proof artifacts only; raw model/runtime payload and dependency trees
        # stay on the worker and are never recursively added to the upload set.
        self.save('actual-main-outcome-before-final-guards.json', self.out)
        try:
            self.qa_checkout('postflight')
            assert sha(__file__) == self.gate['programSHA256']
            assert sha(self.source_bundle / 'composition-inputs.json') == self.gate['manifestSHA256']
            for row in self.manifest['bundleFiles']:
                p = self.bundle / row['relative']
                assert p.stat().st_size == row['bytes'] and sha(p) == row['sha256'], 'Final immutable source-bundle guard'
                original = self.source_bundle / row['relative']
                assert original.stat().st_size == row['bytes'] and sha(original) == row['sha256']
            for row in getattr(self, 'selected_tools', []):
                p = Path(row['path'])
                assert p.stat().st_size == row['bytes'] and sha(p) == row['sha256'], 'Final selected tool guard'
            self.out['finalSourceAndToolGuard'] = 'PASS'
        except BaseException as e:
            self.out['finalSourceAndToolFailure'] = {'type': type(e).__name__, 'message': str(e)[:1000]}
            self.out['outcome'] = 'FAIL-final-source-or-tool-guard'
        roots = [self.root / 'metadata', self.root / 'bindings', self.root / 'acquisition-evidence', self.root / 'build-evidence',
                 self.root / 'sdk/evidence', self.root / 'controller-pre-acquisition', self.root / 'controller-pre-sdk',
                 self.root / 'controller-measure', self.root / 'original-wire', self.root / 'sdk-hot-wire', self.root / 'sdk-compact-wire']
        rows = []; total = 0
        for root in roots:
            if root.exists():
                for path in root.rglob('*'):
                    if path.is_file() and not path.is_symlink():
                        rows.append(descriptor(path, self.root)); total += path.stat().st_size
        self.out['proofBytesBeforeFinalReceipt'] = total
        self.out['completedAtUTC'] = NOW()
        self.out['observedOwnChildrenAtEnd'] = self.observed_children()
        self.save('proof-artifact-files.json', rows)
        self.save('FINAL-composition-outcome.json', self.out)
        # Reserve1MiB of80MiB for this manifest, receipt and whole-owner result.
        assert total + (self.evidence / 'proof-artifact-files.json').stat().st_size + (self.evidence / 'FINAL-composition-outcome.json').stat().st_size <= 79 * 1024**2
        assert not self.out['observedOwnChildrenAtEnd']


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser(); p.add_argument('--root-admission', required=True)
    args = p.parse_args()
    c = Composition(args.root_admission)
    try:
        c.execute()
    except BaseException as e:
        c.out['primary'] = {'type': type(e).__name__, 'message': str(e)[:2000]}
    finally:
        c.finish()
    print(json.dumps({'outcome': c.out['outcome'], 'completedRoles': c.out['completedRoles'],
                      'primary': c.out.get('primary'), 'evidence': str(c.evidence)}))
    return 0 if c.out['outcome'].startswith('PASS-') else 1


if __name__ == '__main__':
    sys.exit(main())
