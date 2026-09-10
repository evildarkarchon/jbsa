# Build the pinned-oracle and Conformance Case harness

Status: none
State: closed
GitHub issue: #31
Source: https://github.com/evildarkarchon/jbsa/issues/31
Author: evildarkarchon
Created: 2026-09-03T06:53:37Z
Source updated: 2026-09-05T10:18:01Z
Closed: 2026-09-05T10:18:01Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#28](../issues/28-bootstrap-the-java-25-maven-and-jpms-reactor.md), [#30](../issues/30-create-the-committed-synthetic-fixture-corpus.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Create the public-interface black-box conformance runner, optional digest-pinned BSArch oracle integration, golden comparisons, independent-validator adapters, and hosted-CI case reporting.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-CONF-*

## Acceptance

- Load the complete CV1 matrix and report each applicable case independently as pass, fail, unavailable, or invalid without percentages or waivers.
- Verify the canonical BSArch digest before local use and cleanly separate optional local oracle/corpus execution from hosted-CI goldens.
- Support BSArch-to-JBSA and JBSA-to-BSArch differentials where encoding exists, structured CLI Observations, semantic comparisons, and case-scoped Binary Conformance evidence.
- Integrate independent archive and DDS validators and make disagreements or registered contradictions block the affected case.
- Enforce fixture provenance, immutable golden/rebaseline policy, deterministic evidence capture, and Automated Conformance reporting in hosted Windows CI.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
