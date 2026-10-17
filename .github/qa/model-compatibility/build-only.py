# SPDX-License-Identifier: GPL-3.0-or-later
"""Compile the genuine SDK and the isolated QA entry; never contact a provider."""
from pathlib import Path
import datetime
import hashlib
import json
import os
import re
import shutil
import subprocess
import sys

os.umask(0o077)
BUNDLE = Path(__file__).resolve().parent
INPUTS = json.loads((BUNDLE / 'source-inputs.json').read_text())
TEMP = Path(os.environ['RUNNER_TEMP'])
assert TEMP.is_absolute() and TEMP.resolve(strict=True) == TEMP
assert TEMP.stat().st_uid == os.geteuid() != 0
RUN_ID = os.environ['GITHUB_RUN_ID']
ATTEMPT = os.environ['GITHUB_RUN_ATTEMPT']
assert re.fullmatch(r'[0-9]+', RUN_ID) and re.fullmatch(r'[0-9]+', ATTEMPT)
RUN = TEMP / ('openhax-sdk-compile-' + RUN_ID + '-' + ATTEMPT)
RUN.mkdir(mode=0o700)
EVIDENCE = RUN / 'evidence'
EVIDENCE.mkdir(mode=0o700)
STATE = {'schema': 'openhax-real-sdk-checked-entry-compile/v1',
         'startedAtUTC': datetime.datetime.now(datetime.timezone.utc).isoformat(),
         'runId': RUN_ID, 'runAttempt': ATTEMPT, 'outcome': 'FAIL',
         'limits': 'Build-only. No models, Docker effects, inference, Mongo or production credit.'}
PATH = os.environ['PATH']
JAVA = os.environ['JAVA_HOME']
CLEAN = {'PATH': PATH, 'JAVA_HOME': JAVA, 'LANG': 'C.UTF-8',
         'GIT_CONFIG_GLOBAL': '/dev/null', 'GIT_CONFIG_NOSYSTEM': '1',
         'GIT_TERMINAL_PROMPT': '0', 'GIT_ASKPASS': '/bin/false',
         'SSH_ASKPASS': '/bin/false'}


def digest(data):
    return hashlib.sha256(data).hexdigest()


def file_digest(path):
    value = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1048576), b''):
            value.update(block)
    return value.hexdigest()


def evidence_budget():
    roots = [EVIDENCE, RUN / 'sdk/evidence']
    size = sum(p.stat().st_size for root in roots for p in root.rglob('*') if p.is_file())
    metadata = TEMP / ('hosted-worker-metadata-' + RUN_ID + '-' + ATTEMPT) / 'measurement.json'
    if metadata.is_file():
        size += metadata.stat().st_size
    # Leave one MiB for the outer owner's finite receipt/diagnostics.
    assert size <= 79 * 1024**2


def write_json(path, value):
    with path.open('x') as stream:
        json.dump(value, stream, indent=2)
        stream.write('\n')


def native(label, argv, cwd, timeout_ms, env=None):
    supervisor = BUNDLE / 'owned-process-supervisor.py'
    assert digest(supervisor.read_bytes()) == INPUTS['supervisorSha256']
    write_json(EVIDENCE / (label + '-intent.json'),
               {'argv': argv, 'cwd': str(cwd), 'timeoutMs': timeout_ms})
    try:
        result = subprocess.run([sys.executable, str(supervisor), '--timeout-ms',
                                 str(timeout_ms), '--max-output-bytes', '2097152',
                                 '--cleanup-ms', '10000', '--', *argv],
                                cwd=cwd, env=env or CLEAN, capture_output=True,
                                timeout=timeout_ms / 1000 + 15)
    except subprocess.TimeoutExpired as error:
        (EVIDENCE / (label + '-outer-timeout-partial.stdout')).write_bytes(error.stdout or b'')
        (EVIDENCE / (label + '-outer-timeout-partial.stderr')).write_bytes(error.stderr or b'')
        write_json(EVIDENCE / (label + '-outer-timeout.json'),
                   {'outerTimeout': True, 'innerCleanup': 'UNKNOWN',
                    'requiredOwner': 'Whole build runner frozen supervisor'})
        raise RuntimeError(label + ': outer timeout; inner cleanup UNKNOWN') from error
    (EVIDENCE / (label + '-native.json')).write_bytes(result.stdout)
    (EVIDENCE / (label + '-adapter-stderr.txt')).write_bytes(result.stderr)
    evidence_budget()
    value = json.loads(result.stdout)
    assert result.returncode == 0 and not result.stderr and value['exit-code'] == 0, label
    assert value['termination'] == 'exited' and value['cleanup-complete?'], label
    assert not value['capture-truncated?'], label
    return value['output']


