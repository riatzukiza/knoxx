"""PREP ONLY. Portable derivative of the reviewed normal-profile v2 controller.

No acquisition/build lives here. A later reviewed workflow supplies exact assets,
normal SDK/QA build closure and a fresh same-job admission. Import has no effects.
"""
from pathlib import Path
import argparse
import datetime
import hashlib
import ipaddress
import json
import os
import re
import signal
import stat
import subprocess
import sys
import time

IMAGE = 'sha256:dacbdaa86a43fb9ed58174a53d15627780829ba4c34badb15263d4104c0a37ec'
REPO = 'ollama/ollama@sha256:98c19ced6600f2924e80b92d701cd867d8f7ef0c4dde516c619484e31e47f103'
CEILING = 8326942720
HEADROOM = 2147483648
LOGS = 83886080
SDK_ALLOWANCE = 4294967296
MODEL_G = '7fbdbf8f5e45a75bb122155ed546e765b4d9c53a1285f62fd9f506baa1c5a47e'
MODEL_Q = '64b933495768fbd3b87c20583d379728a07471e0c66733a9df87cd1901b3c44b'
ORIGIN = 'http://192.168.239.2:11434'
SUBNET = ipaddress.ip_network('192.168.239.0/29')
NOW = lambda: datetime.datetime.now(datetime.timezone.utc).isoformat()


def sha(path):
    h = hashlib.sha256()
    with Path(path).open('rb') as f:
        for block in iter(lambda: f.read(1048576), b''):
            h.update(block)
    return h.hexdigest()


def exact_file(path, row, uid=None):
    p = Path(path)
    s = p.lstat()
    assert stat.S_ISREG(s.st_mode) and s.st_size == row['bytes']
    if uid is not None:
        assert s.st_uid == uid
    assert sha(p) == row['sha256'], str(p)
    return p


def physical(path):
    p = Path(path)
    assert p.is_absolute() and str(p) == str(p.resolve(strict=True))
    assert p.is_dir()
    return p


def run(argv, timeout=45, cwd=None, env=None):
    return subprocess.run(argv, cwd=cwd, env=env or {'PATH': '/usr/bin:/bin', 'LANG': 'C.UTF-8'},
                          capture_output=True, timeout=timeout, check=False)


