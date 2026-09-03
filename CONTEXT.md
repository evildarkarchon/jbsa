# Bethesda Archive Compatibility

This context describes the Bethesda archive formats and the compatibility criteria used to reproduce their behavior from the pinned reference snapshot.

## Language

**Bethesda Archive**:
A BSA or BA2 container belonging to a game- and payload-specific archive family supported by the reference snapshot.
_Avoid_: Package, bundle

**Archive Family**:
A distinct Bethesda Archive encoding identified by its game generation, container version, and payload kind, such as TES3, SSE BSA, Fallout 4 General BA2, or Starfield DDS BA2.
_Avoid_: Game format, archive type

**Versioned BSA**:
The `BSA\0` format lineage spanning wire versions `0x67`, `0x68`, and `0x69` and their distinct Archive Families. It is colloquially called TES4 BSA; TES4 / Oblivion BSA denotes only the `0x67` Archive Family.
_Avoid_: TES4 / Oblivion BSA when naming the whole lineage

**Tolerated Noncanonical Archive**:
A Bethesda Archive whose structure is noncanonical but remains bounded, unambiguous, and safely decodable. It produces a stable diagnostic and is never a valid encoder output.
_Avoid_: Malformed-but-valid archive, lenient archive

**Archive Disposition**:
The format-intrinsic classification of a recognized Bethesda Archive as conforming, tolerated noncanonical, or rejected. It is independent of the requested operation, caller policy, available capabilities, environment, and destination.
_Avoid_: Operation result, extraction status

**Extraction Eligibility**:
Whether a requested extraction can safely proceed for its destination, caller policy, and available capabilities. It is distinct from Archive Disposition; a structurally conforming archive can still be ineligible for extraction.
_Avoid_: Archive validity

**Validation Extent**:
The declared boundary through which an archive has been examined: recognition, structure, or a specified set of payloads. A result never implies validity beyond its Validation Extent.
_Avoid_: Validation level, fully valid

**Detection Status**:
The bounded-recognition outcome for an input: unrecognized, indeterminate from an incomplete recognizable prefix, a supported Archive Family, or a recognizable unsupported variant. Detection Status does not assert an Archive Disposition.
_Avoid_: Archive validity, format guess

**Archive Assessment**:
An immutable Archive Disposition, Validation Extent, and ordered set of Conformance Diagnostics describing the evidence established by one operation. Later validation can produce a new assessment but never mutates an earlier one.
_Avoid_: Mutable validation state

**Reference Snapshot**:
The exact revision pinned in the read-only `TES5Edit` submodule and used as the primary source of archive and CLI behavior.
_Avoid_: Upstream, latest TES5Edit

**Conformance Oracle**:
The locally provisioned BSArch executable whose identity is fixed by digest and whose behavior is used for differential and golden testing against the Reference Snapshot.
_Avoid_: Reference binary, latest BSArch

**Conformance Contract**:
The versioned authority, case matrix, and pass/fail rules governing compatibility claims. The initial contract is identified as `conformance-v1`.
_Avoid_: Test plan, compatibility checklist

**Conformance Case**:
The smallest independently gated compatibility scenario, identified by its Archive Family, operation, fixture, codec, and configuration. Every required case must pass; `N/A` is reserved for structurally inapplicable combinations.
_Avoid_: Test row, matrix cell

**Decode Conformance**:
For a supported Bethesda Archive, reproducing the reference entry names, metadata, and uncompressed entry bytes exactly.
_Avoid_: Read compatibility

**Encode Conformance**:
Producing a Bethesda Archive accepted by the relevant game and decoded equivalently to the intended entries; byte identity is an additional requirement only for designated deterministic cases.
_Avoid_: Write compatibility

**Binary Conformance**:
Producing byte-for-byte identical archive output to the Reference Snapshot for an input and configuration that earned deterministic designation through repeatability qualification.
_Avoid_: Exact compatibility

**Automated Conformance**:
The claim that every required Conformance Case runnable in hosted continuous integration passes. It does not imply current game or official-tool acceptance evidence.
_Avoid_: Full conformance, CI certification

**CLI Observation**:
The stable exit status, stream placement, semantic output, and filesystem artifacts used to judge command-line compatibility. Timing, progress repainting, and other nondeterministic presentation are excluded unless explicitly designated observable.
_Avoid_: Raw transcript, console snapshot

**BSArch-compatible CLI**:
The supported thin command-line consumer that exposes BSArch-shaped operations and options exclusively through the public archive-library interface. Its safe default follows normative behavior; qualified reference deviations require an explicit Compatibility Profile.
_Avoid_: Example CLI, BSArch clone

**Archive Name Encoding**:
The explicit Windows code page used to translate archive name bytes without losing their original wire representation. Windows-1252 is the deterministic default; the `bsarch-1.0` Compatibility Profile may select the active Windows ANSI code page.
_Avoid_: Platform default encoding, implicit ANSI

**Normalized Name Identity**:
The optional locale-independent archive-entry key formed from a structurally safe complete name by canonical separator mapping and ASCII-only case folding. It is distinct from display spelling, original wire-name bytes, hashes, and host filesystem identity.
_Avoid_: Normalized path, lowercase name

