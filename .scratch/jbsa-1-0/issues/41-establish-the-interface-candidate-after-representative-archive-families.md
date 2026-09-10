# Establish the Interface Candidate after representative Archive Families

Status: ready-for-agent
State: open
GitHub issue: #41
Source: https://github.com/evildarkarchon/jbsa/issues/41
Author: evildarkarchon
Created: 2026-09-03T06:54:01Z
Source updated: 2026-09-03T06:54:01Z
Closed: none
Migrated: 2026-09-10
Labels: ready-for-agent
Assignees: none
Blocked by: [#37](../issues/37-implement-the-tes3-morrowind-bsa-vertical-slice.md), [#40](../issues/40-implement-the-fallout-4-dds-ba2-vertical-slice.md), [#39](../issues/39-implement-the-fallout-4-general-ba2-vertical-slice.md), [#38](../issues/38-implement-the-tes4-oblivion-bsa-vertical-slice.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Explicit ownership and acceptance criteria; all imported native prerequisites are closed.

## Original issue body

## Objective

Review and stabilize the public interface after TES3, versioned BSA, General BA2, and DDS BA2 have exercised every major structural branch.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-LIB-*
- JBSA-OPS-*
- JBSA-IO-*

## Acceptance

- Audit public packages, names, immutable values, requests, results, errors, diagnostics, ownership, resource limits, progress, cancellation, and artifact semantics against all four representative consumers.
- Run compiled CLI-like and embedded consumer contract tests and the deletion/depth tests for the deep library module.
- Remove accidental public provider, storage, executor, buffer, pool, transaction, native, and third-party seams while preserving required caller control.
- Resolve every interface finding with specification and requirement-registry updates plus compatibility tests.
- Declare the Interface Candidate milestone; later breaking corrections require explicit evidence and gate reset, while 1.0 remains unfrozen.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### Post-1.0 Xbox DDS deferral

On 2026-09-10 the maintainer clarified during implementation: “Those unqualified
results pertain to a feature slated for a later build and should be deferred
until after version 1.0.” This refers to the outstanding Xbox DDS feature and
qualification results identified in the interface audit. Xbox encode,
reconstruction, target inference and their qualification are post-1.0 work;
PC rejection of Xbox inputs remains in scope. JBSA-SCOPE-009 records this
decision without deleting the historical Xbox cases or the public target value.

### Interface audit implementation

The [audit](../../../docs/development/interface-candidate.md) records the public
requirement trace, four-family compiled consumers, module deletion/depth checks,
JDK mechanism boundary guard, and specification corrections. No production API
signature was removed. Specification 0.13.0 changes the governing evidence
identity; successor conformance goldens require a separate reviewed rebaseline
before Interface Candidate can be declared. The issue remains open until that
gate is evaluated on the final immutable candidate.

The maintainer explicitly approved activation of review packet
`a2e9702750796da6cad78a0e36458475c14bbdaf9b2d3da69c16825710b93c3c`.
The approved 109-object rebaseline was activated after the immutable-identity
verifier passed. Final candidate execution and milestone declaration follow
that recorded approval; no Xbox result is promoted to passing evidence.
