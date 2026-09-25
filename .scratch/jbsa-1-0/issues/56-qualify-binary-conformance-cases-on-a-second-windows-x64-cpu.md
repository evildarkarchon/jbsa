# Qualify Binary Conformance cases on a second Windows x64 CPU

Status: none
State: closed
GitHub issue: #56
Source: https://github.com/evildarkarchon/jbsa/issues/56
Author: evildarkarchon
Created: 2026-09-03T06:54:36Z
Source updated: 2026-09-05T11:21:05Z
Closed: 2026-09-05T11:21:05Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#52](../issues/52-qualify-jlibdeflate-and-select-the-standard-zlib-dispatch.md), [#53](../issues/53-assemble-and-verify-the-self-contained-windows-x64-application-image.md), [#50](../issues/50-complete-the-mandatory-automated-conformance-matrix.md)
Parent: [#23](../map.md)

Intended owner: human
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Confirm and publish only those designated deterministic cases that earn byte-for-byte Binary Conformance under the final release profile and a second Windows x64 CPU.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-CONF-*
- JBSA-REL-*

## Acceptance

- Use the pinned ordered-file configuration, final codec/profile/toolchain identity, exact fixtures, and five fresh identical Conformance Oracle runs per candidate case.
- Confirm byte-identical JBSA output and required cross-decoding on the primary qualification machine.
- Repeat the required confirmation on a second Windows x64 CPU and record CPU, Windows, JVM, provider, fixture, command, and digest evidence.
- Publish claims at Conformance Case granularity only; omit compressed or DDS cases that do not independently earn the designation.
- Attach the complete evidence and update traceability without generalizing results to other inputs, worker modes, codecs, or profiles.

## Ownership

Human-driven when unblocked. Preparation may be automated, but the named evidence or approval must come from the maintainer or operator.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
