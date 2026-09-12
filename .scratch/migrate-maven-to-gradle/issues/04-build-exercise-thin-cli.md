# 04: Build and exercise the thin CLI with Gradle

**What to build:** Give CLI operators the same thin application and external runtime inputs from the Gradle build.

Blocked by: [03: Build and test the public library with Gradle](03-build-test-public-library.md)
Status: ready-for-agent
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved and fully specified; its sole prerequisite, ticket 03, is closed.

## Acceptance criteria

- [ ] Preserve the thin CLI JAR name, entry point, coordinates, JPMS identity, target location, and fixed archive timestamp.
- [ ] Copy the external runtime dependencies with exactly the established filenames and bytes, including native classifiers; verify their hashes.
- [ ] Run CLI unit/integration coverage with explicit built-JAR dependencies and deterministic test conventions, and smoke-test preserved module/native launch arguments and CLI observations.
- [ ] Disable incidental application archives and launcher distributions and verify that they cannot enter canonical release inputs.
- [ ] Keep BSArch-compatible CLI behavior and Java implementation sources unchanged; do not implement the future self-contained application image.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.
