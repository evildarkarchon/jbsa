# Complete the mandatory Automated Conformance matrix

Status: ready-for-agent
State: open
GitHub issue: #50
Source: https://github.com/evildarkarchon/jbsa/issues/50
Author: evildarkarchon
Created: 2026-09-03T06:54:21Z
Source updated: 2026-09-03T06:54:21Z
Closed: none
Migrated: 2026-09-10
Labels: ready-for-agent
Assignees: none
Blocked by: [#49](../issues/49-complete-the-cross-family-bsarch-compatible-cli.md), [#31](../issues/31-build-the-pinned-oracle-and-conformance-case-harness.md), [#48](../issues/48-introduce-bounded-deterministic-parallel-archive-operations.md), [#47](../issues/47-implement-fallout-4-ba2-v7-and-v8-decoding.md), [#61](../issues/61-implement-assurance-v2.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-28
Triage rationale: #31, #47, #48, #49, and #61 are closed. The ticket is unblocked and remains agent-owned. The current gate is compact generated conformance, not completion of every historical committed CV1 row.

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

### Assurance v2 gate — 2026-09-13

This ticket now closes the compact generated Automated Conformance gate defined
by [#61](../issues/61-implement-assurance-v2.md). It does not require completion,
rebinding, or case-level publication of every row in the historical committed
CV1 catalog.

At completion, the compact capability and semantic-scenario sources must
deterministically generate every applicable required case; the run must pass all
required generated assertions and retain a compact traceability/result capsule.
The gate continues to cover decode and encode differentials, independent
validators, malformed inputs, interactions, warnings and errors, resource
limits, cancellation, rollback, extraction containment, sequential/parallel
equivalence, and JBSA determinism. Unknown impact remains fail-closed. Binary
Conformance may be claimed only for individually supported cases, but earning a
Binary Conformance claim is not mandatory for this gate.

This current acceptance supersedes conflicting expanded-CV1 wording in the
original body while retaining that text as historical context.

### Issue #50 deviation approval — 2026-09-28

The maintainer replied "looks good" to the explicit request to approve the 12
in-scope `bsarch-1.0/v1` deviation dispositions and pinned-oracle observations
in `docs/development/evidence/issue50-automated-conformance/README.md`. This
approves the exact rows in `tests/assurance/deviation-review.json` against
oracle observation digest
`38307fddcd7bfbf2840de419ea63dc19480dc0deccd7e46375942ac6ef2642d9`.
Xbox filename inference remains deferred under JBSA-SCOPE-009. A change to
the pinned oracle, reviewed observations, profile specification, or product
implementation requires revalidation. The Automated Conformance gate remains
open until the exact candidate passes hosted full CI and its result capsule is
retained.
