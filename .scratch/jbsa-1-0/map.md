# Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI

Status: none
State: open
GitHub issue: #23
Source: https://github.com/evildarkarchon/jbsa/issues/23
Author: evildarkarchon
Created: 2026-09-03T06:52:31Z
Source updated: 2026-09-03T06:52:31Z
Closed: none
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: none

Intended owner: maintainer
Triage reviewed: 2026-09-10
Triage rationale: Parent delivery map; select work from its child tickets.

## Original issue body

## Destination

Implement and qualify the Windows-first Java 25 Bethesda archive library and thin BSArch-compatible CLI specified by the completed Wayfinder effort, culminating in the first public JBSA 1.0 GitHub Release.

## Planning authority

- [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1) is the planning map.
- [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) records the accepted implementation order, milestones, and gate policy.
- Version-controlled normative specifications and their permanent requirement identifiers become the implementation authority before production code begins.

## Delivery model

- Child issues are independently mergeable, evidence-gated units connected with native GitHub dependencies.
- Archive work proceeds through risk-ordered vertical slices across the same deep public library interface; family-specific CLI behavior, fixtures, documentation, and applicable conformance evidence ship with each slice.
- Automated Conformance and independent validation close a slice. Manual game/tool Release Qualification blocks the public release rather than unrelated implementation.
- Conformance cannot be waived by performance. Provider promotion cannot mutate an existing release profile.
- Apply `ready-for-agent` or `ready-for-human` only when a child is fully specified, unblocked, and safe to start.

## Interface milestones

1. **Contract Baseline** after the normative specification and compileable public types exist.
2. **Interface Candidate** after representative TES3, versioned BSA, General BA2, and DDS BA2 slices pass their automated gates.
3. **Interface Freeze** after all Archive Families, bounded scheduling, the complete CLI, and Automated Conformance exercise the public interface.
4. **Release Candidate** after performance, packaging, compliance, provenance, and distribution verification; JBSA 1.0 follows manual Release Qualification.

## Scope boundaries

- The pinned `TES5Edit` Reference Snapshot is read-only evidence and must never be modified.
- No proprietary game assets enter version control or release artifacts.
- GUI work, non-Windows guarantees, Maven Central/GitHub Packages publication, and moving-reference compatibility are outside this program.

## Local sub-issues

