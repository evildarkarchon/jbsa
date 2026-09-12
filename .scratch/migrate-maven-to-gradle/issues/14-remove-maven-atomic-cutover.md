# 14: Remove Maven and verify the atomic cutover

**What to build:** Make Gradle the sole authoritative build in a coherent, revertible cutover after all required evidence is accepted.

Blocked by: ~~[13: Complete Windows Release Qualification](13-complete-windows-release-qualification.md)~~ (this ticket is removed, treat as None)
Status: ready-for-agent
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved scope; prerequisites remain open. Reassess readiness when all blockers close.

## Acceptance criteria

- [ ] Confirm every preceding ticket is closed and the same candidate is supported by parity, Windows/Linux, offline, reproducibility, policy, compliance, staging, conformance, documentation, and Release Qualification evidence.
- [ ] Remove all active Maven POMs, wrapper files, Maven-only configuration, and temporary comparison machinery without compatibility shims or hidden fallback.
- [ ] Retain reusable artifact inspectors, Gradle-only reproducibility coverage, the normalized final parity report, and historical evidence.
- [ ] Run final Gradle verification and relevant platform/offline/reproducibility gates after removal; prove the active-reference scan passes without temporary migration exceptions.
- [ ] Check that final staged artifacts still match qualified identities; any material change invalidates affected evidence and requires regeneration/requalification before acceptance.
- [ ] Prepare one coherent revertible cutover through reviewable commits on the migration branch; rollback restores the coherent prior state rather than adding Maven beside Gradle.
- [ ] Refresh the knowledge graph after implementation changes, preserve the read-only Reference Snapshot, and leave remote publication, signing, deployment, and unrelated product work outside scope.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

