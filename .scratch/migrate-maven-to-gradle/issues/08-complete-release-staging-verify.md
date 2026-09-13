# 08: Complete release staging through clean verify

**What to build:** Give maintainers one complete Gradle verification command that produces and audits the unchanged canonical staging set.

Blocked by: [05: Run Automated Conformance through Gradle](05-run-automated-conformance.md), [06: Preserve the standalone benchmark workflow](06-preserve-standalone-benchmarks.md), [07: Generate and audit production compliance inputs](07-generate-audit-compliance-inputs.md)
Status: needs-triage
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved scope; tickets 05 and 06 are closed, while ticket 07 remains open. Reassess readiness when the remaining blocker closes.

## Acceptance criteria

- [ ] Make gradlew clean verify explicitly build canonical artifacts, generate publication/compliance inputs, run pre-staging compliance, stage release inputs, and complete the post-staging audit.
- [ ] Keep assemble, check, and build narrower than the complete verification lifecycle and test their task-graph boundaries.
- [ ] Keep jbsa-dist a non-Java staging/audit project; exclude benchmark, test-support, conformance, internal manifests, and incidental application distributions from release inputs.
- [ ] Retain PowerShell staging algorithms and prove complete-input success, exact copies, deterministic manifests, stale-file removal, missing-input failure, and blocked-dependency rejection.
- [ ] Prove checksum rejection does not damage prior staging and post-staging audit cannot run successfully ahead of required inputs.
- [ ] Give custom Kotlin orchestration tasks declared inputs, outputs, process contracts, and focused unit/TestKit failure and ordering coverage, backed by the real six-project build.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.
