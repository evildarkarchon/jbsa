# Qualify jlibdeflate and select the standard zlib dispatch

Status: ready-for-agent
State: open
GitHub issue: #52
Source: https://github.com/evildarkarchon/jbsa/issues/52
Author: evildarkarchon
Created: 2026-09-03T06:54:26Z
Source updated: 2026-09-03T06:54:26Z
Closed: none
Migrated: 2026-09-10
Labels: ready-for-agent
Assignees: none
Blocked by: [#50](../issues/50-complete-the-mandatory-automated-conformance-matrix.md), [#32](../issues/32-build-the-performance-v1-harness-and-benchmark-corpus.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-28
Triage rationale: #32 and #50 are closed. The accepted Assurance v2 gate replaces the original expanded CV1/PV1 rerun wording; the remaining codec qualification and dispatch decision are specified and agent-owned.

## Original issue body

## Objective

Use complete conformance and measured evidence to promote or defer the optional jlibdeflate provider and select bounded whole-buffer dispatch without changing an existing immutable profile.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-CODEC-*
- JBSA-PERF-*
- JBSA-CONF-*

## Acceptance

- Qualify decode, encode, malformed input, provider fallback, deterministic output, memory credits, native loading, packaging, and notice behavior against the JDK zlib baseline.
- Measure every required codec throughput, memory, size, and relevant binary-repeatability case, accounting explicitly for the libdeflate 1.25 versus reference 1.24 mismatch.
- Select evidence-backed input-size and memory-credit dispatch thresholds or defer promotion if any conformance, performance, memory, native, or compliance gate fails.
- If promoted, create a new digest-identified immutable codec profile and rerun every affected CV1 and PV1 case; never silently mutate the existing profile.
- Keep provider selection and thresholds internal and document the exact decision, evidence, fallback, and requalification triggers.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### Assurance v2 readiness — 2026-09-28

The original CV1/PV1 rerun wording is historical after #61. A promoted provider
must rerun the impacted generated Assurance Scenarios and curated Performance
Lanes under the new immutable codec profile, with shared-core or unknown impact
selecting the full conformance tier. The codec behavior, safety, performance,
compliance, and decision requirements remain in force.
