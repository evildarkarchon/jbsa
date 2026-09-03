# Bethesda Archive Compatibility

This context describes the Bethesda archive formats and the compatibility criteria used to reproduce their behavior from the pinned reference snapshot.

## Language

**Bethesda Archive**:
A BSA or BA2 container belonging to a game- and payload-specific archive family supported by the reference snapshot.
_Avoid_: Package, bundle

**Archive Family**:
A distinct Bethesda Archive encoding identified by its game generation, container version, and payload kind, such as TES3, SSE BSA, Fallout 4 General BA2, or Starfield DDS BA2.
_Avoid_: Game format, archive type

**Tolerated Noncanonical Archive**:
A Bethesda Archive whose structure is noncanonical but remains bounded, unambiguous, and safely decodable. It produces a stable diagnostic and is never a valid encoder output.
_Avoid_: Malformed-but-valid archive, lenient archive

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

**Archive Name Encoding**:
The explicit Windows code page used to translate archive name bytes without losing their original wire representation. Windows-1252 is the deterministic default; the `bsarch-1.0` Compatibility Profile may select the active Windows ANSI code page.
_Avoid_: Platform default encoding, implicit ANSI

**Conformance Diagnostic**:
A machine-comparable identifier, severity, operation, location, and value set emitted for a warning or failure. Human-readable wording is not part of library conformance.
_Avoid_: Error string, exception text

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
