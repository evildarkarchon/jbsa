# 12: Regenerate the Gradle acceptance evidence

**What to build:** Produce a complete fresh evidence set attributable to the Gradle candidate and ready for manual qualification.

Blocked by: [10: Prove artifact parity, reproducibility, and offline operation](10-prove-parity-reproducibility-offline.md), [11: Migrate active instructions and enforce reference hygiene](11-migrate-active-instructions.md)
Status: needs-triage
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved scope; prerequisites remain open. Reassess readiness when all blockers close.

## Acceptance criteria

- [ ] Regenerate build, dependency, publication, SBOM, staging, reproducibility, and Automated Conformance evidence with candidate source/version, toolchain, and artifact identities.
- [ ] Collect passing Windows complete verification, Linux portability, verified offline execution, policy/module tests, compliance/staging audits, and normalized parity evidence.
- [ ] Validate documentation migration and the active-reference scan with only reviewed history and bounded pre-cutover machinery allowed.
- [ ] Keep old evidence unchanged and clearly separate it from evidence qualifying Gradle-produced artifacts.
- [ ] Prepare the actual Gradle-produced artifacts, checksums, procedures, and Conformance Case scope for manual Windows Release Qualification; do not claim that hosted CI supplies that qualification.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

