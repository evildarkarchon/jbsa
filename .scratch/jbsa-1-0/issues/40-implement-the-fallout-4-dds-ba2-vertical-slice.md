# Implement the Fallout 4 DDS BA2 vertical slice

Status: none
State: closed
GitHub issue: #40
Source: https://github.com/evildarkarchon/jbsa/issues/40
Author: evildarkarchon
Created: 2026-09-03T06:53:59Z
Source updated: 2026-09-08T23:49:24Z
Closed: 2026-09-08T23:49:24Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#32](../issues/32-build-the-performance-v1-harness-and-benchmark-corpus.md), [#39](../issues/39-implement-the-fallout-4-general-ba2-vertical-slice.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Exercise the highest-risk BA2 payload branch early by implementing Fallout 4 DX10 v1 chunking, zlib payloads, and canonical DDS reconstruction end to end.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-DX10-*
- JBSA-DDS-*
- JBSA-CLI-*
- JBSA-CONF-*
- JBSA-PERF-*

## Acceptance

- Implement BTDX/DX10 v1 metadata, texture formats, dimensions, arrays/cubemaps, mip partitioning, chunk records, zlib-per-chunk decoding, and writable output.
- Implement the internal DDS envelope, canonical header reconstruction, input analysis, mip slicing, and output comparison without exposing DDS internals as a public provider seam.
- Always produce valid compressed production chunks under the safe default and implement the specified immutable CLI compatibility deviation separately.
- Pass bidirectional oracle differentials, DirectXTex or equivalent independent validation, malformed/boundary fixtures, reconstruction byte checks, and applicable CV1 cases.
- Run and record targeted DDS chunking, reconstruction, compression, peak-memory, output-size, and random-access performance cases before merge.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
