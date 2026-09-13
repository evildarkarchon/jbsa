# 11: Migrate active instructions and enforce reference hygiene

**What to build:** Ensure contributors and operators follow the Gradle build and stale active Maven instructions are detected.

Blocked by: [09: Run stable CI gates on Windows and Linux](09-run-stable-ci-gates.md)
Status: ready-for-agent
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved and fully specified; prerequisite ticket 09 is closed and ticket 10 has completed the parity proof needed to update active instructions safely.

## Acceptance criteria

- [ ] Update normative build requirements, requirement bindings, developer documentation, CI commands, operational procedures, and then-current actionable local-ticket instructions to Gradle.
- [ ] Implement and test an active-reference scan covering specifications, documentation, automation, CI, tests, and local issues, with a reviewed historical allowlist.
- [ ] Preserve imported bodies/comments, canonical historical ticket identities, research, observations, and earlier evidence, as well as factual consumer-POM requirements and the Maven Central proper name.
- [ ] Document gradlew clean verify, narrower Gradle commands, installed Java 25, exact qualification JDK, candidate versions, native/platform boundaries, and evidence procedures.
- [ ] Document manual isolated checksum-reviewed Gradle/plugin upgrades, disabled optimization/telemetry policies, and rollback by coherent cutover reversion.
- [ ] Keep temporary migration comparison references explicitly bounded for removal at cutover; repeat the actionable-ticket audit against current tracker state.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.
