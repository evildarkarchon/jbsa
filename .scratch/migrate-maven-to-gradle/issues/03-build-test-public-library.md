# 03: Build and test the public library with Gradle

**What to build:** Produce the compatible public library and local publication artifacts while running its existing tests through Gradle.

Blocked by: [02: Establish the verified Gradle build foundation](02-establish-verified-gradle-foundation.md)
Status: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and verified; no further work remains in this ticket.

## Acceptance criteria

- [x] Preserve Java sources, coordinates, artifact names, Java release 25, parameter metadata, UTF-8, all lint diagnostics, and the fixed 2026-09-03T00:00:00Z archive timestamp.
- [x] Build library binary, sources, Javadocs, and parent-free consumer POM with exact project metadata and dependency semantics: unclassified LWJGL core/LZ4 compile scope, Windows natives runtime scope, and no false optionality or exclusions.
- [x] Port the required build-only test-support project without installation or publication. Keep test sources in place, compile them once, and execute *Test and *IT exactly once in their intended tasks.
- [x] Verify one fork, deterministic class-name ordering, UTC, US locale, UTF-8, native-access settings, Gradle-native report locations, and module-path inference.
- [x] Supply built JAR paths through Gradle properties with explicit producing-task dependencies; retain black-box descriptor, exports, requirements, public-signature, and class-path usability tests.
- [x] Prove that only the library produces the public publication set and no reachable remote publication action or remote repository is configured.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-12 — Implemented

Added role-specific Java, public-library, and build-only test-support conventions. The Gradle build
now compiles the unchanged library and test-support Java sources at release 25 with parameter and
lint metadata, produces the binary/sources/Javadocs and exact parent-free consumer POM, normalizes
every archive entry to the approved timestamp, assembles the exact four-file Maven-compatible local
publication set, and verifies the packaged descriptor, exports, requirements, public signatures,
and class-path usability.

The shared test source set compiles once and feeds disjoint deterministic `test` and
`integrationTest` tasks with one fork, JPMS inference, native-access grants, fixed locale/time-zone,
UTF-8, Gradle-native reports, and explicit artifact producer edges. Focused unit/TestKit coverage
and the real 310-test library suite passed, followed by Java-25 `clean verify --no-daemon` under
strict locks and independently reviewed SHA-256 verification metadata.
