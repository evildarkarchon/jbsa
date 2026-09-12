# 01: Capture the Maven parity baseline

**What to build:** Give migration reviewers a repeatable baseline and reusable artifact inspectors before changing build ownership.

Blocked by: None (can start immediately)
Status: ready-for-agent
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved, fully specified, and has no prerequisites.

## Acceptance criteria

- [ ] Capture the current Maven outputs, resolved graph, gate results, and implemented behavior at a recorded source revision, candidate version, and pinned qualification JDK; preserve all existing evidence.
- [ ] Compare or inventory JAR entries and payloads, JPMS descriptors, public signatures, sources, Javadocs, normalized consumer POMs, runtime filenames and SHA-256 values, SBOM graphs, notices, staging manifests, and CLI observations.
- [ ] Separate reusable artifact inspectors from temporary Maven invocation; exercise inspectors with matching and deliberately mismatched artifacts.
- [ ] Record normative requirements that Maven does not yet implement; neither bless those gaps as parity nor expand this migration into unrelated product work.
- [ ] Establish one migration branch and a repeatable same-revision comparison protocol that prevents the two builds from contaminating each other's generated outputs.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

