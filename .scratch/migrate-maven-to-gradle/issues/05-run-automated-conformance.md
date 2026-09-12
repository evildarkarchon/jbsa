# 05: Run Automated Conformance through Gradle

**What to build:** Execute the existing Automated Conformance workflow through Gradle while preserving its evidence and failure meaning.

Blocked by: [04: Build and exercise the thin CLI with Gradle](04-build-exercise-thin-cli.md)
Status: needs-triage
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved scope; prerequisites remain open. Reassess readiness when all blockers close.

## Acceptance criteria

- [ ] Port the build-only conformance project and its tagged tasks without installation, publication, or production dependency leakage.
- [ ] Retain existing Archive Family tags, Conformance Cases, oracle identity checks, native settings, and deterministic test execution.
- [ ] Declare process inputs, outputs, task dependencies, and ordering while retaining regression-tested PowerShell conformance algorithms.
- [ ] Test expected evidence outcomes separately from infrastructure failures; preserve evidence before propagating the final gate result.
- [ ] Keep ordinary portable integration tests separable from Windows-only Conformance Oracle and qualification procedures; compilation alone cannot establish compatibility.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

