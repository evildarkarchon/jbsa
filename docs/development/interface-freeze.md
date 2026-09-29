# JBSA 1.0 public archive interface freeze audit

Issue [51](../../.scratch/jbsa-1-0/issues/51-freeze-the-jbsa-1-0-public-archive-interface.md)
reviews the public seam after all eight Archive Families, the thin CLI, bounded
parallel operations, and the [Automated Conformance Gate](evidence/issue50-automated-conformance/README.md).
The normative owners are [JBSA-LIB-001–012](../spec/library-interface.md),
[JBSA-OPS-001–011](../spec/operation-semantics.md), and
[JBSA-REL-006–007](../spec/release-gates.md). This record is an interface audit;
it does not substitute for per-candidate Automated Conformance or later
Performance and Release Qualification gates.

## Exported surface and compatibility baseline

The only production library module is `io.github.evildarkarchon.jbsa`, and its
only exported package has the same name. The compiled package has 77 public
top-level and nested types. There is one stateless `BethesdaArchives` facade;
`OpenArchive` and its child content channels carry the closeable lifetimes.
The [source declaration baseline](interface-freeze-source-api.txt) records the
export plus `javap -protected` output, and the
[binary descriptor baseline](interface-freeze-binary-api.txt) adds `javap -s` JVM
descriptors. Their SHA-256 values, over UTF-8 with LF endings, are respectively
`0a86cb60e9bce9038fcf9a438bbecd4ca22b844a36899ed7c654c1d02c579eac`
and `ed4acb4c0bf1ad9f61acbff68c82c51984aff7ab3e7716af4bb9a4ec9ce99f28`.
After the three-line module header, the source declaration baseline is byte-for-byte
identical to the [Interface Candidate snapshot](interface-candidate-api.txt):
the eight-family implementation required no public type or member change from
that earlier candidate.

`PublicApiFreezeIT` enumerates every public class in the exported package from
the built JAR and compares both snapshots exactly under the Java 25 toolchain.
An addition, removal, descriptor change, or export change fails this review
gate. `PublicModuleConsumerIT` independently compiles two named modules with
`--release 25` against only the library JAR and runs them with no CLI or test
support module. This tests the source seam and resolves binary references from
real consumer code. `ModuleArchitectureIT` checks the sole export and traverses
visible generic types, annotations, record components, and permitted subclasses.
Its JDK allow-list rejects storage, executor, buffer-pool, native-memory, and
console mechanisms; third-party and non-exported types are also rejected. No
provider, scheduler, transaction, staging, codec-dispatch, or native handle
became a public type or configuration option.

## Requirement and behavior trace

| Requirement | Audited caller-visible contract | Evidence |
| --- | --- | --- |
| `LIB-001` | Path-first synchronous facade, optionless inspection equals standard options, no facade lifetime | `PublicArchiveContractIT`, `PublicModuleConsumerIT` |
| `LIB-002` | Detached immutable requests, metadata, reports, diagnostics, and byte-valued facts; checked non-success | `PublicMetadataContractIT`, `PublicRequestContractIT`, `PublicOutcomeContractIT` |
| `LIB-003` | Idempotent parent ownership, detached metadata, child invalidation | `PublicModuleConsumerIT`, `OwnedArchiveTest`, `OwnedArchiveRaceTest` |
| `LIB-004` | Fresh sequential entry channels, terminal payload assessment, early close and parent lifetime | `PublicModuleConsumerIT`, `OwnedArchiveValidationTest` |
| `LIB-005` | `long` archive quantities and bounded channels instead of whole-payload arrays | `PublicArchiveContractIT`, `PublicMetadataContractIT`, compiled consumers |
| `LIB-006` | Archive Family distinct from wire selectors; sealed family metadata and separate wire/display/identity names | `PublicMetadataContractIT`, family conformance tests |
| `LIB-007` | Immutable request policies, DDS encode target, independent BSA flags, worker selection | `PublicRequestContractIT`, compiled consumers |
| `LIB-008` | Ordered sources, name-identity overlay, deterministic directory expansion, generated-channel ownership | `PublicRequestContractIT`, `PublicModuleConsumerIT`, Automated Conformance plan |
| `LIB-009` | Exact standard semantic limits and unchecked invalid ceilings; no public resource-credit mechanics | `PublicRequestContractIT`, `ModuleArchitectureIT`, Automated Conformance plan |
| `LIB-010` | Only mutations accept observer/cancellation; worker limit stays semantic | `PublicOutcomeContractIT`, `PublicModuleConsumerIT`, `ParallelPackTest`, `ParallelExtractTest` |
| `LIB-011` | Sole deep public module, no storage/provider/scheduler/transaction/dispatch or third-party types | `ModuleArchitectureIT`, `PublicApiFreezeIT` |
| `LIB-012` | Optional scalar-defined Normalized Name Identity, distinct synthetic display and wire bytes | `PublicMetadataContractIT`, Automated Conformance plan |
| `OPS-001` | Checked exception taxonomy, primary and secondary causes, dedicated cancellation subtype | `PublicOutcomeContractIT`, `OperationSemanticsTest` |
| `OPS-002` | Detached immutable disposition and Validation Extent, separate extraction eligibility | `PublicOutcomeContractIT`, family conformance tests |
| `OPS-003` | Recognition, structure, selected payload, extraction, and packing stop at declared extents | `PublicArchiveContractIT`, family conformance tests, Automated Conformance plan |
| `OPS-004` | Stable Failure Kinds for format, support, capability, policy, source, destination, observer, internal, cancellation | `PublicOutcomeContractIT`, Automated Conformance plan |
| `OPS-005` | Ordered structured diagnostics, warning policy, bounded retention | `PublicOutcomeContractIT`, `OperationSemanticsTest`, Automated Conformance plan |
| `OPS-006` | Primary and Secondary Failure selection by phase and Logical Plan Order | `ParallelPackTest`, Automated Conformance plan |
| `OPS-007` | Long semantic progress counters, phase ordering, exact terminal snapshots | `PublicOutcomeContractIT`, `OperationSemanticsTest`, Automated Conformance plan |
| `OPS-008` | Serialized observer delivery, safe observer failure and backpressure | `OperationSemanticsTest`, `ParallelExtractTest` |
| `OPS-009` | Cooperative cancellation before publication and settled admitted work | `PublicModuleConsumerIT`, `ParallelPackTest`, `ParallelExtractTest` |
| `OPS-010` | Streaming late failure, terminal assessment, close and interruption outcomes | `PublicModuleConsumerIT`, `OwnedArchiveRaceTest`, `OwnedArchiveValidationTest` |
| `OPS-011` | Deterministic artifact states, rollback and residual cleanup ownership | `PublicOutcomeContractIT`, `PublicationFailuresTest`, Automated Conformance plan |

