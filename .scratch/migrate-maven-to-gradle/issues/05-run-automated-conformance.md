# 05: Run Automated Conformance through Gradle

**What to build:** Execute the existing Automated Conformance workflow through Gradle while preserving its evidence and failure meaning.

Blocked by: [04: Build and exercise the thin CLI with Gradle](04-build-exercise-thin-cli.md)
Status: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Approved and fully specified; its sole prerequisite, ticket 04, is closed.

## Acceptance criteria

- [x] Port the build-only conformance project and its tagged tasks without installation, publication, or production dependency leakage.
- [x] Retain existing Archive Family tags, Conformance Cases, oracle identity checks, native settings, and deterministic test execution.
- [x] Declare process inputs, outputs, task dependencies, and ordering while retaining regression-tested PowerShell conformance algorithms.
- [x] Test expected evidence outcomes separately from infrastructure failures; preserve evidence before propagating the final gate result.
- [x] Keep ordinary portable integration tests separable from Windows-only Conformance Oracle and qualification procedures; compilation alone cannot establish compatibility.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-12 — Implemented

Added a dedicated build-only conformance convention plugin with test-only project and JUnit
dependencies. Gradle now exposes disjoint portable contract, architecture, policy, harness,
performance, and TES3/BSA/BA2/DDS tag selections with the established one-fork, class ordering,
locale, time-zone, encoding, module inference, and conformance-only native-access settings.

The `automatedConformance` gate explicitly orders built library/CLI candidates, the retained
PowerShell harness checks, evidence capture, and final outcome interpretation. The capture task
declares its scripts, specifications, fixtures, candidate artifacts, codec profile, evidence
directory, and exit marker. Exit 0 remains a complete pass, exit 1 remains trustworthy non-passing
case evidence, and other exits fail as infrastructure only after available evidence is retained.
The stable CI `conformance` gate now invokes this Gradle task with `--no-daemon`, and uploads the
Gradle-native harness reports alongside the unchanged evidence paths.

The BA2 validator accepts exact Gradle-owned library and compiled-observer paths through its child
process environment while retaining the historical Maven-layout fallback. The PowerShell runner
likewise accepts exact Gradle candidate paths without changing its catalog, comparison, oracle,
or evidence algorithms. Focused TestKit and PowerShell regressions, the real harness, all retained
Archive Family selections, the stable CI launcher, and `gradlew clean verify --no-daemon` passed.
The generated 508-case report retained exit 1 and `automated_conformance: false`, as required for
the currently incomplete product evidence.

The repository-required Maven `clean verify` also passed after restoring the missing Surefire
`jbsa.version` property introduced by the preceding thin-CLI migration and applying its pending
Spotless formatting. This keeps the parallel Maven validation path green until atomic cutover.
