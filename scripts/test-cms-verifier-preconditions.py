#!/usr/bin/env python3
"""CMS verifiers must fail before testing an unidentified checkout."""
from contextlib import contextmanager
import json
import hashlib
import os
from pathlib import Path
import shutil
import signal
import subprocess
import tempfile
import time
import unittest
from urllib.parse import parse_qs, urlsplit

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
    def legacy_phase(self, mode="closed", cleanup_failure=False, project="real-synthetic-project",
                     actual_verifier=False, recover=False):
        with tempfile.TemporaryDirectory(prefix="knoxx-legacy-phase-synthetic-") as temporary:
            root = Path(temporary)
            checkout = root / "checkout"
            (root / "durable").mkdir()
            for rel in ["scripts/lib", "backend/node_modules/.bin", "contracts", "bin", "scratch", "durable"]:
                (checkout / rel).mkdir(parents=True, exist_ok=True)
            helper = checkout / "scripts/lib/translation-split-review-fixture.sh"
            helper.symlink_to(ROOT / "scripts/lib/translation-split-review-fixture.sh")
            for name in ["credential-transport.sh", "credential-transport.mjs"]:
                (checkout / "scripts/lib" / name).symlink_to(ROOT / "scripts/lib" / name)
            (checkout / "scripts/verify-translation-split-review.sh").symlink_to(ROOT / "scripts/verify-translation-split-review.sh")
            for args in [["init", "--quiet"], ["-c", "user.name=Fixture", "-c", "user.email=fixture@example.invalid",
                                                   "-c", "commit.gpgsign=false", "commit", "--quiet", "--allow-empty", "-m", "Synthetic checkout"]]:
                subprocess.run(["git", "-C", str(checkout), "-c", "core.hooksPath=/dev/null",
                                "-c", "commit.gpgsign=false", *args], check=True, capture_output=True, timeout=10)
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
 if action=='seed': print('RESOURCE_SEED_SENTINEL',file=sys.stderr);sys.exit(77)
 if action=='legacy-seed':
  if owned.exists(): sys.exit(8)
  owned.write_text(json.dumps(dict(i,version=1,source_commit=os.environ['VERIFY_SOURCE_COMMIT']))); owned.chmod(0o600)
  s['present']=True; save(); result=dict(i,action='legacy-seeded')
 elif action=='legacy-read':
  if s['mode']=='missing-readback': sys.exit(9)
  result=dict(i,action='legacy-read')
 elif action=='legacy-cleanup':
  if s['cleanup_failure']: print(json.dumps(dict(error='translation_verification_failed',code='verification-owned-delete-uncertain',primary_thrown=True,primary_error_present=True,unknown_message='DO_NOT_LOG_SYNTHETIC_SECRET')),file=sys.stderr);sys.exit(9)
  s['present']=False;save(); print(json.dumps(dict(i,action='legacy-cleaned',removed_segment_count=1,removed_label_count=0,remaining_segment_count=0,remaining_label_count=0)));sys.exit(0)
 else: sys.exit(11)
 print(json.dumps(dict(result,segment_count=1,label_count=0,label_version_counter=s['counter'],status='pending',source_text_matches=True,translated_text_matches=True)));sys.exit(0)
args=sys.argv[1:]; method=args[args.index('-X')+1] if '-X' in args else 'GET';url=args[-1]; path=urlsplit(url).path
headers=[args[n+1] for n,x in enumerate(args[:-1]) if x in ['-H','--header']]
auth=any(x.lower().startswith('x-api-key: synthetic-key') for x in headers)
data=args[args.index('-d')+1] if '-d' in args else None
event(dict(kind='http',method=method,url=url,auth=auth,headers=headers,args=args,payload=json.loads(data) if data else None))
if path=='/health':status=200;body={'synthetic':True}
elif path=='/api/publications/documents':status=200;body={'documents':[{'document':{'id':'knoxx.verifysplit.rcausal-owned-run/doc01'}}]}
elif auth and path=='/api/publications/translations/reviews' and method=='GET':
 status=200;body={'project':os.environ['VERIFY_PROJECT'],'reviews':[{'publication':'knoxx.verifysplit.rcausal-owned-run/doc%02d-es'%i,'candidate_present':False,'work_state':'missing'} for i in range(1,19)]}
