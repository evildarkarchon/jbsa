# 11: Migrate active instructions and enforce reference hygiene

**What to build:** Ensure contributors and operators follow the Gradle build and stale active Maven instructions are detected.

Blocked by: [09: Run stable CI gates on Windows and Linux](09-run-stable-ci-gates.md)
Status: none
Labels: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and verified; no further work remains in this ticket.

## Acceptance criteria

- [x] Update normative build requirements, requirement bindings, developer documentation, CI commands, operational procedures, and then-current actionable local-ticket instructions to Gradle.
- [x] Implement and test an active-reference scan covering specifications, documentation, automation, CI, tests, and local issues, with a reviewed historical allowlist.
- [x] Preserve imported bodies/comments, canonical historical ticket identities, research, observations, and earlier evidence, as well as factual consumer-POM requirements and the Maven Central proper name.
- [x] Document gradlew clean verify, narrower Gradle commands, installed Java 25, exact qualification JDK, candidate versions, native/platform boundaries, and evidence procedures.
- [x] Document manual isolated checksum-reviewed Gradle/plugin upgrades, disabled optimization/telemetry policies, and rollback by coherent cutover reversion.
- [x] Keep temporary migration comparison references explicitly bounded for removal at cutover; repeat the actionable-ticket audit against current tracker state.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-13 — Implemented

Migrated the active normative build specification, requirement bindings, contributor guidance,
developer runbooks, compliance procedures, and focused evidence commands to the pinned Gradle 9.7.1
Kotlin DSL build. The documentation now distinguishes installed Java 25 from the exact hosted
Temurin `25.0.4+7.0.LTS` qualification identity, uses `gradlew clean verify` as the complete gate,
documents narrower tasks and `-Pversion` candidates, and retains the Windows/native/manual evidence
boundaries, disabled telemetry and remote task-output caching, isolated checksum-reviewed upgrades,
and coherent-revert rollback policy.

Added `verifyActiveReferences` to root `verify`. Its behavioral coverage proves all required scan
surfaces, actionable diagnostics, exact-line historical approvals, preserved consumer-POM and Maven
Central terminology, and automatic expiry of migration-comparison exceptions when ticket 14 closes.
The reviewed allowlist contains 59 exact occurrence digests. Imported tickets, dated evidence,
research, reviews, and the approved migration record remain unchanged; comparison automation and
legacy policy fixtures are explicitly bounded to the atomic cutover. A fresh audit found no open
local ticket with an actionable legacy build invocation.

The build now forwards only the six reviewed `jbsa.*` local-evidence opt-ins into forked test JVMs,
and the optional BA2 local-corpus test participates in the `ba2ConformanceTest` task. Build-logic
tests, the active-reference task, requirements-registry parsing, and focused BA2 task selection pass.
The complete `gradlew.bat clean verify --no-daemon` run passed through reference hygiene, policy,
compliance, compilation, unit, and ordinary integration checks, then stopped at the expected stale
`docs/spec/requirements.yaml` conformance-catalog digest. Ticket 12 owns regeneration of that newly
invalidated evidence; existing evidence was deliberately left unchanged as provenance.

### 2026-09-13 — Review follow-up

Retired the specification registry's GitHub-number implementation binding. Schema version 2 uses
one or more repository-relative `implementation_tickets` paths, validates that every referenced
local tracker file exists, and binds the migrated Gradle build requirements to this ticket while
retaining their imported predecessor tickets. The performance-requirement supplement uses the same
local identity. Historical GitHub decision links remain only as archival provenance; new normative
changes must originate from an authoritative ticket under `.scratch/`.
