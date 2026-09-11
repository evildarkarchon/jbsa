# Implement BSA for Fallout 3, New Vegas, and Skyrim LE

Status: none
State: closed
GitHub issue: #42
Source: https://github.com/evildarkarchon/jbsa/issues/42
Author: evildarkarchon
Created: 2026-09-03T06:54:04Z
Source updated: 2026-09-03T06:54:04Z
Closed: 2026-09-11
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#41](../issues/41-establish-the-interface-candidate-after-representative-archive-families.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Explicit maintainer approval recorded; all 52 goldens activated and all 52 active-catalog cases passed under specification 0.14.0.

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

### Implementation and review, 2026-09-11

Implemented 0x68 through the common reader/writer and JDK zlib, including mixed
compression, explicit embedded prefixes, unsigned family hashing, flags,
ordering, metadata limits and all three CLI aliases. The public model is unchanged.
See the [implementation and requirement trace](../../../docs/development/bsa-068.md).

The seven-module Java 25 clean build passed: 445 reported tests, zero failures
or errors, seven optional skips. All new game vectors and pinned local oracle
cross-decodes passed. The targeted performance investigation found identical
output sizes and no version-specific heap increase in fresh bounded JVMs.

The complete [CV1 review packet](../../../docs/reviews/issue42-cv1/README.md)
passed all 52 proposed cases, including real 4.25-GiB splitting and cleanup.
The proposal catalog SHA-256 is
`ef912247053ac719165b1c6e9af90db3b4c3d714245061eed87683791f70aa2d`.
Its final execution report SHA-256 is
`07835137b2a83d413b8c861ebf12ff4385fbacea8d4f5fe0d1cb589f41aac776`.
Standards and Spec review findings were resolved. No proprietary assets or
Reference Snapshot files were modified.

The active conformance catalog and approval records remain unchanged. This
ticket stays open until the maintainer explicitly approves the packet and its
goldens are activated and checked. Stored-byte candidates do not claim Binary
Conformance, and no game, ACP-932, or formal PV1 qualification is inferred.
Dependent #44 remains blocked while this ticket is open.

### Completion — 2026-09-11

The maintainer explicitly approved the exact packet above. The immutable
rebaseline verifier passed before all 52 approval records and the reviewed
catalog were activated. A fresh ordinary runner passed all 52 BSA 0x68 cases,
including real large splitting; see the [active summary](../../../docs/reviews/issue42-cv1/evidence/active-summary.json)
and [approval record](../../../docs/reviews/issue42-cv1/activation.json).

Activation exposed a reporting-only defect where PowerShell-wrapped strings
became property objects. A failing regression preceded the fix, evidence and
execution regressions passed, and fresh registrations bind the corrected
reporting helper. Approved golden bytes are unchanged. The scoped run does not
claim whole-release Automated Conformance or additional platform qualification.
Issue #44 was reassessed and is now ready for an agent; its qualification gates
remain in force.
