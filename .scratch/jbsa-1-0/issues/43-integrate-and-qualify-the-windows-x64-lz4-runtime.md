# Integrate and qualify the Windows x64 LZ4 runtime

Status: ready-for-agent
State: open
GitHub issue: #43
Source: https://github.com/evildarkarchon/jbsa/issues/43
Author: evildarkarchon
Created: 2026-09-03T06:54:06Z
Source updated: 2026-09-03T06:54:06Z
Closed: none
Migrated: 2026-09-10
Labels: ready-for-agent
Assignees: none
Blocked by: [#32](../issues/32-build-the-performance-v1-harness-and-benchmark-corpus.md), [#29](../issues/29-establish-licensing-and-dependency-provenance-gates.md), [#41](../issues/41-establish-the-interface-candidate-after-representative-archive-families.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: All prerequisites are closed after the Interface Candidate passed in #41. Acceptance remains current under specification 0.13.0; ready for agent implementation.

## Original issue body

## Objective

Provide required replaceable internal LZ4-frame and raw-LZ4 adapters with pinned native artifacts, bounded dispatch, deterministic behavior, and qualified Windows launch policy.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-CODEC-*
- JBSA-PERF-*
- JBSA-LIC-*

## Acceptance

- Pin LWJGL and upstream LZ4 artifacts and checksums, notices, native classifiers, and explicit Java 25 native-access requirements.
- Implement separate internal LZ4-frame and raw-LZ4 profiles with lazy native loading, capability preflight, bounded input/output checks, and provider-neutral failures.
- Keep native handles, buffers, dispatch thresholds, and adapter types out of the public interface and make failure before destination effects deterministic.
- Pass codec corpus, malformed-frame/block, memory-credit, cancellation-delay, deterministic-output, and native-loading tests.
- Run targeted throughput, memory, output-size, and launch qualification before the adapters may block or enable Archive Family slices.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### Readiness after #41

Reassessed on 2026-09-10: every listed prerequisite is closed. The acceptance
criteria remain applicable under specification 0.13.0 and this agent-owned
ticket is ready for implementation. No prerequisite link was removed.
