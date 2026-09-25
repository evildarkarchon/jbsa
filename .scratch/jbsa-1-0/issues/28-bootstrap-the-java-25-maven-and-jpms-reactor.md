# Bootstrap the Java 25 Maven and JPMS reactor

Status: none
State: closed
GitHub issue: #28
Source: https://github.com/evildarkarchon/jbsa/issues/28
Author: evildarkarchon
Created: 2026-09-03T06:53:29Z
Source updated: 2026-09-03T22:11:44Z
Closed: 2026-09-03T22:11:44Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#27](../issues/27-materialize-the-cli-and-qualification-specifications.md), [#26](../issues/26-materialize-the-library-behavior-specifications.md), [#25](../issues/25-materialize-the-archive-family-specifications.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Create the reproducible greenfield reactor, deep library and thin CLI module seams, build-only verification modules, and basic hosted Windows build gates.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-BUILD-*

## Acceptance

- Create the Maven wrapper, parent/aggregator, jbsa, jbsa-cli, jbsa-test-support, jbsa-conformance-tests, jbsa-benchmarks, and jbsa-dist projects with one reactor version.
- Target the pinned Java 25 toolchain and provide explicit JPMS descriptors, exports, requires clauses, and architectural tests that prevent CLI or third-party type leakage across the public seam.
- Configure reproducible builds, dependency pinning, flattened consumer POM generation, sources and Javadoc artifacts, and deterministic test entry points.
- Add hosted Windows GitHub Actions for compile, unit, architecture, formatting, and foundational policy checks without claiming local Release Qualification.
- Leave modules empty of Archive Family behavior except for the minimum compileable skeleton required to verify the seams.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
