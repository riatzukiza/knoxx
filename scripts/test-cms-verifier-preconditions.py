#!/usr/bin/env python3
"""CMS verifiers must fail before testing an unidentified checkout."""
from contextlib import contextmanager
import json
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[1]


class CmsVerifierPreconditionsTest(unittest.TestCase):
    @contextmanager
    def verifier_fixture(self, script_name, git_body='printf "%s\\n" 0123456789abcdef0123456789abcdef01234567\n',
                         checkout=None, symlink_entry=False, git_root_body=None):
        with tempfile.TemporaryDirectory(prefix="knoxx-cms-preconditions-") as temporary:
            temporary_root = Path(temporary)
            fixture = temporary_root / "source checkout"
            if checkout:
                real_git = shutil.which("git")
                self.assertIsNotNone(real_git, "Git is required for actual checkout fixtures")

                def git(*arguments, directory):
                    return subprocess.run(
                        [real_git, "-C", str(directory), "-c", "core.hooksPath=/dev/null",
                         "-c", "commit.gpgsign=false", "-c", "user.name=CMS fixture",
                         "-c", "user.email=cms-fixture@example.invalid", *arguments],
                        check=True, capture_output=True, text=True, timeout=10,
                    )

                repository = fixture if checkout == "own" else temporary_root / "enclosing checkout"
                repository.mkdir()
                git("init", "--quiet", directory=repository)
                git("commit", "--quiet", "--allow-empty", "-m", "Isolated verifier fixture",
                    directory=repository)
                if checkout == "enclosing":
                    fixture = repository / "copied source"
                elif checkout == "worktree":
                    git("worktree", "add", "--quiet", "--detach", str(fixture), "HEAD",
                        directory=repository)
                    self.assertTrue((fixture / ".git").is_file(), "linked-worktree control was not created")
                else:
                    self.assertEqual(checkout, "own")
            for directory in (
                "scripts",
                "bin",
                "backend/node_modules",
                "backend/src/cljs/knoxx/backend/infra",
                "backend/test/cljs/knoxx/backend",
            ):
                (fixture / directory).mkdir(parents=True, exist_ok=True)
            (fixture / "backend/src/cljs/knoxx/backend/infra/cms_store.cljs").touch()
            (fixture / "backend/test/cljs/knoxx/backend/cms_source_locale_review_test.cljs").touch()
            # Execute the checked-in script unchanged, with its checkout and
            # preconditions rooted in an isolated fixture through BASH_SOURCE.
            script = fixture / "scripts" / script_name
            script.symlink_to(ROOT / "scripts" / script_name)
            stubs = {
                "git": 'case "$*" in\n*--show-toplevel)\n' + (
                    git_root_body if git_root_body is not None else
                    'printf "%s\\n" "$KNOXX_VERIFIER_GIT_ROOT"\n') + ';;\n*)\n' + git_body + ';;\nesac\n',
                "mktemp": 'printf "fixture allocation called\\n" > "$KNOXX_VERIFIER_MKTEMP_MARKER"\nexec "$KNOXX_VERIFIER_REAL_MKTEMP" "$@"\n',
                "node": 'printf "helper called\\n" > "$KNOXX_VERIFIER_NODE_MARKER"\nexit 7\n',
                "pnpm": 'printf "pnpm called\\n" > "$KNOXX_VERIFIER_PNPM_MARKER"\nexit 99\n',
                "clojure": "exit 0\n",
            }
            if checkout:
                del stubs["git"]
            for name, body in stubs.items():
                executable = fixture / "bin" / name
                executable.write_text("#!/bin/sh\n" + body)
                executable.chmod(0o755)
            node_marker = fixture / "node-started"
            pnpm_marker = fixture / "pnpm-started"
            environment = os.environ.copy()
            environment.update({
                "PATH": str(fixture / "bin") + os.pathsep + environment.get("PATH", os.defpath),
                "KNOXX_VERIFIER_NODE_MARKER": str(node_marker),
                "KNOXX_VERIFIER_PNPM_MARKER": str(pnpm_marker),
                "KNOXX_VERIFIER_GIT_ROOT": str(fixture),
                "KNOXX_VERIFIER_MKTEMP_MARKER": str(fixture / "mktemp-started"),
                "KNOXX_VERIFIER_REAL_MKTEMP": shutil.which("mktemp"),
                "TMPDIR": str(fixture),
            })
            if symlink_entry:
                alias = temporary_root / "linked source path"
                alias.symlink_to(fixture, target_is_directory=True)
                script = alias / "scripts" / script_name
            result = subprocess.run(
                ["bash", str(script)],
                cwd=fixture,
                env=environment,
                capture_output=True,
                text=True,
                timeout=10,
            )
            yield result, node_marker, pnpm_marker

    def assert_helper_failure_stops_verifier(self, script_name):
        with self.verifier_fixture(script_name) as (result, node_marker, pnpm_marker):
            detail = result.stdout + result.stderr
            self.assertTrue(node_marker.is_file(), "Node helper was not reached:\n" + detail)
            self.assertEqual(7, result.returncode, detail)
            self.assertFalse(pnpm_marker.exists(), "pnpm started after the helper failed:\n" + detail)
            self.assertIn("at 0123456789abcdef0123456789abcdef01234567", result.stdout)
            self.assertNotIn("PASS ", result.stdout)

    def test_unverified_git_identity_stops_both_verifiers_before_helper_or_compile(self):
        for script_name in ("verify-cms-history.sh", "verify-cms-source-locale-review.sh"):
            for git_body in ('printf "git rejected checkout\\n" >&2\nexit 23\n',
                             "exit 0\n", 'printf "not-a-full-commit\\n"\n'):
                with self.subTest(script=script_name, git=git_body):
                    with self.verifier_fixture(script_name, git_body) as (result, node_marker, pnpm_marker):
                        detail = result.stdout + result.stderr
                        self.assertNotEqual(0, result.returncode, detail)
                        self.assertFalse(node_marker.exists(), "helper started without a verified checkout:\n" + detail)
                        self.assertFalse(pnpm_marker.exists(), "compiler started without a verified checkout:\n" + detail)
                        self.assertFalse((node_marker.parent / "mktemp-started").exists(),
                                         "fixture allocated without a verified checkout:\n" + detail)
                        self.assertNotIn("Verifying checkout", result.stdout)
                        self.assertNotIn("PASS ", result.stdout)

    def test_cms_history_preserves_helper_failure(self):
        self.assert_helper_failure_stops_verifier("verify-cms-history.sh")

    def test_cms_source_locale_review_preserves_helper_failure(self):
        self.assert_helper_failure_stops_verifier("verify-cms-source-locale-review.sh")

    def test_enclosing_checkout_cannot_supply_an_unrelated_commit(self):
        for script_name in ("verify-cms-history.sh", "verify-cms-source-locale-review.sh"):
            with self.subTest(script=script_name):
                with self.verifier_fixture(script_name, checkout="enclosing") as (result, node_marker, pnpm_marker):
                    detail = result.stdout + result.stderr
                    self.assertNotEqual(0, result.returncode, detail)
                    self.assertFalse(node_marker.exists(), "helper started under an enclosing identity:\n" + detail)
                    self.assertFalse(pnpm_marker.exists(), "compiler started under an enclosing identity:\n" + detail)
                    self.assertFalse((node_marker.parent / "mktemp-started").exists(),
                                     "fixture allocated under an enclosing identity:\n" + detail)
                    self.assertNotIn("Verifying checkout", result.stdout)
                    self.assertNotIn("PASS ", result.stdout)

    def test_own_checkouts_and_linked_worktrees_admit_physical_source_paths(self):
        for script_name in ("verify-cms-history.sh", "verify-cms-source-locale-review.sh"):
            for checkout in ("own", "worktree"):
                for symlink_entry in (False, True):
                    with self.subTest(script=script_name, checkout=checkout, symlink=symlink_entry):
                        with self.verifier_fixture(script_name, checkout=checkout, symlink_entry=symlink_entry) as (result, node_marker, pnpm_marker):
                            detail = result.stdout + result.stderr
                            self.assertEqual(7, result.returncode, detail)
                            self.assertTrue(node_marker.is_file(), "own checkout did not reach the helper sentinel:\n" + detail)
                            self.assertFalse(pnpm_marker.exists(), "compiler started after helper failure:\n" + detail)
                            self.assertIn("Verifying checkout " + str(node_marker.parent.resolve()) + " at ", result.stdout)
                            self.assertRegex(result.stdout, r" at [0-9a-f]{40}\n")
                            self.assertNotIn("PASS ", result.stdout)

    def test_unresolved_git_root_stops_before_fixture_helper_or_compile(self):
        for script_name in ("verify-cms-history.sh", "verify-cms-source-locale-review.sh"):
            for root_body in ('printf "git root unavailable\\n" >&2\nexit 23\n', "exit 0\n"):
                with self.subTest(script=script_name, root=root_body):
                    with self.verifier_fixture(script_name, git_root_body=root_body) as (result, node_marker, pnpm_marker):
                        detail = result.stdout + result.stderr
                        self.assertNotEqual(0, result.returncode, detail)
                        self.assertFalse((node_marker.parent / "mktemp-started").exists(), detail)
                        self.assertFalse(node_marker.exists(), detail)
                        self.assertFalse(pnpm_marker.exists(), detail)
                        self.assertNotIn("Verifying checkout", result.stdout)
                        self.assertNotIn("PASS ", result.stdout)


