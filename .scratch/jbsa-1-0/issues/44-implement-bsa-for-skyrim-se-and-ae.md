# Implement BSA for Skyrim SE and AE

Status: ready-for-agent
State: open
GitHub issue: #44
Source: https://github.com/evildarkarchon/jbsa/issues/44
Author: evildarkarchon
Created: 2026-09-03T06:54:08Z
Source updated: 2026-09-03T06:54:08Z
Closed: none
Migrated: 2026-09-10
Labels: ready-for-agent
Assignees: none
Blocked by: [#42](../issues/42-implement-bsa-for-fallout-3-new-vegas-and-skyrim-le.md), [#43](../issues/43-integrate-and-qualify-the-windows-x64-lz4-runtime.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-11
Triage rationale: Both #42 and #43 are closed. Acceptance remains current under specification 0.14.0; ready for agent implementation. The family consumer still requires its conformance and targeted performance qualification before merge.

## Original issue body

## Objective

Complete versioned-BSA support with BSA 0x69 stored and LZ4-frame behavior using the qualified native adapter.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-BSA-*
- JBSA-CODEC-*
- JBSA-CLI-*
- JBSA-CONF-*
- JBSA-PERF-*

## Acceptance

- Implement BSA 0x69 24-byte folder records, embedded names, stored/LZ4-frame/mixed entries, flags, hashes, ordering, validation, decode, and encode behavior.
- Use the qualified internal LZ4-frame profile with bounded streaming and deterministic capability failures; do not reinterpret raw LZ4 as framed LZ4.
- Add applicable library/CLI behavior, committed and optional local fixtures, malformed frames/records, cancellation, resource, and split coverage.
- Pass bidirectional oracle differentials, independent validation, applicable CV1 cases, and only earned stored Binary Conformance claims.
- Run and record the targeted LZ4-frame, native-loading, memory, size, random-access, and regression performance checkpoint before merge.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### Runtime prerequisite reassessment — 2026-09-10

Ticket #43's runtime-only sequencing exception does not waive this ticket's
family conformance or pre-merge performance gates. Its
[impact manifest](../../../docs/reviews/issue43-lz4-runtime/performance-impact.json)
retains 126 BSA 0x69 LZ4-frame assignments for qualification with the implemented
consumer. Acceptance remains current under specification 0.14.0. The ticket
remains `needs-triage` while #42 is open.

### Readiness after #42 — 2026-09-11

Issue #42's approved 52-case catalog is activated and all 52 active cases pass.
Both listed prerequisites are now closed; this unassigned, agent-owned issue is
ready. The existing acceptance criteria and 126 LZ4-frame performance assignments
remain required. No prerequisite or qualification gate was removed.