class Controller:
    def __init__(self, binding_path, admission_path, phase):
        self.phase = phase
        self.binding_path = Path(binding_path)
        self.b = json.loads(self.binding_path.read_text())
        self.a = json.loads(Path(admission_path).read_text())
        self.bundle = physical(self.b['bundleRoot'])
        self.assets = json.loads(exact_file(self.bundle / 'portable-assets.json', self.b['assetManifest']).read_text())
        self.uid, self.gid = os.geteuid(), os.getegid()
        assert self.uid > 0 and self.gid > 0
        assert [self.uid, self.gid] == [self.b['uid'], self.b['gid']]
        for key in ['GITHUB_RUN_ID', 'GITHUB_RUN_ATTEMPT', 'GITHUB_JOB', 'GITHUB_SHA']:
            assert os.environ.get(key) == self.b['job'][key]
        self.temp = physical(os.environ['RUNNER_TEMP'])
        assert str(self.temp) == self.b['runnerTemp'] and self.temp.stat().st_uid == self.uid
        token = self.b['job']['GITHUB_RUN_ID'] + '-' + self.b['job']['GITHUB_RUN_ATTEMPT']
        assert re.fullmatch(r'[0-9]+-[0-9]+', token)
        self.root = self.temp / ('openhax-model-compat-' + token)
        assert str(self.root) == self.b['root']
        assert self.b['sourceRoots'] == {'Knoxx': str(self.root / 'source'), 'SDK': str(self.root / 'sdk-source')}
        assert str(Path(sys.executable).resolve()) == self.b['python']
        assert len(self.b['tools']) == 6 and {r['path'] for r in self.b['tools']} == {self.b['node'], self.b['python'], '/usr/bin/docker', '/usr/bin/curl', self.b['ip'], '/usr/bin/git'}
        assert [r['path'] for r in self.b['tools'] if r.get('role') == 'ip'] == [self.b['ip']]
        self.owner = 'openhax-model-compat-' + token
        self.name = self.owner
        self.network = self.owner
        assert self.a['admitted'] is True and self.a['phase'] == phase
        assert self.a['scope'] == 'ONE hosted normal-profile exact0332 original-probe plus hot then compact checked SDK'
        assert self.a['programSHA256'] == sha(__file__)
        assert self.a['bindingSHA256'] == sha(self.binding_path)
        assert self.a['assetManifestSHA256'] == self.b['assetManifest']['sha256']
        assert self.a['job'] == self.b['job']
        assert 0 <= time.time() - self.a['admittedAtUnix'] <= 120
        closure = json.loads(exact_file(self.bundle / self.a['qaClosure']['relative'], self.a['qaClosure']).read_text())
        assert closure['job'] == self.b['job'] and closure['closed'] is True and closure['activeConsumers'] == []
        assert not self.root.is_symlink()
        assert physical(self.root).stat().st_uid == self.uid
        if phase == 'pre-acquisition':
            # Source-only preparation precedes model/payload admission. Do not
            # pretend this already-cloned RUN is absent or freshly recreated.
            assert {p.name for p in self.root.iterdir()} <= {'source', 'sdk-source', 'metadata', 'bindings'}
            assert all((self.root / name).is_dir() and not (self.root / name).is_symlink()
                       for name in ['source', 'sdk-source'])
        if phase == 'pre-sdk':
            assert not (self.root / 'sdk').exists() and not (self.root / 'sdk').is_symlink()
        self.evidence = self.root / ('controller-' + phase)
        self.evidence.mkdir(mode=0o700)  # One phase, no replay or overwrite.
        self.seq = 0
        self.cid = None
        self.start = None
        self.create_attempted = False
        self.net_attempted = False
        self.out = {'schema': 'owned-hosted-exact0332-normal-profile/v1', 'startedAtUTC': NOW(),
                    'phase': phase, 'job': self.b['job'], 'effects': [],
                    'limits': 'Whole-host ceiling upper bound; no production-headroom, minimum-RAM, cold-second-tier, Mongo or release claim'}
        self.docker = ['/usr/bin/docker', '--host', 'unix:///var/run/docker.sock', '--config', str(self.root / 'docker-client')]

    def save(self, name, value, raw=False):
        data = value if raw else (json.dumps(value, indent=2) + '\n').encode()
        with (self.evidence / name).open('xb') as f:
            f.write(data)
        (self.evidence / name).chmod(0o600)

    def command(self, name, argv, timeout=45):
        self.seq += 1
        stem = f'{self.seq:03d}-{name}'
        self.save(stem + '-intent.json', {'atUTC': NOW(), 'argv': argv, 'timeoutSeconds': timeout})
        try:
            r = run(argv, timeout=timeout)
            out, err = r.stdout, r.stderr
            result = {'exit': r.returncode, 'atUTC': NOW()}
        except subprocess.TimeoutExpired as e:
            out, err = e.stdout or b'', e.stderr or b''
            result = {'exit': None, 'timedOut': True, 'atUTC': NOW()}
        self.save(stem + '.stdout', out, True)
        self.save(stem + '.stderr', err, True)
        self.save(stem + '-result.json', {**result, 'stdoutBytes': len(out), 'stderrBytes': len(err)})
        assert result['exit'] == 0, {'command': name, **result}
        return out

    def source_guard(self, built=False):
        for role, expected in self.assets['sourceRoles'].items():
            root = physical(self.b['sourceRoots'][role])
            for row in expected['files']:
                exact_file(root / row['path'], row)
            r = run(['/usr/bin/git', '-C', str(root), 'rev-parse', expected['commit'] + '^{commit}'])
            assert r.returncode == 0 and r.stdout.decode().strip() == expected['commit']
            for row in expected['files']:
                r = run(['/usr/bin/git', '-C', str(root), 'show', expected['commit'] + ':' + row['path']])
                assert r.returncode == 0 and hashlib.sha256(r.stdout).hexdigest() == row['sha256']
            actual_head = run(['/usr/bin/git', '-C', str(root), 'rev-parse', 'HEAD'])
            status = run(['/usr/bin/git', '-C', str(root), 'status', '--porcelain=v1', '-z', '--untracked-files=all'])
            assert actual_head.returncode == status.returncode == 0
            assert actual_head.stdout.decode().strip() == self.b['sourceState'][role]['head']
            assert hashlib.sha256(status.stdout).hexdigest() == self.b['sourceState'][role]['statusSHA256']
        for row in self.assets['bundleFiles']:
            exact_file(self.bundle / row['relative'], row)
        ip = Path(self.b['ip']); mode = ip.lstat().st_mode
        assert ip.is_absolute() and ip.resolve(strict=True) == ip and stat.S_ISREG(mode), {'toolRole': 'ip', 'path': str(ip), 'mode': oct(mode)}
        for tool in self.b['tools']:
            exact_file(tool['path'], tool)
        if built:
            receipt = json.loads(exact_file(self.root / self.b['buildClosure']['relative'], self.b['buildClosure']).read_text())
            assert receipt['job'] == self.b['job'] and receipt['closed'] is True
            assert receipt['SDKSourceCommit'] == self.assets['sourceRoles']['SDK']['commit']
            assert receipt['KnoxxSourceCommit'] == self.assets['sourceRoles']['Knoxx']['commit']
            assert receipt['zeroCompilerWarnings'] is True and receipt['activeConsumers'] == []
            assert receipt['qaSourceSHA256'] == self.assets['qaEntrySHA256']
            assert receipt['sdkSelectedInventorySHA256'] == self.assets['sdkSelectedInventorySHA256']
            for child in receipt['nativeChildren']:
                assert child['termination'] == 'exited' and child['exit-code'] == 0
                assert child['cleanup-complete?'] and not child['capture-truncated?']
                capture = json.loads(exact_file(self.root / child['capture']['relative'], child['capture']).read_text())
                for key in ['termination', 'exit-code', 'cleanup-complete?', 'capture-truncated?']:
                    assert capture[key] == child[key]
            assert receipt['nativeChildren'] and receipt['roles'] == ['normalSDKFrozenBuild', 'privateQACompile', 'owningLint', 'nodeSyntax']
            assert [child['role'] for child in receipt['nativeChildren']] == receipt['roles']
            build = physical(self.root / 'sdk/filtered-build')
            for row in self.assets['sourceRoles']['SDK']['files']:
                exact_file(build / row['path'], row)
            qa = build / 'packages/openplanner-sdk/.qa-live-embedding'
            for row in self.assets['sourceRoles']['Knoxx']['files'][:5]:
                relative = row['path'].split('backend/src/cljs/', 1)[1]
                exact_file(qa / 'src' / relative, row)
            for source, target in [('qa-src/knoxx/backend/extern/live_checked_embedding_qa.cljs', 'src/knoxx/backend/extern/live_checked_embedding_qa.cljs'),
                                   ('qa-native-driver.mjs', 'qa-native-driver.mjs'), ('qa-deps.edn', 'deps.edn'), ('qa-shadow-cljs.edn', 'shadow-cljs.edn')]:
                row = next(r for r in self.assets['bundleFiles'] if r['relative'] == source)
                exact_file(qa / target, row)
            manifest = json.loads(exact_file(self.root / receipt['outputManifest']['relative'], receipt['outputManifest']).read_text())
            found = set()
            for parent, dirs, files in os.walk(build, followlinks=False):
                for leaf in dirs + files:
                    p = Path(parent) / leaf
                    if p.is_symlink() or p.is_file():
                        found.add(str(p.relative_to(build)))
            assert found == {r['relative'] for r in manifest['rows']}
            for row in manifest['rows']:
                p = build / row['relative']
                if row['type'] == 'file':
                    exact_file(p, row, self.uid)
                else:
                    assert row['type'] == 'symlink' and p.is_symlink() and os.readlink(p) == row['link']
                    resolved = p.resolve(strict=False)
                    assert resolved.is_relative_to(build)
                    assert resolved.relative_to(build).as_posix() == row['resolvedRelativePath']
                    assert isinstance(row['targetExists'], bool) and resolved.exists() is row['targetExists']
            for sub in ['src', 'dist']:
                assert (build / 'packages/openplanner-sdk/.qa-live-embedding' / sub).is_dir()

    def host(self):
        mem = {k: int(v.split()[0]) for k, v in (l.split(':', 1) for l in Path('/proc/meminfo').read_text().splitlines())
               if k in ['MemTotal', 'MemAvailable', 'SwapTotal', 'SwapFree']}
        psi = {l.split()[0]: {k: float(v) for k, v in (x.split('=') for x in l.split()[1:])}
               for l in Path('/proc/pressure/memory').read_text().splitlines()}
        relative = next(l.split('::', 1)[1] for l in Path('/proc/self/cgroup').read_text().splitlines() if l.startswith('0::'))
        assert '..' not in Path(relative).parts
        cg = Path('/sys/fs/cgroup') / relative.lstrip('/')
        vals = {name: (cg / name).read_text().strip() for name in ['memory.max', 'memory.current', 'memory.swap.max', 'memory.events', 'memory.pressure', 'cpu.max']}
        fmt = '{"OSType":{{json .OSType}},"Architecture":{{json .Architecture}},"CgroupVersion":{{json .CgroupVersion}},"DockerRootDir":{{json .DockerRootDir}}}'
        docker = json.loads(self.command('fresh-docker-root', self.docker + ['info', '--format', fmt]))
        assert docker['OSType'] == 'linux' and docker['Architecture'] in ['x86_64', 'amd64']
        assert docker['CgroupVersion'] == '2'
        data = physical(docker['DockerRootDir'])
        assert str(data) == self.b['dockerDataRoot']
        return {'atUTC': NOW(), 'uid': self.uid, 'gid': self.gid, 'cpuCount': os.cpu_count(),
                'memoryKiB': mem, 'memoryPSI': psi, 'cgroup': vals, 'dockerDataRoot': str(data)}

    def admission(self, name, initial=False):
        h = self.host()
        self.save(name + '-fresh-resource.json', h)
        assert h['cpuCount'] >= 4
        assert h['memoryKiB']['MemAvailable'] * 1024 >= CEILING + HEADROOM
        assert h['memoryPSI']['some']['avg10'] <= 10 and h['memoryPSI']['full']['avg10'] <= 5
        if h['cgroup']['memory.max'] != 'max':
            assert int(h['cgroup']['memory.max']) - int(h['cgroup']['memory.current']) >= CEILING + HEADROOM
        quota, period = h['cgroup']['cpu.max'].split()
        assert quota == 'max' or int(quota) / int(period) >= 4
        budgets = [(self.root, LOGS), (Path(self.b['dockerDataRoot']), 0)]
        if initial:
            budgets += [(self.root, self.assets['disk']['models'] + self.assets['disk']['runtimeExpanded']
                         + self.assets['disk']['runtimeArchive'] + SDK_ALLOWANCE),
                        (Path(self.b['dockerDataRoot']), self.assets['disk']['carrierCompressed'] + self.assets['disk']['carrierLogicalExpanded'])]
        elif self.phase == 'pre-sdk':
            budgets += [(self.root, SDK_ALLOWANCE)]
        grouped = {}
        for path, need in budgets:
            s, v = path.stat(), os.statvfs(path)
            row = grouped.setdefault(s.st_dev, {'paths': [], 'requiredBytes': HEADROOM, 'availableBytes': v.f_bavail * v.f_frsize})
            row['paths'].append(str(path)); row['requiredBytes'] += need
            row['availableBytes'] = min(row['availableBytes'], v.f_bavail * v.f_frsize)
        self.save(name + '-per-filesystem-admission.json', list(grouped.values()))
        assert all(x['availableBytes'] >= x['requiredBytes'] for x in grouped.values()), 'HOLD: insufficient actual per-filesystem space'
        return h

    def assets_guard(self):
        exact_file(self.root / 'assets/ollama-linux-amd64.tar.zst', self.assets['runtimeArchive'], self.uid)
        base = physical(self.root / 'assets/runtime')
        actual = set()
        for parent, dirs, files in os.walk(base, followlinks=False):
            for leaf in dirs + files:
                p = Path(parent) / leaf
                if p.is_file() or p.is_symlink(): actual.add(str(p.relative_to(base)))
        assert actual == {r['relative'] for r in self.assets['runtimeFiles'] + self.assets['runtimeLinks']}
        for r in self.assets['runtimeFiles']:
            p = exact_file(base / r['relative'], r, self.uid)
            assert stat.S_IMODE(p.stat().st_mode) == r['mode']
        for r in self.assets['runtimeLinks']:
            p = base / r['relative']
            assert p.is_symlink() and os.readlink(p) == r['link'] and p.resolve(strict=True).is_relative_to(base)
        models = physical(self.root / 'assets/models')
        actual = {str(p.relative_to(models)) for p in models.rglob('*') if p.is_file() or p.is_symlink()}
        assert actual == {r['relative'] for r in self.assets['modelFiles']}
        for r in self.assets['modelFiles']: exact_file(models / r['relative'], r, self.uid)
        fmt = '{"Id":{{json .Id}},"Os":{{json .Os}},"Architecture":{{json .Architecture}},"RepoDigests":{{json .RepoDigests}},"Size":{{json .Size}}}'
        image = json.loads(self.command('exact-carrier-readback', self.docker + ['image', 'inspect', '--format', fmt, IMAGE]))
        assert image['Id'] == IMAGE and image['Os'] == 'linux' and image['Architecture'] == 'amd64'
        assert REPO in image['RepoDigests']
        reported = image.get('Size'); ceiling = self.assets['disk']['carrierLogicalExpanded']
        self.out['carrierLogicalExpandedAdmission'] = {'reportedBytes': reported, 'ceilingBytes': ceiling}
        assert type(reported) is int and 0 < reported <= ceiling, self.out['carrierLogicalExpandedAdmission']

    def create_argv(self):
        argv = self.docker + ['create', '--pull=never', '--name', self.name, '--label', 'org.openhax.private-qa-owner=' + self.owner,
              '--user', f'{self.uid}:{self.gid}', '--read-only', '--cap-drop', 'ALL', '--security-opt', 'no-new-privileges:true',
              '--memory', str(CEILING), '--memory-swap', str(CEILING), '--cpus', '4', '--pids-limit', '256', '--shm-size', '16m',
              '--restart', 'no', '--log-driver', 'local', '--log-opt', 'max-size=5m', '--log-opt', 'max-file=1', '--log-opt', 'compress=false',
              '--network', self.network, '--ip', '192.168.239.2', '--network-alias', 'ollama0332-normal-no-pause']
        for src, target, ro in [(self.root / 'assets/runtime', '/qualified-ollama', True),
                                (self.root / 'assets/models', '/models', True), (self.root / 'qa-home', '/qa-home', False)]:
            argv += ['--mount', f'type=bind,src={src},dst={target}' + (',readonly' if ro else '')]
        argv += ['--tmpfs', f'/tmp:rw,noexec,nosuid,nodev,size=134217728,uid={self.uid},gid={self.gid},mode=0700',
                 '--entrypoint', '/usr/bin/env', IMAGE, '-i', 'PATH=/usr/bin:/bin', 'LANG=C.UTF-8', 'HOME=/qa-home', 'TMPDIR=/tmp',
                 'OLLAMA_MODELS=/models', 'OLLAMA_HOST=0.0.0.0:11434', 'OLLAMA_NO_CLOUD=1', '/qualified-ollama/bin/ollama', 'serve']
        return argv

    def inspect(self, name, target=None):
        fields = ['.Id', '.Name', '.Image', '.Config.User', '.HostConfig.ReadonlyRootfs', '.Config.Entrypoint', '.Config.Cmd',
                  '.State', '.Mounts', '.HostConfig.Memory', '.HostConfig.MemorySwap', '.HostConfig.NanoCpus', '.HostConfig.PidsLimit',
                  '.HostConfig.CapDrop', '.HostConfig.SecurityOpt', '.HostConfig.LogConfig', '.NetworkSettings.Networks',
                  '(index .Config.Labels "org.openhax.private-qa-owner")', '.HostConfig.PortBindings',
                  '.HostConfig.Privileged', '.HostConfig.Devices', '.HostConfig.DeviceRequests', '.HostConfig.CapAdd']
        fmt = '[' + ','.join('{{json ' + x + '}}' for x in fields) + ']'
        return json.loads(self.command(name, self.docker + ['container', 'inspect', '--format', fmt, target or self.cid]))

    def ensure_container(self, v, running):
        assert v[0] == self.cid and v[1] == '/' + self.name and v[2] == IMAGE
        assert v[3] == f'{self.uid}:{self.gid}' and v[4] is True and v[5] == ['/usr/bin/env']
        a = self.create_argv(); assert v[6] == a[a.index(IMAGE) + 1:]
        assert v[9:13] == [CEILING, CEILING, 4000000000, 256]
        assert v[13] == ['ALL'] and 'no-new-privileges:true' in v[14] and v[17] == self.owner
        assert v[15] == {'Type': 'local', 'Config': {'compress': 'false', 'max-file': '1', 'max-size': '5m'}}
        expected = {('/qualified-ollama', str(self.root / 'assets/runtime'), False),
                    ('/models', str(self.root / 'assets/models'), False), ('/qa-home', str(self.root / 'qa-home'), True)}
        assert {(m['Destination'], m['Source'], m['RW']) for m in v[8]} == expected
        assert not any(v[18:23]) and set(v[16]) == {self.network}
        n = v[16][self.network]
        assert n['IPAMConfig']['IPv4Address'] == '192.168.239.2' and 'ollama0332-normal-no-pause' in n['Aliases']
        assert v[7]['Running'] is running
        if running: assert n['IPAddress'] == '192.168.239.2' and (self.start is None or self.start == v[7]['StartedAt'])

    def metrics(self, name):
        v = self.inspect(name + '-inspect'); self.ensure_container(v, True)
        proc = Path('/proc') / str(v[7]['Pid'])
        rel = next(x.split('::', 1)[1] for x in (proc / 'cgroup').read_text().splitlines() if x.startswith('0::'))
        assert self.cid in rel and '..' not in Path(rel).parts
        cg = Path('/sys/fs/cgroup') / rel.lstrip('/')
        values = {k: (cg / k).read_text() for k in ['memory.current', 'memory.peak', 'memory.max', 'memory.swap.current',
                  'memory.swap.max', 'memory.events', 'memory.stat', 'memory.pressure', 'cpu.max', 'cpu.stat', 'cpu.pressure']}
        self.save(name + '.json', {'atUTC': NOW(), 'containerState': v[7], 'cgroup': values})
        assert int(values['memory.max']) == CEILING and int(values['memory.swap.max']) == int(values['memory.swap.current']) == 0
        quota, period = values['cpu.max'].split(); assert int(quota) / int(period) == 4
        events = {}
        for line in values['memory.events'].splitlines():
            key, value = line.split()
            assert key not in events and re.fullmatch(r'[0-9]+', value), 'Unknown memory.events format'
            events[key] = int(value)
        assert {'oom', 'oom_kill'} <= events.keys(), 'Missing OOM resource counters'
        assert all(events.get(key, 0) == 0 for key in ['oom', 'oom_kill', 'oom_group_kill']), 'Owned cgroup reported OOM'
        assert v[7].get('OOMKilled') is False, 'Owned container OOM state unknown or true'
        return values

    def http(self, name, route):
        assert route in ['/api/version', '/api/tags', '/api/ps']
        response = self.evidence / (name + '-response.raw.json')
        status = self.command(name, ['/usr/bin/curl', '--silent', '--show-error', '--noproxy', '*', '--connect-timeout', '15',
                   '--max-time', '15', '--max-filesize', '1048576', '--output', str(response), '--write-out', '%{http_code}', ORIGIN + route], 25)
        assert status == b'200' and response.stat().st_size <= 1048576
        response.chmod(0o600)
        return json.loads(response.read_text())

    def owned_child(self, name, cap_ms, argv):
        supervisor = self.bundle / 'owned-process-supervisor.py'
        blob = self.command(name, [self.b['python'], str(supervisor), '--timeout-ms', str(cap_ms),
               '--max-output-bytes', '8388608', '--cleanup-ms', '10000', '--', *argv], cap_ms / 1000 + 20)
        result = json.loads(blob); self.save(name + '-native.json', result)
        assert result['termination'] == 'exited' and result['exit-code'] == 0
        assert result['cleanup-complete?'] and not result['capture-truncated?']
        return result

    def measure(self):
        self.source_guard(built=True); self.assets_guard(); self.admission('before-network-and-create')
        for p in [self.root / 'qa-home', self.root / 'docker-client', self.root / 'original-wire', self.root / 'sdk-data']:
            p.mkdir(mode=0o700)
        (self.root / 'docker-client/config.json').write_text('{}\n')
        assert b'--timeout' in self.command('stop-help', self.docker + ['stop', '--help'])
        ids = self.command('network-IDs', self.docker + ['network', 'ls', '-q']).decode().splitlines()
        if ids:
            for net in json.loads(self.command('networks-before-create', self.docker + ['network', 'inspect', *ids])):
                assert net['Name'] != self.network
                for cfg in net.get('IPAM', {}).get('Config', []) or []:
                    if cfg.get('Subnet'):
                        old = ipaddress.ip_network(cfg['Subnet']); assert old.version != 4 or not old.overlaps(SUBNET)
        routes = json.loads(self.command('routes-before-create', [self.b['ip'], '-j', '-4', 'route', 'show', 'table', 'all']))
        for route in routes:
            if route.get('dst') and route['dst'] != 'default': assert not ipaddress.ip_network(route['dst'], strict=False).overlaps(SUBNET)
        assert self.command('same-name-container-absence', self.docker + ['ps', '-a', '--filter', 'name=^/' + self.name + '$', '--format', '{{.ID}}']) == b''
        self.net_attempted = True; self.out['effects'].append('ONE-internal-network-create')
        netid = self.command('ONE-network-create', self.docker + ['network', 'create', '--internal', '--driver', 'bridge', '--subnet', str(SUBNET),
                   '--gateway', '192.168.239.1', '--label', 'org.openhax.private-qa-owner=' + self.owner, self.network]).decode().strip()
        assert re.fullmatch('[0-9a-f]{64}', netid)
        net = json.loads(self.command('owned-network-readback', self.docker + ['network', 'inspect', netid]))[0]
        assert net['Internal'] and net['Labels']['org.openhax.private-qa-owner'] == self.owner
        assert net['IPAM']['Config'] == [{'Subnet': str(SUBNET), 'Gateway': '192.168.239.1'}]
        self.create_attempted = True; self.out['effects'].append('ONE-container-create')
        self.cid = self.command('ONE-container-create', self.create_argv()).decode().strip()
        assert re.fullmatch('[0-9a-f]{64}', self.cid)
        self.ensure_container(self.inspect('created-identity'), False); self.source_guard(built=True)
        self.out['effects'].append('ONE-container-start'); self.command('ONE-container-start', self.docker + ['start', self.cid])
        v = self.inspect('started-identity'); self.start = v[7]['StartedAt']; self.ensure_container(v, True)
        time.sleep(3)  # Original startup settling delay; no generation/prewarm.
        self.metrics('cold-before-probe')
        assert self.http('version', '/api/version') == {'version': '0.33.2'}
        tags = self.http('tags', '/api/tags')['models']
        assert len(tags) == 2
        assert {r.get('name', r.get('model')): r['digest'] for r in tags} == {'gemma4:e2b': MODEL_G, 'qwen3-embedding:8b': MODEL_Q}
        assert self.http('cold-residency', '/api/ps')['models'] == []
        self.admission('before-original-probe'); self.source_guard(built=True)
        node = self.b['node']; common = ['/usr/bin/env', '-i', 'PATH=' + str(Path(node).parent) + ':/usr/bin:/bin', 'LANG=C.UTF-8',
                 'OLLAMA_BASE_URL=' + ORIGIN, 'EMBED_PROVIDER_BASE_URL=' + ORIGIN]
        original = common + ['KNOXX_DEPLOY_TRANSLATION_MODEL=gemma4:e2b', 'KNOXX_DEPLOY_EMBEDDING_MODEL=qwen3-embedding:8b',
                 'EMBED_PROVIDER_MODEL=qwen3-embedding:8b', 'KNOXX_DEPLOY_EMBEDDING_DIMENSIONS=1024', 'EMBED_PROVIDER_DIMENSIONS=1024',
                 'BACKEND_PROBE_TIMEOUT_MS=15000', 'KNOXX_OLLAMA_INFERENCE_TIMEOUT_MS=90000',
                 'PROBE_EVIDENCE_DIR=' + str(self.root / 'original-wire'), node, '--require', str(self.bundle / 'observe-original-probe.cjs'),
                 str(self.bundle / 'probe-ollama.js')]
        self.out['effects'].append('ONE-original-Services-probe-90-90-15')
        probe = self.owned_child('ONE-original-Services-probe', 225000, original)
        assert json.loads(probe['output'])['ok'] is True
        for i in range(1, 5):
            for tail in ['request.raw.json', 'response.raw.json']: assert (self.root / 'original-wire' / f'{i:02d}-{tail}').is_file()
        assert json.loads((self.root / 'original-wire/04-response.metadata.json').read_text())['exactEmbedding'] is True
        self.metrics('after-original-probe'); self.http('after-original-residency', '/api/ps')
        qa = self.root / 'sdk/filtered-build/packages/openplanner-sdk/.qa-live-embedding'
        # The two additional children are explicit, sequential and each hard-capped.
        # These are added phases after the original cold probe. The container's
        # already-admitted ceiling remains enforced, while actual host headroom
        # must remain >=2GiB with the same PSI limits. No file-cache/current-use
        # addition is made to MemAvailable. Root must review this phase rule.
        for tier in ['hot', 'compact']:
            self.source_guard(built=True)
            h = self.host(); c = self.metrics('before-' + tier)
            self.save('before-' + tier + '-host.json', h)
            assert h['memoryPSI']['some']['avg10'] <= 10 and h['memoryPSI']['full']['avg10'] <= 5
            assert h['memoryKiB']['MemAvailable'] * 1024 >= HEADROOM
            # Same device is counted once; these are remaining logs/headroom only,
            # since all immutable payload/build allocation has already occurred.
            disks = {}
            for path, extra in [(self.root, LOGS), (Path(self.b['dockerDataRoot']), 0)]:
                device = path.stat().st_dev; fs = os.statvfs(path)
                d = disks.setdefault(device, {'required': HEADROOM, 'available': fs.f_bavail * fs.f_frsize})
                d['required'] += extra; d['available'] = min(d['available'], fs.f_bavail * fs.f_frsize)
            self.save('before-' + tier + '-remaining-disk.json', list(disks.values()))
            assert all(d['available'] >= d['required'] for d in disks.values())
            target = self.root / ('sdk-' + tier + '-wire')
            argv = common + ['EMBED_PROVIDER_MODEL=qwen3-embedding:8b',
                    'EMBED_PROVIDER_COMPACT_MODEL=qwen3-embedding:8b', 'EMBED_PROVIDER_DIMENSIONS=1024',
                    'OPENPLANNER_DATA_DIR=' + str(self.root / 'sdk-data'), 'EMBED_PROVIDER_CACHE_PATH=' + str(self.root / 'sdk-data/cache'),
                    node, str(qa / 'qa-native-driver.mjs'), tier, str(target), ORIGIN]
            self.out['effects'].append('ONE-' + tier + '-checked-SDK-15s-child')
            self.owned_child('ONE-' + tier + '-checked-SDK', 15000, argv)
            outcome = json.loads((target / 'outcome.json').read_text())
            assert outcome['success'] and outcome['calls'] == outcome['completedResponses'] == 1 and outcome['originalFetchRestored']
            self.metrics('after-' + tier); self.http('after-' + tier + '-residency', '/api/ps')
        self.source_guard(built=True); self.assets_guard()
        self.out['outcome'] = 'PASS-hosted-original-services-and-two-actual-checked-tiers'
        self.out['order'] = ['cold-catalog', 'original-translation-90s', 'original-tool-90s', 'original-embedding-15s', 'checked-hot-whole-child-15s', 'checked-compact-whole-child-15s']
        self.out['secondTierIsNotCold'] = True

    def cleanup(self):
        if self.cid is None and self.create_attempted:
            try:
                v = self.inspect('unknown-create-readonly-reconciliation', self.name)
                self.cid = v[0]; assert re.fullmatch('[0-9a-f]{64}', self.cid)
                self.ensure_container(v, bool(v[7]['Running']))
                self.out['unknownCreateReconciledCID'] = self.cid
            except BaseException as e:
                self.out['cleanupFailure'] = 'Unknown create outcome; read-only recovery required: ' + repr(e)
        if self.cid:
            try:
                v = self.inspect('cleanup-current-identity'); self.ensure_container(v, bool(v[7]['Running']))
                if self.phase == 'measure':
                    try:
                        assert v[7]['Running'] is True, 'Final live resource state unavailable: owned container is not running'
                        self.metrics('final-live-before-graceful-stop')
                        self.out['finalResourceGuard'] = 'PASS'
                    except BaseException as e:
                        self.out['finalResourceGuard'] = 'FAIL'
                        self.out['finalResourceFailure'] = {'type': type(e).__name__, 'message': str(e)[:2000]}
                        self.save('final-resource-before-stop-failure.json', self.out['finalResourceFailure'])
                if v[7]['Running']:
                    self.out['effects'].append('ONE-owned-graceful-stop')
                    self.command('ONE-owned-graceful-stop', self.docker + ['stop', '--timeout', '30', self.cid], 45)
                stopped = self.inspect('stopped-identity'); self.ensure_container(stopped, False)
                self.out['stoppedState'] = stopped[7]; self.out['containerRetained'] = True
                if self.phase == 'measure' and stopped[7].get('OOMKilled') is not False:
                    self.out['finalResourceGuard'] = 'FAIL'
                    self.out.setdefault('finalResourceFailure', {'type': 'AssertionError', 'message': 'Stopped owned container OOM state unknown or true'})
            except BaseException as e:
                self.out['cleanupFailure'] = repr(e)
        if self.phase == 'measure' and self.out.get('finalResourceGuard') != 'PASS':
            if self.out.get('outcome', '').startswith('PASS-'):
                self.out['outcome'] = 'FAIL-final-resource-qualification'
        self.out['networkCreateAttemptedNoRemoval'] = self.net_attempted
        try:
            self.source_guard(built=self.phase == 'measure')
            if self.phase == 'measure': self.assets_guard()
            self.out['finalSourceGuard'] = 'PASS'
        except BaseException as e:
            self.out['sourcePostflightFailure'] = repr(e)
        self.out['completedAtUTC'] = NOW(); self.out['CID'] = self.cid
        self.save('FINAL-outcome.json', self.out)


