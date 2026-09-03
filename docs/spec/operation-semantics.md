# Operation semantics

This specification owns caller-observable validation, diagnostics, outcomes,
progress, cancellation, and artifact reporting shared by every Archive Family.
Public type placement belongs to [Library interface](library-interface.md),
filesystem mechanics to [I/O and publication](io-and-publication.md), and
worker mechanics to [Execution model](execution-model.md).

## JBSA-OPS-001

Operational non-success **MUST** throw a checked `ArchiveException` extending
`IOException`. It **MUST** carry one Failure Kind, one Primary Failure, retained
Conformance Diagnostics, deterministically ordered Artifact States, an optional
Archive Assessment when one was established, bounded Secondary Failures, and
the Primary Failure's underlying cause when one exists.

`ArchiveCancelledException` **MUST** be the sole catch-worthy subtype.
Programmer contract violations **MUST** remain unchecked. A Secondary Failure's
cause **MUST** remain on that structured failure and **MUST NOT** be duplicated
into `getSuppressed()`, except when Java itself adds an unavoidable suppressed
exception.

_Source decision: [accepted public outcome and exception taxonomy](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-OPS-002

An Archive Assessment **MUST** be an immutable combination of one Archive
Disposition, one Validation Extent, and deterministically ordered Conformance
Diagnostics. Archive Disposition **MUST** classify recognized encoded content as
conforming, tolerated noncanonical, or rejected independently of the requested
operation, caller policy, capability, environment, and destination. Extraction
Eligibility **MUST** remain a separate operation-specific decision.

Every validation claim beyond bounded Detection Status **MUST** be represented
by a newly established Archive Assessment. Later validation **MUST NOT** mutate
an earlier assessment, and a failure **MUST** retain the latest assessment
established within its declared extent.

_Source decision: [accepted assessment, disposition, and eligibility model](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-OPS-003

Validation **MUST** stop at its declared Validation Extent:

- `detect` performs only the bounded recognition in
  [JBSA-DET-001](formats/detection.md#jbsa-det-001) and makes no Archive
  Disposition claim;
- `inspect` and `open` validate the complete index, names, counts, structural
  relationships, and byte spans without decoding every payload;
- consuming entry content validates that selected payload, including exact
  decoded size;
- `extract` completes containment, collision, capability, and resource preflight
  before destination effects and validates selected payloads while staging; and
- `pack` completes configuration, capability, source-shape, and initial
  destination preflight before stabilization, completes the output-set preflight
  required by [JBSA-IO-008](io-and-publication.md#jbsa-io-008) before destination
  staging or effects, and detects source mutation or data failure during
  stabilization or staging.

`detect` **MUST** return every recognition or support outcome owned by
[JBSA-DET-001](formats/detection.md#jbsa-det-001) rather than throw `FORMAT`,
`UNSUPPORTED`, or `CAPABILITY`. It **MAY** throw only for source I/O or policy
failure.

The initial interface **MUST NOT** add a separate whole-archive `validate`
operation. Unsafe absolute or traversal names **MUST** remain inspectable and
**MUST NOT** alone alter an otherwise conforming Archive Disposition, but they
**MUST** produce a stable diagnostic and make extraction fail as `POLICY` before
output creation.

_Source decisions: [accepted layered validation model](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted split-preflight clarification](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451)._

## JBSA-OPS-004

Every operational non-success **MUST** expose exactly one of these stable Failure
Kinds, classified by caller recovery rather than provider exception type:

| Failure Kind | Required classification |
| --- | --- |
| `FORMAT` | corrupt, truncated, contradictory, or invalid encoded data, including invalid compression and decoded-size mismatch |
| `UNSUPPORTED` | recognized family, version, subtype, direction, or other semantics outside JBSA support |
| `CAPABILITY` | supported semantics unavailable because of platform, native-access, or required-provider capability |
| `POLICY` | Resource Limit, extraction safety, destination-collision policy, or Diagnostic Policy rejection |
| `SOURCE` | pack-source I/O, mutation, or inconsistent regenerated content |
| `DESTINATION` | destination creation, write, publication, rollback, or cleanup failure |
| `OBSERVER` | a Progress Snapshot observer threw |
| `INTERNAL` | a JBSA invariant failed or a provider failed unexpectedly |
| `CANCELLED` | Cooperative Cancellation was accepted before the applicable Publication Commit |

Provider types, messages, native codes, and implementation details **MUST**
remain causes rather than public semantic data. `RESOURCE_LIMIT` **MUST NOT** be
introduced as an additional Failure Kind; a limit breach is `POLICY` with a
stable resource-limit diagnostic.

_Source decisions: [accepted Failure Kind taxonomy](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted resource-bound execution](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-OPS-005

A Conformance Diagnostic **MUST** expose a stable string identifier, `WARNING`
or `ERROR` severity, operation, stable phase, structured location, and
canonically represented and ordered string values. Its structured location
**MUST** identify the applicable archive, entry ordinal or name, field, byte
span, or filesystem artifact. An optional English explanation **MUST NOT** be
part of conformance comparison.

Successful domain values and Operation Reports **MUST** retain their warnings.
Failures **MUST** retain preceding warnings, their Primary Failure, and bounded
Secondary Failures. Public record order **MUST** be deterministic by phase,
Logical Plan Order, structured location, and diagnostic identifier rather than
discovery or worker completion time.

Diagnostic Policy **MUST** be immutable request data and **MAY** reject selected
warning identifiers. A rejected warning **MUST** retain its identifier and
`WARNING` severity, and **MUST** produce a referencing `POLICY` Primary Failure.
Policy evaluation **MUST** precede diagnostic retention limits. If
`maxDiagnostics` or `maxSecondaryFailures` omits records, JBSA **MUST** retain a
deterministic truncation diagnostic containing the observed omitted count and
whether additional unseen records may exist; exact capacity bookkeeping remains
internal.

For an atomic-set operation, a Diagnostic Policy rejection **MUST** prevent
Publication Commit. For existing-tree extraction, a rejection discovered after
earlier per-file commits **MAY** retain those files and **MUST** report their
Artifact States.

_Source decision: [accepted diagnostic and warning-policy contract](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-OPS-006

JBSA **MUST** choose the Primary Failure from observed candidates by this key,
in order:

1. phase: `PREFLIGHT`, `PROCESSING`, `PUBLISHING`, then `CLEANUP`;
2. logical input or artifact ordinal;
3. diagnostic identifier; and
4. structured location.

The public failure representation **MUST** define a canonical ordering for an
absent ordinal, identifier, or location so the comparison remains total.
Already-running earlier ordinals **MAY** settle before selection; no new work
**MAY** be admitted after an outcome is accepted. Remaining observed failures
**MUST** be retained up to `maxSecondaryFailures` in the same deterministic
order. Cleanup, rollback, observer, and later-phase failures **MUST NOT**
displace an earlier Primary Failure. Cancellation **MUST** use the atomic
acceptance rule in [JBSA-OPS-009](#jbsa-ops-009).

_Source decisions: [accepted deterministic failure selection](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted concurrent failure settlement](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-OPS-007

Only `extract` and `pack` **MUST** support Progress Snapshots. Stable progress
phases **MUST** occur in the order `PREFLIGHT`, `PROCESSING`, `PUBLISHING`, and
`CLEANUP` for the phases an operation enters. Each
snapshot **MUST** report exactly one of `ENTRIES`, `BYTES`, or `ARTIFACTS`, a
monotonic completed `long` for that phase and metric, and an optional total that
**MUST NOT** change once present.

When an observer is present, every entered phase-and-metric pair **MUST** emit an
initial snapshot. A successfully completed pair **MUST** emit an exact terminal
snapshot; one snapshot **MAY** serve as both when the values are identical.
Failure and cancellation **MUST NOT** synthesize completion. Method return or
exception, not a progress event, **MUST** remain the authoritative terminal
signal.

_Source decision: [accepted semantic progress model](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-OPS-008

Progress Snapshots for one operation **MUST** be delivered serially, in monotonic
semantic order, and outside library locks. A blocking observer **MAY** delay the
operation. If the observer throws, JBSA **MUST** stop safely, perform normal
cleanup, and record an `OBSERVER` failure candidate in the phase of the snapshot
being delivered, retaining the throwable as that candidate's cause. Selection
as the Primary Failure and retention as a Secondary Failure **MUST** follow
[JBSA-OPS-006](#jbsa-ops-006); only when the observer candidate is selected as
the Primary Failure **MUST** the operation expose `OBSERVER` as its Failure Kind
and the throwable as the Primary Failure cause. Observer re-entry into the same
operation **MUST** be unsupported; unrelated use of the stateless library
**MUST** remain allowed.

Callback thread identity, cadence, batching, coalescing thresholds, and
presentation **MUST NOT** be semantic guarantees. Progress-induced execution
backpressure is refined by
[JBSA-SCHED-009](execution-model.md#jbsa-sched-009).

_Source decisions: [accepted observer behavior](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted serialized progress delivery](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-OPS-009

Cooperative Cancellation **MUST** be explicit request state and **MUST NOT** be
Java thread interruption. A pre-cancelled operation **MUST** fail before side
effects. Cancellation and operational failure acceptance **MUST** race atomically:
the first accepted outcome is primary.

Cancellation accepted during preflight or staging, including after staging but
before Publication Commit, **MUST** prevent publication for an atomic-set
operation. Once an atomic or multipart commit begins, JBSA **MUST** finish that
commit or its rollback, and later cancellation **MUST NOT** replace the outcome.
Existing-tree extraction **MUST** stop before the next per-file Publication
Commit and retain already published files. Cancellation arriving after
publication but before cleanup **MUST NOT** change the result; cleanup completes
and the operation succeeds unless cleanup fails.

Cancellation **MUST** be observed at bounded work boundaries. JBSA **MUST NOT**
promise a wall-clock response bound during an uninterruptible native call.
When such a call returns after cancellation was accepted, its uncommitted result
**MUST** be discarded and no later publication may begin.

_Source decisions: [accepted Cooperative Cancellation and publication contract](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted stop propagation](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-OPS-010

Entry content **MUST** be streaming rather than transactional. Bytes returned
before a later compressed-data or exact-size failure **MUST NOT** be revoked. A
later payload failure **MUST** report `FORMAT` and close that child channel. A
normally completed channel **MUST** make its immutable payload-scoped Archive
Assessment available to the caller after terminal end-of-stream. The parent
archive **MUST** remain usable unless the failure closed or invalidated shared
archive state.

A read racing parent close **MUST** receive `AsynchronousCloseException`, and a
later child read **MUST** receive `ClosedChannelException`. Those lifetime
exceptions **MUST NOT** be Conformance Diagnostics. Direct caller interruption
of a child read **MUST** propagate `ClosedByInterruptException`, close the shared
parent channel, invalidate siblings, and **MUST NOT** become Cooperative
Cancellation. During `extract` or `pack`, interruption-caused channel failure
**MUST** instead remain a structured `SOURCE` or `DESTINATION` operational
failure with the channel exception as its cause.

_Source decisions: [accepted content-channel outcomes](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted interruption boundary](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-OPS-011

Every affected filesystem artifact **MUST** be reported in deterministic Logical
Plan Order with exactly one Artifact State: `PUBLISHED`, `UNCHANGED`, `RESTORED`,
`MISSING`, or `RESIDUAL_STAGING`. Reported paths **MUST** be normalized absolute
`Path` values without symbolic-link resolution. Operation Reports and exceptions
**MUST** reveal retained existing-tree siblings and rollback results without
claiming operation-wide atomic visibility.

Owned cleanup **MUST** complete for success. Cleanup failure after another
Primary Failure **MUST** be secondary. Cleanup failure after otherwise successful
publication **MUST** become a `DESTINATION` Primary Failure while retaining the
successfully published Artifact States. Every exact unremoved staging or scratch
path **MUST** be reported as a Residual Artifact; reporting it **MUST** transfer
cleanup ownership to the caller. The initial interface **MUST NOT** expose a
specialized residual-cleanup operation.

_Source decisions: [accepted Artifact State and Residual Artifact contract](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted publication cleanup ownership](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._
