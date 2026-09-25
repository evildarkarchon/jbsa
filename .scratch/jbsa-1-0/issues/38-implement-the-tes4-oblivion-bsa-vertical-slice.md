# Implement the TES4 / Oblivion BSA vertical slice

Status: none
State: closed
GitHub issue: #38
Source: https://github.com/evildarkarchon/jbsa/issues/38
Author: evildarkarchon
Created: 2026-09-03T06:53:54Z
Source updated: 2026-09-08T11:54:13Z
Closed: 2026-09-08T11:54:13Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#32](../issues/32-build-the-performance-v1-harness-and-benchmark-corpus.md), [#37](../issues/37-implement-the-tes3-morrowind-bsa-vertical-slice.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Introduce the common versioned-BSA model and baseline JDK zlib profile through a complete BSA 0x67 read/write and consumer slice.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-BSA-*
- JBSA-CODEC-*
- JBSA-CLI-*
- JBSA-CONF-*
- JBSA-PERF-*

## Acceptance

- Implement BSA 0x67 stored, zlib, and mixed-entry recognition, metadata, folder/file records, signed-byte TES4 hashing, flags, names, ordering, splitting, decode, and encode behavior.
- Implement the release-pinned JDK zlib stream adapter, size-prefix/framing rules, deterministic settings, bounded streaming, and provider-neutral failures behind an internal seam.
- Expose applicable library and thin-CLI information, list, dump, unpack, and pack behavior with exact format switches, diagnostics, exits, and artifacts.
- Pass bidirectional oracle differentials, an independent validator, malformed/limit cases, and all applicable CV1 cases; restrict Binary Conformance to qualified stored cases.
- Run and record the accepted targeted zlib, I/O, memory, size, and random-access performance checkpoint before merge.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