def main():
    os.umask(0o077)
    parser = argparse.ArgumentParser()
    parser.add_argument('--binding', required=True)
    parser.add_argument('--admission', required=True)
    parser.add_argument('--phase', required=True, choices=['pre-acquisition', 'pre-sdk', 'measure'])
    args = parser.parse_args()
    c = Controller(args.binding, args.admission, args.phase)
    def interrupted(sig, frame):
        raise KeyboardInterrupt('Owned controller signal ' + str(sig))
    signal.signal(signal.SIGTERM, interrupted); signal.signal(signal.SIGINT, interrupted)
    try:
        c.source_guard()
        if args.phase == 'measure':
            c.measure()
        else:
            c.admission(args.phase, initial=args.phase == 'pre-acquisition')
            if args.phase == 'pre-sdk': c.assets_guard()
            c.out['outcome'] = 'PASS-' + args.phase + '-metadata-only'
    except BaseException as e:
        c.out['outcome'] = 'HOLD' if str(e).startswith('HOLD:') else 'FAIL'
        c.out['primary'] = {'type': type(e).__name__, 'message': str(e)[:2000]}
        c.save('primary-before-cleanup.json', c.out)
    finally:
        c.cleanup()
    print(json.dumps(c.out))
    ok = c.out.get('outcome', '').startswith('PASS-') and c.out.get('finalSourceGuard') == 'PASS'
    if args.phase == 'measure':
        ok = ok and c.out.get('finalResourceGuard') == 'PASS' and not c.out.get('finalResourceFailure')
        ok = ok and c.out.get('containerRetained') and c.out.get('stoppedState', {}).get('Running') is False
        ok = ok and c.out.get('stoppedState', {}).get('OOMKilled') is False
    return 0 if ok and not c.out.get('cleanupFailure') and not c.out.get('sourcePostflightFailure') else 1


if __name__ == '__main__':
    sys.exit(main())
