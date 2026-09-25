# 12: Regenerate the Gradle acceptance evidence

**What to build:** Produce a complete fresh evidence set attributable to the Gradle candidate and ready for manual qualification.

Blocked by: [10: Prove artifact parity, reproducibility, and offline operation](10-prove-parity-reproducibility-offline.md), [11: Migrate active instructions and enforce reference hygiene](11-migrate-active-instructions.md)
Status: none
Labels: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Approved and fully specified; tickets 10 and 11 are closed, so evidence regeneration is unblocked.

## Acceptance criteria

- [x] Regenerate build, dependency, publication, SBOM, staging, reproducibility, and Automated Conformance evidence with candidate source/version, toolchain, and artifact identities.
- [x] Collect passing Windows complete verification, Linux portability, verified offline execution, policy/module tests, compliance/staging audits, and normalized parity evidence.
- [x] Validate documentation migration and the active-reference scan with only reviewed history and bounded pre-cutover machinery allowed.
- [x] Keep old evidence unchanged and clearly separate it from evidence qualifying Gradle-produced artifacts.
- [x] Prepare the actual Gradle-produced artifacts, checksums, procedures, and Conformance Case scope for manual Windows Release Qualification; do not claim that hosted CI supplies that qualification.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-13 — Implemented

Regenerated the complete acceptance set for clean Gradle candidate
`c3404f789bdc1a82e777efef5b46dbf53a3121ea`, version `0.1.0-SNAPSHOT`, with exact
Temurin `25.0.4+7` toolchains. Windows complete and verified-offline closures, Linux portability,
publication, dependency, SBOM, compliance/staging, policy/module, active-reference,
reproducibility, and normalized parity evidence passed. The compact tracked record is
[acceptance/c3404f7](../acceptance/c3404f7/README.md); its report binds the ignored raw evidence and
actual 18-entry staged handoff under `target/qualification-evidence/c3404f7` without modifying the
earlier `236202b` evidence.

The fresh 508-case Automated Conformance observation remains `BLOCKED`: every case is `INVALID`
because no public-interface execution adapter is registered. This is retained as honest evidence,
not relabelled as a pass. The manual Windows Release Qualification package and 23 positive Encode
Conformance Case scope are prepared, but no game/official-tool run or operator approval is claimed;
the human-owned qualification and public release remain blocked by their existing prerequisites.

### 2026-09-13 — Review correction

The final evidence cycle was rerun at `c3404f7` after review corrected the tracker index and split
the Gradle launcher, wrapper-JAR, and wrapper-properties identities. The earlier compact `494db4e`
record was removed because a subsequent root `clean` had correctly deleted its ignored raw handoff;
no acceptance claim relies on unavailable files. The `c3404f7` raw evidence remains present for the
operator handoff, and its compact report binds the complete tree.
