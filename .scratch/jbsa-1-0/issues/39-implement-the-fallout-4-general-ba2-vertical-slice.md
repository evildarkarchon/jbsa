# Implement the Fallout 4 General BA2 vertical slice

Status: none
State: closed
GitHub issue: #39
Source: https://github.com/evildarkarchon/jbsa/issues/39
Author: evildarkarchon
Created: 2026-09-03T06:53:57Z
Source updated: 2026-09-08T22:26:41Z
Closed: 2026-09-08T22:26:41Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#38](../issues/38-implement-the-tes4-oblivion-bsa-vertical-slice.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Introduce the shared BTDX/General BA2 model through complete Fallout 4 GNRL v1 stored and zlib behavior.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-GNRL-*
- JBSA-CODEC-*
- JBSA-CLI-*
- JBSA-CONF-*

## Acceptance

- Implement BTDX/GNRL v1 recognition, header, names, hashes, extensions, exactly-one-chunk records, stored packedSize semantics, zlib decode, and writable output.
- Reuse the internal zlib profile and common operation substrate without exposing a new public family-specific or provider interface.
- Expose applicable library and CLI operations, source overlays, ordering, sharing/deduplication, splitting, diagnostics, and invalid-invocation behavior.
- Pass bidirectional oracle differentials, an independent BA2 validator, committed and optional local fixtures, malformed/limit cases, and applicable CV1 cases.
- Run targeted General BA2 metadata, mixed-entry, compression, memory, size, sharing, and split performance cases before merge.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
