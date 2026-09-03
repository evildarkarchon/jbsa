# I/O and publication

This specification owns archive storage, bounded streaming, Windows file
lifetimes, staging, extraction containment, publication, rollback, and cleanup.
Public capabilities belong to [Library interface](library-interface.md),
caller-visible failures and artifact records to
[Operation semantics](operation-semantics.md), codec bounds to
[Codecs](codecs.md), and admission credits to
[Execution model](execution-model.md).

## JBSA-IO-001

Archive positions, offsets, spans, counts, and sizes **MUST** remain `long` end
to end. Arithmetic **MUST** be checked, every narrowing conversion **MUST** be
validated before it occurs, and zero-length entries **MUST** remain valid.

Archive reads and output backpatches **MUST** use explicit-position operations
without depending on a shared mutable channel position. Exact reads and writes
**MUST** loop until complete and **MUST** fail on short end-of-file, persistent
zero progress, overflow, or an invalid span.

_Source decision: [accepted positional large-file I/O](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-IO-002

On the qualified OpenJDK 25 and Windows x64 baseline in
[JBSA-SCOPE-001](scope.md#jbsa-scope-001), every file-backed archive-input
handle and every pack-source handle while consumed **MUST** use `NOSHARE_WRITE`
and `NOSHARE_DELETE` while permitting compatible readers. JBSA **MUST** report
`CAPABILITY` or the applicable source conflict rather than silently weaken this
lifetime guarantee.

The denial **MUST** last for the backing-handle lifetime: until its
`OpenArchive` closes for an opened archive, until a transient query or operation
closes its internal archive handle, and from final source revalidation until
consumption finishes for a pack input.

_Source decision: [accepted Windows deny-write/delete lifetime](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-IO-003

The archive input owned by `OpenArchive` under
[JBSA-LIB-003](library-interface.md#jbsa-lib-003) **MUST** be backed by exactly
one `FileChannel`. Its index **MUST** be eagerly validated and bounded by
`ResourceLimits`, while entry payloads **MUST** remain lazy. Stored content
**MUST** read its bounded range directly; compressed content **MUST** decode
sequentially from the start; and DDS content **MUST** stream its canonical header
and logical chunks. The implementation **MUST NOT** retain a persistent
decoded-payload cache.

The parent and child close contract is owned by
[JBSA-LIB-003](library-interface.md#jbsa-lib-003). Internally, child close
**MUST** release only child-owned buffers and codec state.

_Source decision: [accepted parent-owned lazy archive model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-IO-004

The first-release I/O implementation **MUST NOT** use `FileLock`,
`MappedByteBuffer`, or a writable memory mapping. Read-only arena-backed mapping
**MAY** become an internal
optimization only after qualification proves deterministic unmapping, bounded
memory, and preservation of [JBSA-IO-002](#jbsa-io-002). Entry-local
cancellation **MUST NOT** use thread interruption because interruption may close
the shared `FileChannel`.

_Source decision: [accepted mapping, locking, and interruption exclusions](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-IO-005

Payload processing **MUST** stream through bounded windows and **MUST NOT**
require a buffer proportional to an archive, entry, or decoded payload size.
Ordinary parsing and JDK codec paths **SHOULD** use heap buffers. Direct or
off-heap buffers **MAY** be used only for a required native path or a
benchmark-qualified benefit.

Buffer reuse **MUST** remain operation- or worker-scoped and **MUST NOT** create
a global pool. A whole-buffer codec path **MUST** stay below its qualified
internal threshold and acquire its complete applicable resource credit before
use. Buffer sizes, pooling choices, threshold values, and credit mechanics
**MUST NOT** become public interface.

_Source decisions: [accepted bounded buffering](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted resource-credit execution](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-IO-006

Packing **MUST** be consistent-or-fail rather than claim a point-in-time
filesystem snapshot. Preflight **MUST** establish the complete Logical Plan
Order and record each loose source's identity and expected metadata. A loose
path **MUST** be revalidated immediately before consumption and held under
[JBSA-IO-002](#jbsa-io-002) until reading completes. JBSA **MUST NOT** retain all
source handles merely to preserve a large plan, and it **MUST** hash while
streaming when a hash is required.

Generated inputs **MUST** obey [JBSA-LIB-008](library-interface.md#jbsa-lib-008).
An observed identity, metadata, declared-length, or regenerated-byte
inconsistency **MUST** fail as `SOURCE` before the affected output is published.

_Source decision: [accepted pack-input consistency model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-IO-007

JBSA **MUST** preserve a stable scratch representation when replay, retry,
generated-input comparison, sharing, split assignment, or final output ordering
cannot be satisfied by one bounded streaming pass. A transformed entry **MUST**
be spooled when its encoded size is required before split assignment or
publication. Scratch use **MUST** remain within `ResourceLimits`; thresholds,
layout, storage implementation, and spill mechanics **MUST** remain internal.

_Source decision: [accepted bounded scratch and spill staging](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-IO-008

Before processing work is admitted, each mutating operation **MUST** preflight
capabilities and destination-root containment, establish Logical Plan Order,
and establish bounded admission under `ResourceLimits`. If its complete output
set is knowable without transformed sizes or sharing resolution, it **MUST**
also enforce the exact output-count limit and preflight every target's
containment and collision at that time.

When a split output set depends on transformed sizes or sharing resolution,
JBSA **MAY** admit only the bounded stabilization work required by
[JBSA-IO-007](#jbsa-io-007) to finalize split membership. That work **MUST** use
operation-owned scratch, remain within `ResourceLimits`, and **MUST NOT** create
destination-adjacent staging or any other destination side effect. Once
membership is final, JBSA **MUST** enforce the exact output-count limit and
preflight containment and collisions for its complete output set before creating
a unique, private, operation-owned staging area adjacent to the destination.
Every split part **MUST** be fully staged before any part reaches Publication
Commit.

Each archive part **MUST** use one staged `FileChannel`. JBSA **MUST** validate
table sizes, reserve header regions without proportional buffers, stream
payloads in final order, record checked offsets and encoded sizes, and backpatch
tables through bounded positional writes. Writable output **MUST NOT** be memory
mapped.

_Source decisions: [accepted staged archive-writing model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted two-gate split preflight clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451)._

## JBSA-IO-009

Extraction **MUST** pin the destination root; reject absolute paths, traversal,
alternate-data-stream names, normalized Windows name or case collisions, and
descendant symbolic links, junctions, or other reparse points; deliberately
create paths without following links below that root; and recheck containment
immediately before publication. Unexpected destination mutation **MUST** stop
further publication and invoke the rollback applicable to the affected
publication surface.

This contract treats the caller-supplied destination tree as non-hostile and
**MUST NOT** claim resistance to a privileged concurrent attacker racing NTFS
namespace operations.

_Source decisions: [accepted extraction-path safety model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted extraction eligibility and partial-output semantics](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-IO-010

Mutating requests **MUST** select `FAIL` or `REPLACE`, and `FAIL` **MUST** be the
library default. `FAIL` **MUST** reject every pre-existing target in the complete
output set during the applicable output-set preflight under
[JBSA-IO-008](#jbsa-io-008). Under `REPLACE`, JBSA **MUST** move each predecessor
into private staging before installing its replacement and **MUST NOT** rely on
implementation-specific replacement behavior of an atomic move.

_Source decisions: [accepted target policy and replacement model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted output-set preflight clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451)._

## JBSA-IO-011

Publication Commit **MUST** begin at the first externally visible backup or
publication move: the sole move for one archive or a new extraction root, the
first planned move for a split archive set, and each file's first move for
existing-tree extraction. Cooperative Cancellation accepted before that point
**MUST** have the effect defined by
[JBSA-OPS-009](operation-semantics.md#jbsa-ops-009); once the point is crossed,
the commit or its rollback **MUST** settle.

_Source decisions: [accepted publication mechanics](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted Publication Commit semantics](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-IO-012

On a filesystem supporting the required operation, a single archive and a newly
created extraction root **MUST** publish by one atomic move. Existing-tree
extraction **MUST** publish each file atomically in Logical Plan Order and
**MUST NOT** claim set-wide atomic visibility. Split archives **MUST** publish
their fully staged parts individually in Logical Plan Order and **MUST NOT**
claim simultaneous set visibility.

If the required atomic publication capability is unavailable, JBSA **MUST**
report `CAPABILITY` before that publication surface begins rather than silently
weaken its per-artifact guarantee.

_Source decisions: [accepted atomic publication surfaces](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted partial-output boundary](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-IO-013

If an in-process split-set commit fails, JBSA **MUST** stop forward publication,
remove newly published parts that had no predecessor, and attempt to restore
every staged predecessor. Existing-tree extraction **MUST** limit rollback to
the currently committing file and **MUST** retain previously committed siblings.
Rollback failures and resulting artifacts **MUST** use the Artifact State and
Residual Artifact contract in
[JBSA-OPS-011](operation-semantics.md#jbsa-ops-011).

_Source decisions: [accepted replacement rollback](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted retained-partial-output reporting](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-IO-014

`detect`, `inspect`, `extract`, and `pack` **MUST** close their internal resources
before returning or throwing. Normal cleanup **MUST** close owned handles and
remove owned scratch and staging resources. JBSA **MUST NOT** rely on
`deleteOnExit()` or make `Cleaner` responsible for correctness or staging-tree
removal. A minimal `Cleaner` **MAY** best-effort close an abandoned handle or
child state.

Any unremoved path **MUST** be handed to
[JBSA-OPS-011](operation-semantics.md#jbsa-ops-011), after which cleanup ownership
passes to the caller. Native libraries retain the process lifetime specified by
[JBSA-CODEC-010](codecs.md#jbsa-codec-010).

_Source decisions: [accepted resource ownership and cleanup](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted Residual Artifact ownership](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-IO-015

Atomic visibility **MUST NOT** imply forced durability. The first-release I/O
and publication contract **MUST NOT** promise mandatory `force`, `SYNC` or
`DSYNC`, write-through rename, journaling, crash recovery, or startup scavenging.
Rollback guarantees **MUST** apply only to failures synchronously observed by
the running operation, not process termination, machine failure, or power loss.

_Source decision: [accepted durability boundary](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## Evidence boundaries

OpenJDK's extended sharing options, Java channel interruption behavior, heap and
direct buffer tradeoffs, atomic-move limitations, and cleanup behavior are
recorded in [the Java 25 Windows archive-I/O research](../research/java-25-windows-archive-io.md).
The exact file-identity token, destination-root pin, staging layout, spill
thresholds, and qualified JDK build remain implementation or release evidence;
they are not additional public ports or guarantees.
