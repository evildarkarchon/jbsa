# Implement bounded positional archive I/O and owned lifetimes

Status: none
State: closed
GitHub issue: #34
Source: https://github.com/evildarkarchon/jbsa/issues/34
Author: evildarkarchon
Created: 2026-09-03T06:53:44Z
Source updated: 2026-09-05T12:06:05Z
Closed: 2026-09-05T12:06:05Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#33](../issues/33-establish-the-contract-baseline-public-archive-interface.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Implement the parent-owned positional-channel substrate, bounded exact reads, lazy payload access, and Windows lifetime behavior beneath the public interface.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-IO-*
- JBSA-LIB-*

## Acceptance

- Use checked long arithmetic and bounded positional FileChannel reads for headers, indexes, names, and payload spans.
- Open archives with the specified deny-write/delete Windows lifetime and keep parent/child close behavior linearizable and race-tested.
- Build eager bounded metadata indexes while keeping payload access lazy and sequential entry channels explicit.
- Enforce ResourceLimits for counts, names, spans, handles, heap, native memory, and scratch space without whole-archive buffering.
- Provide deterministic source/destination/capability failures without leaking provider or operating-system exception details through the public interface.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
