#!/usr/bin/env python3
"""A reusable Knoxx qualifier must never resolve source in its caller's repo."""
from pathlib import Path
import json
import subprocess
import sys
import tempfile
import unittest
import yaml

ROOT = Path(__file__).resolve().parents[1]

class ProductionCheckoutTest(unittest.TestCase):
    def test_all_qualification_jobs_checkout_knoxx(self):
        workflow = yaml.load((ROOT / '.github/workflows/deploy-production.yml').read_text(), Loader=yaml.BaseLoader)
        observed = []
        for name, job in workflow['jobs'].items():
            for step in job.get('steps', []):
                if step.get('uses', '').startswith('actions/checkout@'):
                    self.assertEqual('open-hax/knoxx', step['with'].get('repository'), name)
                    self.assertEqual('false', step['with'].get('persist-credentials'), name)
                    observed.append(name)
        # Signing checks out the controller; qualified checks out controller and application.
        self.assertCountEqual(['source', 'integration-e2e', 'mutation',
                               'attest-evidence', 'qualified', 'qualified'], observed)

    def test_mutation_checkout_fetches_equivalence_basis_history(self):
        workflow = yaml.load((ROOT / '.github/workflows/deploy-production.yml').read_text(), Loader=yaml.BaseLoader)
        mutation = workflow['jobs']['mutation']
        checkouts = [step for step in mutation['steps']
                     if step.get('uses', '').startswith('actions/checkout@')]
        self.assertEqual(1, len(checkouts))
        self.assertEqual('${{ needs.source.outputs.sha }}', checkouts[0]['with']['ref'])
        self.assertEqual('0', checkouts[0]['with'].get('fetch-depth'),
                         'equivalence validation needs committed ancestor objects')
        self.assertEqual('false', checkouts[0]['with']['persist-credentials'])
        commands = '\n'.join(step.get('run', '') for step in mutation['steps'])
        self.assertIn('--equivalents mutation/reviewed-equivalents.edn', commands)


    def setup_step(self, name):
        action = yaml.load((ROOT / '.github/actions/knoxx-setup/action.yml').read_text(), Loader=yaml.BaseLoader)
        step = next(step for step in action['runs']['steps'] if step.get('name') == name)
        self.assertEqual('bash', step['shell'])
        return step['run']

    def setup_transport(self, root):
        """Inert Git/pnpm commands: no network, SDK build or qualification credit."""
        tools, record, lifecycle = root / 'tools', root / 'git.jsonl', root / 'pnpm-called'
        tools.mkdir()
        pin = '07085d6557b75834ce6f50e6c54b8ca47e1c7c08'
        git = tools / 'git'
        git.write_text(f'#!{sys.executable}\n' +
            'import json, os, sys\nfrom pathlib import Path\n' +
            f'record = Path({str(record)!r})\n' +
            "keys = ['GIT_CONFIG_NOSYSTEM', 'GIT_CONFIG_GLOBAL', 'GIT_TERMINAL_PROMPT', 'GIT_ASKPASS', 'SSH_ASKPASS']\n" +
            "forbidden = ['GH_TOKEN', 'GITHUB_TOKEN', 'HOME', 'GIT_DIR', 'GIT_WORK_TREE', 'GIT_CONFIG_COUNT', 'GIT_CONFIG_KEY_0', 'GIT_CONFIG_VALUE_0']\n" +
            "row = {'argv': sys.argv[1:], 'configuration': {key: os.environ.get(key) for key in keys}, 'forbiddenNames': [key for key in forbidden if key in os.environ]}\n" +
            "with record.open('a') as stream: print(json.dumps(row), file=stream)\n" +
            f"if 'rev-parse' in sys.argv: print({pin!r})\n")
        git.chmod(0o700)
        pnpm = tools / 'pnpm'
        pnpm.write_text(f'#!{sys.executable}\nfrom pathlib import Path\n' +
                        f'Path({str(lifecycle)!r}).write_text("unexpected lifecycle")\nraise SystemExit(23)\n')
        pnpm.chmod(0o700)
        return tools, record, lifecycle

    def run_setup_fetch(self, workspace, tools):
        return subprocess.run(['/bin/bash', '--noprofile', '--norc', '-euo', 'pipefail', '-c',
                               self.setup_step('Fetch the pinned public OpenPlanner source')],
            cwd=workspace.resolve(), env={'PATH': f'{tools}:/usr/bin:/bin', 'GITHUB_WORKSPACE': str(workspace),
                'GH_TOKEN': 'synthetic-fixture', 'GITHUB_TOKEN': 'synthetic-fixture', 'HOME': '/synthetic-home',
                'GIT_DIR': '/synthetic-git', 'GIT_WORK_TREE': '/synthetic-worktree',
                'GIT_CONFIG_COUNT': '1', 'GIT_CONFIG_KEY_0': 'credential.helper', 'GIT_CONFIG_VALUE_0': 'synthetic'},
            capture_output=True, text=True, timeout=10)

    def test_setup_fetch_refuses_existing_or_symlinked_coordinates_before_git(self):
        for kind in ('workspace-link', 'sibling-directory', 'sibling-live-link', 'sibling-broken-link',
                     'parent-directory', 'parent-live-link', 'parent-broken-link'):
            with self.subTest(kind=kind), tempfile.TemporaryDirectory(prefix='knoxx-setup-refusal-') as directory:
                root = Path(directory)
                workspace = root / 'workspace'
                links, preserved = [], []
                if kind == 'workspace-link':
                    target = root / 'actual-workspace'; target.mkdir()
                    workspace.symlink_to(target, target_is_directory=True); links.append((workspace, target))
                else:
                    workspace.mkdir()
                    coordinate = root / 'openplanner' if kind.startswith('sibling-') else workspace / '_deps'
                    if kind.endswith('-directory'):
                        coordinate.mkdir(); target = coordinate
                    else:
                        target = root / 'preserved-target'
                        if kind.endswith('-live-link'): target.mkdir()
                        coordinate.symlink_to(target, target_is_directory=True); links.append((coordinate, target))
                    if target.exists():
                        sentinel = target / 'preserve'; sentinel.write_bytes(b'foreign fixture bytes\n'); preserved.append(sentinel)
                tools, record, lifecycle = self.setup_transport(root)
                result = self.run_setup_fetch(workspace, tools)
                self.assertNotEqual(0, result.returncode)
                self.assertFalse(record.exists(), 'Refusal must happen before any Git transport')
                self.assertFalse(lifecycle.exists(), 'Refusal must happen before any pnpm lifecycle')
                for link, target in links:
                    self.assertTrue(link.is_symlink()); self.assertEqual(target, link.readlink())
                for sentinel in preserved: self.assertEqual(b'foreign fixture bytes\n', sentinel.read_bytes())

    def test_setup_fetch_uses_only_isolated_exact_public_git_contract(self):
        with tempfile.TemporaryDirectory(prefix='knoxx-setup-public-fetch-') as directory:
            root = Path(directory); workspace = root / 'workspace'; workspace.mkdir()
            tools, record, lifecycle = self.setup_transport(root)
            result = self.run_setup_fetch(workspace, tools)
            self.assertEqual(0, result.returncode, result.stderr)
            rows = [json.loads(line) for line in record.read_text().splitlines()]
            pin = '07085d6557b75834ce6f50e6c54b8ca47e1c7c08'
            prefix = ['-C', str(workspace / '_deps/openplanner')]
            for option in ('core.hooksPath=/dev/null', 'core.fsmonitor=false', 'gc.auto=0',
                           'maintenance.auto=false', 'credential.helper=', 'http.extraHeader=',
                           'http.followRedirects=false', 'protocol.allow=never', 'protocol.https.allow=always'):
                prefix.extend(['-c', option])
            tails = [['init', '--template=', '--initial-branch=sdk-setup'],
                     ['fetch', '--depth=1', '--no-tags', '--no-write-fetch-head', '--no-recurse-submodules',
                      'https://github.com/open-hax/openplanner.git', pin],
                     ['rev-parse', '--verify', pin + '^{commit}'], ['checkout', '--detach', pin], ['rev-parse', 'HEAD']]
            self.assertEqual([prefix + tail for tail in tails], [row['argv'] for row in rows])
            for row in rows:
                self.assertEqual([], row['forbiddenNames'])
                self.assertEqual({'GIT_CONFIG_NOSYSTEM': '1', 'GIT_CONFIG_GLOBAL': '/dev/null',
                    'GIT_TERMINAL_PROMPT': '0', 'GIT_ASKPASS': '/bin/false', 'SSH_ASKPASS': '/bin/false'}, row['configuration'])
            self.assertTrue((workspace / '_deps/openplanner').is_dir())
            self.assertFalse((root / 'openplanner').exists())
            self.assertFalse(lifecycle.exists(), 'Raw source fetch must not execute pnpm')

if __name__ == '__main__':
    unittest.main()
