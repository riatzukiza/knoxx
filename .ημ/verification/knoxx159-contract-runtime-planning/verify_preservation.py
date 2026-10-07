"""Verify current planning inputs using independent Git/fixture identities and owner API.

Historical native captures remain observations at their recorded times. Receipt
admission below always reads today's file through the supplied current owner runtime.
This verifies planning evidence; it does not execute Knoxx or qualify its runtime.
"""

import argparse
import base64
import hashlib
import json
import stat
import subprocess
from pathlib import Path

BASE = "5670338fcf1db7d51a2690f388e4e4c5c409c48a"
CARD = "kanban/epics/knowledge-ops-contract-runtime-dod-restructure.md"
PREFIXES = [CARD, ".ημ/receipts.edn", ".ημ/session-mycology/ledger.md"]


def git(worktree, *args):
    """Read immutable Git source bytes without following fixture links."""
    return subprocess.check_output(["git", "-C", str(worktree), *args])


def identities(worktree):
    """Derive the baseline manifest independently from Git, retaining link identity."""
    rows = []
    records = []
    for line in git(worktree, "ls-tree", "-rz", BASE).split(b"\0"):
        if not line:
            continue
        meta, raw_path = line.split(b"\t")
        mode, kind, oid = meta.decode().split()
        path = raw_path.decode()
        rows.append((path, mode, kind, oid))
        if not path.startswith("kanban/"):
            continue
        data = git(worktree, "cat-file", "blob", oid)
        if mode == "120000":
            records.append({"path": path, "mode": "symlink", "target": data.decode()})
        else:
            assert kind == "blob" and mode in {"100644", "100755"}, (path, mode)
            records.append({"path": path, "bytes": len(data),
                            "sha256": hashlib.sha256(data).hexdigest()})
    return rows, records


def check_fixture(worktree, packet, fixture, independent_manifest, rows, expected):
    """Bind the published manifest to independent identities BEFORE checking fixture FS."""
    independent = json.loads(independent_manifest.read_text())
    published = json.loads((packet / "base-fixture-identities.json").read_text())
    assert json.dumps(independent, sort_keys=True) == json.dumps(expected, sort_keys=True), "independent fixture record differs from immutable Git"
    assert json.dumps(published, sort_keys=True) == json.dumps(independent, sort_keys=True), "published fixture identities differ from independent record"
    modes = {path: mode for path, mode, _, _ in rows}
    for record in independent:
        path = record["path"]
        current = fixture / path
        mode = modes[path]
        info = current.lstat()
        if mode == "120000":
            assert stat.S_ISLNK(info.st_mode) and str(current.readlink()) == record["target"], path
        else:
            assert stat.S_ISREG(info.st_mode), path
            assert bool(info.st_mode & 0o111) == (mode == "100755"), (path, "executable mode")
            data = current.read_bytes()
            if path == CARD:
                assert data == (worktree / CARD).read_bytes(), path
            else:
                assert len(data) == record["bytes"] and hashlib.sha256(data).hexdigest() == record["sha256"], path
    actual_paths = sorted(str(p.relative_to(fixture)) for p in fixture.rglob("*")
                          if p.is_symlink() or p.is_file())
    assert actual_paths == sorted(z["path"] for z in independent), "fixture paths differ"


def validate_current_receipts(root, worktree, packet, at=None):
    """Invoke actual current Receipt River for EVERY physical row and own declared suffix."""
    provenance = json.loads((packet / "owning-receipt-source-provenance.json").read_text())
    assert provenance["owner_pin"] == "154440f3c997aa9208194bba59b5edbef3654f78"
    assert len(provenance["copied_files"]) == 15
    for record in provenance["copied_files"]:
        data = (root / "runtime/receipt-owner" / record["path"]).read_bytes()
        assert len(data) == record["bytes"] and hashlib.sha256(data).hexdigest() == record["sha256"]
    # Baseline has 16 physical records; row17 and every ordinary successor append
    # are owned. No historical capture is used for this current validation.
    if at is not None:
        assert len(at) == 40 and all(c in "0123456789abcdef" for c in at), "--at requires a full commit SHA"
        assert git(worktree, "rev-parse", "--verify", at + "^{commit}").decode().strip() == at
    command = [str(root / "runtime/bin/node"), str(root / "runtime/node_modules/nbb/cli.js"),
               "-cp", str(root / "runtime/receipt-owner/src/cljs"),
               str(packet / "owning_receipt_consumer.cljs"), "git" if at else "file",
               at or str(worktree / ".ημ/receipts.edn"), "16"]
    result = subprocess.run(command, cwd=worktree, capture_output=True, timeout=30, check=False)
    assert result.returncode == 0, ("current owner API refused suffix", result.stderr.decode())
    current = json.loads(result.stdout)
    assert current["historical-count"] == 16 and current["historical-refused"] == 16
    assert current["owned"] and all(z["ok"] and z["schema"]["status"] == "declared"
                                     for z in current["owned"])
    return current


