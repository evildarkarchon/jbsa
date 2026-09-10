# Materialize the Archive Family specifications

Status: none
State: closed
GitHub issue: #25
Source: https://github.com/evildarkarchon/jbsa/issues/25
Author: evildarkarchon
Created: 2026-09-03T06:53:23Z
Source updated: 2026-09-03T08:38:25Z
Closed: 2026-09-03T08:38:25Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#24](../issues/24-publish-the-specification-framework-and-requirement-registry.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Publish the normalized wire-format, detection, decode, encode, codec, name, hashing, ordering, chunking, and reconstruction requirements for every supported Archive Family.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-DET-*
- JBSA-TES3-*
- JBSA-BSA-*
- JBSA-GNRL-*
- JBSA-DX10-*
- JBSA-DDS-*

## Acceptance

- Create detection, TES3 BSA, versioned BSA, General BA2, DDS BA2, and DDS payload specifications under docs/spec/formats/.
- Keep Archive Family, wire version, and BA2 subtype distinct and state exact decode-only versus writable combinations.
- Separate zlib stream, LZ4 frame, and raw LZ4 profiles and specify stored/compressed framing, hashes, names, flags, ordering, splits, and validation behavior.
- Specify DDS mip chunking and canonical reconstruction independently from General BA2 behavior.
- Trace every requirement to the pinned Reference Snapshot research and preserve all evidence-qualified contradictions and fixture-dependent unknowns.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
