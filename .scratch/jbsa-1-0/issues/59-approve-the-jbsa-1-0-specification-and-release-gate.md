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
Blocked by: [#51](../issues/51-freeze-the-jbsa-1-0-public-archive-interface.md), [#58](../issues/58-run-writable-family-release-qualification-with-games-and-official-tools.md), [#55](../issues/55-run-first-release-performance-v1-qualification-and-establish-the-baseline.md), [#57](../issues/57-audit-release-bytes-licenses-notices-sbom-and-provenance.md)
Parent: [#23](../map.md)

Intended owner: human
Triage reviewed: 2026-09-13
Triage rationale: Reassess readiness after open prerequisites close: #51, #58, #55, #57. Final review uses the compact Assurance v2 traceability and result capsule; Binary Conformance is optional and case-scoped.

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

### Assurance v2 final review — 2026-09-13

The final human gate will review the compact Assurance v2 traceability and
release-evidence capsule produced by [#50](../issues/50-complete-the-mandatory-automated-conformance-matrix.md)
and [#55](../issues/55-run-first-release-performance-v1-qualification-and-establish-the-baseline.md),
not an expanded per-row CV1/performance-v1 proof archive.

Approval must confirm that each release-relevant requirement maps to its
applicable implementation, generated scenario, test or validator, curated
performance scenario, packaging evidence, or manual qualification; that all
required generated conformance scenarios pass; that all selected performance
metrics pass; and that artifact identities, limitations, deviations, and claims
are precise. Binary Conformance remains an optional, case-level claim. The lack
of a Binary Conformance claim is not by itself a release blocker, and this ticket
must not imply binary identity beyond any cases that were actually qualified.

This current acceptance supersedes conflicting mandatory-binary or expanded
proof-catalog implications above while retaining the original text as history.
