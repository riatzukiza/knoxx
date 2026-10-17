# Checked embedding runtime — owned HTTP verification

Run from the Knoxx checkout containing the shared checked runtime:

```sh
python3 scripts/verify-checked-embedding-runtime.py
```

Existing Node and pnpm must be installed. `--node /absolute/path/to/node` and
`--pnpm /absolute/path/to/pnpm.cjs` select already-present deployment tools.
The script installs nothing, builds the standard `checked-embedding-tour`
Shadow target and starts its own ephemeral loopback HTTP process. It reads no
provider key and sends no request to a real provider or MongoDB. Its test-build
SDK alias refuses unexpected embedding origins before forwarding.

The serving process checks the physical checkout, current source hashes, loader
hash and every concrete Shadow import against the build's proof. Each operation
first receives an unauthenticated request and must refuse it. An owned fixture
token then exercises actual Knoxx ingestion, hot/compact query, compact
generation and existing-source repair boundaries. Complete source tails and a
single fixture base event survive repair. Legacy partitions refuse before
generation. A short-vector response that the SDK fixture catches still refuses
the overall invocation, preserves its actual source write and produces no
attributable vector. The script prints what each assertion proves and an exact
JSON result with source/runtime hashes and observed requests.

The SDK alias deliberately supplies memory-only persistence and canned search
results. This tour therefore proves checked adapter wiring and refusal over
HTTP, not real SDK chunking, retrieval quality, Mongo transactions, graph closure
or provider output. Compact generation is exercised directly through the
shared compact runtime; Knoxx has no separate compact-memory writer to migrate
in this change. Unsupported SDK parallel pools fail explicitly. Generation is
uncached, and each request carries full inputs, `qwen3-embedding:8b` and explicit
`dimensions:1024`; no Proxx credential is forwarded.

Configuration requires `EMBED_PROVIDER_DIMENSIONS=1024`; hot and compact selected
models must be `qwen3-embedding:8b`. The embedding base must select the same direct
HTTP host Ollama origin as `OLLAMA_BASE_URL`, on port 11434, with only an optional
`/v1` base path and no URL credentials, query or fragment. Redirects refuse.
SDK-owned scope model selection remains intact and is checked before provider
dispatch. Recorded model, dimensional payload and complete chunk identities are
checked before they can count as coverage. Any failed generation remains failed
for that invocation, even if the SDK retains independent successful writes.
Metadata/session reads and Mongo health retain their independent boundary; a
healthy Mongo probe does not qualify an embedding provider or configuration.

Temporary data and logs live in one owned directory. `finally` terminates only
the process group created by this verifier and removes that directory. Source,
dependencies and build outputs remain in the selected checkout; no existing
Knoxx or PM2 process is restarted. The script prints the outstanding genuine
SDK/Mongo/Ollama, whole-source migration, database fence, host bootstrap and
production admission limits every run. This artifact grants no deployment,
readiness or completed-card credit.
