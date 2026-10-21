# Native local stack (no Docker)

`scripts/start-local-knoxx.sh` assumes Docker (to discover the OpenPlanner
Mongo URI) and a Proxx env file. This runbook stands Knoxx up on a plain Linux
host with only free, local providers, and proves the MCP surface end to end.
Verified 2026-10-21 on Debian 13, 8 vCPU / 16 GB, CPU only.

## Prerequisites

| Need | Version used | Notes |
|---|---|---|
| Node | 24.x (`engines` wants >= 22.19) | |
| JDK + Clojure CLI | OpenJDK 21, clojure 1.12 | for `shadow-cljs release server` |
| MongoDB | 8.0 community, single-node replica set | `--replSet rs0`, then `rs.initiate` once |
| Ollama | 0.40+ | chat + embeddings |
| `../openplanner` checkout | sibling of `knoxx/` | `@open-hax/openplanner-sdk` is a `link:` dependency |

Gotchas found on the way:

- **OpenPlanner SDK must be built.** `pnpm install` in `openplanner` (filter
  `@open-hax/openplanner-sdk...`), then `tsc` in `packages/openplanner-sdk` and
  `shadow-cljs release lib` in `packages/stores/document-hydration` and
  `packages/stores/cache`. Otherwise `dist/server.js` fails with
  `ERR_MODULE_NOT_FOUND .../openplanner-sdk/dist/index.js`.
- **gitlibs:** run `gh auth setup-git` first; if the first `shadow-cljs` run
  reports `Unable to fetch ~/.gitlibs/_repos/.../eta-mu`, delete that dir and
  rerun (three deps share the eta-mu repo and the parallel fetch can race).
- **Mongo dbpath:** keep it off any directory a snapshot/backup agent touches.
  Foreign files in the WiredTiger journal dir (`WiredTigerLog.*.<suffix>`)
  make mongod `fassert` and exit.
- **Ollama blob downloads** may be refused when the registry CDN resolves to a
  private range behind a proxy. Pull GGUFs from Hugging Face and
  `ollama create` them instead (below).

## Models (free, local)

```bash
# embeddings, native 1024 dims (~640 MB)
curl -fLo qwen3-embed-0.6b-q8.gguf \
  https://huggingface.co/Qwen/Qwen3-Embedding-0.6B-GGUF/resolve/main/Qwen3-Embedding-0.6B-Q8_0.gguf
printf 'FROM ./qwen3-embed-0.6b-q8.gguf\n' > Modelfile && ollama create qwen3-embedding:0.6b -f Modelfile
# chat: the model contracts/models/gemma4_e2b.edn already routes to :ollama (~2.8 GB)
curl -fLo gemma4-e2b-q4_0.gguf \
  https://huggingface.co/ggml-org/gemma-4-E2B-it-GGUF/resolve/main/gemma-4-E2B-it-Q4_0.gguf
printf 'FROM ./gemma4-e2b-q4_0.gguf\n' > Modelfile.gemma && ollama create gemma4:e2b -f Modelfile.gemma
```

`qwen3-embedding:0.6b` is the laptop choice. Production pins
`qwen3-embedding:8b@1024` (see open-hax/services
`docs/knoxx-ollama-embeddings.md`); the two are **not** interchangeable, so
never point a 0.6b instance at a database indexed by 8b.

## Run

```bash
mongod --replSet rs0 --bind_ip 127.0.0.1 --dbpath /var/lib/knoxx-mongo &
mongosh --eval 'rs.initiate({_id:"rs0",members:[{_id:0,host:"127.0.0.1:27017"}]})'
(cd backend && pnpm install && npx shadow-cljs release server)
(cd backend && node --env-file=$HOME/.config/knoxx/knoxx.env dist/server.js)
```

Start from `scripts/local-native.env.example`. The log line
`surface :mcp accepts :trusted-loopback` confirms the loopback MCP method is
live (see `docs/mcp-local-testing.md`).

## Prove MCP end to end

```bash
cd backend
node --env-file=$ENV scripts/mcp-probe.mjs list            # 83 tools
node --env-file=$ENV scripts/mcp-probe.mjs call push_claim \
  '{"claim":"A team shared memory is what survives translation between its members"}'
node --env-file=$ENV scripts/mcp-probe.mjs call semantic_query \
  '{"query":"identity lifting into a shared team space","topK":3}'
```

Observed on the reference run: `push_claim` reports `storageBackend: mongodb`
and queues vectors; `knoxx.event_chunks` and `graph_node_embeddings` hold
1024-dim vectors tagged `qwen3-embedding:0.6b`; `semantic_query` ranks the
team-memory claim first (score 0.45). A chat through `POST /api/knoxx/direct`
with `model: gemma4:e2b` completes in ~40 s on CPU, after which
`memory_search` / `memory_session` return that session.

## What does not work without more services

| Tool / route | Needs |
|---|---|
| `graph_query` | OpenPlanner HTTP API (`OPENPLANNER_BASE_URL`) — answers "OpenPlanner is not configured" |
| `websearch`, `image_/video_/music_/blaze_generate`, `GET /api/models` | Proxx (`PROXX_BASE_URL` + token) |
| Discord / Bluesky tools | actor credentials |
| `session_mycology` | a workspace session cwd (fails with `reading 'cwd'`) |
| Vector index | Atlas search; community Mongo falls back to in-process scoring (warning at boot) |