class LegacyReviewPreflightProcessTest(unittest.TestCase):
    """Actual shell phase; every HTTP/Mongo result here is explicitly synthetic."""

    @contextmanager
    def legacy_phase(self, mode="closed", cleanup_failure=False, project="real-synthetic-project"):
        with tempfile.TemporaryDirectory(prefix="knoxx-legacy-phase-synthetic-") as temporary:
            root = Path(temporary)
            checkout = root / "checkout"
            for rel in ["scripts/lib", "backend/node_modules/.bin", "contracts", "bin", "scratch", "durable"]:
                (checkout / rel).mkdir(parents=True, exist_ok=True)
            helper = checkout / "scripts/lib/translation-split-review-fixture.sh"
            helper.symlink_to(ROOT / "scripts/lib/translation-split-review-fixture.sh")
            for args in [["init", "--quiet"], ["-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                                                   "-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty", "-m", "Synthetic checkout"]]:
                subprocess.run(["git", "-C", str(checkout), *args], check=True, capture_output=True, timeout=10)
            commit = subprocess.check_output(["git", "-C", str(checkout), "rev-parse", "HEAD"], text=True).strip()
            stub = checkout / "bin/synthetic-boundary"
            stub.write_text('''#!/usr/bin/env python3
import json, os, sys
from pathlib import Path
from urllib.parse import urlsplit, unquote
p=Path(os.environ['SYNTHETIC_STATE']); s=json.loads(p.read_text())
def save(): p.write_text(json.dumps(s))
def event(value):
 with Path(os.environ['SYNTHETIC_EVENTS']).open('a') as f: f.write(json.dumps(value)+'\\n')
def identity():
 return dict(run_id=os.environ['VERIFY_RUN_ID'],document_id=os.environ['VERIFY_LEGACY_DOCUMENT_ID'],
 segment_id='64b000000000000000000001',org_id=os.environ['VERIFY_ORG_ID'],project=os.environ['VERIFY_PROJECT'],
 garden_id=None,target_lang='es',ownership_file=os.environ['VERIFY_LEGACY_OWNERSHIP_FILE'])
if Path(sys.argv[0]).name=='nbb':
 action=sys.argv[-1]; event(dict(kind='helper',action=action)); i=identity(); owned=Path(i['ownership_file'])
 if action=='legacy-seed':
  if owned.exists(): sys.exit(8)
  owned.write_text(json.dumps(dict(i,version=1,source_commit=os.environ['VERIFY_SOURCE_COMMIT']))); owned.chmod(0o600)
  s['present']=True; save(); result=dict(i,action='legacy-seeded')
 elif action=='legacy-read':
  if s['mode']=='missing-readback': sys.exit(9)
  result=dict(i,action='legacy-read')
 elif action=='legacy-cleanup':
  if s['cleanup_failure']: print('DO_NOT_LOG_SYNTHETIC_SECRET',file=sys.stderr);sys.exit(9)
  s['present']=False;save(); print(json.dumps(dict(i,action='legacy-cleaned',removed_segment_count=1,removed_label_count=0,remaining_segment_count=0,remaining_label_count=0)));sys.exit(0)
 else: sys.exit(11)
 print(json.dumps(dict(result,segment_count=1,label_count=0,label_version_counter=s['counter'],status='pending',source_text_matches=True,translated_text_matches=True)));sys.exit(0)
args=sys.argv[1:]; method=args[args.index('-X')+1] if '-X' in args else 'GET';url=args[-1]; path=urlsplit(url).path
headers=[args[n+1] for n,x in enumerate(args[:-1]) if x in ['-H','--header']]
auth=any(x.lower().startswith('x-api-key: synthetic-key') for x in headers)
data=args[args.index('-d')+1] if '-d' in args else None
event(dict(kind='http',method=method,url=url,auth=auth,headers=headers,args=args,payload=json.loads(data) if data else None))
if not auth:
 status=200 if s['mode']=='detail-open' and method=='GET' else int(s['mode'].split('-')[1]) if s['mode'].startswith('status-') else 401
 if s['mode']=='counter-change':s['counter']+=1;save()
 body={'error':'missing_error' if s['mode']=='missing-error' else 'no_session','unexpected_content':'DO_NOT_LOG_SYNTHETIC_SECRET'}
elif path=='/api/translations/documents':status=200;body={'documents':[] if not s['present'] else [{'document_id':os.environ['VERIFY_LEGACY_DOCUMENT_ID']}]}
else:
 i=identity();segment=dict(id=i['segment_id'],document_id=i['document_id'],org_id=i['org_id'],project=i['project'],garden_id=None,target_lang='es',status='pending',labels=[],source_text='synthetic source',translated_text='synthetic translation')
 if s['mode']=='wrong-live-database':segment['id']='64b000000000000000000099'
 status=200;body=segment if '/segments/' in path else {'document':{'id':i['document_id']},'segments':[segment],'summary':{'total_segments':1,'pending':1,'approved':0,'rejected':0,'in_review':0,'overall_status':'pending'}}
print(json.dumps(body));print(status)
''')
            stub.chmod(0o755)
            (checkout / "bin/curl").symlink_to(stub)
            (checkout / "backend/node_modules/.bin/nbb").symlink_to(stub)
            clojure = checkout / "bin/clojure"
            clojure.write_text("#!/bin/sh\nexit 0\n")
            clojure.chmod(0o755)
            state = root / "synthetic-store.json"
            state.write_text(json.dumps(dict(mode=mode, cleanup_failure=cleanup_failure, present=False, counter=0)))
            events = root / "synthetic-events.jsonl"
            events.touch()
            ownership = checkout / "durable/ownership.json"
            wrapper = root / "phase.sh"
            wrapper.write_text('''#!/usr/bin/env bash
set -uo pipefail
. "$REPO_ROOT/scripts/lib/translation-split-review-fixture.sh"
FAIL_COUNT=0
pass() { printf 'PASS %s\\n' "$1"; }
fail() { FAIL_COUNT=$((FAIL_COUNT+1)); printf 'FAIL %s\\n' "$1"; }
translation_fixture_legacy_auth_http() { translation_fixture_legacy_http "$1" "$2" auth; }
cleanup() { code=$?; trap - EXIT; translation_fixture_legacy_cleanup || { [ "$code" -ne 0 ] || code=1; }; exit "$code"; }
trap cleanup EXIT
translation_fixture_legacy_preflight || exit 1
printf 'RESOURCE_NAVIGATION_ALLOWED\\n'
''')
            env = os.environ.copy()
            env.update(dict(PATH=str(checkout / "bin")+os.pathsep+env.get("PATH",os.defpath),
                            REPO_ROOT=str(checkout), CONTRACTS_DIR=str(checkout/"contracts"),
                            FIXTURE_DIR=str(checkout/"contracts/_verify_translation_split_review"),
                            RUN_ID="causal-owned-run", VERIFY_RUN_ID="causal-owned-run", VERIFY_ORG_ID="synthetic-org",
                            VERIFY_PROJECT=project, VERIFY_SOURCE_COMMIT=commit,
                            VERIFY_LEGACY_DOCUMENT_ID="knoxx.verifysplit.rcausal-owned-run/legacy-auth",
                            VERIFY_LEGACY_GARDEN_ID="__NONE__", VERIFY_LEGACY_OWNERSHIP_FILE=str(ownership),
                            VERIFY_TMP_DIR=str(checkout/"scratch"), KNOXX_PUBLICATION_CONTENT_ROOT=str(checkout/"durable"),
                            BASE_URL="http://127.0.0.1:8000", API_KEY="synthetic-key", LOOPBACK_HTTP="1",
                            SYNTHETIC_STATE=str(state), SYNTHETIC_EVENTS=str(events)))
            result = subprocess.run(["bash",str(wrapper)],env=env,cwd=checkout,capture_output=True,text=True,timeout=15)
            yield result, [json.loads(line) for line in events.read_text().splitlines()], json.loads(state.read_text()), ownership

    def test_closed_phase_has_exact_three_isolated_probes_before_resource_navigation(self):
        with self.legacy_phase() as (result,events,state,ownership):
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)
            anonymous=[e for e in events if e['kind']=='http' and not e['auth']]
            self.assertEqual(['GET','POST','POST'],[e['method'] for e in anonymous])
            self.assertEqual(anonymous[0]['url'],next(e['url'] for e in events if e['kind']=='http' and e['auth']))
            for e in anonymous:
                self.assertIn('--cookie',e['args']);self.assertEqual('',e['args'][e['args'].index('--cookie')+1])
                self.assertFalse(any(h.split(':',1)[-1].strip() for h in e['headers'] if h.lower().startswith(('authorization:','cookie:','x-api-key:','x-knoxx-'))))
                if e['method']=='POST':
                    self.assertEqual('reject',e['payload']['overall']);self.assertNotIn('corrected_text',e['payload'])
            self.assertIn('RESOURCE_NAVIGATION_ALLOWED',result.stdout)
            self.assertFalse(state['present']);self.assertFalse(ownership.exists())

    def test_detail_open_fails_without_logging_content_and_still_cleans(self):
        with self.legacy_phase('detail-open') as (result,events,state,ownership):
            self.assertNotEqual(0,result.returncode)
            self.assertNotIn('RESOURCE_NAVIGATION_ALLOWED',result.stdout)
            self.assertNotIn('DO_NOT_LOG_SYNTHETIC_SECRET',result.stdout+result.stderr)
            self.assertIn('legacy detail GET',result.stdout)
            self.assertFalse(state['present']);self.assertFalse(ownership.exists())
            self.assertEqual(1,sum(e['kind']=='helper' and e['action']=='legacy-cleanup' for e in events))

    def test_wrong_auth_status_error_counter_and_live_identity_refuse(self):
        for mode in ['status-200','status-400','status-404','status-503','missing-error','counter-change','wrong-live-database','missing-readback']:
            with self.subTest(mode=mode),self.legacy_phase(mode) as (result,events,state,ownership):
                self.assertNotEqual(0,result.returncode,result.stdout+result.stderr)
                self.assertTrue(any(e['kind']=='helper' and e['action']=='legacy-seed' for e in events),
                                'negative control never entered the owned phase: '+result.stdout+result.stderr)
                self.assertNotIn('RESOURCE_NAVIGATION_ALLOWED',result.stdout)
                self.assertNotIn('DO_NOT_LOG_SYNTHETIC_SECRET',result.stdout+result.stderr)
                self.assertFalse(state['present']);self.assertFalse(ownership.exists())

    def test_cleanup_failure_preserves_first_probe_failure_and_durable_ownership(self):
        with self.legacy_phase('detail-open',cleanup_failure=True) as (result,events,state,ownership):
            self.assertNotEqual(0,result.returncode)
            self.assertIn('legacy detail GET',result.stdout)
            self.assertIn('legacy cleanup',result.stdout)
            self.assertTrue(ownership.exists());self.assertTrue(state['present'])
            self.assertNotIn('DO_NOT_LOG_SYNTHETIC_SECRET',result.stdout+result.stderr)

    def test_missing_live_project_refuses_before_any_seed_or_http(self):
        with self.legacy_phase(project='') as (result,events,state,ownership):
            self.assertNotEqual(0,result.returncode)
            self.assertIn('legacy preflight requires the live nonblank project',result.stdout)
            self.assertEqual([],events)
            self.assertFalse(ownership.exists())


if __name__ == "__main__":
    unittest.main()
