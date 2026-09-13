# 10: Prove artifact parity, reproducibility, and offline operation

**What to build:** Provide measured proof that Gradle preserves existing contracts and independently reproduces verified release inputs.

Blocked by: [09: Run stable CI gates on Windows and Linux](09-run-stable-ci-gates.md)
Status: ready-for-agent
State: open
Assignees: none
Intended owner: agent
Triage rationale: Approved and fully specified; prerequisite ticket 09 is closed.

## Acceptance criteria

- [ ] Compare Maven and Gradle at one source revision, candidate version, and pinned qualification JDK across every baseline artifact, metadata, dependency, staging, CLI, and gate seam; explain differences against normative requirements.
- [ ] Ignore archive-envelope differences only for cross-tool comparison; require complete byte equality between two clean Gradle builds for consumer POM, library binary, sources, Javadocs, and thin CLI.
- [ ] Adapt the reproducibility workflow without narrowing its artifact set and preserve reusable inspectors and a normalized parity report.
- [ ] Run a primed, verified-cache offline qualification build covering all supported task inputs; demonstrate strict-verification failures and lock consistency.
- [ ] Complete Gradle-native build-policy and black-box coverage without weakening Maven policy during parallel validation; include negative dependency/plugin fixtures and real multi-project interaction.
- [ ] Exercise intended cache-compatible tasks twice with configuration-cache checking; require explicit tested rejection for incompatible evidence/staging/process tasks while leaving the mode disabled normally.
- [ ] Record comparable cold/warm measurements on the same machine without claiming unsupported speed improvements or waiving correctness gates.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.
