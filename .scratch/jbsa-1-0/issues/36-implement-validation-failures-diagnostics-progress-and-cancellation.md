# Implement validation, failures, diagnostics, progress, and cancellation

Status: none
State: closed
GitHub issue: #36
Source: https://github.com/evildarkarchon/jbsa/issues/36
Author: evildarkarchon
Created: 2026-09-03T06:53:49Z
Source updated: 2026-09-06T04:09:30Z
Closed: 2026-09-06T04:09:29Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#35](../issues/35-implement-safe-staging-publication-rollback-and-extraction-containment.md), [#34](../issues/34-implement-bounded-positional-archive-i-o-and-owned-lifetimes.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Implement the shared caller-observable operation semantics that every Archive Family slice must exercise consistently.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-OPS-*
- JBSA-SCHED-*

## Acceptance

- Implement layered recognition, structure, payload, policy, source, destination, and capability validation without adding an unsupported full-archive validate operation.
- Implement stable Conformance Diagnostic identifiers, immutable Diagnostic Policy, checked Failure Kind outcomes, and deterministic Primary and bounded Secondary Failure selection.
- Implement semantic monotonic Progress Snapshots whose timing and cadence remain non-normative and isolate observer failures as specified.
- Implement explicit Cooperative Cancellation rather than thread interruption, including pre/post-Publication Commit behavior and deterministic cleanup settlement.
- Test failure ordering, streaming late-failure behavior, cancellation races, rollback, residual artifacts, and exact operation reports through the public interface.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
