# Freeze the JBSA 1.0 public archive interface

Status: none
State: closed
GitHub issue: #51
Source: https://github.com/evildarkarchon/jbsa/issues/51
Author: evildarkarchon
Created: 2026-09-03T06:54:24Z
Source updated: 2026-09-03T06:54:24Z
Closed: 2026-09-29
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#50](../issues/50-complete-the-mandatory-automated-conformance-matrix.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-29
Triage rationale: Completed after the all-family consumer and API baseline checks, a full local verification, and the hosted exact-candidate Interface Freeze evaluation.

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

## Outcome

The [Interface Freeze audit](../../../docs/development/interface-freeze.md)
traces every `JBSA-LIB-*` and `JBSA-OPS-*` contract and the `JBSA-REL-006/007`
gate to public API checks. The compiled source and JVM descriptor baselines
cover 77 exported types. Isolated embedded and CLI-like consumers exercised all
eight Archive Families with worker limits 1 and 4. The module architecture
check found no third-party or internal implementation types in caller-visible
signatures. The Java source changes are three corrections to obsolete
pre-1.0 Javadocs; no executable production behavior changed.

The complete local Windows `gradle clean verify --no-daemon` gate passed. PR
[#65](https://github.com/evildarkarchon/jbsa/pull/65) passed all nine hosted
checks on head `16e00e1b20b63be2663342d2ad6f593c4e4f5bdb`. Its tested
merge commit `8c7fbcee2c8f270fea5b4c3a739e9fe9b46b7a69` produced a
content-addressed hosted `full` Evidence Capsule with 138 of 138 scenarios
`PASS`, none failed or invalid. The exact candidate, specification, plan,
profiles, provider, corpus, Oracle, validator, toolchain, platform, artifact
location, evaluator, time, and procedure are retained in the
[gate evaluation](../../../docs/development/evidence/issue51-interface-freeze/gate-evaluation.json).
The documentation-only closure commit must receive fresh hosted CI; its exact
candidate is evaluated through PR #65's subsequent checks and capsule.

This records Interface Freeze only for a candidate with its own passing hosted
evidence. Later breaking changes require an explicit decision, source/binary
compatibility assessment, specification revision, and reset of affected gates.
