# 02: Establish the verified Gradle build foundation

**What to build:** Let maintainers launch a verified Gradle build with the existing six project roles and safe, deterministic shared policy.

Blocked by: [01: Capture the Maven parity baseline](01-capture-maven-parity-baseline.md)
Status: ready-for-agent
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved and fully specified; its sole prerequisite, ticket 01, is closed.

## Acceptance criteria

- [ ] Pin the binary Gradle 9.7.1 wrapper to the distribution and wrapper-JAR SHA-256 values in the approved spec; test both launchers, attributes, and line endings.
- [ ] Preserve jbsa-parent identity and all six project roles using Kotlin DSL, an included convention-plugin build, and centralized pinned dependency and plugin versions.
- [ ] Validate the default 0.1.0-SNAPSHOT or explicit -Pversion candidate once and apply identical group/version identity to every project; require installed Java 25 and disable automatic JDK provisioning.
- [ ] Enforce central Maven Central dependency and Gradle Plugin Portal plugin repositories, locks for all resolvable configurations, strict reviewed SHA-256 verification, conflict failure, and rejection of dynamic/changing modules and release-time snapshots. Extend locks and verification as later tickets introduce configurations.
- [ ] Use focused unit and TestKit tests for defaults, overrides, inconsistent identity, invalid versions, missing locks, prohibited repositories, unpinned plugins, and dependency-policy failures.
- [ ] Preserve operational target output locations; a clean sentinel/digest test proves tracked automation is unchanged while owned generated output is removed.
- [ ] Keep configuration cache, parallel execution, remote task-output caching, scans, and telemetry disabled; retain local daemon use and provide no-daemon evidence execution.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.
