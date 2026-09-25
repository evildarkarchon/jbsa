# Publish the first public JBSA GitHub Release

Status: needs-triage
State: open
GitHub issue: #60
Source: https://github.com/evildarkarchon/jbsa/issues/60
Author: evildarkarchon
Created: 2026-09-03T06:54:46Z
Source updated: 2026-09-03T06:54:46Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#59](../issues/59-approve-the-jbsa-1-0-specification-and-release-gate.md)
Parent: [#23](../map.md)

Intended owner: human
Triage reviewed: 2026-09-10
Triage rationale: Reassess readiness after open prerequisites close: #59.

## Original issue body

## Objective

Publish the approved JBSA 1.0 artifacts and evidence as the first public GitHub Release without introducing unqualified assets or claims.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-DIST-*
- JBSA-REL-*

## Acceptance

- Create the final version tag and GitHub Release only from the exact approved commit and release-candidate artifact digests.
- Attach the self-contained Windows x64 ZIP, library artifacts, flattened POM, sources/Javadoc, checksums, license/notices, SBOM, provenance, and approved evidence documents.
- Publish precise scope, Automated/Decode/Encode/Binary/Release Qualification claims, compatibility profile, codec profile, requirements, known limitations, and upgrade/support information.
- Verify anonymous download, clean-machine launch, checksums, asset names, links, and release notes after publication.
- Do not publish to Maven Central or GitHub Packages and do not attach local oracle, proprietary corpus, unapproved binaries, or superseded artifacts.

## Ownership

Human-driven when unblocked. Preparation may be automated, but the named evidence or approval must come from the maintainer or operator.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### Assurance v2 publication evidence — 2026-09-14

Publication must attach or link the concise accepted Assurance v2 release
Evidence Capsule and the content-addressed CI or release artifacts containing
voluminous raw evidence. Public claims must distinguish Automated, Decode,
Encode, Performance, manual Release Qualification, and any optional case-level
Binary Conformance actually earned. Frozen CV1/performance-v1 catalogs and
review packets remain historical provenance and are not required release assets.