The class names in the table refer to the tests in
`jbsa-conformance-tests/src/test/java/io/github/evildarkarchon/jbsa/verification`
or `jbsa/src/test/java/io/github/evildarkarchon/jbsa` (including its
`internal/io` subpackage). The [Assurance v2 plan](assurance-v2.md) and its
[issue 50 evidence](evidence/issue50-automated-conformance/README.md) bind the
cross-family and failure-policy scenarios to the exact tested candidate.

## Family, scheduling, and codec review

Both independently compiled consumer styles run the public `pack`, `detect`,
`inspect`, `open`/entry-content, and `extract` operations for every `ArchiveFamily`:
TES3 BSA, TES4 BSA, Fallout 3/New Vegas/Skyrim LE BSA, SSE BSA, Fallout 4
General BA2, Fallout 4 DDS BA2, Starfield General BA2, and Starfield DDS BA2.
Each style uses explicit `WorkerSelection.UpTo(1)` and `UpTo(4)` with two entries.
The embedded client checks generated-channel ownership, payload EOF and detached
metadata, canonical repack, and byte-identical output across worker limits. The
CLI-like client checks detection, both inspection overloads, extraction bytes,
artifact reporting, and pre-cancellation without publication. The consumer
source depends only on the exported library API.

The isolated consumer intentionally chooses stored SSE output and explicit
zlib Starfield DDS output so its runtime needs only the library module. The
standard SSE LZ4-frame and Starfield DDS raw-LZ4 paths remain exercised through
the public `PackRequest` seam by `Bsa69PackTest`, `DdsBa2PackTest`, and
`StarfieldDdsBa2ConformanceIT`, under their qualified native runtime. The
assurance plan separately covers applicable family directions, safe-default and
explicit Compatibility Profile behavior, malformed inputs, CLI behavior, and
sequential/parallel equivalence. This audit makes no Binary Conformance claim
for output modes without separately qualified cases.

The public performance characteristics are semantic: streaming entry content,
long quantities, bounded `ResourceLimits`, and a caller-selected worker ceiling.
The interface promises no callback thread, codec threshold, pool size, absolute
throughput, or persistent decoded cache. `ParallelPackTest`,
`ParallelExtractTest`, and the
[parallel checkpoint](evidence/issue48-parallel/checkpoint.md) exercise bounded
scheduling and worker-independent outcomes. Later Performance Qualification
still owns measured release thresholds and baselines.

## Change control

After Interface Freeze, a breaking public-interface change requires an explicit
decision, a source and binary compatibility assessment, and a specification
revision before implementation. The source and binary snapshots must be reset
deliberately. Every affected implementation, consumer, conformance, performance,
packaging, documentation, qualification, approval, and publication gate must be
reevaluated; an earlier `PASS` cannot be carried forward. This is the
[JBSA-REL-007](../spec/release-gates.md#jbsa-rel-007) and
[JBSA-REL-020](../spec/release-gates.md#jbsa-rel-020) rule.

## Gate record

The issue 50 hosted `full` capsule reports 138 of 138 selected Assurance
Scenarios `PASS` for its recorded candidate and specification identity. The
interface changes in this issue require fresh exact-candidate evidence before
the Interface Freeze Gate can be recorded as `PASS`.

The approved `bsarch-1.0/v1` deviation rows and pinned Oracle observations are
unchanged. Three production Javadocs describing the obsolete Contract Baseline
were corrected; no production declaration or executable statement changed.
Because the deviation review hashes all production source bytes, its
`implementation_sha256` was revalidated from
`sha256:6aab1e3f35aaacddfc32fa302f34784c2e7da63639670ed76f5ac24ad0638925`
to `sha256:0fee241f2ed65612042c2ed33fce0683920ee4356fe37e1bcec56ea4c2153e23`.
The prior maintainer approval still identifies the same 12 dispositions; this
comment-only rebind is checked by the deviation-review validation test and must
also pass the fresh full Assurance selection.

The focused checks are:

```powershell
.\gradlew.bat :jbsa-conformance-tests:integrationTest --tests io.github.evildarkarchon.jbsa.verification.PublicApiFreezeIT
.\gradlew.bat :jbsa-conformance-tests:integrationTest --tests io.github.evildarkarchon.jbsa.verification.PublicModuleConsumerIT
.\gradlew.bat :jbsa-conformance-tests:architectureTest
```

The final local build is `.\gradlew.bat clean verify --no-daemon`. A hosted
`full` Assurance evaluation must bind the final candidate and the revised
requirement-registry digest; the earlier issue 50 capsule alone cannot certify
the later commit.
