"""PREP ONLY: one exact public acquisition, under the existing frozen outer owner.

No standalone authority, retry, fallback endpoint, model pull command, container,
board protocol or resource-controller implementation is provided here.
"""
from pathlib import Path
import argparse
import ctypes
import datetime
import hashlib
import json
import os
import re
import signal
import stat
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request

IMAGE = 'sha256:dacbdaa86a43fb9ed58174a53d15627780829ba4c34badb15263d4104c0a37ec'
REPO = 'ollama/ollama@sha256:98c19ced6600f2924e80b92d701cd867d8f7ef0c4dde516c619484e31e47f103'
ARCHIVE = '9785247dea264d9072f09f6c9c0eb4b8e666892826a3d8388eba3e8fb9ed1db9'
NOW = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def physical(path):
    p = Path(path)
    assert p.is_absolute() and p.resolve(strict=True) == p and p.is_dir()
    return p


def safe_relative(text):
    p = Path(text)
    assert not p.is_absolute() and all(x not in ['', '.', '..'] for x in text.split('/'))
    assert not any(ord(x) < 32 for x in text) and '\\' not in text
    return p


def rename_once(source, target):
    # Linux/Ubuntu required. Unlike replace/ordinary rename, refuse a target
    # that appeared after the initial check, without creating a hardlink alias.
    assert source.parent.stat().st_dev == target.parent.stat().st_dev
    libc = ctypes.CDLL(None, use_errno=True)
    call = libc.renameat2
    call.argtypes = [ctypes.c_int, ctypes.c_char_p, ctypes.c_int, ctypes.c_char_p, ctypes.c_uint]
    call.restype = ctypes.c_int
    if call(-100, os.fsencode(source), -100, os.fsencode(target), 1) != 0:
        value = ctypes.get_errno()
        raise OSError(value, os.strerror(value), str(target))
    fd = os.open(target.parent, os.O_RDONLY | os.O_DIRECTORY)
    try:
        os.fsync(fd)
    finally:
        os.close(fd)


class OfficialRedirects(urllib.request.HTTPRedirectHandler):
    def __init__(self, transitions):
        super().__init__()
        self.transitions = transitions

    def redirect_request(self, req, fp, code, msg, headers, newurl):
        url = urllib.parse.urlsplit(newurl)
        assert url.scheme == 'https' and not url.username and not url.password and not url.fragment
        assert url.port in [None, 443]
        # Initial URLs are exact manifest bytes. Only their official HTTPS CDN
        # redirects are allowed; no alternative endpoint is selected on errors.
        host = url.hostname or ''
        assert host in ['github.com', 'release-assets.githubusercontent.com', 'registry.ollama.ai'] or host.endswith('.r2.cloudflarestorage.com')
        assert len(self.transitions) < 3, 'At most three official redirects'
        self.transitions.append({'status': code, 'host': host,
                                 'urlSHA256': hashlib.sha256(newurl.encode()).hexdigest()})
        return super().redirect_request(req, fp, code, msg, headers, newurl)


