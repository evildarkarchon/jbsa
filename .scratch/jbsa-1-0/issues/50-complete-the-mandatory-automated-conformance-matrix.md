# Complete the mandatory Automated Conformance matrix

Status: needs-triage
State: open
GitHub issue: #50
Source: https://github.com/evildarkarchon/jbsa/issues/50
Author: evildarkarchon
Created: 2026-09-03T06:54:21Z
Source updated: 2026-09-03T06:54:21Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#49](../issues/49-complete-the-cross-family-bsarch-compatible-cli.md), [#31](../issues/31-build-the-pinned-oracle-and-conformance-case-harness.md), [#48](../issues/48-introduce-bounded-deterministic-parallel-archive-operations.md), [#47](../issues/47-implement-fallout-4-ba2-v7-and-v8-decoding.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #49, #48, #47.

## Original issue body

## Objective

Close every hosted-CI-runnable conformance-v1 obligation across formats, operations, codecs, interactions, malformed inputs, resources, concurrency, deviations, CLI behavior, and filesystem safety.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-CONF-*
- JBSA-COMPAT-*
- JBSA-CLI-*

## Acceptance

- Run every applicable CV1 case with mandatory assertions and publish case-level evidence; no percentage, waiver, expected failure, or invalid result counts as passing.
- Complete decode and encode differentials, independent validators, interaction matrices, warning/error policies, malformed inputs, resource limits, cancellation, rollback, and extraction containment.
- Prove sequential/parallel semantic equivalence and JBSA determinism across worker counts while restricting Binary Conformance to separately qualified cases.
- Qualify safe-default and bsarch-1.0/v1 behavior independently and ensure every deviation has identity, evidence, safety analysis, tests, approval, and revalidation triggers.
- Resolve each contradiction or unknown with evidence or keep its affected case blocked; publish the precise Automated Conformance claim earned by hosted CI.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
