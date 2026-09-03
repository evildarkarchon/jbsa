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
| `inspect(Path)` | detached archive inspection |
| `open(Path, OpenOptions)` | caller-owned `OpenArchive` |
| `extract(ExtractRequest, OperationControl)` | Operation Report |
| `pack(PackRequest, OperationControl)` | Operation Report |

The thin CLI, conformance suite, benchmarks, and embedded consumers **MUST** use
this same interface for archive behavior. `BethesdaArchives.standard()` itself
**MUST NOT** own a closeable resource lifetime.

_Source decisions: [accepted library interface](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted structured synchronous outcomes](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777)._

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
Entry metadata **MUST** preserve the decoded display name, normalized lookup
identity, and original wire-name bytes as distinct values. Archive and entry
metadata **MUST** use strongly typed sealed values for TES3 BSA, Versioned BSA,
General BA2, and DDS BA2 facts without exposing parser, codec, or provider
implementations. Exact fields and canonical encoder ordering remain owned by the
[Archive Family specifications](README.md#specification-index).

_Source decision: [accepted public metadata model](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103)._

## JBSA-LIB-007

`ExtractRequest` **MUST** identify the source archive, destination, selected
entries, target policy, Diagnostic Policy, `ResourceLimits`, worker selection,
and applicable immutable open and extraction options. `PackRequest` **MUST**
identify the destination, target Archive Family and encoding, ordered
`PackSource` values, target policy, Diagnostic Policy, `ResourceLimits`, worker
selection, and applicable filtering, compression, sharing, and splitting
choices.

Programmer contract violations in a request **MUST** be reported as unchecked
failures; archive, source, policy, capability, and destination non-success after
an operation is invoked **MUST** use [Operation semantics](operation-semantics.md).

_Source decisions: [accepted immutable operation requests](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted programmer-error boundary](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted worker selection](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

## JBSA-LIB-008

The ordered `PackSource` list **MUST** be interpreted from left to right as an
overlay. A later case-insensitive normalized-name match **MUST** replace the
earlier logical entry while retaining its first insertion position until the
target Archive Family's encoder ordering is applied.

The first-release sealed `PackSource` interface **MUST** support detected `Path`
sources for directories, individual loose files, and existing Bethesda
Archives; explicit named loose files; and caller-generated entries. A generated
payload factory **MUST** be repeatable, **MUST** return a fresh
`ReadableByteChannel` for every invocation, and **MAY** be invoked more than once
for stabilization, hashing, comparison, retry, or packing. Ownership of every
returned channel **MUST** pass to JBSA.

_Source decisions: [accepted source-overlay and generated-input interface](https://github.com/evildarkarchon/jbsa/issues/9#issuecomment-5519230103), [accepted generated-input lifetime](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067)._

## JBSA-LIB-009

`OpenOptions` and mutating-operation requests **MUST** expose immutable,
policy-level `ResourceLimits`. The limits **MUST** include semantic ceilings for
entry count, metadata bytes, decoded bytes, scratch bytes, output count,
retained diagnostics, and retained Secondary Failures. A limit breach **MUST**
produce the `POLICY` outcome defined by
[JBSA-OPS-004](operation-semantics.md#jbsa-ops-004).

Allocation sizes, buffer counts, pools, provider thresholds, spill layout, and
resource-credit bookkeeping **MUST NOT** become public configuration. Bounded
enforcement is owned by [I/O and publication](io-and-publication.md) and
[Execution model](execution-model.md).

_Source decisions: [accepted public `ResourceLimits`](https://github.com/evildarkarchon/jbsa/issues/12#issuecomment-5520294067), [accepted diagnostic retention limits](https://github.com/evildarkarchon/jbsa/issues/13#issuecomment-5520636777), [accepted internal resource credits](https://github.com/evildarkarchon/jbsa/issues/15#issuecomment-5520876855)._

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
