# Reproduce the pinned Sandbox SDK bootstrap

Run this from an owned Knoxx checkout with Node24, pnpm10.20.0, Java21,
Git and GNU tar on PATH. The SDK source checkout must have HEAD
`07085d6557b75834ce6f50e6c54b8ca47e1c7c08` (tree
`1dc69c04c8e832661d8062c15ee24a853f135d35`). Registry and Maven network
access is required; cold restoration is not offline. No application, database,
model, production credential or service is used.

```bash
mkdir -p /tmp/my-owned-sdk-review
bash scripts/build-sandbox-openplanner-sdk.sh verify \
  --source /path/to/exact/openplanner \
  --run-root /tmp/my-owned-sdk-review/verification
```

The initially absent run root and its parent must be owned by your user and
must not traverse symlinks. `verify` seeds and removes its own cold fixtures;
its retained `evidence/verify.log` and `outcome.json` explain failures. SIGINT,
SIGTERM and command deadlines escalate to KILL after two seconds for owned
process groups, even when the leader has exited and descendants closed their
output streams. Native Node waits/reaps direct children; the host reaps orphaned
nonchildren. Controls verify no owned descendant remains live. Do not remove evidence until children have exited.

The explicit registered equivalent is `pnpm -C backend test:sandbox-sdk-bootstrap`
with `KNOXX_SANDBOX_SDK_SOURCE` pointing at that exact source. It is outside the
default test glob. It executes the authored Sandbox preparation stanza, builds
the genuine SDK/hydration/cache closure, installs the backend from its frozen
lockfile and imports the unchanged genuine `search-core` module through the
owned sibling link and fatal-error guard. Then it deletes the original source
and build, executes the manifest's actual restore command, and repeats the real
import from a fresh build. The fixture removes only its dedicated directories.
This verifies dependency preparation, not a running application or deployment.

Both Sandbox and the pinned setup action call the same `prepare` boundary:

```bash
sdk="$(bash scripts/build-sandbox-openplanner-sdk.sh prepare \
  --source /path/to/exact/openplanner \
  --run-root /tmp/my-owned-sdk-review/initial)"
```

Only47 immutable regular source files are materialized: the SDK, hydration and
cache package inputs plus root package/workspace/lock metadata. Relative path
checksums must hash to
`3f67946839edd484db2973dd19b2eb822e41e10ada9ac878bb2d24468839aece`.
Raw `.env`, `.npmrc`, auth/config stores, Git history, installed packages and
old dist are excluded before lifecycle execution. A fresh home/config and two
distinct empty npmrc files replace inherited credential configuration. Java
`user.home` is explicitly bound to that fresh home and checked before lifecycle
execution; setting only the shell HOME does not isolate Maven configuration. The
helper verifies source, Node, pnpm and Java before the upstream frozen filtered
install and build; stdout returns a verified owned build path only on success.

`evidence/` retains selected/excluded relative inventories, source archive,
source/helper hashes, tool versions, install/build logs, generated export
hashes and actual outcomes. Absolute build paths are recorded separately and
are expected to differ across runs. Source snapshots precede lifecycle work.
Use the retained helper and archive for restoration:

```bash
if [[ -e ../openplanner && ! -L ../openplanner ]]; then
  printf 'Refusing an existing real OpenPlanner sibling.\n' >&2
  exit 1
fi
sdk="$(bash /path/to/bundle/sdk/build-sandbox-openplanner-sdk.sh restore \
  --bundle /path/to/bundle/sdk \
  --run-root /tmp/my-owned-sdk-review/restored)"
# Only after successful restoration, expose the owned sibling for Knoxx installs.
ln -sfn "$sdk" ../openplanner
pnpm -C backend install --frozen-lockfile
pnpm -C backend typecheck
pnpm -C backend test:coverage
```

Archive identity, checksum, paths and regular-file types are checked before
materialization; the immutable selected checksum is checked again before
install. Wrong/unavailable source, wrong pnpm, missing exports, install/build
failure, unexpected archive content and interruption fail nonzero. Transport
controls use synthetic hostile files/environment names and subprocess doubles
to prove exclusion, shared caller ordering, failure short circuit, fail-closed
metadata and TERM-ignore cleanup; those controls supply no SDK semantic success.

Qualification requires zero-diagnostic `actionlint .github/workflows/sandbox-bundle.yml`,
the explicit integration, backend typecheck, coverage and full lint. Registry
warnings and existing lint failures remain visible and block completion. The
existing `knoxx-knoxx-backend-lint-zero-warning-gate` prerequisite remains open
until independently satisfied; a successful SDK build cannot complete that
owner or this card. Independent frontend/ingestion results and partial evidence
remain collected even when SDK preparation fails.
