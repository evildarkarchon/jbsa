# 10: Prove artifact parity, reproducibility, and offline operation

**What to build:** Provide measured proof that Gradle preserves existing contracts and independently reproduces verified release inputs.

Blocked by: [09: Run stable CI gates on Windows and Linux](09-run-stable-ci-gates.md)
Status: none
Labels: none
State: closed
Assignees: none
Intended owner: agent
Triage rationale: Implemented and measured; no further work remains in this ticket.

## Acceptance criteria

- [x] Compare Maven and Gradle at one source revision, candidate version, and pinned qualification JDK across every baseline artifact, metadata, dependency, staging, CLI, and gate seam; explain differences against normative requirements.
- [x] Ignore archive-envelope differences only for cross-tool comparison; require complete byte equality between two clean Gradle builds for consumer POM, library binary, sources, Javadocs, and thin CLI.
- [x] Adapt the reproducibility workflow without narrowing its artifact set and preserve reusable inspectors and a normalized parity report.
- [x] Run a primed, verified-cache offline qualification build covering all supported task inputs; demonstrate strict-verification failures and lock consistency.
- [x] Complete Gradle-native build-policy and black-box coverage without weakening Maven policy during parallel validation; include negative dependency/plugin fixtures and real multi-project interaction.
- [x] Exercise intended cache-compatible tasks twice with configuration-cache checking; require explicit tested rejection for incompatible evidence/staging/process tasks while leaving the mode disabled normally.
- [x] Record comparable cold/warm measurements on the same machine without claiming unsupported speed improvements or waiving correctness gates.

## Context

Follow the [approved migration spec](../spec.md) and [ADR-0001](../../../docs/adr/0001-migrate-maven-build-to-gradle-kotlin-dsl.md). Work on the shared migration branch; preserve Java sources, project boundaries, unrelated dependency versions, historical evidence, and the read-only Reference Snapshot.

## Comments

### 2026-09-13 — Implemented

Added the same-revision Gradle qualification workflow and retained build-tool-neutral inspectors,
ported the five-artifact reproducibility gate to Gradle, and added Gradle-native real-build policy
coverage plus dependency/plugin negative fixtures. The measured proof at `236202b` used the exact
pinned Temurin archive, passed strict verified-cache offline product and included-build closures,
proved lock consistency and checksum rejection, and exercised configuration-cache acceptance and
explicit rejection behavior.

The [compact parity report](../parity/236202b/README.md) records zero unexplained normative
differences, exact reproducibility hashes, gate/CLI results, and same-machine cold/warm observations
without a speed claim. Clean-worktree qualification also exposed and fixed stale line-ending and
transitive-generator identities in the BSA fixture manifests.
