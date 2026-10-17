<!-- SPDX-License-Identifier: GPL-3.0-or-later -->
# Translation agent run and split authority

This note keeps the rationale moved from four API docstrings in
`knoxx.backend.law.translation-agent`. The callable contract lives in the source
and its schemas; this note creates no alternate admission or runtime authority.

The current API receives a server-admitted translation turn. `run-id` derives an
attempt identity from the dispatch key and attempt id, with the legacy claim
instant fallback. `session-policies` projects the record plus turn, including the
turn, split manifest, candidate claim and execution identities. `translation-brief`
includes pinned memory guidance and each server-issued source split with the
exact identifiers the agent must echo. `translation-needed-event` validates the
turn before constructing its closed event and delegates to those projections.

The current event API takes `[record turn]`; a producing run id and source text
are held inside the admitted turn and its manifest. They are not separate
`translation-needed-event` arguments. The split brief uses explicit SOURCE SPLIT /
END TRANSLATION SPLIT markers rather than a long Markdown fence around a whole
unsplit document. This corrects the former docstrings' stale interface description;
the implementation and existing tests are unchanged.

## Preserved source rationale

The following four docstrings are copied verbatim from source revision
`fde4d2381345174869530abc9bb05b6c5c053e5b`. They explain the earlier design and
preserve its reasoning, including historical wording that predates the current
split-turn API. The concise source docstrings above describe the current API;
these archived passages are not an alternative callable contract.

### `run-id`

````text
The correlation value one dispatch attempt's agent run carries.

   Minted by Knoxx *before* the session starts, which is what closes a race the
   obvious design has. `law.translation-dispatch/output-revision` requires the
   producing run id to already be bound to the claim, so if the id could only be
   learned from the runtime's answer, an agent that submitted a pair quickly
   would arrive before the binding and be refused as `:dispatch-record-missing`
   — losing work that had actually been done.

   Deliberately *not* the runtime's own session id. `domain.action.start-agent-session`
   derives session and conversation ids from the trigger and the event, and
   taking that over would couple this composition to identifiers whose format is
   the runtime's business. The run id travels in the session's resource policies
   instead, so the two identity schemes stay independent and the claim is joined
   by the value Knoxx bound it to.

   Derived from the dispatch key *and the immutable attempt id*, not from the key
   alone. Historical records without an attempt id fall back to the claim instant.
   The key is deliberately stable across attempts — that is what makes duplicate
   dispatch a duplicate — so a run id derived from it would be identical on
   re-translation, the output revision would not change, and an approval of the
   first translation would silently authorize the second. That is the exact
   failure `output-revision` exists to prevent, so the discriminator has to be
   something a *replaced* claim changes. `reserve-dispatch!` replaces a retriable
   claim wholesale with a fresh `:dispatch/attempt-id`, even when two attempts
   are admitted in the same millisecond.

   Digested rather than concatenated because a dispatch key contains `pr-str`ed
   keywords and pipes, while this value ends up inside an output revision that
   `infra.translation-agent-content` turns into a filename and a human has to be
   able to read back.
````

### `session-policies`

````text
The policy overlay pinning a session to `record`'s claim.

   Derived from the dispatch record rather than from the publication intent,
   because the record is the value that already survived
   `law.translation-dispatch/assert-record!` — it carries the concrete revision,
   the tenant, the garden and the document wire id as one validated whole. Built
   from the intent instead, a caller could pin a session to a locale pair no
   claim was ever taken for, and the sink's join would then find nothing while
   the agent had already done the work.

   The run id is supplied rather than read off the record, because at the moment
   a session is started the claim is not yet bound to it — binding is what the
   caller does with the value this map carries.
````

### `translation-brief`

````text
The message the translating agent is actually handed.

   Carried in the payload's `:content`, which is the field
   `domain.action.start-agent-session/render-start-message` already renders into
   a triggered session's opening message. Using the existing convention is what
   makes this composition need *no* change to that generic action — the
   alternative was teaching it a translation-shaped payload field, which is
   domain knowledge a generic action must not hold.

   The source document is embedded verbatim rather than referenced by path. That
   is what makes the receipt honest: the agent translates the exact bytes the
   claim was taken for, so `law.translation-dispatch/source-drift-refusal` stops
   being the only available evidence of what was translated and becomes a
   redundant second check. Referenced by path, the agent would read the file
   *now*, which is the worker path's weakness restated.

   Fenced with a long delimiter rather than triple backticks because the source
   is Markdown and very often contains a fenced code block of its own; a
   three-backtick fence would be closed early by the document's own content and
   the tail would read as instructions.
````

### `translation-needed-event`

````text
The event `record`'s claim emits when it wants an agent run.

   `run-id` is supplied rather than derived here so that the caller which bound
   it to the claim is the same one that announces it. Deriving it again would be
   a second chance to derive it differently, and the failure would be silent: the
   agent would work, submit, and be refused for a claim nobody could find.

   `source-content` is embedded in the brief rather than left for the agent to
   fetch — see `translation-brief` for why that strengthens the receipt rather
   than merely saving a tool call.
````
