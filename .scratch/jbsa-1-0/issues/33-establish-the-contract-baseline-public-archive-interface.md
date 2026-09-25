# Establish the Contract Baseline public archive interface

Status: none
State: closed
GitHub issue: #33
Source: https://github.com/evildarkarchon/jbsa/issues/33
Author: evildarkarchon
Created: 2026-09-03T06:53:42Z
Source updated: 2026-09-05T11:30:14Z
Closed: 2026-09-05T11:30:14Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#28](../issues/28-bootstrap-the-java-25-maven-and-jpms-reactor.md), [#31](../issues/31-build-the-pinned-oracle-and-conformance-case-harness.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Publish the provisional first-release deep library interface and contract tests without exposing hypothetical storage, provider, executor, or transaction seams.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-LIB-*
- JBSA-OPS-*

## Acceptance

- Implement the concrete stateless BethesdaArchives.standard() module and Path-first detect, inspect, open/openContent, extract, and pack entry points required by the specification.
- Implement immutable Archive Family, wire-version, subtype, request, result, metadata, diagnostic, policy, report, failure, progress, resource-limit, and operation-control domain values.
- Implement owned OpenArchive and child-channel contracts with long sizes, counts, and offsets and explicit caller-visible lifetime rules.
- Add compile-time and behavioral contract tests using both CLI-like and embedded-library consumers through the public JPMS exports.
- Mark the interface as pre-1.0 Contract Baseline and keep all third-party, provider, storage, scheduling, buffering, and publication mechanics internal.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
