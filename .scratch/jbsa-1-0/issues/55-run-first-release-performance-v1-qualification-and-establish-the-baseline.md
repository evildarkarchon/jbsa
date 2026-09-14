# Run first-release performance-v1 qualification and establish the baseline

Status: needs-triage
State: open
GitHub issue: #55
Source: https://github.com/evildarkarchon/jbsa/issues/55
Author: evildarkarchon
Created: 2026-09-03T06:54:33Z
Source updated: 2026-09-03T06:54:33Z
Closed: none
Migrated: 2026-09-10
Labels: needs-triage
Assignees: none
Blocked by: [#32](../issues/32-build-the-performance-v1-harness-and-benchmark-corpus.md), [#53](../issues/53-assemble-and-verify-the-self-contained-windows-x64-application-image.md), [#50](../issues/50-complete-the-mandatory-automated-conformance-matrix.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-13
Triage rationale: Reassess readiness after open prerequisites close: #53, #50. Qualification will use the curated Assurance v2 hot-path plan rather than the historical 1,384-case product matrix.

## Original issue body

## Objective

Run the full local same-machine performance-v1 protocol on final release artifacts and establish the immutable first Performance Baseline.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-PERF-*
- JBSA-REL-*

## Acceptance

- Preflight the final candidate, Conformance Oracle, Benchmark Corpus, JVM, provider profile, runtime, protocol, and machine identities and reject mismatched/noisy runs as INVALID.
- Run all applicable throughput, random-access latency, peak memory, parallel scaling, and output-size Performance Cases with raw samples and confidence evidence.
- Meet candidate/oracle throughput lower bound 0.80, applicable baseline bound, latency ceilings, strict memory/size limits, and 1.50x/2.50x/4.00x scaling gates at 2/4/8 workers.
- Record independent metric outcomes without composites or waivers and investigate every regression or invalid result.
- Publish the first immutable Performance Baseline artifact and its complete Benchmark Corpus, JVM, provider, protocol, and checksum identity.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### Full qualification retained after #43

The initial runtime-only exception in specification 0.14.0 does not change this
release gate. The
[runtime impact manifest](../../../docs/reviews/issue43-lz4-runtime/performance-impact.json)
records the 1,384-case full-profile qualification trigger as not qualified.
Reconcile that inventory with the final corpus, configuration and applicability
rules, bind the exact candidate and comparators, and pass every required case
before release. Supplemental adapter measurements and historical results from
another identity cannot satisfy this ticket.

### Assurance v2 performance baseline — 2026-09-13

The historical 1,384-case inventory is no longer the release gate. This ticket
now establishes the first baseline from the curated, risk-based release hot-path
plan implemented by [#61](../issues/61-implement-assurance-v2.md), expected to be
approximately 20–30 distinct implementation-path scenarios.

The final release candidate must still be measured on a controlled machine for
representative throughput, random access, peak memory, worker scaling, and
output-size behavior across the applicable archive, codec, and workload paths.
Each scaling scenario records one experiment containing the full worker vector,
and output-size results are derived from the corresponding pack run. Session
identity and validity are checked once where possible. Compact definitions,
digests, thresholds, metric outcomes, and the baseline result capsule remain in
version control; voluminous raw samples may be retained as release or CI
artifacts.

Every selected metric must pass its explicit bound independently, and every
invalid or regressing selected scenario must be investigated. No run of all
historical performance-v1 assignments is required. This current acceptance
supersedes conflicting full-matrix wording above while preserving it as history.
