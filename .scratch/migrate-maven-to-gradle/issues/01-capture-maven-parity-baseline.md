# 01: Capture the Maven parity baseline

**What to build:** Give migration reviewers a repeatable baseline and reusable artifact inspectors before changing build ownership.

Blocked by: None (can start immediately)
Status: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Completed on the shared migration branch; no active triage role remains.

## Acceptance criteria

- [x] Capture the current Maven outputs, resolved graph, gate results, and implemented behavior at a recorded source revision, candidate version, and pinned qualification JDK; preserve all existing evidence.
- [x] Compare or inventory JAR entries and payloads, JPMS descriptors, public signatures, sources, Javadocs, normalized consumer POMs, runtime filenames and SHA-256 values, SBOM graphs, notices, staging manifests, and CLI observations.
- [x] Separate reusable artifact inspectors from temporary Maven invocation; exercise inspectors with matching and deliberately mismatched artifacts.
- [x] Record normative requirements that Maven does not yet implement; neither bless those gaps as parity nor expand this migration into unrelated product work.
- [x] Establish one migration branch and a repeatable same-revision comparison protocol that prevents the two builds from contaminating each other's generated outputs.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-12 — Implemented

Captured the retained [Maven parity baseline](../baseline/08bcd8ba3626bb5ae9372fffab97fa847867f664/README.md)
from clean revision `08bcd8ba3626bb5ae9372fffab97fa847867f664`, candidate
`0.1.0-SNAPSHOT`, Apache Maven 3.9.16, and the exact hosted qualification pin, Eclipse Temurin
`25.0.4+7`. The reusable build-tool-neutral inspector is separate from the temporary Maven
orchestration and its fixture tests prove both semantic matches and deliberate archive, JPMS/API,
POM, SBOM, runtime-name, and byte mismatches.

The baseline honestly retains the current failed `clean verify` and conformance outcomes plus the
class-path CLI `--version` failure; successful output materialization and the other stable gates are
recorded independently. Those observations are not parity expectations, product qualification
claims, or authorization to expand the migration into unrelated fixes.
