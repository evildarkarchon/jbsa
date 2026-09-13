# 07: Generate and audit production compliance inputs

**What to build:** Let compliance reviewers audit actual Gradle resolution and production artifacts using deterministic model data.

Blocked by: [04: Build and exercise the thin CLI with Gradle](04-build-exercise-thin-cli.md)
Status: none
Labels: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and verified; no further work remains in this ticket.

## Acceptance criteria

- [x] Generate schema-versioned internal build-layout and resolved-production-dependency manifests with deterministic ordering and containment checks; do not expose them as release assets.
- [x] Record source project, usage/configuration, requested and resolved coordinates, classifier or selected variant, and artifact SHA-256 in the dependency manifest.
- [x] Produce deterministic CycloneDX 1.6 without a serial number, rooted at jbsa-parent and restricted to production components.
- [x] Adapt compliance consumers to the manifests, generated consumer POM, locks, and verification metadata while keeping the existing licensing inventory authoritative and retaining tested PowerShell algorithms.
- [x] Cover schema validity, deterministic serialization, path containment, production-only inventory, notices, SBOM reconciliation, artifact-byte identity, and rejected dependencies in consumer/regression tests.
- [x] Review dependency verification against the Maven baseline, compliance inventory, and independent upstream checksums, including plugins, transitive artifacts, metadata, sources, and Javadocs required by supported tasks.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-12 — Implemented

Added deterministic schema-version-1 build-layout and resolved-production manifests under
`target/compliance`. The dependency model records all three production resolution scopes, requested
and selected coordinates, variant attributes, classifiers, artifact filenames, and exact SHA-256
values while retaining the four-artifact Maven baseline and excluding build-only/test graphs.

Applied the pinned CycloneDX 3.4.1 plugin and canonicalized its model into a deterministic 1.6 SBOM
without timestamps or a serial number. The graph retains `jbsa-parent`, `jbsa`, `jbsa-cli`, the four
approved LWJGL artifacts, and the baseline relationships only. The PowerShell audit now reconciles
these inputs with the generated consumer POM, production/root locks, both verification-metadata
files, and the authoritative licensing inventory while retaining its existing notice, archive,
native-byte, release-input, and artifact-identity algorithms.

Focused TestKit coverage, all 50 PowerShell regression cases, the real compliance integration tests,
the independent 153-artifact upstream checksum audit, a primed-cache offline compliance run, and the
Java-25 `gradlew clean verify --no-daemon` gate passed. The verification review records the complete
345-artifact metadata union and confirms supported tasks resolve no external sources or Javadocs.
