# Freeze the JBSA 1.0 public archive interface

Status: needs-triage
State: open
GitHub issue: #51
Source: https://github.com/evildarkarchon/jbsa/issues/51
Author: evildarkarchon
Created: 2026-09-03T06:54:24Z
Source updated: 2026-09-03T06:54:24Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#50](../issues/50-complete-the-mandatory-automated-conformance-matrix.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #50.

## Original issue body

## Objective

Establish the 1.0 compatibility baseline only after all Archive Families, real consumers, native capability behavior, bounded scheduling, and Automated Conformance have exercised the deep interface.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-LIB-*
- JBSA-OPS-*
- JBSA-REL-*

## Acceptance

- Audit every public package, exported type, invariant, ordering rule, error mode, configuration, ownership contract, and performance characteristic against the normative specifications.
- Run source/binary compatibility baselining plus compiled CLI-like and embedded consumer suites across all Archive Families and sequential/parallel modes.
- Verify third-party types and internal storage, provider, scheduler, transaction, buffer, pool, native, and dispatch details remain absent from the public interface.
- Resolve all conformance-driven interface corrections and update requirement/test traceability before recording the freeze.
- Document that a later breaking change requires an explicit decision, compatibility assessment, specification revision, and reset of affected conformance/release gates.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