class Acquisition:
    def __init__(self, binding_path, admission_path):
        self.bundle = Path(__file__).resolve().parent
        self.binding_path = Path(binding_path)
        self.b = json.loads(self.binding_path.read_text())
        self.a = json.loads(Path(admission_path).read_text())
        manifest = self.bundle / 'portable-assets.json'
        assert sha(manifest) == self.b['assetManifest']['sha256'] == self.a['assetManifestSHA256']
        assert manifest.stat().st_size == self.b['assetManifest']['bytes']
        self.assets = json.loads(manifest.read_text())
        assert self.a['programSHA256'] == sha(__file__) and self.a['bindingSHA256'] == sha(self.binding_path)
        assert self.a['admitted'] is True and self.a['scope'] == 'ONE exact public0332 full-runtime models and carrier acquisition'
        assert 0 <= time.time() - self.a['admittedAtUnix'] <= 120
        assert os.geteuid() == self.b['uid'] > 0 and os.getegid() == self.b['gid'] > 0
        assert self.a['job'] == self.b['job']
        for key in ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB', 'GITHUB_SHA']:
            assert self.b['job'][key] == os.environ[key]
        token = self.b['job']['GITHUB_RUN_ID'] + '-' + self.b['job']['GITHUB_RUN_ATTEMPT']
        assert re.fullmatch(r'[0-9]+-[0-9]+', token)
        self.temp = physical(os.environ['RUNNER_TEMP'])
        self.root = physical(self.temp / ('openhax-model-compat-' + token))
        assert str(self.root) == self.b['root'] and self.root.stat().st_uid == os.geteuid()
        receipt_path = self.root / 'controller-pre-acquisition/FINAL-outcome.json'
        expected = self.a['preAcquisitionReceipt']
        assert sha(receipt_path) == expected['sha256'] and receipt_path.stat().st_size == expected['bytes']
        receipt = json.loads(receipt_path.read_text())
        assert receipt['job'] == self.b['job'] and receipt['phase'] == 'pre-acquisition'
        assert receipt['outcome'] == 'PASS-pre-acquisition-metadata-only' and receipt['finalSourceGuard'] == 'PASS'
        assert not receipt.get('cleanupFailure') and not receipt.get('sourcePostflightFailure')
        observed = datetime.datetime.fromisoformat(receipt['completedAtUTC']).timestamp()
        assert 0 <= time.time() - observed <= 120, 'Fresh existing resource-controller receipt required'
        assert self.assets['runtimeArchive']['sha256'] == ARCHIVE
        assert self.assets['runtimeArchive']['bytes'] == 1422262024
        assert self.assets['runtimeArchive']['url'] == 'https://github.com/ollama/ollama/releases/download/v0.33.2/ollama-linux-amd64.tar.zst'
        assert self.assets['carrier']['portableImmutableReference'] == REPO
        assert self.assets['carrier']['exactLocalConfigIDMatched'] == IMAGE
        assert len(self.assets['runtimeFiles']) == 34 and len(self.assets['runtimeLinks']) == 17
        assert len(self.assets['modelFiles']) == 8 and sum(x['bytes'] for x in self.assets['modelFiles']) == 11839212319
        for row in self.assets['modelFiles']:
            safe_relative(row['relative'])
            assert row['url'].startswith('https://registry.ollama.ai/v2/library/')
            assert row['url'].endswith('/blobs/sha256:' + row['sha256'])
        for row in self.assets['runtimeFiles'] + self.assets['runtimeLinks']:
            safe_relative(row['relative'])
        for row in self.assets['runtimeLinks']:
            assert not Path(row['link']).is_absolute()
        self.dest = self.root / 'assets'
        assert not self.dest.exists() and not self.dest.is_symlink()
        self.evidence = self.root / 'acquisition-evidence'
        self.evidence.mkdir(mode=0o700)
        self.dest.mkdir(mode=0o700)
        self.out = {'schema': 'owned-exact-public-model-assets-once/v1', 'startedAtUTC': NOW(),
                    'job': self.b['job'], 'outcome': 'FAIL', 'effects': [], 'downloads': []}
        self.seq = 0

    def save(self, name, value, raw=False):
        data = value if raw else (json.dumps(value, indent=2) + '\n').encode()
        with (self.evidence / name).open('xb') as f:
            f.write(data)
        assert sum(p.stat().st_size for p in self.evidence.iterdir() if p.is_file()) <= 32 * 1024**2

    def native(self, label, argv, cap_ms):
        self.seq += 1
        stem = f'{self.seq:02d}-{label}'
        self.save(stem + '-intent.json', {'argv': argv, 'timeoutMs': cap_ms, 'atUTC': NOW()})
        supervisor = self.bundle / 'owned-process-supervisor.py'
        assert sha(supervisor) == '348bd2f8451e0c7d232407cd0dcdd328698e896b80838e68e8681a3b7892941b'
        try:
            r = subprocess.run([self.b['python'], str(supervisor), '--timeout-ms', str(cap_ms),
                    '--max-output-bytes', '1048576', '--cleanup-ms', '10000', '--', *argv],
                    env={'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'}, capture_output=True, timeout=cap_ms / 1000 + 15)
        except subprocess.TimeoutExpired as e:
            self.save(stem + '-outer-timeout-partial.stdout', e.stdout or b'', True)
            self.save(stem + '-outer-timeout-partial.stderr', e.stderr or b'', True)
            self.save(stem + '-outer-timeout.json', {'cleanup': 'UNKNOWN', 'requiredOwner': 'Frozen whole-acquisition supervisor'})
            raise RuntimeError(label + ': outer timeout; cleanup UNKNOWN') from e
        self.save(stem + '-native.json', r.stdout, True)
        self.save(stem + '-adapter.stderr', r.stderr, True)
        value = json.loads(r.stdout)
        assert r.returncode == 0 and not r.stderr and value['exit-code'] == 0
        assert value['termination'] == 'exited' and value['cleanup-complete?'] and not value['capture-truncated?']
        return value['output']

    def download(self, name, row, destination):
        initial = urllib.parse.urlsplit(row['url'])
        assert initial.scheme == 'https' and initial.hostname in ['github.com', 'registry.ollama.ai']
        assert not initial.username and not initial.password and not initial.query and not initial.fragment
        assert initial.port in [None, 443]
        destination.parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        assert physical(destination.parent).stat().st_uid == os.geteuid()
        partial = destination.with_name(destination.name + '.partial')
        assert not destination.exists() and not destination.is_symlink()
        transitions = []
        opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), OfficialRedirects(transitions))
        h = hashlib.sha256(); count = 0
        observed = {'name': name, 'initialURL': row['url'], 'expectedBytes': row['bytes'],
                    'expectedSHA256': row['sha256'], 'atUTC': NOW(), 'partial': str(partial), 'redirects': transitions}
        self.save(name + '-download-intent.json', observed)
        deadline = time.monotonic() + 900
        self.out['effects'].append('ONE-' + name + '-HTTPS-download')
        try:
            with partial.open('xb') as sink:
                request = urllib.request.Request(row['url'], headers={'User-Agent': 'openhax-exact-public-asset-qualification/1'})
                with opener.open(request, timeout=15) as response:
                    assert response.status == 200
                    observed['httpStatus'] = response.status
                    length = response.headers.get('Content-Length')
                    if length is not None:
                        assert int(length) == row['bytes']
                    while True:
                        assert time.monotonic() <= deadline, 'One-download900s read-loop deadline'
                        block = response.read(min(1048576, row['bytes'] - count + 1))
                        if not block:
                            break
                        sink.write(block); count += len(block); h.update(block)
                        assert count <= row['bytes'], 'Response exceeds exact byte count'
                    sink.flush(); os.fsync(sink.fileno())
            assert count == row['bytes'] and h.hexdigest() == row['sha256'], 'Exact public content mismatch'
            rename_once(partial, destination)
            observed.update({'outcome': 'PASS', 'actualBytes': count, 'actualSHA256': h.hexdigest(), 'final': str(destination)})
        except BaseException as e:
            observed.update({'outcome': 'FAIL', 'receivedBytes': count, 'receivedSHA256': h.hexdigest(),
                             'failureType': type(e).__name__, 'partialRetained': partial.exists(),
                             'finalExists': destination.exists()})
            if isinstance(e, urllib.error.HTTPError): observed['httpStatus'] = e.code
            # Exception text may contain signed CDN query values; retain type,
            # controlled operation and content counters, never uncontrolled URL.
            self.save(name + '-download-result.json', observed)
            raise RuntimeError(name + ': exact public acquisition failed; retained partial and metadata') from None
        self.out['downloads'].append(observed)
        self.save(name + '-download-result.json', observed)

    def verify_runtime(self):
        root = physical(self.dest / 'runtime')
        actual = set()
        for parent, dirs, files in os.walk(root, followlinks=False):
            for name in dirs + files:
                p = Path(parent) / name
                if p.is_file() or p.is_symlink(): actual.add(p.relative_to(root).as_posix())
        assert actual == {r['relative'] for r in self.assets['runtimeFiles'] + self.assets['runtimeLinks']}
        rows = []
        for r in self.assets['runtimeFiles']:
            p = root / r['relative']; s = p.lstat()
            assert stat.S_ISREG(s.st_mode) and s.st_uid == os.geteuid() and s.st_size == r['bytes']
            assert stat.S_IMODE(s.st_mode) == r['mode'] and sha(p) == r['sha256']
            rows.append({**r, 'path': str(p), 'uid': s.st_uid, 'gid': s.st_gid, 'device': s.st_dev, 'inode': s.st_ino})
        for r in self.assets['runtimeLinks']:
            p = root / r['relative']; assert p.is_symlink() and os.readlink(p) == r['link']
            assert p.resolve(strict=True).is_relative_to(root) and p.resolve(strict=True).is_file()
            rows.append({**r, 'path': str(p), 'uid': p.lstat().st_uid})
        assert sum(r['bytes'] for r in self.assets['runtimeFiles']) == 2248016608
        self.save('actual-full-runtime-files-and-links.json', rows)

    def guarded_acquisition_tools(self):
        # These two roles are discovered once by the compositor. Execute only
        # their exact resolved physical files; aliases are not runtime inputs.
        rows = self.a['acquisitionTools']
        assert len(rows) == 2 and {row['role'] for row in rows} == {'tar', 'zstd'}
        assert len({row['path'] for row in rows}) == 2
        selected = {row['role']: row for row in rows}
        for role, row in selected.items():
            p = Path(row['path']); mode = p.lstat().st_mode
            detail = {'toolRole': role, 'path': str(p), 'mode': oct(mode)}
            assert p.is_absolute() and p.resolve(strict=True) == p and stat.S_ISREG(mode), detail
            # GNU tar treats its decompressor argument as a command string.
            # Refuse shell metacharacters instead of escaping or PATH lookup.
            assert re.fullmatch(r'/[A-Za-z0-9_./+-]+', str(p)) and os.access(p, os.X_OK), detail
            assert p.stat().st_size == row['bytes'] and sha(p) == row['sha256'], detail
        return selected

    def acquire(self):
        # Tools are required before any network acquisition. Their exact selected
        # binaries are the future binding's responsibility, no install fallback.
        selected = self.guarded_acquisition_tools()
        self.out['boundAcquisitionTools'] = list(selected.values())
        tools = {x['path']: x for x in self.b['tools']}
        for name in ['/usr/bin/docker', self.b['python']]:
            assert name in tools and Path(name).is_file()
            assert sha(name) == tools[name]['sha256'] and Path(name).stat().st_size == tools[name]['bytes']
        archive = self.dest / 'ollama-linux-amd64.tar.zst'
        self.download('original-runtime-archive', self.assets['runtimeArchive'], archive)
        for i, row in enumerate(self.assets['modelFiles'], 1):
            self.download(f'model-{i:02d}', row, self.dest / 'models' / row['relative'])
        runtime = self.dest / 'runtime'
        runtime.mkdir(mode=0o700)
        self.out['effects'].append('ONE-exact-full-original-archive-extraction')
        # The archive SHA matches the retained full34file/17link qualification,
        # not an untrusted name-list approximation. Keep archive bytes afterward.
        selected = self.guarded_acquisition_tools()
        self.native('extract-full-original-runtime', [selected['tar']['path'], '--extract',
                    '--use-compress-program=' + selected['zstd']['path'], '--file', str(archive),
                    '--directory', str(runtime), '--no-same-owner', '--same-permissions', '--keep-old-files'], 300000)
        self.verify_runtime()
        docker_config = self.dest / 'docker-client'
        docker_config.mkdir(mode=0o700)
        (docker_config / 'config.json').write_text('{}\n')
        docker = ['/usr/bin/docker', '--host', 'unix:///var/run/docker.sock', '--config', str(docker_config)]
        self.out['effects'].append('ONE-public-exact-platform-carrier-pull')
        self.native('pull-exact-carrier', docker + ['pull', '--platform', 'linux/amd64', REPO], 900000)
        fmt = '{"Id":{{json .Id}},"Os":{{json .Os}},"Architecture":{{json .Architecture}},"RepoDigests":{{json .RepoDigests}},"Size":{{json .Size}}}'
        image = json.loads(self.native('selected-carrier-readback', docker + ['image', 'inspect', '--format', fmt, IMAGE], 30000))
        assert image['Id'] == IMAGE and REPO in image['RepoDigests']
        assert image['Os'] == 'linux' and image['Architecture'] == 'amd64'
        reported = image.get('Size'); ceiling = self.assets['disk']['carrierLogicalExpanded']
        self.out['carrierLogicalExpandedAdmission'] = {'reportedBytes': reported, 'ceilingBytes': ceiling}
        assert type(reported) is int and 0 < reported <= ceiling, self.out['carrierLogicalExpandedAdmission']
        self.save('actual-exact-carrier-selected-metadata.json', image)
        for row in self.assets['modelFiles']:
            p = self.dest / 'models' / row['relative']
            assert p.stat().st_size == row['bytes'] and sha(p) == row['sha256']
        assert archive.stat().st_size == 1422262024 and sha(archive) == ARCHIVE
        self.guarded_acquisition_tools()
        self.out['outcome'] = 'PASS-exact-public-assets-acquired-once-only'
        self.out['limits'] = 'No runtime/server/model load, container creation, provider inference, SDK build or production claim'


def main():
    os.umask(0o077)
    p = argparse.ArgumentParser()
    p.add_argument('--binding', required=True); p.add_argument('--admission', required=True)
    args = p.parse_args()
    a = Acquisition(args.binding, args.admission)
    def interrupted(sig, frame):
        raise KeyboardInterrupt('Whole-owned acquisition received signal ' + str(sig))
    signal.signal(signal.SIGTERM, interrupted)
    try:
        a.acquire()
    except BaseException as e:
        a.out['primary'] = {'type': type(e).__name__, 'message': str(e)[:1000]}
    finally:
        a.out['completedAtUTC'] = NOW()
        a.save('FINAL-acquisition-outcome.json', a.out)
        print(json.dumps({'outcome': a.out['outcome'], 'primary': a.out.get('primary'), 'evidence': str(a.evidence)}))
    return 0 if a.out['outcome'].startswith('PASS-') else 1


if __name__ == '__main__':
    sys.exit(main())
