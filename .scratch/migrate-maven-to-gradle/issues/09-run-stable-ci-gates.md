# 09: Run stable CI gates on Windows and Linux

**What to build:** Keep the established CI interface while running Gradle verification and portable contributor checks.

Blocked by: [08: Complete release staging through clean verify](08-complete-release-staging-verify.md)
Status: needs-triage
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved scope; prerequisites remain open. Reassess readiness when all blockers close.

## Acceptance criteria

- [ ] Preserve compile, unit, architecture, formatting, policy, and conformance gate names and launcher interface; test explicit task mapping, results, and evidence semantics.
- [ ] Use pinned Spotless with unchanged Google Java Format behavior/version and no unrelated source formatting churn.
- [ ] Run the complete authoritative Windows gate with Temurin 25.0.4+7.0.LTS; run compilation, formatting, unit tests, and ordinary integration tests on Linux.
- [ ] Test both wrapper launchers and retain CI trigger policy, native launch exceptions, and the distinction between portable checks and Windows-only procedures.
- [ ] Use --no-daemon for CI/evidence and publish conformance evidence before returning final status.
- [ ] Allow only checksum-verified distribution/dependency caching through a commit-SHA-pinned action; reject remote task-output caches, automatic build scans, Develocity, and telemetry.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

