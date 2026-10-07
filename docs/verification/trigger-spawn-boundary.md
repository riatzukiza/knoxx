# Trigger spawn capability boundary

Issue161 removes the domain action's dependency on the global agent runner. The
outer runtime composes a named, validated `:spawn-agent!` capability, the event
context carries it separately from payload data, and the action refuses an
absent or nonfunction capability before effectful contract resolution.

Run `scripts/verify-trigger-spawn-boundary.sh /absolute/private/isolated-command.py`
from this checkout with a prepared **privately owned** isolation runner. Its
command interface must be `offline CWD ARGV...`, and it must supply a private
network namespace, writable home/dependency caches and temporary storage, a
cleared credential-free environment, read-only host mounts and cleanup of owned
processes. The recorded verification uses bubblewrap, Node24.21.0, an owned JVM
home with a 768MiB heap, a 2GiB Node heap, lock-frozen dependencies and an owned
non-snap Chrome binary selected through `KNOXX_CHROMIUM_PATH`. Dependencies must
already be available; the script does not install packages or restart services.
Unavailable prerequisites and failed assertions remain nonzero failures.

The script rebuilds this checkout through the existing guarded compiled test
runner, checks that the HTTP fixture executed, and verifies that the Git head
remained stable. It never addresses the host's live Knoxx or PM2 processes.
The fixture boots the production Fastify route on loopback port0, seeds a copied
contract tree in a unique temporary directory and installs real Clio run/thread
providers. The unauthenticated request must return401 without creating a run.
The admitted request must return202, match its trigger, traverse the production
dispatcher/action/runner, and leave an admitted durable run/thread plus an active
agent session. All original state and owned temporary files are restored in
`finally`; bubblewrap owns the process namespace and private temporary mount.
The shell script removes only its own output directory through an EXIT trap.

Provider session construction, hydration and prompting use local fixture seams;
the runner and durable admission are not mocked. Fixture admission is a local
HTTP guard, not a test of deployed authentication or a live model response.
Separate tests retain the existing event-policy authority refusals and positive
identity assertions. Captured production Discord gateway callbacks cover both
message and voice events with absent and hostile caller capability maps, without
opening a gateway. They preserve policy handles, actor IDs, event types and
payload data while replacing the capability map. Other ingress tests check that
hostile configuration/payload capability
slots cannot replace the trusted runtime function. Missing/noncallable values
must refuse before resolution or runner admission. No test marks the board
checklist complete or waives the required lint/typecheck gates.

Integration with the separate publication-scope and durable-queue work remains
explicit: this change preserves runner implementation/payloads and the current
internal/external event provenance decision. Those lanes must retain the
capability slot when integrating their own caller changes.
