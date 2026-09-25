# Build the performance-v1 harness and Benchmark Corpus

Status: none
State: closed
GitHub issue: #32
Source: https://github.com/evildarkarchon/jbsa/issues/32
Author: evildarkarchon
Created: 2026-09-03T06:53:39Z
Source updated: 2026-09-05T11:04:27Z
Closed: 2026-09-05T11:03:36Z
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#27](../issues/27-materialize-the-cli-and-qualification-specifications.md), [#28](../issues/28-bootstrap-the-java-25-maven-and-jpms-reactor.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Already closed on GitHub; preserved as history, outside the work queue.

## Original issue body

## Objective

Create deterministic performance inputs and local paired-run tooling early enough to gate codec, I/O, DDS, native, concurrency, random-access, and packaging decisions.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-PERF-*

## Acceptance

- Implement the versioned Benchmark Corpus generators, seeds, manifests, structural templates, and content-addressed materialization process.
- Implement same-machine candidate, Conformance Oracle, and later Performance Baseline runners with pinned JVM/provider/protocol identities.
- Measure throughput, random-access latency, peak heap/native/scratch memory, output size, and 1/2/4/8/16-worker scaling as applicable.
- Encode the accepted thresholds, confidence handling, warmup/order controls, invalid-run rules, raw result schema, and non-waivable independent metric outcomes.
- Expose explicit targeted and full local qualification commands; do not run benchmarks in GitHub Actions or ordinary mvn verify.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.
