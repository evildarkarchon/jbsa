# Implement safe staging, publication, rollback, and extraction containment

Status: none
State: closed
GitHub issue: #35
Source: https://github.com/evildarkarchon/jbsa/issues/35
Author: evildarkarchon
Created: 2026-09-03T06:53:46Z
Source updated: 2026-09-06T00:20:30Z
Closed: 2026-09-06T00:20:30Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#34](../issues/34-implement-bounded-positional-archive-i-o-and-owned-lifetimes.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Implement bounded spill-backed staging and the Windows filesystem safety model for archive outputs, split sets, extraction trees, replacement, rollback, and residual cleanup.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-IO-*
- JBSA-OPS-*

## Acceptance

- Implement adjacent private staging, explicit FAIL/REPLACE behavior, atomic per-artifact publication, and ordered split-set publication.
- Implement rollback/restoration and exact Artifact State and Residual Artifact reporting when cleanup or replacement fails.
- Contain extraction against traversal, absolute paths, alternate data streams, symlink/junction/reparse-point escapes, and Windows name/case collisions.
- Preserve per-file atomicity for existing extraction trees without claiming operation-wide atomic visibility, crash recovery, forced durability, or privileged race protection.
- Verify Publication Commit behavior and ensure pre-commit accepted cancellation prevents externally visible publication.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
