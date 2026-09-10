# Audit release bytes, licenses, notices, SBOM, and provenance

Status: needs-triage
State: open
GitHub issue: #57
Source: https://github.com/evildarkarchon/jbsa/issues/57
Author: evildarkarchon
Created: 2026-09-03T06:54:38Z
Source updated: 2026-09-03T06:54:38Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#53](../issues/53-assemble-and-verify-the-self-contained-windows-x64-application-image.md), [#29](../issues/29-establish-licensing-and-dependency-provenance-gates.md), [#54](../issues/54-complete-release-provenance-notices-and-operator-documentation.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #53, #54.

Triage follow-up: Reconcile the DCO acceptance item with the retirement decision in [#62](../../retire-external-contributor-verification/issues/01-retire-external-contributor-verification-gate.md) before marking ready.

## Original issue body

## Objective

Inspect the exact assembled release artifacts and prove that every shipped byte, legal notice, dependency, native payload, fixture, checksum, SBOM entry, and provenance statement is authorized and complete.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-LIC-*
- JBSA-DIST-*
- JBSA-REL-*

## Acceptance

- Enumerate and inspect the final library, POM, sources/Javadoc, application image, ZIP, native libraries, licenses, notices, SBOM, provenance, and checksum artifacts.
- Match every dependency and native byte to the approved inventory, source, license, version, checksum, notices, and redistribution evidence.
- Prove no proprietary corpus, local oracle, ignored fixture, opaque binary, unapproved adaptation, credential, or build-machine residue entered the artifacts.
- Verify SPDX/REUSE, Apache-2.0/NOTICE, CC0 fixture, DCO, trademark/branding, and fixture-provenance obligations and stop on unresolved counsel-dependent questions.
- Record a reproducible audit result against exact release digests; remediation requires rebuilt artifacts and a fresh audit.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
