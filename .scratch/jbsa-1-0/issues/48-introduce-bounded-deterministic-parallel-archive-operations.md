# Introduce bounded deterministic parallel archive operations

Status: none
State: closed
GitHub issue: #48
Source: https://github.com/evildarkarchon/jbsa/issues/48
Author: evildarkarchon
Created: 2026-09-03T06:54:17Z
Source updated: 2026-09-03T06:54:17Z
Closed: 2026-09-25
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#47](../issues/47-implement-fallout-4-ba2-v7-and-v8-decoding.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-25
Triage rationale: Implemented bounded ordered pack and extraction workers, public concurrency tests, and a local scaling checkpoint.

## Original issue body

## Objective

Add operation-owned bounded platform-worker scheduling beneath the caller-thread coordinator without changing single-worker semantics or observable ordering.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-SCHED-*
- JBSA-OPS-*
- JBSA-PERF-*
- JBSA-CONF-*

## Acceptance

- Complete preflight and Logical Plan Order assignment before task admission or destination effects and preserve UP_TO(1) as the sequential correctness mode.
- Implement AUTOMATIC and bounded parallelism with at most 2N admitted tasks plus heap, native, scratch, handle, and result-slot credits.
- Stage private worker results while the coordinator alone sequences hashing/sharing decisions, archive writes, splits, publication, progress, and failures.
- Prove worker-count-independent bytes where deterministic, semantic output, diagnostics, Primary/Secondary Failures, cancellation, rollback, and return-after-settlement behavior.
- Pass bounded/out-of-order stress and record 1/2/4/8/16-worker scaling, throughput, memory, and backpressure qualification before merge.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### 2026-09-25 implementation outcome

Added operation-owned bounded platform workers, private staged results, ordered
coordinator consumption and publication, preadmitted resource credits, and
noninterrupting settlement. Public tests cover worker-count equivalence,
sharing and splits, compressed paths, ordered failures, cancellation,
backpressure, and independent operations. The local 1/2/4/8/16-worker
throughput, output-size, and sampled heap checkpoint is recorded at
`docs/development/evidence/issue48-parallel/`.
