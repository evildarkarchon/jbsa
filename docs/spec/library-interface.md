# Library interface

This specification owns the external seam of the deep `jbsa` module: the
operations, values, capabilities, and lifetime facts a Java caller must learn.
Operation outcomes are owned by [Operation semantics](operation-semantics.md),
storage and filesystem guarantees by
[I/O and publication](io-and-publication.md), codec behavior by
[Codecs](codecs.md), and concurrency effects by
[Execution model](execution-model.md).

## JBSA-LIB-001

`BethesdaArchives.standard()` **MUST** return the sole concrete, stateless
library-module instance for the JBSA release. Its synchronous instance interface
**MUST** provide these Path-first operations:

| Operation | Successful result |
| --- | --- |
| `detect(Path)` | detached archive detection |
| `inspect(Path)` | detached archive inspection using `OpenOptions.standard()` |
| `inspect(Path, OpenOptions)` | detached archive inspection under the supplied options |
| `open(Path, OpenOptions)` | caller-owned `OpenArchive` |
| `extract(ExtractRequest, OperationControl)` | Operation Report |
| `pack(PackRequest, OperationControl)` | Operation Report |

`inspect(path)` **MUST** be behaviorally identical to
`inspect(path, OpenOptions.standard())`. `OpenOptions` **MUST** carry the
selected Compatibility Profile or none, `ResourceLimits`, and an optional
explicit `DdsTarget` used only for DDS reconstruction. `OpenOptions.standard()`
**MUST** select no Compatibility Profile, `ResourceLimits.standard()`, and no
explicit `DdsTarget`. The optionless inspection form **MUST NOT** infer any of
those values from the environment.

The thin CLI, conformance suite, benchmarks, and embedded consumers **MUST** use
this same interface for archive behavior. `BethesdaArchives.standard()` itself
**MUST NOT** own a closeable resource lifetime.

