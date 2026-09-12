# 07: Generate and audit production compliance inputs

**What to build:** Let compliance reviewers audit actual Gradle resolution and production artifacts using deterministic model data.

Blocked by: [04: Build and exercise the thin CLI with Gradle](04-build-exercise-thin-cli.md)
Status: needs-triage
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved scope; prerequisites remain open. Reassess readiness when all blockers close.

## Acceptance criteria

- [ ] Generate schema-versioned internal build-layout and resolved-production-dependency manifests with deterministic ordering and containment checks; do not expose them as release assets.
- [ ] Record source project, usage/configuration, requested and resolved coordinates, classifier or selected variant, and artifact SHA-256 in the dependency manifest.
- [ ] Produce deterministic CycloneDX 1.6 without a serial number, rooted at jbsa-parent and restricted to production components.
- [ ] Adapt compliance consumers to the manifests, generated consumer POM, locks, and verification metadata while keeping the existing licensing inventory authoritative and retaining tested PowerShell algorithms.
- [ ] Cover schema validity, deterministic serialization, path containment, production-only inventory, notices, SBOM reconciliation, artifact-byte identity, and rejected dependencies in consumer/regression tests.
- [ ] Review dependency verification against the Maven baseline, compliance inventory, and independent upstream checksums, including plugins, transitive artifacts, metadata, sources, and Javadocs required by supported tasks.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

