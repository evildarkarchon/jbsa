# Establish licensing and dependency provenance gates

Status: none
State: closed
GitHub issue: #29
Source: https://github.com/evildarkarchon/jbsa/issues/29
Author: evildarkarchon
Created: 2026-09-03T06:53:32Z
Source updated: 2026-09-04T00:52:30Z
Closed: 2026-09-04T00:52:30Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#26](../issues/26-materialize-the-library-behavior-specifications.md), [#25](../issues/25-materialize-the-archive-family-specifications.md), [#27](../issues/27-materialize-the-cli-and-qualification-specifications.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Make independent authorship, fixture rights, dependency/native provenance, notices, SBOM generation, and release-input inspection enforceable from the first implementation commit.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-LIC-*
- JBSA-DIST-*

## Acceptance

- Add Apache-2.0 LICENSE and NOTICE materials plus the CC0-1.0 synthetic-fixture policy and required SPDX/REUSE metadata.
- Document and enforce the prohibition on copied, mechanically translated, or structure-preserving adaptation from the Reference Snapshot.
- Create maintained dependency and native-byte inventories with license, source, version, checksum, notice, and redistribution evidence.
- Generate an SBOM and required notices and prevent proprietary/local fixtures, unapproved native payloads, and ambiguous artifacts from entering release inputs.
- Document the DCO path for external contributions and make unclear rights, branding, EULA, patent, or provenance evidence a stop-before-merge or release condition.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
