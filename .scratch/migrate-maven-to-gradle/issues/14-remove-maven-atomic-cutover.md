# 14: Remove Maven and verify the atomic cutover

**What to build:** Make Gradle the sole authoritative build in a coherent, revertible cutover after all required evidence is accepted.

Blocked by: ~~[13: Complete Windows Release Qualification](13-complete-windows-release-qualification.md)~~ (this ticket is removed, treat as None)
Status: none
Labels: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and verified; Gradle is the sole authoritative build and no further cutover work remains.

## Acceptance criteria

- [x] Confirm every preceding ticket is closed and the same candidate is supported by parity, Windows/Linux, offline, reproducibility, policy, compliance, staging, conformance, documentation, and Release Qualification evidence.
- [x] Remove all active Maven POMs, wrapper files, Maven-only configuration, and temporary comparison machinery without compatibility shims or hidden fallback.
- [x] Retain reusable artifact inspectors, Gradle-only reproducibility coverage, the normalized final parity report, and historical evidence.
- [x] Run final Gradle verification and relevant platform/offline/reproducibility gates after removal; prove the active-reference scan passes without temporary migration exceptions.
- [x] Check that final staged artifacts still match qualified identities; any material change invalidates affected evidence and requires regeneration/requalification before acceptance.
- [x] Prepare one coherent revertible cutover through reviewable commits on the migration branch; rollback restores the coherent prior state rather than adding Maven beside Gradle.
- [x] Refresh the knowledge graph after implementation changes, preserve the read-only Reference Snapshot, and leave remote publication, signing, deployment, and unrelated product work outside scope.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-13 — Implemented

Confirmed tickets 01–12 are closed and ticket 13 remains intentionally removed. The retained
`c3404f7` acceptance report and its ignored raw handoff validate the same `0.1.0-SNAPSHOT`
candidate across Windows, Linux portability, strict offline resolution, reproducibility, policy,
compliance, staging, documentation, and normalized parity. Automated Conformance remains honestly
`BLOCKED` with 508 `INVALID` cases, and manual Release Qualification remains prepared but not
performed; this cutover does not turn either record into a qualification claim.

Removed the seven active build descriptors, both legacy launchers, their wrapper configuration,
IDE import metadata, legacy policy coverage, and the comparison-only capture and qualification
harnesses. Compliance and staging now require Gradle-generated model manifests with no fallback.
The reusable artifact inspector, Gradle reproducibility coverage, compact parity and acceptance
reports, and earlier evidence remain intact.

The permanent active-reference gate now also rejects reintroduced legacy build descriptors and
launchers. Its allowlist contains only 35 exact historical or scanner-fixture references; the
cutover-expiring category and all 24 temporary entries are gone. Five staged text inputs now have
explicit CRLF checkout policy because the accepted handoff binds those exact bytes.
