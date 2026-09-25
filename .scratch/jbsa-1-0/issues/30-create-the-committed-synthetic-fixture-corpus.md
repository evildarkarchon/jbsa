# Create the committed synthetic Fixture Corpus

Status: none
State: closed
GitHub issue: #30
Source: https://github.com/evildarkarchon/jbsa/issues/30
Author: evildarkarchon
Created: 2026-09-03T06:53:35Z
Source updated: 2026-09-05T09:12:49Z
Closed: 2026-09-05T09:12:49Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#29](../issues/29-establish-licensing-and-dependency-provenance-gates.md), [#28](../issues/28-bootstrap-the-java-25-maven-and-jpms-reactor.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Create redistributable deterministic fixtures, generators, manifests, malformed and boundary inputs, and golden-storage rules without committing proprietary game data.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-CONF-*
- JBSA-LIC-*

## Acceptance

- Define the fixture manifest and generator schema with creator/source, SPDX license, command/options, Reference Snapshot revision, oracle digest when applicable, input/output SHA-256, date, and redistribution class.
- Create small structural, boundary, malformed, compression, name-encoding, ordering, overlay, split, and resource-limit fixtures needed by conformance-v1.
- Provide independently authored generated coverage for supported-but-unused Fallout 4 General BA2 v7 and Starfield General BA2 v3 combinations.
- Define immutable content-addressed golden storage and deliberate rebaseline records containing old/new hashes, oracle identity, rationale, and approval.
- Keep the ignored 12-archive local corpus discoverable but read-only and outside every committed or assembled artifact.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
