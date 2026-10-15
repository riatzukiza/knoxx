#!/usr/bin/env python3
"""Build and drive the disposable checked-runtime HTTP tour, without Mongo or a provider."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import signal
import subprocess
import tempfile
import time
import urllib.error
import urllib.parse
import urllib.request

ROOT = Path(__file__).resolve().parents[1]
SOURCES = (
    "backend/src/cljs/knoxx/backend/law/embedding.cljc",
    "backend/src/cljs/knoxx/backend/shape/embedding.cljc",
    "backend/src/cljs/knoxx/backend/extern/embedding.cljs",
    "backend/src/cljs/knoxx/backend/extern/openplanner_sdk.cljs",
    "backend/src/cljs/knoxx/backend/extern/openplanner_sdk_vectors.cljs",
    "backend/test/js/openplanner_sdk_test_stub.mjs",
    "backend/test/cljs/knoxx/backend/extern/checked_embedding_verifier.cljs",
    "backend/shadow-cljs.edn",
    "scripts/verify-checked-embedding-runtime.py",
)


def digest(file):
    return hashlib.sha256(Path(file).read_bytes()).hexdigest()


def source_proof():
    env = dict(os.environ, GIT_OPTIONAL_LOCKS="0")
    root = subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "--show-toplevel"], env=env, text=True).strip()
    if Path(root).resolve() != ROOT.resolve():
        raise ValueError("Verifier must run from its owning physical checkout")
    return {"checkout": str(ROOT.resolve()),
            "head": subprocess.check_output(["git", "-C", str(ROOT), "rev-parse", "HEAD"], env=env, text=True).strip(),
            "files": {name: digest(ROOT / name) for name in SOURCES}}


def stop(process):
    if process is None or process.poll() is not None:
        return
    os.killpg(process.pid, signal.SIGTERM)
    try:
        process.wait(timeout=5)
    except subprocess.TimeoutExpired:
        os.killpg(process.pid, signal.SIGKILL)
        process.wait(timeout=5)


class NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, request, file, code, message, headers, url):
        raise ValueError("Owned fixture must not redirect")


def request(origin, method, path, token=None):
    parsed = urllib.parse.urlsplit(origin)
    if parsed.scheme != "http" or parsed.hostname != "127.0.0.1" or not parsed.port or parsed.path:
        raise ValueError("Expected an ephemeral loopback fixture origin")
    headers = {} if token is None else {"X-Verify-Token": token}
    opener = urllib.request.build_opener(urllib.request.ProxyHandler({}), NoRedirect())
    try:
        response = opener.open(urllib.request.Request(origin + path, method=method, headers=headers), timeout=15)
        return response.status, json.loads(response.read())
    except urllib.error.HTTPError as error:
        return error.code, json.loads(error.read())


def check(condition, meaning):
    if not condition:
        raise AssertionError(meaning)
    print("PASS " + meaning, flush=True)


def verify(args):
    initial = source_proof()
    node = str(Path(args.node or shutil.which("node") or "").resolve())
    pnpm = args.pnpm or shutil.which("pnpm")
    if not Path(node).is_file() or not pnpm:
        raise ValueError("Existing Node and pnpm are required; this verifier installs nothing")
    version = subprocess.check_output([node, "--version"], text=True, timeout=10).strip()
    match = re.fullmatch(r"v(\d+)\.(\d+)\.(\d+)", version)
    if not match or tuple(map(int, match.groups())) < (22, 19, 0):
        raise ValueError("Node must satisfy the backend's existing >=22.19.0 contract")
    pnpm_command = [node, pnpm] if pnpm.endswith(".cjs") else [pnpm]
    env = {key: value for key, value in os.environ.items()
           if not re.search(r"(?:TOKEN|API_KEY|PASSWORD|SECRET)", key)}
    env.update(EMBED_PROVIDER_MODEL="qwen3-embedding:8b", EMBED_PROVIDER_COMPACT_MODEL="qwen3-embedding:8b",
               EMBED_PROVIDER_DIMENSIONS="1024", OLLAMA_BASE_URL="http://127.0.0.1:11434",
               EMBED_PROVIDER_BASE_URL="http://127.0.0.1:11434", NPM_CONFIG_USERCONFIG="/dev/null")
    with tempfile.TemporaryDirectory(prefix="knoxx-checked-embedding-") as directory:
        private = Path(directory)
        runtime = private / "runtime"
        runtime.mkdir()
        build_log = private / "build.log"
        command = pnpm_command + ["-C", "backend", "exec", "shadow-cljs", "compile", "checked-embedding-tour"]
        build = None
        try:
            with build_log.open("wb") as output:
                build = subprocess.Popen(command, cwd=ROOT, env=env, stdout=output, stderr=subprocess.STDOUT,
                                         start_new_session=True)
                build.wait(timeout=180)
            log = build_log.read_text()
            if build.returncode or "0 warnings" not in log or "Build completed" not in log:
                raise ValueError("Owned verifier build failed or has no zero-warning receipt: " + log[-3000:])
        finally:
            stop(build)
        check(source_proof() == initial, "build inputs still match the selected physical source checkout")
        compiled = ROOT / "backend/target/checked-embedding-tour.cjs"
        imports = re.findall(r'^SHADOW_IMPORT\("([^"\n]+)"\);$', compiled.read_text(), re.MULTILINE)
        compiled_root = ROOT / "backend/.shadow-cljs/builds/checked-embedding-tour/dev/out/cljs-runtime"
        if not imports:
            raise ValueError("Missing concrete Shadow import custody")
        proof = dict(initial, **{"node": {"path": node, "version": version, "sha256": digest(node)},
                                 "compiled-sha256": digest(compiled),
                                 "runtime-files": {str((compiled_root / name).resolve()): digest(compiled_root / name)
                                                   for name in imports}})
        proof_file = private / "proof.json"
        proof_file.write_text(json.dumps(proof))
        process = None
        fixture_log = private / "fixture.log"
        results = {}
        try:
            with fixture_log.open("wb") as output:
                process = subprocess.Popen([node, str(compiled), str(runtime), str(proof_file)], cwd=ROOT,
                                           env=env, stdout=output, stderr=subprocess.STDOUT, start_new_session=True)
            ready_file = runtime / "ready.json"
            deadline = time.monotonic() + 20
            while not ready_file.exists() and time.monotonic() < deadline and process.poll() is None:
                time.sleep(0.1)
            if not ready_file.exists():
                raise ValueError("Owned runtime did not become ready: " + fixture_log.read_text()[-3000:])
            ready = json.loads(ready_file.read_text())
            origin, token = ready["origin"], ready["token"]
            check(request(origin, "GET", "/verify/proof")[0] == 401, "unauthenticated source proof is refused")
            status, wire = request(origin, "GET", "/verify/proof", token)
            check(status == 200 and wire["result"] == proof, "serving process verified current source, loader and every concrete Shadow import")
            for name in ("ingest", "query", "compact", "repair", "legacy", "malformed"):
                path = "/verify/" + name
                check(request(origin, "POST", path)[0] == 401, name + " refuses an unauthenticated operation")
                status, value = request(origin, "POST", path, token)
                check(status == 200, name + " reaches the owned checked-runtime boundary")
                results[name] = value
            source = ready["source"]
            for name in ("ingest", "repair"):
                result = results[name]["result"]
                check(result["coverage"]["embedding-dimensions"] == 1024 and result["coverage"]["vector-count"] == 1,
                      name + " retains Qwen/1024 vector coverage")
                check(result["stored"]["total"] == 1 and result["stored"]["rows"][0]["text"] == source,
                      name + " retains the complete source and exactly one immutable fixture base event")
            check(len(results["query"]["requests"]) == 2, "hot and compact query contexts each use the checked provider")
            check(results["compact"]["result"] == {"count": 1, "width": 1024}, "compact generation retains the same dimensional contract")
            check(results["legacy"]["result"]["refused"] and results["legacy"]["requests"] == [],
                  "legacy model partition is refused before provider dispatch")
            refused = results["malformed"]["result"]
            check(refused["refused"] and refused["code"] == "knoxx_embedding_invocation_failed"
                  and refused["cause-code"] == "knoxx_embedding_contract_invalid" and refused["vectors"] == [],
                  "swallowed short-vector failure refuses completion and writes no attributable vector")
            check(refused["stored"]["total"] == 1, "malformed generation retains its actual source progress")
            for value in results.values():
                for item in value["requests"]:
                    check(item["url"] == "http://127.0.0.1:11434/v1/embeddings"
                          and item["request"]["model"] == "qwen3-embedding:8b"
                          and item["request"]["dimensions"] == 1024,
                          "observed hermetic request binds direct route, model and dimensions")
            check(source_proof() == initial, "all owned source inputs remain unchanged after the tour")
            print("RESULT " + json.dumps({"proof": proof, "results": results}, separators=(",", ":")), flush=True)
        finally:
            stop(process)
        check(process.poll() is not None, "owned HTTP process is terminated; memory-only seeded data is gone")
    print("WARN SDK persistence/search results and provider responses are explicit hermetic fixtures; this is not genuine SDK/Mongo/Ollama qualification.")
    print("WARN full-source migration, database writer fence, production image/admission and host model bootstrap remain separate obligations.")
    print("PASS owned fixture directory and logs removed; no existing Knoxx or PM2 process was restarted.")


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--node", help="Existing Node executable, preferably the deployment's checked Node 24")
    parser.add_argument("--pnpm", help="Existing pnpm executable or cached pnpm.cjs; no installation is performed")
    try:
        verify(parser.parse_args())
    except (AssertionError, OSError, ValueError, subprocess.SubprocessError) as error:
        print("FAIL " + str(error), flush=True)
        raise SystemExit(1)
