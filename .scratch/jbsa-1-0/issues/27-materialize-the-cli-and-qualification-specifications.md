# Materialize the CLI and qualification specifications

Status: none
State: closed
GitHub issue: #27
Source: https://github.com/evildarkarchon/jbsa/issues/27
Author: evildarkarchon
Created: 2026-09-03T06:53:27Z
Source updated: 2026-09-03T09:48:00Z
Closed: 2026-09-03T09:48:00Z
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

Publish the normative consumer, compatibility-profile, conformance, performance, distribution, and release-gate contracts used to judge every implementation slice and release claim.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-COMPAT-*
- JBSA-CLI-*
- JBSA-CONF-*
- JBSA-PERF-*
- JBSA-DIST-*
- JBSA-REL-*

## Acceptance

- Create compatibility-profiles, bsarch-cli, conformance-v1, performance-v1, distribution, and release-gates specifications.
- Define the safe normative default and immutable bsarch-1.0/v1 deviation bundle, including commands, switches, streams, exits, records, progress, cancellation, and implicit DDS compression.
- Define CV1 Conformance Case identities, authority order, oracle and fixture rules, differential directions, independent validators, binary qualification, hosted-CI claims, and rebaseline controls.
- Define PV1 Performance Case identities, deterministic Benchmark Corpus, paired protocol, thresholds, invalid-run rules, baseline lifecycle, and targeted/full requalification triggers.
- Define the self-contained Windows application-image contract and ordered specification, interface, conformance, performance, packaging, compliance, manual qualification, approval, and publication gates.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
