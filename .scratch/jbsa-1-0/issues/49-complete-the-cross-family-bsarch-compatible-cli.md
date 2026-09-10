# Complete the cross-family BSArch-compatible CLI

Status: needs-triage
State: open
GitHub issue: #49
Source: https://github.com/evildarkarchon/jbsa/issues/49
Author: evildarkarchon
Created: 2026-09-03T06:54:19Z
Source updated: 2026-09-03T06:54:19Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#47](../issues/47-implement-fallout-4-ba2-v7-and-v8-decoding.md), [#48](../issues/48-introduce-bounded-deterministic-parallel-archive-operations.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #47, #48.

## Original issue body

## Objective

Complete the strict thin Windows CLI contract across every Archive Family and operation using only the public jbsa library interface.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-CLI-*
- JBSA-COMPAT-*
- JBSA-DIST-*

## Acceptance

- Complete command and switch parsing, help/version, information/list/dump, pack/unpack, source overlays, defaults, format selection, multithreading controls, and invalid-invocation behavior.
- Map only public library requests, reports, failures, diagnostics, progress, cancellation, and artifacts to deterministic UTF-8 records, streams, exit statuses, and filesystem effects.
- Implement the safe default and immutable all-or-nothing bsarch-1.0/v1 Compatibility Profile, including every qualified deviation and implicit DDS compression rule.
- Implement Ctrl+C through Cooperative Cancellation, deterministic progress presentation, cleanup, and no business logic or reference-code adaptation in the CLI.
- Pass the complete CLI Observation matrix through library-backed integration tests, including single/multithreaded equivalence and all Archive Families.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