elif auth and path.startswith('/api/publications/'):
 status=409 if data and json.loads(data)=={'candidate_set_id':'not-present','split_id':'not-present','status':'approved'} else 400;body={'error':'synthetic_contract_refusal'}
elif not auth:
 status=200 if s['mode']=='detail-open' and method=='GET' else int(s['mode'].split('-')[1]) if s['mode'].startswith('status-') else 401
 if s['mode']=='counter-change':s['counter']+=1;save()
 body={'detail':'Not authenticated','error_code':'missing_error' if s['mode']=='missing-error' else 'no_session','unexpected_content':'DO_NOT_LOG_SYNTHETIC_SECRET'}
 if s['mode']=='error-only':body={'detail':'Not authenticated','error':'no_session','unexpected_content':'DO_NOT_LOG_SYNTHETIC_SECRET'}
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
            ownership = root / "durable/ownership.json"
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
                            VERIFY_TMP_DIR=str(checkout/"scratch"), KNOXX_PUBLICATION_CONTENT_ROOT=str(root/"durable"),
                            BASE_URL="http://127.0.0.1:8000", API_KEY="synthetic-key", KNOXX_API_KEY="synthetic-key", LOOPBACK_HTTP="1",
                            SYNTHETIC_STATE=str(state), SYNTHETIC_EVENTS=str(events)))
            command = checkout / "scripts/verify-translation-split-review.sh" if actual_verifier else wrapper
            result = subprocess.run(["bash",str(command)],env=env,cwd=checkout,capture_output=True,text=True,timeout=15)
            if recover:
                first_failure = Path(str(ownership)+".failure.json")
                result.first_failure_bytes = first_failure.read_bytes()
                current=json.loads(state.read_text());current['cleanup_failure']=False;state.write_text(json.dumps(current))
                recovery = root / "recover.sh"
                recovery.write_text(wrapper.read_text().replace('translation_fixture_legacy_preflight || exit 1',
                    'translation_fixture_legacy_resume_cleanup "$VERIFY_LEGACY_OWNERSHIP_FILE" || exit 1'))
                result.recovery = subprocess.run(["bash",str(recovery)],env=env,cwd=checkout,capture_output=True,text=True,timeout=15)
                result.first_failure_after_recovery = first_failure.read_bytes()
            yield result, [json.loads(line) for line in events.read_text().splitlines()], json.loads(state.read_text()), ownership

    def test_closed_phase_has_exact_three_isolated_probes_before_resource_navigation(self):
        with self.legacy_phase(project='real scope + β') as (result,events,state,ownership):
            self.assertEqual(0,result.returncode,result.stdout+result.stderr)
            anonymous=[e for e in events if e['kind']=='http' and not e['auth']]
            self.assertEqual(['GET','POST','POST'],[e['method'] for e in anonymous])
            self.assertEqual(anonymous[0]['url'],next(e['url'] for e in events if e['kind']=='http' and e['auth']))
            self.assertEqual(['real scope + β'],parse_qs(urlsplit(anonymous[0]['url']).query)['project'])
            self.assertIn('%2Flegacy-auth/es?',anonymous[0]['url'])
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
        for mode in ['status-200','status-302','status-307','status-400','status-404','status-503','missing-error','error-only','counter-change','wrong-live-database','missing-readback']:
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
            outcomes=[json.loads(p.read_text()) for p in ownership.parent.glob('ownership.json.action-legacy-cleanup-*.json')]
            self.assertEqual([9],[item['native_exit'] for item in outcomes])
            self.assertEqual(['verification-owned-delete-uncertain'],[item['diagnostic']['code'] for item in outcomes])
            self.assertNotIn('DO_NOT_LOG_SYNTHETIC_SECRET',json.dumps(outcomes))

    def test_missing_live_project_refuses_before_any_seed_or_http(self):
        with self.legacy_phase(project='') as (result,events,state,ownership):
            self.assertNotEqual(0,result.returncode)
            self.assertIn('legacy preflight requires the live nonblank project',result.stdout)
            self.assertEqual([],events)
            self.assertFalse(ownership.exists())

    def test_actual_verifier_orders_legacy_cleanup_before_existing_resource_seed(self):
        with self.legacy_phase(actual_verifier=True) as (result,events,state,ownership):
            self.assertIn('production-law fixture helper could not seed',result.stderr)
            self.assertIn('one independent anonymous detail GET and two independent legacy POST probes completed',result.stdout)
            actions=[e['action'] for e in events if e['kind']=='helper']
            self.assertLess(actions.index('legacy-cleanup'),actions.index('seed'))
            self.assertEqual(1,actions.count('legacy-seed'));self.assertEqual(1,actions.count('legacy-cleanup'))
            self.assertFalse(state['present']);self.assertFalse(ownership.exists())
            self.assertNotIn('legacy auth preflight failed',result.stderr)

    def test_fresh_shell_reentry_retires_only_after_absence_without_erasing_original_failure(self):
        with self.legacy_phase('detail-open',cleanup_failure=True,recover=True) as (result,events,state,ownership):
            self.assertNotEqual(0,result.returncode)
            self.assertEqual(0,result.recovery.returncode,result.recovery.stdout+result.recovery.stderr)
            self.assertEqual(result.first_failure_bytes,result.first_failure_after_recovery)
            self.assertFalse(ownership.exists());self.assertFalse(state['present'])
            self.assertEqual(1,sum(e['kind']=='helper' and e['action']=='legacy-seed' for e in events))
            self.assertEqual(2,sum(e['kind']=='helper' and e['action']=='legacy-cleanup' for e in events))


