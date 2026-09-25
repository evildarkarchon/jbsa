# 09: Run stable CI gates on Windows and Linux

**What to build:** Keep the established CI interface while running Gradle verification and portable contributor checks.

Blocked by: [08: Complete release staging through clean verify](08-complete-release-staging-verify.md)
Status: none
Labels: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and verified; no further work remains in this ticket.

## Acceptance criteria

- [x] Preserve compile, unit, architecture, formatting, policy, and conformance gate names and launcher interface; test explicit task mapping, results, and evidence semantics.
- [x] Use pinned Spotless with unchanged Google Java Format behavior/version and no unrelated source formatting churn.
- [x] Run the complete authoritative Windows gate with Temurin 25.0.4+7.0.LTS; run compilation, formatting, unit tests, and ordinary integration tests on Linux.
- [x] Test both wrapper launchers and retain CI trigger policy, native launch exceptions, and the distinction between portable checks and Windows-only procedures.
- [x] Use --no-daemon for CI/evidence and publish conformance evidence before returning final status.
- [x] Allow only checksum-verified distribution/dependency caching through a commit-SHA-pinned action; reject remote task-output caches, automatic build scans, Develocity, and telemetry.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-12 — Implemented

Preserved the six public gate names while moving every gate's primary mapping to explicit Gradle
tasks through the same `run-ci-gate.ps1 -Gate <name>` interface on Windows and Linux. Added
behavioral launcher fixtures for exact task arguments, failure propagation, and the policy gate's
temporary Maven-era reproducibility follow-up, which remains until ticket 10 migrates that harness.

Applied Spotless 8.10.2 with Google Java Format 1.36.0, UTF-8, annotation formatting, and unused
import removal across the same Java source scope. The only pre-existing formatting correction was in
the migration's compliance policy test. Extended strict verification with 88 independently checked
Spotless and cross-platform build inputs; all hashes matched fresh repository downloads.

CI now retains the named Windows matrix, adds a complete `clean verify --no-daemon` Windows job on
the exact Temurin `25.0.4+7.0.LTS` identity, and runs compile, formatting, build-logic, unit, and
ordinary integration gates on Ubuntu. Caching is limited to Gradle distributions and dependencies
through commit-SHA-pinned `setup-java`, backed by wrapper and dependency checksums. Conformance
evidence uploads run with `always()` after both the named and complete gates.

Windows-native archive identity, transactional publication, native codec launch, and real archive
consumer procedures remain active on Windows and become visible JUnit skips on Linux. The exact
Linux gate passed under Ubuntu, and the final real six-project Windows `gradlew.bat clean verify
--no-daemon` gate passed with all 62 tasks, retained conformance evidence, and the audited 18-file
staging set.
