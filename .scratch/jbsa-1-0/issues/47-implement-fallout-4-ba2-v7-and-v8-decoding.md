# Implement Fallout 4 BA2 v7 and v8 decoding

Status: needs-triage
State: open
GitHub issue: #47
Source: https://github.com/evildarkarchon/jbsa/issues/47
Author: evildarkarchon
Created: 2026-09-03T06:54:15Z
Source updated: 2026-09-03T06:54:15Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#46](../issues/46-implement-starfield-dds-ba2.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #46.

## Original issue body

## Objective

Complete the supported Archive Family matrix with decode-only Fallout 4 General and DDS BA2 v7/v8 behavior and explicit encode rejection.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-GNRL-*
- JBSA-DX10-*
- JBSA-DDS-*
- JBSA-CLI-*
- JBSA-CONF-*

## Acceptance

- Implement BTDX/GNRL and BTDX/DX10 v7/v8 recognition, version-specific records, names, hashes, chunks, zlib payloads, DDS reconstruction, and bounded validation.
- Reject every v7/v8 encode request as unsupported before destination effects while keeping decoding available through the shared public interface and CLI.
- Add project-authored/oracle-generated fixtures for absent shipping combinations and optional local corpus observations where legally available.
- Pass oracle decode differentials, independent General/DDS validation, malformed/limit cases, and all applicable decode-only CV1 cases.
- Run targeted unpack, metadata, DDS, memory, and random-access regression performance cases before merge.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### Assurance v2 completion contract — 2026-09-14

The active assurance namespace is `JBSA-ASR-*`; `JBSA-CONF-*` is frozen
historical provenance. The General and DDS v7/v8 capabilities must generate the
applicable decode, explicit encode-rejection, CLI, malformed, resource,
Independent Validator, oracle, and interaction Assurance Scenarios. Curated
unpack, metadata, DDS reconstruction, random-access, and peak-memory lanes cover
the materially distinct performance paths without recreating the expanded v1
matrices. The dependency on #46 remains unchanged.
