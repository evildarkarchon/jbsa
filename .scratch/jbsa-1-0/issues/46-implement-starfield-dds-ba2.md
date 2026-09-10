# Implement Starfield DDS BA2

Status: needs-triage
State: open
GitHub issue: #46
Source: https://github.com/evildarkarchon/jbsa/issues/46
Author: evildarkarchon
Created: 2026-09-03T06:54:13Z
Source updated: 2026-09-03T06:54:13Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#45](../issues/45-implement-starfield-general-ba2.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #45.

## Original issue body

## Objective

Extend the shared DDS BA2 and DDS-envelope implementations to Starfield v2 zlib and v3 raw-LZ4 chunk behavior.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-DX10-*
- JBSA-DDS-*
- JBSA-CODEC-*
- JBSA-CLI-*
- JBSA-CONF-*
- JBSA-PERF-*

## Acceptance

- Implement DX10 BA2 v2 and v3 extra-header and compression-method behavior across DDS metadata, mip chunks, reconstruction, decode, and encode paths.
- Support the specified v2 zlib and v3 method-3 raw-LZ4 profiles, including the Starfield default and allowed zlib behavior.
- Reuse common BA2/DDS implementations and internal codecs without leaking format-specific or native details into the public interface.
- Pass bidirectional oracle differentials, DirectXTex or equivalent validation, committed fixtures, malformed/limit cases, reconstruction checks, and all CV1 cases.
- Run targeted Starfield DDS chunk, codec, memory, output-size, and random-access performance cases before merge.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
