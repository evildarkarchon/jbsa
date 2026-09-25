# 02: Establish the verified Gradle build foundation

**What to build:** Let maintainers launch a verified Gradle build with the existing six project roles and safe, deterministic shared policy.

Blocked by: [01: Capture the Maven parity baseline](01-capture-maven-parity-baseline.md)
Status: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and verified; no further work remains in this ticket.

## Acceptance criteria

- [x] Pin the binary Gradle 9.7.1 wrapper to the distribution and wrapper-JAR SHA-256 values in the approved spec; test both launchers, attributes, and line endings.
- [x] Preserve jbsa-parent identity and all six project roles using Kotlin DSL, an included convention-plugin build, and centralized pinned dependency and plugin versions.
- [x] Validate the default 0.1.0-SNAPSHOT or explicit -Pversion candidate once and apply identical group/version identity to every project; require installed Java 25 and disable automatic JDK provisioning.
- [x] Enforce central Maven Central dependency and Gradle Plugin Portal plugin repositories, locks for all resolvable configurations, strict reviewed SHA-256 verification, conflict failure, and rejection of dynamic/changing modules and release-time snapshots. Extend locks and verification as later tickets introduce configurations.
- [x] Use focused unit and TestKit tests for defaults, overrides, inconsistent identity, invalid versions, missing locks, prohibited repositories, unpinned plugins, and dependency-policy failures.
- [x] Preserve operational target output locations; a clean sentinel/digest test proves tracked automation is unchanged while owned generated output is removed.
- [x] Keep configuration cache, parallel execution, remote task-output caching, scans, and telemetry disabled; retain local daemon use and provide no-daemon evidence execution.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-12 — Implemented

Established the Gradle 9.7.1 Kotlin DSL foundation with the approved distribution and wrapper-JAR
checksums, the `jbsa-parent` root and six retained roles, an included `build-logic` convention build,
central repositories and version catalog, installed-Java-25 enforcement, strict locks and SHA-256
verification with a [digest-bound independent review](../dependency-verification-review.md),
deterministic dependency failure policy, and `target/` output ownership.

Focused unit and TestKit coverage exercises defaults, overrides, invalid and inconsistent identity,
repository/plugin restrictions, missing locks, strict verification failure, dynamic/changing/conflicting
dependencies, release snapshots, both wrapper launchers and byte policies, and clean sentinel safety.
The real build passed Java-25 `clean verify --no-daemon` and the same gate from a primed cache with
`--offline`; Java 26 failed with the intended installed-Java-25 diagnostic.