_Source decisions: [accepted library interface](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted structured synchronous outcomes](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [profile-aware inspection clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179510)._

## JBSA-LIB-002

Public requests, options, policies, detections, detached metadata, Archive
Assessments, Operation Reports, diagnostics, progress snapshots, and failure
data **MUST** be immutable values. Collections and byte-valued metadata exposed
by those values **MUST NOT** be mutable through a reference retained by either
the caller or JBSA.

Successful query operations **MUST** return their domain values. Successful
`extract` and `pack` operations **MUST** return the common Operation Report, and
operational non-success **MUST** use the checked outcome contract in
[JBSA-OPS-001](operation-semantics.md#jbsa-ops-001).

_Source decisions: [accepted immutable request and metadata interface](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted public outcome model](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-LIB-003

`open` **MUST** return an `AutoCloseable` `OpenArchive` that owns its archive
input, validated index, and outstanding entry-content channels. It **MUST**
support repeated access to its immutable inspection and individual entries.

Closing an `OpenArchive` **MUST** be idempotent and linearizable, **MUST** prevent
new child channels, **MUST** invalidate and close outstanding child channels,
and **MUST** close the shared backing handle. Detached archive and entry metadata
obtained before close **MUST** remain usable afterward. The Windows
backing-handle lifetime is defined by
[JBSA-IO-002](io-and-publication.md#jbsa-io-002), and close-race observations are
defined by [JBSA-OPS-010](operation-semantics.md#jbsa-ops-010).

_Source decisions: [accepted owned archive interface](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted archive lifetime model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-LIB-004

An `ArchiveEntry` **MUST** be a capability tied to its parent `OpenArchive`.
Every `openContent()` invocation **MUST** return a fresh, sequential,
caller-closed `EntryContent` extending `ReadableByteChannel` and exposing
canonical uncompressed entry bytes. `EntryContent.assessment()` **MUST** return
an empty `Optional` until normal terminal end-of-stream, then **MUST** return the
new immutable payload-scoped Archive Assessment. It **MUST** remain empty if the
caller closes the channel before terminal end-of-stream.

The parent archive **MUST** outlive the channel; closing a child **MUST** affect
only that child.

The interface **MUST NOT** promise seeking within decoded entry content,
persistent decoded-payload caching, or transactional revocation of bytes already
returned. Payload validation and late channel failure are governed by
[JBSA-OPS-010](operation-semantics.md#jbsa-ops-010).

_Source decisions: [accepted entry capability](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted lazy content-channel model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted streaming failure semantics](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

## JBSA-LIB-005

Every public size, count, offset, span, ordinal, and progress counter **MUST** use
`long`. Payloads **MUST NOT** cross the interface as whole-entry `byte[]` values.
Entry reads and caller-generated pack inputs **MUST** use channels, and the
interface **MUST** admit archives and entries larger than 2 GiB without requiring
resident payload memory proportional to the total archive, entry, or decoded
payload size.

_Source decisions: [accepted large-value interface](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted bounded large-file model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-LIB-006

`ArchiveFamily` **MUST** remain distinct from wire version and BA2 subtype.
Entry metadata **MUST** preserve one display name: decoded wire text when every
required name component is present and decodable, otherwise the exact marked
synthetic name required by [JBSA-LIB-012](#jbsa-lib-012) or the applicable
Archive Family. It **MUST** preserve that display name, Normalized Name Identity
when present, and original wire-name bytes when present as distinct values.
Identity and its absence are owned by
[JBSA-LIB-012](#jbsa-lib-012). Archive and entry
metadata **MUST** use strongly typed sealed values for TES3 BSA, Versioned BSA,
General BA2, and DDS BA2 facts without exposing parser, codec, or provider
implementations. Exact fields and canonical encoder ordering remain owned by the
[Archive Family specifications](README.md#specification-index).

_Source decisions: [accepted public metadata model](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517), [undecodable-wire-name review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812807), [accepted `0.9.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5532860947)._

## JBSA-LIB-007

`ExtractRequest` **MUST** identify the source archive, destination, selected
entries, selected Compatibility Profile or none, target policy, Diagnostic
Policy, `ResourceLimits`, worker selection, and applicable immutable open and
extraction options. `PackRequest` **MUST** identify the destination, target
Archive Family and encoding, selected Compatibility Profile or none, ordered
`PackSource` values, target policy, Diagnostic Policy, `ResourceLimits`, worker
selection, and applicable filtering, compression, sharing, and splitting
choices. For Versioned BSA, the request **MUST** independently identify
automatic or explicit archive-flag selection and automatic or explicit
file-flag selection.

The public `DdsTarget` value **MUST** have exactly `PC` and `XBOX` variants and
**MUST** remain distinct from Archive Family, wire version, BA2 subtype, codec,
and destination target policy. A `PackRequest` for FO4 DDS BA2 or Starfield DDS
BA2 **MUST** carry exactly one encode `DdsTarget`; a request for every other
Archive Family **MUST NOT** carry one. That encode field is independent of any
`DdsTarget` later supplied through `OpenOptions` for reconstruction. Missing or
inapplicable encode-target data is a programmer contract violation.

Programmer contract violations in a request **MUST** be reported as unchecked
failures; archive, source, policy, capability, and destination non-success after
an operation is invoked **MUST** use [Operation semantics](operation-semantics.md).

_Source decisions: [accepted immutable operation requests](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted programmer-error boundary](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted worker selection](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855), [DDS encode-target clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179549)._

## JBSA-LIB-008

The ordered `PackSource` list **MUST** be interpreted from left to right as an
overlay. A later entry with the same Normalized Name Identity under
[JBSA-LIB-012](#jbsa-lib-012) **MUST** replace the earlier entry's name,
metadata, and payload while retaining only its first-insertion position until
the target Archive Family's encoder ordering is applied. A source entry without
that identity **MUST** fail canonical pack preflight before overlay processing.

The first-release sealed `PackSource` interface **MUST** support detected `Path`
sources for directories, individual loose files, and existing Bethesda
Archives; explicit named loose files; and caller-generated entries. A directory
source's root **MUST** be the supplied directory itself. Each recursively
discovered regular file **MUST** receive the archive entry name formed from its
nonempty root-relative `Path` name elements, preserving their characters and
joining them with U+005C (`\`). The root's final element and every ancestor
**MUST NOT** participate. An individual loose-file source **MUST** use only its
final `Path` name element. An existing-archive source **MUST** contribute its
entries in decoded archive order and use each entry's retained display name when
its Normalized Name Identity is present; an entry without that identity remains
subject to the identity preflight above. Explicit named loose files and
caller-generated entries **MUST** use
exactly their caller-supplied complete archive names. No mapping **MAY** infer a
source root from the process working directory, an ancestor named `Data`, or a
common ancestor of multiple sources. A derived name that is not a valid complete
name for the target Archive Family **MUST** fail canonical pack preflight; the
caller may instead use an explicit named loose file.

Directory-source discovery **MUST** inspect the supplied root and every
descendant without following a filesystem indirection. A symbolic link and, on
the qualified Windows platform, a junction or other reparse point **MUST** be
treated as an indirection for this rule. The supplied root **MUST** be a
directory under that no-follow classification; an indirection to a directory
**MUST** fail source-shape preflight as `SOURCE`.

Only a descendant classified as a regular file without following an
indirection **MAY** contribute an entry. A descendant indirection **MUST** be
omitted regardless of whether its target is a file or directory: JBSA
**MUST NOT** create an entry for it, read content through it, or traverse any
descendant reachable only through it. Other no-follow non-regular descendants
**MUST** likewise be omitted. An ordinary hard-linked regular file remains a
regular file and **MUST** contribute separately under each discovered name.
Classification, stable-identity, and final-revalidation mechanics are owned by
[JBSA-IO-006](io-and-publication.md#jbsa-io-006).

A directory source **MUST** complete discovery before overlay processing. After
name-identity preflight, its discovered files **MUST** be sorted first by
Normalized Name Identity and then, only to break an equal-identity tie, by the
derived archive entry name. Both comparisons **MUST** be lexicographic over
Unicode scalar values: the lower scalar at the first difference sorts first,
and a sequence that is an exact prefix sorts first. This is the directory
source's expansion order for insertion into the left-to-right overlay;
filesystem enumeration order, host collation, locale, worker selection, and
worker completion order **MUST NOT** affect it. Archive Family encoder ordering
**MUST** remain a later step. After overlay replacement and that ordering, the
resulting entries **MUST** supply the Logical Plan Order established by
[JBSA-IO-008](io-and-publication.md#jbsa-io-008).

A generated payload factory **MUST** be repeatable, **MUST** return a fresh
`ReadableByteChannel` for every invocation, and **MAY** be invoked more than once
for stabilization, hashing, comparison, retry, or packing. Ownership of every
returned channel **MUST** pass to JBSA.

_Source decisions: [accepted source-overlay and generated-input interface](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted generated-input lifetime](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517), [filesystem-source-name review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812829), [directory-order review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812837), [accepted `0.9.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5532860947), [accepted `0.10.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5533832048)._

## JBSA-LIB-009

`OpenOptions` and mutating-operation requests **MUST** expose immutable,
policy-level `ResourceLimits`. The limits **MUST** include semantic ceilings for
entry count, metadata bytes, decoded bytes, scratch bytes, output count,
retained diagnostics, and retained Secondary Failures. A limit breach **MUST**
produce the `POLICY` outcome defined by
[JBSA-OPS-004](operation-semantics.md#jbsa-ops-004).

`ResourceLimits.standard()` **MUST** have exactly these values:

| Member | Standard value |
| --- | ---: |
| `maxEntries` | `1_000_000` |
| `maxMetadataBytes` | `1_073_741_824` (1 GiB) |
| `maxDecodedBytes` | `1_099_511_627_776` (1 TiB) |
| `maxScratchBytes` | `274_877_906_944` (256 GiB) |
| `maxOutputs` | `1_000_000` |
| `maxDiagnostics` | `4_096` |
| `maxSecondaryFailures` | `256` |

`maxEntries` **MUST** count every admitted entry candidate, including one later
replaced by an overlay. `maxMetadataBytes` **MUST** count encoded header, table,
record, and name bytes parsed or planned, not Java object size.
`maxDecodedBytes` **MUST** count each selected final logical entry once,
independent of compression, sharing, replay, or retry; for an `EntryContent`
outside a mutation it applies separately to each channel. `maxScratchBytes`
**MUST** limit the peak simultaneously retained scratch and spool extent.
`maxOutputs` **MUST** count intended published leaf files or archive parts and
exclude directories, staging, and backups. The diagnostic and Secondary Failure
limits **MUST** count returned records and reserve one diagnostic position for
the truncation diagnostic required by
[JBSA-OPS-005](operation-semantics.md#jbsa-ops-005). Equality with a ceiling is
permitted; admitting the next counted unit **MUST** breach it.

Every public convenience or builder default that omits explicit limits **MUST**
use `ResourceLimits.standard()`. The CLI **MUST** use that exact value for
inspection, pack, and unpack and **MUST NOT** vary it by Compatibility Profile,
JVM heap, processor count, available storage, environment, or provider. A direct
library caller **MAY** supply different immutable limits.

Every explicit ceiling **MUST** be nonnegative, and `maxDiagnostics` **MUST** be
at least one so the reserved truncation diagnostic is representable. A value
violating those programmer contracts **MUST** be rejected as an unchecked
failure before an operation begins.

Allocation sizes, buffer counts, pools, provider thresholds, spill layout, and
resource-credit bookkeeping **MUST NOT** become public configuration. Bounded
enforcement is owned by [I/O and publication](io-and-publication.md) and
[Execution model](execution-model.md).

_Source decisions: [accepted public `ResourceLimits`](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted diagnostic retention limits](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted internal resource credits](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855), [standard-limit clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179557)._

## JBSA-LIB-010

Only `extract` and `pack` **MUST** accept `OperationControl`.
`OperationControl` **MUST** carry only a Progress Snapshot observer and a
Cooperative Cancellation source, with a no-op, never-cancelled default.

Each mutating request **MUST** carry the public `WorkerSelection` value whose
variants and execution semantics are defined by
[JBSA-SCHED-001](execution-model.md#jbsa-sched-001). `OperationControl` and
`WorkerSelection` **MUST NOT** expose an executor, callback executor, scheduler,
task, queue, cancellation checkpoint, callback cadence, or callback thread
identity. Their observable behavior is defined by
[Operation semantics](operation-semantics.md) and
[Execution model](execution-model.md).

_Source decisions: [accepted Operation Control boundary](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted progress and cancellation surface](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted public worker limit](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-LIB-011

Subject to [JBSA-BUILD-003](modules-and-build.md#jbsa-build-003),
[JBSA-BUILD-004](modules-and-build.md#jbsa-build-004), and
[JBSA-BUILD-006](modules-and-build.md#jbsa-build-006), exported library types
**MUST NOT** expose generalized archive-storage or positional-I/O ports,
extraction-target ports, output transactions, provider selection, provider or
parser implementations, executors, schedulers, buffers, pools, spill or staging
mechanics, callback dispatch mechanics, codec thresholds, native paths or
handles, console streams, process exits, or third-party types.

An additional public adapter seam **MUST NOT** be introduced without an explicit
specification change and a demonstrated second production adapter. Internal
in-memory, fault-injecting, codec, scheduling, and storage adapters do not meet
that threshold.

_Source decisions: [accepted deep interface and rejected storage ports](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted internal codec/provider boundary](https://github.com/evildarkarchon/jbsa/issues/11#issuecomment-5519440971), [accepted internal I/O model](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted internal scheduler model](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-LIB-012

For every complete decoded or caller-supplied entry name, JBSA **MUST** derive
an optional Normalized Name Identity before converting the name to a host
`Path`. A wire name **MUST** first decode through the selected Archive Name
Encoding without replacement. When every required wire-name component is
present but any component is undecodable, decode **MUST** apply the disposition
and diagnostic required by [JBSA-DET-006](formats/detection.md#jbsa-det-006),
retain every original component's wire bytes, use
`__jbsa_wire__\e{entryOrdinal:08x}` as the decoded display name, and leave
Normalized Name Identity absent. `entryOrdinal` **MUST** be the entry's
zero-based decoded archive-order ordinal rendered as exactly eight lowercase
hexadecimal digits. This marked synthetic display name **MUST NOT** be treated
as decoded wire text or as original wire-name bytes. A format-owned synthetic
name for absent wire-name components remains governed by that Archive Family.
An unmappable caller name retains its caller-supplied display name but has no
identity.

Starting with the decoded Unicode scalar sequence, JBSA **MUST** map U+002F
(`/`) to U+005C (`\`). A name is structurally ineligible for an identity when it
is empty; begins or ends with a separator; contains consecutive separators; has
a segment equal to `.` or `..`; contains U+0000 or U+003A (`:`); or has a
segment ending in U+0020 SPACE or U+002E FULL STOP.

For an eligible name, JBSA **MUST** map only U+0041 through U+005A (`A` through
`Z`) to U+0061 through U+007A and leave every other scalar unchanged. The
resulting separator-joined scalar sequence is the Normalized Name Identity, and
identity equality **MUST** be exact scalar-for-scalar equality. Normalization
**MUST NOT** perform Unicode normalization, locale-sensitive or full-Unicode
case folding, host-`Path` normalization, filesystem lookup, short-name
expansion, or symbolic-link resolution.

A missing, synthetic, unmappable, or structurally ineligible name has no
Normalized Name Identity. Decode **MUST** retain its display name and any
original wire bytes and **MAY** inspect it, but canonical pack **MUST** reject it
before overlay processing and extraction **MUST** treat it as ineligible.
Host-path extraction restrictions, including the qualified-Windows segment
rules in [JBSA-IO-009](io-and-publication.md#jbsa-io-009), do not by themselves
remove an otherwise present Normalized Name Identity or make an archive name
ineligible for canonical packing.

_Source decisions: [accepted byte-defined ASCII normalization](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5524023451), [normalized-name-identity clarification](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924179517), [undecodable-wire-name review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812807), [Windows-name review](https://github.com/evildarkarchon/jbsa/pull/61#discussion_r3924812819), [accepted `0.9.0` review clarifications](https://github.com/evildarkarchon/jbsa/issues/24#issuecomment-5532860947)._