class LegacyActualAdapterInterruptionTest(unittest.TestCase):
    """Actual NBB cleanup adapter with inert file storage, never a live Mongo DB."""

    def test_term_after_actual_owned_label_delete_then_fresh_process_reentry(self):
        nbb = ROOT / "backend/node_modules/.bin/nbb"
        self.assertTrue(nbb.is_file(), "Actual adapter process fixture requires installed backend NBB")
        driver = ROOT / "backend/test/cljs/knoxx/backend/extern/translation_review_verification_child.cljs"
        self.assertTrue(driver.is_file(), "Actual adapter process driver is required")
        evidence_parent = os.environ.get("KNOXX_FIXTURE_PROCESS_EVIDENCE_DIR")
        if evidence_parent:
            parent = Path(evidence_parent)
            self.assertTrue(parent.is_absolute(), "Actual process evidence directory must be absolute")
            parent.mkdir(mode=0o700, parents=True, exist_ok=True)
        directory = Path(tempfile.mkdtemp(prefix="knoxx-actual-adapter-synthetic-", dir=evidence_parent))
        directory.chmod(0o700)
        # Persist synthetic request/store/ownership and every raw native stream on
        # success AND failure. No TemporaryDirectory may destroy first-cause data.
        print("Synthetic actual-adapter evidence directory: " + str(directory))

        def retain(name, data):
            descriptor = os.open(directory / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(descriptor, "wb") as output:
                output.write(data)
                output.flush()
                os.fsync(output.fileno())

        def retain_outcome(stage, child, **extra):
            metadata = dict(stage=stage, returncode=child.returncode, pid=child.pid,
                            timed_out=getattr(child, "fixture_timed_out", False), **extra)
            for stream in ("stdout", "stderr"):
                path = directory / (stage + "." + stream + ".bin")
                raw = path.read_bytes()
                metadata[stream] = dict(path=str(path), bytes=len(raw), sha256=hashlib.sha256(raw).hexdigest())
            retain(stage + ".outcome.json", (json.dumps(metadata, indent=2) + "\n").encode())

        @contextmanager
        def retained_child(stage, arguments, cwd):
            # Open raw binary files BEFORE launch. Output remains durable even if
            # marker inspection, assertion or process control subsequently fails.
            stdout_fd = os.open(directory / (stage + ".stdout.bin"), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            stderr_fd = os.open(directory / (stage + ".stderr.bin"), os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(stdout_fd, "wb") as output, os.fdopen(stderr_fd, "wb") as error_output:
                child = subprocess.Popen(arguments, cwd=cwd, env=environment,
                                         stdout=output, stderr=error_output, start_new_session=True)
                primary = None
                try:
                    yield child
                except BaseException as error:
                    primary = error
                    details = dict(stage=stage, primary_type=type(error).__name__)
                    for field in ("lineno", "colno", "pos", "errno"):
                        value = getattr(error, field, None)
                        if isinstance(value, int): details[field] = value
                    try: retain(stage + ".primary-failure.json", (json.dumps(details) + "\n").encode())
                    except BaseException:
                        print("Synthetic primary retention failed: " + str(directory))
                    raise
                finally:
                    secondary = None
                    try:
                        if child.poll() is None: os.killpg(child.pid, signal.SIGKILL)
                        child.wait(timeout=5)
                    except BaseException as error:
                        secondary = type(error).__name__
                    try:
                        output.flush(); error_output.flush()
                        os.fsync(output.fileno()); os.fsync(error_output.fileno())
                        retain_outcome(stage, child, cleanup_error_type=secondary)
                    except BaseException:
                        if primary is None: raise
                        print("Synthetic process evidence retention failed: " + str(directory))
                    if secondary is not None and primary is None:
                        self.fail("Synthetic child cleanup failed; retained descriptors: " + str(directory))

        def run_retained(stage, arguments, timeout, cwd=ROOT / "backend"):
            with retained_child(stage, arguments, cwd) as child:
                try: child.wait(timeout=timeout)
                except subprocess.TimeoutExpired: child.fixture_timed_out = True
            if getattr(child,"fixture_timed_out",False):
                self.fail("Synthetic child timed out; retained descriptors: "+str(directory))
            return subprocess.CompletedProcess(arguments, child.returncode,
                       (directory / (stage + ".stdout.bin")).read_bytes(),
                       (directory / (stage + ".stderr.bin")).read_bytes())

        def strict_json(stage, raw):
            try: return json.loads(raw)
            except (json.JSONDecodeError, UnicodeDecodeError) as error:
                details = dict(stage=stage, primary_type=type(error).__name__)
                for field in ("lineno", "colno", "pos", "start", "end"):
                    value = getattr(error, field, None)
                    if isinstance(value, int): details[field] = value
                retain(stage + ".parse-failure.json", (json.dumps(details) + "\n").encode())
                # Never print, strip, select the last line, or substitute unknown
                # stderr. Original bytes + exception category/location remain.
                self.fail("Synthetic child full-output JSON refused; retained descriptors: " + str(directory))

        with self.subTest(evidence_directory=str(directory)):
            dependencies = directory / "cljs-deps"
            dependencies.mkdir()
            environment = os.environ.copy()
            environment.update(REPO_ROOT=str(ROOT), CONTRACTS_DIR=str(ROOT/"contracts"),
                               FIXTURE_DIR=str(ROOT/"contracts/_verify_translation_split_review_process"),
                               RUN_ID="owned-process-run")
            classpath = run_retained("classpath",
                ["bash", "-c", '. "$REPO_ROOT/scripts/lib/translation-split-review-fixture.sh"; '
                 'translation_fixture_dependency_classpath "$1"', "owned-classpath", str(dependencies)], 45, cwd=ROOT)
            self.assertEqual(0,classpath.returncode, "Accepted fixture classpath refused; retained descriptors: "+str(directory))
            try: cp = classpath.stdout.decode("utf-8") + os.pathsep + str(ROOT/"backend/test/cljs")
            except UnicodeDecodeError:
                self.fail("Accepted classpath is not UTF-8; retained descriptors: " + str(directory))
            ownership = directory / "ownership.json"
            store = directory / "synthetic-store.json"
            marker = directory / "after-label-delete.json"
            request_file = directory / "request.json"
            uri = "mongodb://inert.invalid"
            owned_id,foreign_id = "64b000000000000000000001","64b000000000000000000099"
            scope = dict(document_id="owned-legacy-document",org_id="owned-org",project="owned-project",
                         garden_id=None,target_lang="es")
            record = dict(scope,version=1,run_id="owned-process-run",source_commit="a"*40,
                          mongo_uri_sha256=hashlib.sha256(uri.encode()).hexdigest(),mongo_db="owned-db",segment_id=owned_id)
            ownership.write_text(json.dumps(record));ownership.chmod(0o600)
            ownership_bytes = ownership.read_bytes()
            retain("original-ownership.bin", ownership_bytes)
            owned = dict(scope,_id=owned_id,status="pending",label_version_counter=1,source_lang="en",segment_index=0,
                         source_text="Owned legacy source for authorization verification.",
                         translated_text="Traducción sintética de verificación de autorización.")
            foreign = dict(owned,_id=foreign_id,document_id="foreign-document",org_id="foreign-org",project="foreign-project")
            owned_label = dict(_id="64b000000000000000000002",segment_id=owned_id,org_id="owned-org",project="owned-project")
            foreign_labels = [dict(_id="64b000000000000000000003",segment_id=foreign_id,org_id="foreign-org",project="foreign-project"),
                              dict(_id="64b000000000000000000004",segment_id=owned_id,org_id="foreign-org",project="owned-project")]
            initial = dict(version=1,collections=dict(translation_segments=[owned,foreign],
                           translation_labels=[owned_label,*foreign_labels]),operations=[])
            store.write_text(json.dumps(initial));store.chmod(0o600)
            request = dict(action="legacy-cleanup",settings={"mongo-uri":uri,"mongo-db":"owned-db",
                           "source-commit":"a"*40,"ownership-file":str(ownership)},
                           scope={"run-id":"owned-process-run","org-id":"owned-org","project":"owned-project",
                                  "document-id":"owned-legacy-document","garden-id":None,"target-locale":"es"})
            request_file.write_text(json.dumps(request));request_file.chmod(0o600)
            command = [str(nbb),"--classpath",cp,str(driver),"__owned-verification-child__",
                       str(request_file),str(store),str(marker)]
            malformed=json.loads(json.dumps(initial))
            malformed['collections']['translation_segments'][0]['_id']=None
            store.write_text(json.dumps(malformed));malformed_bytes=store.read_bytes()
            retain("malformed-store-input.bin", malformed_bytes)
            refused=run_retained("refused", [*command,"finish"], 35)
            refused_state = {}
            for name, path in (("store", store), ("ownership", ownership), ("marker", marker)):
                refused_state[name] = dict(path=str(path), present=path.exists())
                if path.exists():
                    raw=path.read_bytes();retain("refused-after-"+name+".bin",raw)
                    refused_state[name].update(bytes=len(raw),sha256=hashlib.sha256(raw).hexdigest())
            retain("refused-owned-state.json",(json.dumps(refused_state,indent=2)+"\n").encode())
            self.assertEqual(1,refused.returncode,"Malformed synthetic ObjectId did not refuse before driver construction")
            self.assertEqual(0,len(refused.stdout),"Malformed input child wrote stdout; retained descriptors: "+str(directory))
            diagnostic=strict_json("refused-stderr",refused.stderr)
            self.assertTrue(isinstance(diagnostic,dict),"Malformed-input diagnostic shape refused; retained descriptors: "+str(directory))
            self.assertTrue(diagnostic.get('error')=='translation_verification_failed', 'Unexpected diagnostic category; retained descriptors: '+str(directory))
            self.assertTrue(diagnostic.get('code')=='translation-verification-contract-invalid', 'Unexpected diagnostic code; retained descriptors: '+str(directory))
            self.assertTrue(diagnostic.get('contract')==':knoxx.backend.law.translation-review-verification-fixture/segment-id', 'Unexpected diagnostic contract; retained descriptors: '+str(directory))
            self.assertFalse(marker.exists())
            self.assertTrue(malformed_bytes==store.read_bytes(), 'Malformed store changed; retained descriptors: '+str(directory))
            self.assertTrue(ownership_bytes==ownership.read_bytes(), 'Ownership changed; retained descriptors: '+str(directory))
            store.write_text(json.dumps(initial))
            with retained_child("terminated", [*command,"pause-after-label-delete"], ROOT / "backend") as child:
                deadline = time.monotonic()+35
                while not marker.exists() and child.poll() is None and time.monotonic()<deadline:
                    time.sleep(0.02)
                checkpoint_reached, checkpoint_alive = marker.exists(), child.poll() is None
                # Snapshot bytes before parsing or assertions, and before recovery
                # can change the persistent synthetic store. No raw content logs.
                for name, path in (("store", store), ("ownership", ownership), ("marker", marker)):
                    if path.exists(): retain("before-TERM-" + name + ".bin", path.read_bytes())
                if checkpoint_reached and checkpoint_alive:
                    os.killpg(child.pid,signal.SIGTERM)
                    try: child.wait(timeout=5)
                    except subprocess.TimeoutExpired: child.fixture_timed_out = True
            # retained_child has written raw output and actual terminal status
            # before any checkpoint/result assertion or JSON interpretation.
            self.assertTrue(checkpoint_reached,"Actual adapter did not reach owned-label-delete; retained descriptors: "+str(directory))
            self.assertTrue(checkpoint_alive,"Actual child exited before causal TERM; retained descriptors: "+str(directory))
            self.assertEqual(-signal.SIGTERM,child.returncode,"TERM outcome refused; retained descriptors: "+str(directory))
            marker_value=strict_json("terminated-marker",(directory/"before-TERM-marker.bin").read_bytes())
            self.assertTrue(dict(phase="after-owned-label-delete",store_file=str(store))==marker_value,
                            "Synchronization marker refused; retained descriptors: "+str(directory))
            partial = strict_json("terminated-store",(directory/"before-TERM-store.bin").read_bytes())
            self.assertTrue([owned,foreign]==partial['collections']['translation_segments'],"Partial segments changed; retained descriptors: "+str(directory))
            self.assertTrue(foreign_labels==partial['collections']['translation_labels'],"Partial labels changed; retained descriptors: "+str(directory))
            self.assertTrue(ownership_bytes==(directory/"before-TERM-ownership.bin").read_bytes(),"Partial ownership changed; retained descriptors: "+str(directory))
            self.assertTrue(ownership_bytes==ownership.read_bytes(), 'Ownership changed; retained descriptors: '+str(directory))
            operations = partial['operations']
            self.assertTrue(any(op[0]=='delete' and op[1]=='translation_labels' for op in operations))
            self.assertFalse(any(op[0]=='delete' and op[1]=='translation_segments' for op in operations))
            recovery = run_retained("recovery", [*command,"finish"], 35)
            self.assertEqual(0,recovery.returncode,"Actual adapter fresh-process recovery refused; retained descriptors: "+str(directory))
            outcome=strict_json("recovery-stdout",recovery.stdout)
            self.assertTrue(outcome.get('action')=='legacy-cleaned', 'Recovery action refused; retained descriptors: '+str(directory))
            self.assertTrue((1,0,0,0)==(outcome['removed_segment_count'],outcome['removed_label_count'],
                                      outcome['remaining_segment_count'],outcome['remaining_label_count']),
                            'Recovery counts refused; retained descriptors: '+str(directory))
            final=strict_json("recovery-store",store.read_bytes())
            self.assertTrue([foreign]==final['collections']['translation_segments'], 'Final segments changed; retained descriptors: '+str(directory))
            self.assertTrue(foreign_labels==final['collections']['translation_labels'], 'Final labels changed; retained descriptors: '+str(directory))
            self.assertTrue(operations==final['operations'][:len(operations)], 'Operation prefix changed; retained descriptors: '+str(directory))
            self.assertTrue(ownership_bytes==ownership.read_bytes(), 'Ownership changed; retained descriptors: '+str(directory))
            proof=dict(kind="synthetic-actual-adapter-TERM-reentry",real_database=False,live_auth=False,
                       malformed_store_returncode=refused.returncode,malformed_store_contract=diagnostic['contract'],
                       malformed_store_and_ownership_unchanged=True,malformed_store_marker_absent=True,
                       terminated_returncode=child.returncode,term_after_actual_label_delete=True,
                       partial_owned_labels=0,partial_owned_segments=1,foreign_rows_and_labels_preserved=True,
                       partial_operations=operations,final_operations=final['operations'],fresh_process_returncode=recovery.returncode,
                       cleanup_result=outcome,ownership_sha256=hashlib.sha256(ownership_bytes).hexdigest(),
                       ownership_retained_after_reentry=True,terminated_stdout_bytes=(directory/"terminated.stdout.bin").stat().st_size,
                       terminated_stderr_bytes=(directory/"terminated.stderr.bin").stat().st_size)
            retain("proof.json", (json.dumps(proof,indent=2)+"\n").encode())
            print("Synthetic actual-adapter TERM/reentry evidence: "+str(directory/"proof.json"))



class CoreReviewPreflightProcessTest(unittest.TestCase):
    """Existing shell helper with only inert transport/snapshot boundaries."""

    def core_phase(self, mode="closed"):
        with tempfile.TemporaryDirectory(prefix="knoxx-core-preflight-") as raw:
            root = Path(raw)
            (root / "contracts").mkdir()
            events = root / "events.jsonl"
            events.touch()
            boundary = root / "boundary.py"
            boundary.write_text("""import json,os,sys
from pathlib import Path
p=Path(os.environ['EVENTS']); mode=os.environ['MODE']; args=sys.argv[1:]
with p.open('a') as f:f.write(json.dumps(args)+'\\n')
if args[0]=='snapshot':
 count=sum(json.loads(x)[0]=='snapshot' for x in p.read_text().splitlines())
 print(json.dumps(dict(action='dispatch-read',run_id='owned',org_id='org',project='owned-project',document_id='knoxx.verifysplit.rowned/doc01',source_revision='source-revision',dispatch_count=0,claim_count=0,batch_count=0,receipt_count=0,dispatch_statuses=[],batch_statuses=[],dispatch_ids=[],batch_ids=[],receipt_ids=[],state_sha256=('b' if mode=='changed-state' and count>1 else 'a')*64)));sys.exit(0)
method,path,authorization=args[:3]
status=200 if authorization=='auth' else 401
body={}
if authorization=='auth' and 'reviews' in path:body=dict(project='owned-project',reviews=[dict(publication='knoxx.verifysplit.rowned/doc01-es')])
if authorization=='auth' and path=='/api/auth/context':body=dict(org={'id':'org'},user={'email':'owned@example.invalid'})
if authorization=='auth' and mode=='unready':status=503
if authorization=='anonymous':
 if 'dispatch' in path and mode.startswith('dispatch-'):status=int(mode.split('-')[1])
 if 'sft' in path and mode=='sft-open':status=200
 body=dict(error_code='missing' if mode=='missing-code' else 'no_session',secret='DO_NOT_PRINT_CORE_SECRET')
print(status);print(json.dumps(body))
""")
            shell = root / "phase.sh"
            shell.write_text("""set -uo pipefail
. "$SOURCE_HELPER"
pass() { printf 'PASS %s\\n' "$1"; }
fail() { printf 'FAIL %s\\n' "$1"; }
translation_fixture_helper() { python3 "$BOUNDARY" snapshot; }
translation_fixture_legacy_http() { python3 "$BOUNDARY" "$@"; }
translation_fixture_legacy_auth_http() { translation_fixture_legacy_http "$1" "$2" auth; }
inventory='{"project":"owned-project","reviews":[{"publication":"knoxx.verifysplit.rowned/doc01-es"}]}'
translation_fixture_core_preflight "$inventory"
""")
            environment = dict(PATH=os.environ['PATH'], SOURCE_HELPER=str(ROOT/'scripts/lib/translation-split-review-fixture.sh'),
                BOUNDARY=str(boundary),EVENTS=str(events),MODE=mode,CONTRACTS_DIR=str(root/'contracts'),
                FIXTURE_DIR=str(root/'contracts/_verify_translation_split_review_core'),RUN_ID='owned',
                VERIFY_ORG_ID='org',VERIFY_PROJECT='owned-project',VERIFY_SOURCE_COMMIT='a'*40,
                BASE_URL='http://127.0.0.1:42591',LOOPBACK_HTTP='1',VERIFY_TMP_DIR=str(root))
            result=subprocess.run(['bash',str(shell)],env=environment,capture_output=True,text=True,timeout=10)
            return result,[json.loads(x) for x in events.read_text().splitlines()]

    def test_exact_core_sft_dispatch_probes_use_owned_publication_and_unchanged_snapshot(self):
        result,events=self.core_phase()
        self.assertEqual(0,result.returncode,result.stdout+result.stderr)
        anonymous=[x for x in events if len(x)>2 and x[2]=='anonymous']
        self.assertEqual(['GET','POST'],[x[0] for x in anonymous])
        self.assertIn('include_corrected=true',anonymous[0][1])
        self.assertEqual({'publication':'knoxx.verifysplit.rowned/doc01-es'},json.loads(anonymous[1][3]))
        self.assertEqual(2,sum(x[0]=='snapshot' for x in events))
        self.assertNotIn('DO_NOT_PRINT_CORE_SECRET',result.stdout+result.stderr)

    def test_wrong_core_auth_status_code_readiness_and_state_fail_closed(self):
        for mode in ['sft-open','dispatch-200','dispatch-400','dispatch-404','dispatch-503','missing-code','changed-state','unready']:
            with self.subTest(mode=mode):
                result,events=self.core_phase(mode)
                self.assertNotEqual(0,result.returncode,result.stdout+result.stderr)
                self.assertNotIn('DO_NOT_PRINT_CORE_SECRET',result.stdout+result.stderr)
                self.assertTrue(events,'control did not reach a real source helper boundary')

if __name__ == "__main__":
    unittest.main()
