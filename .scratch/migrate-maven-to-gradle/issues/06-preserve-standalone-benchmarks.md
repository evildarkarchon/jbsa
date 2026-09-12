# 06: Preserve the standalone benchmark workflow

**What to build:** Allow benchmark maintainers to build and launch the existing standalone JMH artifact with Gradle.

Blocked by: [03: Build and test the public library with Gradle](03-build-test-public-library.md)
Status: needs-triage
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved scope; prerequisites remain open. Reassess readiness when all blockers close.

## Acceptance criteria

- [ ] Use explicit pinned JMH dependencies and annotation processing and a pinned Shadow plugin without unrelated upgrades.
- [ ] Inspect the standalone artifact for required benchmark classes, merged service metadata, correct JMH main class, and absence of module descriptors.
- [ ] Smoke-test the benchmark launcher while preserving existing artifact naming and operational output location.
- [ ] Verify the benchmark project and standalone JAR are neither installed nor published and are excluded from staging and production SBOM inputs.
- [ ] Retain performance tag selection and existing Benchmark Corpus and Performance Case semantics.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

