# Implement BSA for Fallout 3, New Vegas, and Skyrim LE

Status: ready-for-agent
State: open
GitHub issue: #42
Source: https://github.com/evildarkarchon/jbsa/issues/42
Author: evildarkarchon
Created: 2026-09-03T06:54:04Z
Source updated: 2026-09-03T06:54:04Z
Closed: none
Migrated: 2026-09-10
Labels: ready-for-agent
Assignees: none
Blocked by: [#41](../issues/41-establish-the-interface-candidate-after-representative-archive-families.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: All prerequisites are closed after the Interface Candidate passed in #41. Acceptance remains current under specification 0.13.0; ready for agent implementation.

## Original issue body

## Objective

Extend the common BSA implementation to version 0x68 without forking the public model or duplicating version-0x67 behavior.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-BSA-*
- JBSA-CLI-*
- JBSA-CONF-*

## Acceptance

- Implement BSA 0x68 stored, zlib, and mixed-entry decode/encode behavior, unsigned TES5 hashing, embedded-name prefixes, flags, ordering, and validation.
- Reuse the common BSA and JDK zlib implementations while isolating version-specific records and name/hash rules internally.
- Add family-specific library/CLI cases, game-specific committed fixtures, optional local-corpus observations, malformed inputs, and resource-limit coverage.
- Pass bidirectional oracle differentials, independent validation, applicable CV1 cases, and qualified stored Binary Conformance candidates only.
- Run targeted regression performance cases and investigate any material change to the established zlib, I/O, memory, size, or random-access evidence.

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