| Ticket | State |
| --- | --- |
| [#24 — Publish the specification framework and requirement registry](issues/24-publish-the-specification-framework-and-requirement-registry.md) | closed |
| [#25 — Materialize the Archive Family specifications](issues/25-materialize-the-archive-family-specifications.md) | closed |
| [#26 — Materialize the library behavior specifications](issues/26-materialize-the-library-behavior-specifications.md) | closed |
| [#27 — Materialize the CLI and qualification specifications](issues/27-materialize-the-cli-and-qualification-specifications.md) | closed |
| [#28 — Bootstrap the Java 25 Maven and JPMS reactor](issues/28-bootstrap-the-java-25-maven-and-jpms-reactor.md) | closed |
| [#29 — Establish licensing and dependency provenance gates](issues/29-establish-licensing-and-dependency-provenance-gates.md) | closed |
| [#30 — Create the committed synthetic Fixture Corpus](issues/30-create-the-committed-synthetic-fixture-corpus.md) | closed |
| [#31 — Build the pinned-oracle and Conformance Case harness](issues/31-build-the-pinned-oracle-and-conformance-case-harness.md) | closed |
| [#32 — Build the performance-v1 harness and Benchmark Corpus](issues/32-build-the-performance-v1-harness-and-benchmark-corpus.md) | closed |
| [#33 — Establish the Contract Baseline public archive interface](issues/33-establish-the-contract-baseline-public-archive-interface.md) | closed |
| [#34 — Implement bounded positional archive I/O and owned lifetimes](issues/34-implement-bounded-positional-archive-i-o-and-owned-lifetimes.md) | closed |
| [#35 — Implement safe staging, publication, rollback, and extraction containment](issues/35-implement-safe-staging-publication-rollback-and-extraction-containment.md) | closed |
| [#36 — Implement validation, failures, diagnostics, progress, and cancellation](issues/36-implement-validation-failures-diagnostics-progress-and-cancellation.md) | closed |
| [#37 — Implement the TES3 / Morrowind BSA vertical slice](issues/37-implement-the-tes3-morrowind-bsa-vertical-slice.md) | closed |
| [#38 — Implement the TES4 / Oblivion BSA vertical slice](issues/38-implement-the-tes4-oblivion-bsa-vertical-slice.md) | closed |
| [#39 — Implement the Fallout 4 General BA2 vertical slice](issues/39-implement-the-fallout-4-general-ba2-vertical-slice.md) | closed |
| [#40 — Implement the Fallout 4 DDS BA2 vertical slice](issues/40-implement-the-fallout-4-dds-ba2-vertical-slice.md) | closed |
| [#41 — Establish the Interface Candidate after representative Archive Families](issues/41-establish-the-interface-candidate-after-representative-archive-families.md) | closed |
| [#42 — Implement BSA for Fallout 3, New Vegas, and Skyrim LE](issues/42-implement-bsa-for-fallout-3-new-vegas-and-skyrim-le.md) | closed |
| [#43 — Integrate and qualify the Windows x64 LZ4 runtime](issues/43-integrate-and-qualify-the-windows-x64-lz4-runtime.md) | closed |
| [#44 — Implement BSA for Skyrim SE and AE](issues/44-implement-bsa-for-skyrim-se-and-ae.md) | closed |
| [#45 — Implement Starfield General BA2](issues/45-implement-starfield-general-ba2.md) | closed |
| [#46 — Implement Starfield DDS BA2](issues/46-implement-starfield-dds-ba2.md) | closed |
| [#47 — Implement Fallout 4 BA2 v7 and v8 decoding](issues/47-implement-fallout-4-ba2-v7-and-v8-decoding.md) | open |
| [#48 — Introduce bounded deterministic parallel archive operations](issues/48-introduce-bounded-deterministic-parallel-archive-operations.md) | open |
| [#49 — Complete the cross-family BSArch-compatible CLI](issues/49-complete-the-cross-family-bsarch-compatible-cli.md) | open |
| [#50 — Complete the mandatory Automated Conformance matrix](issues/50-complete-the-mandatory-automated-conformance-matrix.md) | open |
| [#51 — Freeze the JBSA 1.0 public archive interface](issues/51-freeze-the-jbsa-1-0-public-archive-interface.md) | open |
| [#52 — Qualify jlibdeflate and select the standard zlib dispatch](issues/52-qualify-jlibdeflate-and-select-the-standard-zlib-dispatch.md) | open |
| [#53 — Assemble and verify the self-contained Windows x64 application image](issues/53-assemble-and-verify-the-self-contained-windows-x64-application-image.md) | open |
| [#54 — Complete release provenance, notices, and operator documentation](issues/54-complete-release-provenance-notices-and-operator-documentation.md) | open |
| [#55 — Run first-release performance-v1 qualification and establish the baseline](issues/55-run-first-release-performance-v1-qualification-and-establish-the-baseline.md) | open |
| [#56 — Qualify Binary Conformance cases on a second Windows x64 CPU](issues/56-qualify-binary-conformance-cases-on-a-second-windows-x64-cpu.md) | closed |
| [#57 — Audit release bytes, licenses, notices, SBOM, and provenance](issues/57-audit-release-bytes-licenses-notices-sbom-and-provenance.md) | open |
| [#58 — Run writable-family Release Qualification with games and official tools](issues/58-run-writable-family-release-qualification-with-games-and-official-tools.md) | open |
| [#59 — Approve the JBSA 1.0 specification and release gate](issues/59-approve-the-jbsa-1-0-specification-and-release-gate.md) | open |
| [#60 — Publish the first public JBSA GitHub Release](issues/60-publish-the-first-public-jbsa-github-release.md) | open |
| [#61 — Implement compact generated conformance and risk-based performance assurance](issues/61-implement-assurance-v2.md) | closed |

## Comments

No comments at migration time.