def git(directory, arguments):
    return ['git', '-C', str(directory), '-c', 'core.hooksPath=/dev/null',
            '-c', 'core.fsmonitor=false', '-c', 'gc.auto=0',
            '-c', 'maintenance.auto=false', '-c', 'credential.helper=',
            '-c', 'http.extraHeader=', '-c', 'http.followRedirects=false',
            '-c', 'protocol.allow=never', '-c', 'protocol.https.allow=always',
            *arguments]


def acquire_source(label, revision, repository, rows):
    target = RUN / label
    target.mkdir(mode=0o700)
    native(label + '-init', git(target, ['init', '--template=', '--initial-branch=qa-source']), RUN, 30000)
    native(label + '-fetch', git(target, ['fetch', '--depth=1', '--no-tags',
           '--no-write-fetch-head', '--no-recurse-submodules', repository, revision]), RUN, 120000)
    native(label + '-checkout', git(target, ['checkout', '--detach', revision]), RUN, 30000)
    assert native(label + '-head', git(target, ['rev-parse', 'HEAD']), RUN, 30000).strip() == revision
    expected_tree = INPUTS['sourceTree' if label == 'source' else 'sdkTree']
    assert native(label + '-tree', git(target, ['rev-parse', 'HEAD^{tree}']), RUN, 30000).strip() == expected_tree
    for row in rows:
        data = (target / row['path']).read_bytes()
        assert len(data) == row['bytes'] and digest(data) == row['sha256'], row['path']
    return target


def preserve_files(root, target):
    rows = []
    byte_count = 0
    def walk_error(error):
        raise error
    for current, directories, files in os.walk(root, followlinks=False, onerror=walk_error):
        for name in sorted(directories + files):
            path = Path(current) / name
            relative = path.relative_to(root).as_posix()
            if path.is_symlink():
                resolved = path.resolve(strict=True)
                assert resolved.is_relative_to(RUN), relative
                rows.append({'path': relative, 'symlink': os.readlink(path)})
            elif path.is_file():
                size = path.stat().st_size
                byte_count += size
                assert size <= 256 * 1024**2 and byte_count <= 4 * 1024**3
                rows.append({'path': relative, 'bytes': size, 'sha256': file_digest(path)})
            else:
                assert path.is_dir(), 'Unexpected special dependency/output entry: ' + relative
            assert len(rows) <= 100000
    assert len(json.dumps(rows).encode()) <= 20 * 1024**2
    write_json(target, sorted(rows, key=lambda row: row['path']))
    evidence_budget()


