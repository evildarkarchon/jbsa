# 08: Complete release staging through clean verify

**What to build:** Give maintainers one complete Gradle verification command that produces and audits the unchanged canonical staging set.

Blocked by: [05: Run Automated Conformance through Gradle](05-run-automated-conformance.md), [06: Preserve the standalone benchmark workflow](06-preserve-standalone-benchmarks.md), [07: Generate and audit production compliance inputs](07-generate-audit-compliance-inputs.md)
Status: none
Labels: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and verified; no further work remains in this ticket.

## Acceptance criteria

- [x] Make gradlew clean verify explicitly build canonical artifacts, generate publication/compliance inputs, run pre-staging compliance, stage release inputs, and complete the post-staging audit.
- [x] Keep assemble, check, and build narrower than the complete verification lifecycle and test their task-graph boundaries.
- [x] Keep jbsa-dist a non-Java staging/audit project; exclude benchmark, test-support, conformance, internal manifests, and incidental application distributions from release inputs.
- [x] Retain PowerShell staging algorithms and prove complete-input success, exact copies, deterministic manifests, stale-file removal, missing-input failure, and blocked-dependency rejection.
- [x] Prove checksum rejection does not damage prior staging and post-staging audit cannot run successfully ahead of required inputs.
- [x] Give custom Kotlin orchestration tasks declared inputs, outputs, process contracts, and focused unit/TestKit failure and ordering coverage, backed by the real six-project build.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-12 — Implemented

Added declared `stageReleaseInputs` and `verifyStagedReleaseInputs` tasks to the non-Java `jbsa-dist`
project. Root `verify` now builds the canonical library, consumer POM, sources, Javadocs, thin CLI,
runtime dependencies, and compliance evidence; runs pre-staging compliance; stages the release-input
transaction; and completes a read-only post-staging audit. Standard `assemble`, `check`, and `build`
remain outside this complete release lifecycle.

The retained PowerShell staging algorithm now consumes the schema-version-1 Gradle build-layout
manifest while preserving its temporary Maven parity fallback. Regression coverage proves exact
canonical inclusion, exclusion of build-only/internal/incidental outputs, exact copies, deterministic
manifests, stale-file removal, missing-input and blocked-dependency rejection, and checksum failure
without damage to prior staging. Post-staging compliance verifies generated notices and release notes
without rewriting the pre-staging outputs.

Focused TestKit task-graph, process-contract, and failure coverage, all 53 compliance regression cases, staging
regressions, and the real Java-25 six-project `gradlew clean verify --no-daemon` gate passed. The real
build staged and audited the unchanged 18-file canonical input set.
