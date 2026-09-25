# Implement the TES3 / Morrowind BSA vertical slice

Status: none
State: closed
GitHub issue: #37
Source: https://github.com/evildarkarchon/jbsa/issues/37
Author: evildarkarchon
Created: 2026-09-03T06:53:51Z
Source updated: 2026-09-08T10:33:21Z
Closed: 2026-09-08T10:33:21Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#36](../issues/36-implement-validation-failures-diagnostics-progress-and-cancellation.md), [#31](../issues/31-build-the-pinned-oracle-and-conformance-case-harness.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Deliver the first complete stored-archive walking slice across detection, inspection, owned reading, extraction, packing, CLI behavior, and conformance evidence.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-TES3-*
- JBSA-LIB-*
- JBSA-CLI-*
- JBSA-CONF-*

## Acceptance

- Implement unversioned TES3 recognition, layout, names, hashes, ordering, validation, decode, extraction, and encode behavior; compression requests remain intentionally ignored as specified.
- Exercise detect, inspect, open/openContent, extract, and pack through the public interface and applicable thin-CLI commands without duplicated business logic.
- Add committed independent fixtures, malformed and boundary cases, optional local-corpus/oracle coverage, and bidirectional semantic differentials.
- Qualify the designated stored Binary Conformance candidate only if its repeatability evidence passes; otherwise report only earned semantic conformance.
- Review Contract Baseline ergonomics with compiled CLI-like and embedded consumers and resolve findings before the interface spreads.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
