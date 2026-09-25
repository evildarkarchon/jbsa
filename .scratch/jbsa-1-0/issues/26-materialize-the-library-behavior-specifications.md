# Materialize the library behavior specifications

Status: none
State: closed
GitHub issue: #26
Source: https://github.com/evildarkarchon/jbsa/issues/26
Author: evildarkarchon
Created: 2026-09-03T06:53:25Z
Source updated: 2026-09-03T09:30:00Z
Closed: 2026-09-03T09:30:00Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#24](../issues/24-publish-the-specification-framework-and-requirement-registry.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Publish the complete deep-module interface and caller-observable operation contract while keeping storage, providers, scheduling mechanics, and transactions internal.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-LIB-*
- JBSA-OPS-*
- JBSA-IO-*
- JBSA-CODEC-*
- JBSA-SCHED-*

## Acceptance

- Create library-interface, operation-semantics, io-and-publication, codecs, and execution-model specifications.
- Specify BethesdaArchives.standard(), immutable requests/results, OpenArchive ownership, entry channels, long-valued sizes and offsets, ResourceLimits, and OperationControl.
- Specify Archive Assessment, Validation Extent, Diagnostic Policy, Failure Kind, deterministic Primary and Secondary Failures, Progress Snapshots, Cooperative Cancellation, Artifact State, and Residual Artifacts.
- Specify positional I/O, Windows deny-write/delete lifetimes, bounded streaming, spill staging, extraction containment, Publication Commit, replacement, rollback, and durability limits.
- Keep provider selection, executors, buffers, pools, spill mechanics, callback cadence, codec thresholds, native details, and generalized storage ports outside the public interface.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