def verify(root, fixture, independent_manifest, at=None):
    """Check preserved source, archive transports, mode-faithful fixture and live suffix."""
    worktree = root / "worktree"
    packet = worktree / ".ημ/verification/knoxx159-contract-runtime-planning"
    rows, expected = identities(worktree)
    assert len(rows) == 2597
    unchanged = 0
    for path, mode, kind, oid in rows:
        current = worktree / path
        data = git(worktree, "cat-file", "blob", oid)
        if path in PREFIXES:
            assert current.read_bytes().startswith(data), path
        elif mode == "120000":
            assert current.is_symlink() and str(current.readlink()).encode() == data, path
            unchanged += 1
        else:
            assert kind == "blob" and current.read_bytes() == data, path
            unchanged += 1
    assert unchanged == 2594
    original = git(worktree, "show", BASE + ":" + CARD)
    assert (worktree / CARD).read_bytes().split(b"---", 2)[1] == original.split(b"---", 2)[1]
    for record in json.loads((packet / "accepted-source-archives.json").read_text()):
        encoded = (packet / record["archive"]).read_bytes()
        data = base64.b64decode(encoded, validate=True)
        assert base64.b64encode(data) == encoded
        assert data == git(worktree, "show", BASE + ":" + record["path"])
        assert len(data) == record["bytes"] and hashlib.sha256(data).hexdigest() == record["sha256"]
    for name, size, digest in [
        ("knowledge-ops-contract-runtime-dod-restructure.md.b64", 11649,
         "17627b37bb0714488b61d6b0ad5fc7cd71e60b5c219bc52745fe8adbc8482ee5"),
        ("superseded-2026.04.17.10.11.17.md.b64", 10381,
         "77bffa2fda761f89e2dc7ae8cdbaac03395fe84735d2fd7c59c5128ded1d879a"),
    ]:
        encoded = (packet / name).read_bytes()
        data = base64.b64decode(encoded, validate=True)
        assert base64.b64encode(data) == encoded and len(data) == size
        assert hashlib.sha256(data).hexdigest() == digest
    streams = 0
    for group in json.loads((packet / "capture-manifest.json").read_text()):
        for key in ["stdout", "stderr"]:
            record = group[key]["public_view"]
            encoded = (packet / record["path"]).read_bytes()
            data = base64.b64decode(encoded, validate=True)
            assert base64.b64encode(data) == encoded and len(data) == record["bytes"]
            assert hashlib.sha256(data).hexdigest() == record["sha256"]
            streams += 1
    check_fixture(worktree, packet, fixture, independent_manifest, rows, expected)
    for label in ["native-neutral-base-task-read-mount-corrected", "native-neutral-refined-task-read"]:
        output = json.loads(base64.b64decode((packet / (label + ".stdout.b64")).read_bytes()))
        assert output["uuid"] == "knoxx-knowledge-ops-contract-runtime-dod-restructure"
        assert output["frontmatter"]["status"] == "ready"
        assert output["frontmatter"]["priority"] == "P2" and output["frontmatter"]["points"] == "null"
    runtime = root / "runtime"
    assert all(p.resolve().is_relative_to(runtime) for p in runtime.rglob("*") if p.is_symlink())
    store = root / "source.git"
    missing = subprocess.check_output(["git", "--git-dir=" + str(store), "rev-list", "--objects", "--all", "--missing=print"])
    assert not any(line.startswith(b"?") for line in missing.splitlines())
    assert not (store / "objects/info/alternates").exists() and not (store / "shallow").exists()
    assert not list(store.rglob("*.promisor"))
    assert all(p.stat().st_nlink == 1 for p in (store / "objects").rglob("*") if p.is_file())
    subprocess.run(["git", "-C", str(worktree), "diff", "--check", BASE], check=True)
    current = validate_current_receipts(root, worktree, packet, at)
    return {"base_entries": len(rows), "other_inherited_exact": unchanged, "prefixes": 3,
            "frontmatter_exact": True, "fixture_entries": len(expected),
            "fixture_regular": 267, "fixture_symlinks": 1, "strict_historical_streams": streams,
            "receipt_input": {"kind": "immutable-git" if at else "working-file", "at": at},
            "current_receipt_physical_count": current["count"],
            "current_owned_physical_rows": [z["line-number"] for z in current["owned"]],
            "current_owned_declared_valid": True, "inherited_refused16_preserved": True,
            "runtime_closure_private": True, "complete_git_store": True,
            "no_knoxx_product_runtime_or_board_operation": True}


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--root", type=Path, default=Path(__file__).resolve().parents[4])
    parser.add_argument("--fixture", type=Path)
    parser.add_argument("--independent-manifest", type=Path)
    parser.add_argument("--at", help="Validate receipt bytes at this exact full commit SHA")
    args = parser.parse_args()
    print(json.dumps(verify(args.root, args.fixture or args.root / "fixture",
                            args.independent_manifest or args.root / ".final-proof/base-fixture-before.json", args.at)))