**DDS Target**:
The explicit PC or Xbox platform selection used to validate DDS input for packing or choose canonical DDS reconstruction. It is distinct from Archive Family, codec, and destination Target Policy.
_Avoid_: Archive target, inferred platform

**Resource Limits**:
The immutable semantic ceilings a caller applies to archive entries, metadata, decoded content, scratch use, outputs, diagnostics, and Secondary Failures. They are distinct from implementation buffer and scheduling controls.
_Avoid_: Memory settings, tuning parameters

**Conformance Diagnostic**:
A machine-comparable identifier, severity, operation, phase, structured location, and canonically represented value set emitted for a warning or failure. An optional human explanation is not part of library conformance.
_Avoid_: Error string, exception text

**Diagnostic Policy**:
An immutable caller choice that accepts warnings or rejects selected Conformance Diagnostic identifiers. Rejecting a warning changes the operation outcome without changing the warning's original severity or identity.
_Avoid_: Warning callback, mutable strict mode

**Operation Report**:
The structured completion record of a successful mutating archive operation, including its published artifacts and Conformance Diagnostics. Operational non-success is represented separately and never disguised as a successful report.
_Avoid_: Console output, success message

**Artifact State**:
The post-operation state reported for an affected filesystem artifact: published, unchanged, restored, missing, or residual staging. It describes observable state rather than promising operation-wide atomic visibility.
_Avoid_: Partial output flag

**Publication Commit**:
The point at which a staged artifact or atomic output set begins becoming externally visible. Cooperative Cancellation accepted before this point prevents publication; a later request does not retroactively cancel the commit.
_Avoid_: Final write, save completed

**Residual Artifact**:
Owned staging or scratch data that remains after cleanup fails. Its exact path and Artifact State are reported, after which cleanup ownership passes to the caller.
_Avoid_: Temp-file leak

**Logical Plan Order**:
The stable order assigned during preflight to an archive operation's logical inputs and artifacts after source-overlay and Archive Family ordering rules are applied. It governs observable ordering independently of execution or completion timing.
_Avoid_: Submission order, completion order, worker order

**Primary Failure**:
The deterministic failure selected from an operation's observed failures by operation phase and logical plan order. Cleanup, rollback, progress-observer, and other secondary failures never displace an earlier Primary Failure.
_Avoid_: First error, first exception

**Secondary Failure**:
An additional bounded failure retained after the Primary Failure has been selected. Its ordering is deterministic, and it provides context without changing the operation's primary outcome.
_Avoid_: Suppressed noise

**Failure Kind**:
A stable, recovery-oriented category for an operational non-success: format, unsupported, capability, policy, source, destination, observer, internal, or cancelled. It is coarser than a Conformance Diagnostic identifier and is not derived from provider exception classes or messages.
_Avoid_: Exception class, error code

**Cooperative Cancellation**:
An explicit request to stop an archive operation at a safe observation point. A request accepted before publication aborts the pending work, while a request arriving after publication commits does not retroactively turn completion into cancellation.
_Avoid_: Thread interruption, forced termination

**Progress Snapshot**:
An advisory observation of an archive operation's stable phase and monotonic completed logical units, with a total when known. Delivery timing, cadence, and presentation are not semantic guarantees.
_Avoid_: Progress tick, console percentage

**Independent Validator**:
A tool independent of the Reference Snapshot and Conformance Oracle that corroborates a Conformance Case without becoming its normative authority. A disagreement blocks release until investigated.
_Avoid_: Secondary oracle

**Compatibility Deviation**:
A named, evidence-backed, narrowly scoped exception to safe normative behavior that must be explicitly enabled. A Compatibility Deviation can never permit extraction outside its destination.
_Avoid_: Quirk, bug compatibility

**Compatibility Profile**:
An immutable, versioned, and digest-identified set of Compatibility Deviations selected explicitly for a consumer. No Compatibility Profile is active by default.
_Avoid_: Legacy mode, compatibility switch

**Release Qualification**:
Recorded manual Windows evidence that encoded Bethesda Archives are accepted by the relevant game or official tool. It is a release gate performed outside hosted CI, not a per-commit test.
_Avoid_: Game test, self-hosted CI check

**Benchmark Corpus**:
The versioned generators, seeds, manifests, and small structural templates from which content-addressed performance inputs are materialized deterministically. Ignored proprietary game assets may corroborate results but are not part of this corpus.
_Avoid_: Game corpus, performance fixtures

**Performance Case**:
The smallest independently gated performance scenario, identified by its measured surface, Benchmark Corpus workload, Archive Family or layout, codec and provider configuration, and worker count. Every applicable metric passes independently rather than contributing to a composite score.
_Avoid_: Benchmark test, performance score

**Reference Performance Qualification**:
A paired comparison between JBSA and the Conformance Oracle rerun on the same available Windows machine under one protocol. Hardware and Windows builds are recorded only as diagnostic context; no particular machine or build defines acceptance.
_Avoid_: CI benchmark, cross-machine comparison, canonical benchmark machine

**Performance Baseline**:
An immutable, versioned JBSA artifact and its Benchmark Corpus, JVM, provider, and protocol identities, rerun beside a candidate on the same machine. Stored absolute timings are evidence rather than portable acceptance standards, and replacing the artifact is a deliberate requalification.
_Avoid_: Rolling baseline, latest run, golden timing
