# 06: Preserve the standalone benchmark workflow

**What to build:** Allow benchmark maintainers to build and launch the existing standalone JMH artifact with Gradle.

Blocked by: [03: Build and test the public library with Gradle](03-build-test-public-library.md)
Status: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and verified; no further work remains in this ticket.

## Acceptance criteria

- [x] Use explicit pinned JMH dependencies and annotation processing and a pinned Shadow plugin without unrelated upgrades.
- [x] Inspect the standalone artifact for required benchmark classes, merged service metadata, correct JMH main class, and absence of module descriptors.
- [x] Smoke-test the benchmark launcher while preserving existing artifact naming and operational output location.
- [x] Verify the benchmark project and standalone JAR are neither installed nor published and are excluded from staging and production SBOM inputs.
- [x] Retain performance tag selection and existing Benchmark Corpus and Performance Case semantics.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-12 — Implemented

Added a dedicated build-only benchmark convention with explicit JMH 1.37 core and annotation-processor
dependencies and the pinned Shadow 9.6.1 plugin. Gradle now produces the unchanged
`jbsa-benchmarks/target/jbsa-benchmarks-<version>-standalone.jar`, merges service providers, retains
`org.openjdk.jmh.Main`, strips every module descriptor, and normalizes the established archive timestamp.

The artifact gate inspects every project benchmark class, generated JMH metadata and harness class,
service provider, duplicate entry, launcher manifest, and JPMS exclusion. The smoke task uses the actual
`java -jar` operator seam with `-l`, discovering both random-access operations without running a timed
Performance Case. Benchmark tests retain their native-access setting, while the existing explicit
`performanceHarnessTest` tag and Benchmark Corpus/Performance Case semantics remain unchanged.

Publication policy now rejects install or publication tasks on the benchmark and rejects benchmark
dependencies from the library, CLI, and staging input graphs. The project role remains build-only for the
production SBOM and the closed staging allowlist remains unchanged. Strict locks and independently reviewed
SHA-256 metadata cover the JMH and Shadow graphs; focused TestKit, real benchmark, artifact-inspector, and
performance-harness tests passed.
