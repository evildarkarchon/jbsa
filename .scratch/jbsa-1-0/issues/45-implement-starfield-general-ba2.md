# Implement Starfield General BA2

Status: ready-for-agent
State: open
GitHub issue: #45
Source: https://github.com/evildarkarchon/jbsa/issues/45
Author: evildarkarchon
Created: 2026-09-03T06:54:11Z
Source updated: 2026-09-03T06:54:11Z
Closed: none
Migrated: 2026-09-10
Labels: ready-for-agent
Assignees: none
Blocked by: [#44](../issues/44-implement-bsa-for-skyrim-se-and-ae.md), [#61](../issues/61-implement-assurance-v2.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-14
Triage rationale: #44 and #61 are closed. The current Assurance v2 acceptance is complete and this ticket is ready for agent implementation.

## Original issue body

## Objective

Extend the shared General BA2 model to Starfield v2 zlib and v3 method-3 raw-LZ4 decode and encode behavior.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-GNRL-*
- JBSA-CODEC-*
- JBSA-CLI-*
- JBSA-CONF-*
- JBSA-PERF-*

## Acceptance

- Implement General BA2 v2 extra-header behavior and v3 compression-method fields while preserving stored, zlib, and raw-LZ4 distinctions.
- Emit v3 only for raw LZ4 and otherwise v2; decode specified non-method-3 v3 inputs as zlib and reject oversize or invalid blocks safely.
- Use the qualified internal raw-LZ4 profile and common BA2 model without changing the public Archive Family/subtype/wire-version separation.
- Add applicable library/CLI behavior, project-authored v3 fixtures, optional local-corpus coverage, oracle differentials, independent validation, and all CV1 cases.
- Run and record targeted raw-LZ4, zlib, memory, output-size, native-loading, and General BA2 performance cases before merge.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### LZ4 performance gate carried forward from #43

Specification 0.14.0 permits the initial internal runtime to enable family
implementation; it does not qualify this consumer. The
[runtime impact manifest](../../../docs/reviews/issue43-lz4-runtime/performance-impact.json)
retains 126 Starfield General method-3 raw-LZ4 assignments. This ticket's formal
targeted conformance/performance evidence remains required before merge. #44
remains its prerequisite.

### Dependency reassessment after #44 — 2026-09-14

Ticket #44 is closed with complete family-level Assurance v2 data. This ticket
remains `needs-triage` and is now blocked by #61 because its historical
acceptance still requires the expanded CV1 and 126-case performance-v1 model.
Once migrated to compact generated conformance and risk-based performance, it
will be eligible for `ready-for-agent` reassessment.

### Assurance v2 completion contract — 2026-09-14

This Archive Family must add its capability and stable behavioral variants to
the Assurance Plan, supply selectors for every applicable semantic, malformed,
resource, extraction, CLI, Independent Validator, and bidirectional-oracle
scenario, and run the affected generated tier. Its performance work consists of
the curated General BA2 zlib and raw-LZ4 implementation-path lanes, including
derived output size, memory, native-loading, and regression evidence. No new CV1
expansion, rebaseline packet, or performance-v1 product matrix is required.

Ticket #61 is closed; this current contract supersedes the historical expanded
catalog wording above. Ticket #45 is unblocked and ready for an agent.