try:
    for row in INPUTS['bundleFiles']:
        data = (BUNDLE / row['path']).read_bytes()
        assert len(data) == row['bytes'] and digest(data) == row['sha256'], row['path']
    source = acquire_source('source', INPUTS['sourceRevision'],
                            'https://github.com/riatzukiza/knoxx.git', INPUTS['productRows'])
    sdk_source = acquire_source('sdk-source', INPUTS['sdkRevision'],
                                'https://github.com/open-hax/openplanner.git', INPUTS['sdkRows'])
    helper = source / 'scripts/build-sandbox-openplanner-sdk.sh'
    assert digest(helper.read_bytes()) == INPUTS['sdkHelperSha256']
    native('SDK-prepare', ['bash', str(helper), 'prepare',
           '--source', str(sdk_source), '--run-root', str(RUN / 'sdk')], RUN, 1800000)
    filtered = RUN / 'sdk/filtered-build'
    sdk_state = json.loads((RUN / 'sdk/evidence/outcome.json').read_text())
    assert sdk_state['filtered_tree'] == str(filtered)
    assert filtered.resolve(strict=True) == filtered and filtered.stat().st_uid == os.geteuid()
    assert sdk_state['outcome'] == sdk_state['install'] == sdk_state['build'] == 'success'
    assert sdk_state['source_revision'] == INPUTS['sdkRevision']
    assert sdk_state['selected_inventory_sha256'] == INPUTS['selectedSdkInventorySha256']
    assert all(row['code'] == 0 and not row['signal'] and not row['timed_out']
               for row in sdk_state['commands'])
    for row in INPUTS['sdkRows']:
        data = (filtered / row['path']).read_bytes()
        assert len(data) == row['bytes'] and digest(data) == row['sha256'], row['path']
    package = filtered / 'packages/openplanner-sdk'
    sdk_outputs = []
    for name in ['config', 'embedding-runtime']:
        path = package / 'dist' / (name + '.js')
        assert path.is_file() and not path.is_symlink()
        sdk_outputs.append({'relative': path.relative_to(filtered).as_posix(),
                            'bytes': path.stat().st_size, 'sha256': file_digest(path)})
    write_json(EVIDENCE / 'actual-SDK-two-public-subpath-output-files.json',
               {'files': sdk_outputs, 'scope': 'Normal built output custody; no module import or provider execution.'})
    qa = package / '.qa-live-embedding'
    qa.mkdir(mode=0o700)
    for row in INPUTS['qaFiles']:
        target = qa / row['target']
        target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        shutil.copyfile(BUNDLE / row['path'], target)
    runtime_paths = INPUTS['runtimeProductPaths']
    assert len(runtime_paths) == len(set(runtime_paths)) == 5
    copied_runtime_paths = []
    for row in INPUTS['productRows']:
        if row['path'] in INPUTS['runtimeProductPaths']:
            target = qa / 'src' / Path(row['path']).relative_to('backend/src/cljs')
            target.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
            shutil.copyfile(source / row['path'], target)
            copied_runtime_paths.append(row['path'])
    assert sorted(copied_runtime_paths) == sorted(runtime_paths)

    def verify_build_inputs():
        for row in INPUTS['sdkRows']:
            target = filtered / row['path']
            assert target.stat().st_size == row['bytes'] and file_digest(target) == row['sha256']
        for row in INPUTS['qaFiles']:
            assert (qa / row['target']).read_bytes() == (BUNDLE / row['path']).read_bytes()
        for row in INPUTS['productRows']:
            if row['path'] in runtime_paths:
                target = qa / 'src' / Path(row['path']).relative_to('backend/src/cljs')
                assert target.stat().st_size == row['bytes'] and file_digest(target) == row['sha256']

    verify_build_inputs()
    home = RUN / 'qa-home'
    home.mkdir(mode=0o700)
    compile_env = {**CLEAN, 'CLJ_CONFIG': str(RUN / 'qa-clojure-config'),
                   'XDG_CACHE_HOME': str(RUN / 'qa-clojure-cache'),
                   'JAVA_TOOL_OPTIONS': '-Duser.home=' + str(home) + ' -Xmx2g'}
    output = native('QA-release', ['clojure', '-M:cljs', '-m',
                    'shadow.cljs.devtools.cli', 'release', 'entry'], qa, 600000, compile_env)
    matches = re.findall(r'\[:entry\] Build completed\. \([^\n]*, (\d+) warnings,', output)
    assert matches == ['0'], 'Exactly one real entry build with zero compiler warnings required'
    verify_build_inputs()
    preserve_files(filtered, EVIDENCE / 'actual-filtered-build-source-dependency-output-inventory.json')
    dist_files = list((qa / 'dist').rglob('*'))
    assert len(dist_files) <= 4096 and not any(p.is_symlink() for p in dist_files)
    assert sum(p.stat().st_size for p in dist_files if p.is_file()) <= 16 * 1024**2
    shutil.copytree(qa / 'dist', EVIDENCE / 'qa-compiled-dist')
    evidence_budget()
    for label, target, revision, tree, rows in [
            ('source', source, INPUTS['sourceRevision'], INPUTS['sourceTree'], INPUTS['productRows']),
            ('sdk-source', sdk_source, INPUTS['sdkRevision'], INPUTS['sdkTree'], INPUTS['sdkRows'])]:
        assert native(label + '-postflight-head', git(target, ['rev-parse', 'HEAD']), RUN, 30000).strip() == revision
        assert native(label + '-postflight-tree', git(target, ['rev-parse', 'HEAD^{tree}']), RUN, 30000).strip() == tree
        for row in rows:
            path = target / row['path']
            assert path.stat().st_size == row['bytes'] and file_digest(path) == row['sha256']
        assert native(label + '-postflight-clean', git(target, ['status', '--porcelain=v1', '-z']), RUN, 30000) == ''
    STATE['outcome'] = 'PASS-genuine-SDK-and-QA-compilation-only'
    STATE['actualSdkOutcome'] = sdk_state
except BaseException as error:
    STATE['failure'] = {'type': type(error).__name__, 'message': str(error)[:2000]}
finally:
    STATE['completedAtUTC'] = datetime.datetime.now(datetime.timezone.utc).isoformat()
    write_json(EVIDENCE / 'FINAL-build-only-outcome.json', STATE)
    evidence_budget()
    print(json.dumps({'outcome': STATE['outcome'], 'failure': STATE.get('failure'),
                      'evidence': str(EVIDENCE)}))
if STATE['outcome'] != 'PASS-genuine-SDK-and-QA-compilation-only':
    sys.exit(1)
