# Execution model

This specification owns bounded concurrency, Logical Plan Order, backpressure,
ordered coordination, and worker settlement beneath synchronous `extract` and
`pack`. Public controls belong to [Library interface](library-interface.md),
outcomes to [Operation semantics](operation-semantics.md), I/O and publication
to [I/O and publication](io-and-publication.md), and provider state to
[Codecs](codecs.md).

## JBSA-SCHED-001

The `WorkerSelection` interface owned by
[JBSA-LIB-010](library-interface.md#jbsa-lib-010) **MUST** provide `AUTOMATIC`
and `UP_TO(n)`. `UP_TO(n)` **MUST** require positive `n`. `AUTOMATIC` **MUST**
snapshot
`Runtime.getRuntime().availableProcessors()` when the operation starts and
**MUST** use at least one. `UP_TO(1)` **MUST** be the sequential worker mode.

The effective value **MUST** be an upper bound on concurrently executing
processing work and **MUST NOT** include the synchronous coordinator. Work
readiness and resource availability **MAY** reduce actual concurrency. Executor
and scheduler exclusion is owned by
[JBSA-LIB-010](library-interface.md#jbsa-lib-010).

_Source decision: [accepted public worker-limit model](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-SCHED-002

`extract` and `pack` **MUST** remain synchronous. Each operation **MUST** own its
named, non-daemon platform workers, and **MUST NOT** return or throw until every
admitted work item has settled and operation- and worker-scoped resources have
closed. Independent operations **MUST NOT** share a hidden process-wide worker
cap; callers control aggregate load through each operation's worker limit.

The initial implementation **MUST** use stable Java 25 concurrency primitives
and fixed platform workers. It **MUST NOT** use a caller-supplied executor, the
common pool, a process-wide worker pool, virtual threads, `ForkJoinPool`,
common-pool `CompletableFuture` execution, `Flow`, or preview
`StructuredTaskScope`. A later internal runner change **MUST** preserve this
public interface and rerun affected conformance, memory, and performance
qualification.

_Source decision: [accepted operation-owned worker lifetime](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-SCHED-003

The synchronous calling thread **MUST** be the coordinator and **MUST** perform
phase transitions, bounded admission, progress-delivery sequencing, ordered
writes, Publication Commit, deterministic failure selection, and cleanup. The
coordinator **MUST** await each observer invocation's return or throw before it
delivers a later Progress Snapshot or resumes admission or ordered consumption;
the invocation **MAY** execute on any implementation-selected thread, subject to
[JBSA-OPS-008](operation-semantics.md#jbsa-ops-008). The coordinator **MUST NOT**
perform any of its actions while holding a library lock.

Source and plan preflight **MUST** finish and assign Logical Plan Order before
processing work is admitted. When [JBSA-IO-008](io-and-publication.md#jbsa-io-008)
requires transformed-size- or sharing-dependent split stabilization, only the
bounded work required by [JBSA-IO-007](io-and-publication.md#jbsa-io-007)
**MAY** be admitted before complete output-set and collision preflight finishes.
Destination side effects **MUST NOT** begin until that final preflight finishes.
Completion timing **MUST NOT** become observable ordering.

_Source decisions: [accepted coordinator and preflight model](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855), [accepted split-preflight and progress-thread clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451)._

## JBSA-SCHED-004

Processing **MUST** use an entry-rooted dependency graph whose work may include
stabilization and read, hashing, sharing resolution, transform, and
Archive-Family-defined chunks. Scheduler partitioning **MUST NOT** change codec
or format boundaries, and an unsplittable codec stream **MUST** remain one work
item. Ready work **MAY** prefer lower ordinals while allowing later independent
work to run when an earlier item blocks.

Hash results **MUST** be resolved in Logical Plan Order. When data sharing is
enabled, the earliest byte-equivalent entry in that order **MUST** own the
canonical payload regardless of completion timing. When sharing is disabled and
replay is unnecessary, hashing and transformation **MAY** fuse into one bounded
streaming pass.

_Source decision: [accepted entry task graph and deterministic sharing](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-SCHED-005

For effective worker limit `N`, no more than `2N` processing work items **MAY**
be admitted across queued, executing, and completed-but-not-consumed states.
Plans and work descriptors **MUST** be materialized lazily so the implementation
does not admit every archive entry. The definition of a work item **MUST** remain
internal and subordinate to the entry and format boundaries in
[JBSA-SCHED-004](#jbsa-sched-004); no queue or task type may enter the public
interface.

_Source decision: [accepted bounded-admission rule](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-SCHED-006

Before admitting a work item, JBSA **MUST** account for its worst-case heap,
direct or native memory, scratch or spool bytes, open handles, and ordered-result
retention against applicable public `ResourceLimits` and operation-internal
bounded budgets. Each cost **MUST** remain charged while its out-of-order result,
staged artifact, buffer, native state, handle, or scratch extent remains live.

Work that cannot fit **MUST** use a release-qualified bounded streaming or spill
path or fail as `POLICY`; it **MUST NOT** bypass the budget. Disk scratch **MUST**
remain bounded. Provider cost declarations, credits, permits, buffer allocation,
and spill mechanics **MUST** remain internal.

_Source decisions: [accepted bounded-memory and scratch model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted resource-credit admission](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-SCHED-007

Workers **MUST** produce private, operation-owned staged results. The coordinator
**MUST** consume only the next result in Logical Plan Order and **MUST** be the
sole sequencer for archive payload writes, backpatching, output offsets, split
assignment, sharing ownership, destination commits, and Artifact State
reporting.

Offsets and split membership **MUST** derive from ordered transformed sizes, not
completion timing. Existing-tree extraction **MUST** commit staged files in
Logical Plan Order; an atomic output set **MUST** complete all staging before its
Publication Commit.

_Source decision: [accepted ordered-write and publication coordination](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-SCHED-008

Given identical inputs, immutable request, Compatibility Profile, and qualified
deterministic codec profile, JBSA output bytes **MUST** be independent of worker
limit and scheduling. Diagnostics, Artifact States, sharing and split choices,
progress phase order, Primary Failure, and Secondary Failure order **MUST**
likewise be independent of worker completion timing.

Every parallel mode **MUST** preserve semantic conformance. Binary Conformance
**MUST** remain limited to separately designated and qualified cases and
**MUST NOT** be inferred from parallel Reference Snapshot output.

_Source decision: [accepted schedule-independent output and outcome model](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-SCHED-009

Subject to the observer contract in
[JBSA-OPS-008](operation-semantics.md#jbsa-ops-008), workers **MUST** publish
only bounded internal results and counters. A blocking observer **MUST** delay
coordinator admission and ordered consumption so ordinary bounds propagate
backpressure.

Subject to [JBSA-OPS-008](operation-semantics.md#jbsa-ops-008), the
implementation **MAY** coalesce snapshots at deterministic logical-unit or byte
thresholds only when it preserves every required initial and exact successful
terminal snapshot.

_Source decisions: [accepted semantic observer contract](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted progress backpressure](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-SCHED-010

Stop propagation **MUST** use explicit atomic operation state and **MUST NOT** use
thread interruption. The state **MUST** distinguish caller-requested Cooperative
Cancellation from operational failure and observer stop. Once cancellation or
failure wins the outcome race, the coordinator **MUST NOT** admit new work.

Already-running work with earlier Logical Plan Order than the current failure
candidate **MUST** settle so an earlier Primary Failure can still be observed.
Later work **MUST** stop or discard uncommitted results at a bounded observation
point. Every admitted work item **MUST** settle before the operation returns, and
the coordinator **MUST** then apply
[JBSA-OPS-006](operation-semantics.md#jbsa-ops-006).

Observation classes **MUST** cover admission, bounded I/O loops, codec-call
boundaries, resource-credit waits, staging handoff, ordered writes, and the point
before Publication Commit. Exact checkpoint placement **MUST** remain internal.

_Source decisions: [accepted Cooperative Cancellation semantics](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted stop and drain model](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-SCHED-011

Orderly shutdown **MUST** stop admission, request non-interrupting worker
shutdown, and join every worker. The implementation **MUST NOT** use
`Future.cancel(true)`, `shutdownNow()`, thread interruption as cancellation, or
an `ExecutorService.close()` path that can translate an interrupted wait into
`shutdownNow()`.

If the caller is interrupted while the coordinator drains workers, JBSA
**MUST** remember the interruption, complete non-interrupting settlement and
cleanup, and restore the caller thread's interrupt status. A
`ClosedByInterruptException` during operation-owned channel I/O **MUST** remain
a structured `SOURCE` or `DESTINATION` failure rather than Cooperative
Cancellation.

An ordinary worker exception **MUST** become a structured failure and **MUST NOT**
silently terminate a worker. A fatal JVM condition such as
`VirtualMachineError` **MUST** trigger stop and best-effort cleanup and then
**MUST** be rethrown rather than represented as an ordinary archive failure.

_Source decision: [accepted shutdown, interruption, and fatal-failure model](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-SCHED-012

The worker limit and operation scheduler **MUST NOT** govern lazy
`OpenArchive.openContent()` channels. A child channel **MUST** execute
synchronously on its consumer's thread and **MUST** be single-consumer. Distinct
sibling channels **MAY** be consumed concurrently through bounded
absolute-position reads, without a guarantee of physical parallelism.

_Source decisions: [accepted lazy-channel execution model](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855), [accepted positional sibling reads](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-SCHED-013

Automated execution-model evidence **MUST** cover single-worker determinism;
parallel semantic equivalence; worker-limit-independent output for deterministic
profiles; stable sharing and split choices; deterministic failure aggregation;
every implementation-defined cancellation observation class; serialized,
blocking, and failing observers; slow early ordinals and out-of-order completion;
resource starvation; generated-source replay; duplicate sharing; split
boundaries; worker exceptions; parent-close races; native-call cancellation;
and concurrent independent operations.

Performance qualification **MUST** exercise worker limits 1, 2, 4, 8, and 16
where supported by the host and **MUST** apply the owning throughput, scaling,
memory, output-size, and regression gates independently.

_Source decision: [accepted scheduling qualification consequences](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._
