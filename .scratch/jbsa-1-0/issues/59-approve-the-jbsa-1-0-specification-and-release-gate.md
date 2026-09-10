# Approve the JBSA 1.0 specification and release gate

Status: needs-triage
State: open
GitHub issue: #59
Source: https://github.com/evildarkarchon/jbsa/issues/59
Author: evildarkarchon
Created: 2026-09-03T06:54:43Z
Source updated: 2026-09-03T06:54:43Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#56](../issues/56-qualify-binary-conformance-cases-on-a-second-windows-x64-cpu.md), [#51](../issues/51-freeze-the-jbsa-1-0-public-archive-interface.md), [#58](../issues/58-run-writable-family-release-qualification-with-games-and-official-tools.md), [#55](../issues/55-run-first-release-performance-v1-qualification-and-establish-the-baseline.md), [#57](../issues/57-audit-release-bytes-licenses-notices-sbom-and-provenance.md)
Parent: [#23](../map.md)

Intended owner: human
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #51, #58, #55, #57.

## Original issue body

## Objective

Perform the final human gate over the frozen specification, traceability, conformance claims, performance evidence, packaging, compliance, documentation, and manual qualification.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-REL-*

## Acceptance

- Verify every requirement registry row has the required review, implementation, test, conformance, performance, package, or manual-release evidence.
- Approve the exact Automated Conformance, case-level Binary Conformance, Encode/Decode Conformance, and Release Qualification claims without implication beyond their evidence.
- Approve the final public interface baseline, immutable compatibility/codec profiles, deviations, known limitations, documentation, packaging, and artifact digests.
- Confirm all performance metrics pass independently and the first Performance Baseline is fixed and reproducible.
- Record explicit approval of the JBSA 1.0 release candidate or return named gates to open state with required remediation.

## Ownership

Human-driven when unblocked. Preparation may be automated, but the named evidence or approval must come from the maintainer or operator.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
