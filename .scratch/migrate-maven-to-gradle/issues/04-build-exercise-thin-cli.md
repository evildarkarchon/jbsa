# 04: Build and exercise the thin CLI with Gradle

**What to build:** Give CLI operators the same thin application and external runtime inputs from the Gradle build.

Blocked by: [03: Build and test the public library with Gradle](03-build-test-public-library.md)
Status: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Approved and fully specified; its sole prerequisite, ticket 03, is closed.

## Acceptance criteria

- [x] Preserve the thin CLI JAR name, entry point, coordinates, JPMS identity, target location, and fixed archive timestamp.
- [x] Copy the external runtime dependencies with exactly the established filenames and bytes, including native classifiers; verify their hashes.
- [x] Run CLI unit/integration coverage with explicit built-JAR dependencies and deterministic test conventions, and smoke-test preserved module/native launch arguments and CLI observations.
- [x] Disable incidental application archives and launcher distributions and verify that they cannot enter canonical release inputs.
- [x] Keep BSArch-compatible CLI behavior and Java implementation sources unchanged; do not implement the future self-contained application image.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-12 — Implemented

Added thin-application and runtime-input convention plugins. Gradle now builds the versioned explicit
`jbsa-cli` module at its Maven-compatible `target/libs` path, verifies its closed descriptor and
entry point, and runs all CLI behavior tests against the produced CLI and library JARs with explicit
external runtime inputs. A packaged smoke task retains the checked-in module path, native-access,
native root-module, encoding, and illegal-native-access arguments.

The distribution project copies the four approved LWJGL runtime artifacts, including both Windows
native classifiers, to `jbsa-dist/target/runtime-dependencies` with their established filenames and
verifies exact SHA-256 hashes from `launch-policy.json`. Application-plugin scripts, install trees,
ZIPs, and TARs are disabled and an absence gate rejects stale incidental outputs. Focused TestKit
coverage, the 15-test CLI suite, and Java-25 `clean verify --no-daemon` all passed without changing
production Java sources or implementing the future self-contained application image.
