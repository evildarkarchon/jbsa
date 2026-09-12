# 03: Build and test the public library with Gradle

**What to build:** Produce the compatible public library and local publication artifacts while running its existing tests through Gradle.

Blocked by: [02: Establish the verified Gradle build foundation](02-establish-verified-gradle-foundation.md)
Status: needs-triage
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved scope; prerequisites remain open. Reassess readiness when all blockers close.

## Acceptance criteria

- [ ] Preserve Java sources, coordinates, artifact names, Java release 25, parameter metadata, UTF-8, all lint diagnostics, and the fixed 2026-09-03T00:00:00Z archive timestamp.
- [ ] Build library binary, sources, Javadocs, and parent-free consumer POM with exact project metadata and dependency semantics: unclassified LWJGL core/LZ4 compile scope, Windows natives runtime scope, and no false optionality or exclusions.
- [ ] Port the required build-only test-support project without installation or publication. Keep test sources in place, compile them once, and execute *Test and *IT exactly once in their intended tasks.
- [ ] Verify one fork, deterministic class-name ordering, UTC, US locale, UTF-8, native-access settings, Gradle-native report locations, and module-path inference.
- [ ] Supply built JAR paths through Gradle properties with explicit producing-task dependencies; retain black-box descriptor, exports, requirements, public-signature, and class-path usability tests.
- [ ] Prove that only the library produces the public publication set and no reachable remote publication action or remote repository is configured.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

