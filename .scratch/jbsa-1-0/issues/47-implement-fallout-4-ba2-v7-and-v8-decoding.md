# Implement Fallout 4 BA2 v7 and v8 decoding

Status: none
State: closed
GitHub issue: #47
Source: https://github.com/evildarkarchon/jbsa/issues/47
Author: evildarkarchon
Created: 2026-09-03T06:54:15Z
Source updated: 2026-09-03T06:54:15Z
Closed: 2026-09-14
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#46](../issues/46-implement-starfield-dds-ba2.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-14
Triage rationale: #46 is closed; the v7/v8 decode-only Assurance v2 contract is current, fully specified, and has no remaining open prerequisites.

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

### Implemented with scoped qualification — 2026-09-14

The shared General and DDS BA2 readers now admit Fallout 4 versions 7 and 8
through the existing public library and CLI decode paths. Both versions use the
24-byte Fallout 4 envelope, retain their exact wire version, reuse the bounded
v1-shaped General and texture/chunk records, and decode stored or independently
framed zlib payloads as permitted. Every library v7/v8 encode request fails as
unsupported before source factories or destination effects; the CLI exposes no
v7/v8 pack selector and continues emitting version 1 for Fallout 4 compatibility.

Project-authored CC0 vectors now cover v8 General stored/zlib and v7/v8 DDS
zlib in addition to the existing v7 General vectors. Independent bounded General
and DDS scanners, public metadata/extraction/CLI cases, malformed and Resource
Limits cases, digest-pinned oracle decode observations, and read-only local v8
General plus v7/v8 DDS corpus observations pass. The two decode-only performance
checkpoints cover metadata, unpack or DDS reconstruction, random entry opens,
and heap-pool peaks. The 40-result affected local Evidence Capsule is retained
at `tests/assurance/issue47-local-capsule.json` with digest
`sha256:525ac556cb63483da7ee510892229d416ffcc68594dfa81d94607d9ebd59b9ec`.

Producer evidence remains deliberately asymmetric. Creation Kit General output
is version 8 and common third-party tools emit version 1; no known producer or
shipping archive emits General version 7. That decode path is implemented and
corroborated only with project-authored vectors and the Conformance Oracle
against Reference Snapshot behavior, so Assurance v2 retains `fo4-gnrl-v7` as
explicitly incomplete and JBSA
does not guarantee its shipping compatibility. No Binary Conformance or
release-wide Performance Qualification claim is made.
