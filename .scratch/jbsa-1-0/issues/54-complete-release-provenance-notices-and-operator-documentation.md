# Complete release provenance, notices, and operator documentation

Status: needs-triage
State: open
GitHub issue: #54
Source: https://github.com/evildarkarchon/jbsa/issues/54
Author: evildarkarchon
Created: 2026-09-03T06:54:31Z
Source updated: 2026-09-03T06:54:31Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#51](../issues/51-freeze-the-jbsa-1-0-public-archive-interface.md), [#52](../issues/52-qualify-jlibdeflate-and-select-the-standard-zlib-dispatch.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #51, #52.

## Original issue body

## Objective

Publish the complete operator-facing and release evidence package for the frozen interface, selected provider profile, compatibility behavior, limitations, and safe operation.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-DIST-*
- JBSA-LIC-*
- JBSA-REL-*
- JBSA-CODEC-*

## Acceptance

- Publish the digest-identified codec-profile manifest with exact providers, parameters, dispatch rules, fallback, native configuration, and requalification identity.
- Complete library and CLI usage, native-access, resource-limit, progress/cancellation, partial-output, residual-cleanup, profile/deviation, and troubleshooting documentation.
- Publish dependency/native notices, fixture provenance, SBOM/provenance procedures, license obligations, checksums, and release-evidence locations.
- Document oracle attestation limits, unsupported operations, random-access meaning, consistency rather than snapshot semantics, atomicity/durability limits, and cancellation caveats.
- Verify every public claim and example against requirement identifiers and current Automated Conformance evidence without overstating Binary or Release Qualification.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
