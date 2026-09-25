# Run writable-family Release Qualification with games and official tools

Status: needs-triage
State: open
GitHub issue: #58
Source: https://github.com/evildarkarchon/jbsa/issues/58
Author: evildarkarchon
Created: 2026-09-03T06:54:41Z
Source updated: 2026-09-03T06:54:41Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#50](../issues/50-complete-the-mandatory-automated-conformance-matrix.md), [#53](../issues/53-assemble-and-verify-the-self-contained-windows-x64-application-image.md)
Parent: [#23](../map.md)

Intended owner: human
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #50, #53.

## Original issue body

## Objective

Collect current manual Windows evidence that final encoded archives for every writable Archive Family are accepted by the relevant game or official tool.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-REL-*
- JBSA-CONF-*

## Acceptance

- Use the exact release candidate, codec/profile identity, representative fixtures, and documented qualification procedure for every writable Archive Family.
- Record game/tool version, environment, archive configuration, fixture and output hashes, commands, observed acceptance, diagnostics, and operator sign-off.
- Cover TES3, every writable BSA version, Fallout 4 BA2 v1 General/DDS, and Starfield BA2 v2/v3 General/DDS as applicable.
- Keep proprietary inputs and resulting game assets outside version control while storing redistributable evidence and attestations.
- Block complete Encode Conformance and the public release on any missing, stale, ambiguous, or failed qualification result.

## Ownership

Human-driven when unblocked. Preparation may be automated, but the named evidence or approval must come from the maintainer or operator.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
