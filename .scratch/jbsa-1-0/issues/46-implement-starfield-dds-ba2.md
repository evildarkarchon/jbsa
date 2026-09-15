# Implement Starfield DDS BA2

Status: ready-for-agent
State: open
GitHub issue: #46
Source: https://github.com/evildarkarchon/jbsa/issues/46
Author: evildarkarchon
Created: 2026-09-03T06:54:13Z
Source updated: 2026-09-03T06:54:13Z
Closed: none
Migrated: 2026-09-10
Labels: ready-for-agent
Assignees: none
Blocked by: [#45](../issues/45-implement-starfield-general-ba2.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-14
Triage rationale: #45 is closed; this ticket's Assurance v2 contract is current, it is fully specified, and no open prerequisites remain.

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

### LZ4 performance gate carried forward from #43

Specification 0.14.0 permits the initial internal runtime to enable family
implementation; it does not qualify this consumer. The
[runtime impact manifest](../../../docs/reviews/issue43-lz4-runtime/performance-impact.json)
retains 84 Starfield DDS method-3 raw-LZ4 assignments. This ticket's formal
targeted conformance/performance evidence remains required before merge. #45
remains its prerequisite.

### Assurance v2 completion contract — 2026-09-14

The active assurance namespace is `JBSA-ASR-*`; `JBSA-CONF-*` and
`JBSA-PERF-*` are frozen historical mappings. This Archive Family must extend
the compact capability and scenario sources with its stable DDS behavioral
variants and cover bidirectional oracle differentials, DirectXTex or equivalent
independent validation, malformed and bounded-resource behavior, reconstruction,
and applicable interactions through generated Assurance Scenarios.

Performance work is limited to the curated DDS zlib/raw-LZ4 chunk,
reconstruction, random-access, peak-memory, and associated pack output-size
lanes. No expanded CV1 or performance-v1 packet is required. The dependency on
#45 remains unchanged.

### Dependency reassessment after #45 — 2026-09-14

Ticket #45 is closed with the shared Starfield General v2/v3 header, codec,
validation, oracle, and performance paths qualified. This ticket has no
remaining open prerequisite and is ready for agent implementation under its
Assurance v2 completion contract.
