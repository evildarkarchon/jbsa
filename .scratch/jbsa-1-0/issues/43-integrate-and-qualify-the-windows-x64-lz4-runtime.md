# Integrate and qualify the Windows x64 LZ4 runtime

Status: none
State: closed
GitHub issue: #43
Source: https://github.com/evildarkarchon/jbsa/issues/43
Author: evildarkarchon
Created: 2026-09-03T06:54:06Z
Source updated: 2026-09-03T06:54:06Z
Closed: 2026-09-10
Migrated: 2026-09-10
Labels: none
Assignees: none
Blocked by: [#32](../issues/32-build-the-performance-v1-harness-and-benchmark-corpus.md), [#29](../issues/29-establish-licensing-and-dependency-provenance-gates.md), [#41](../issues/41-establish-the-interface-candidate-after-representative-archive-families.md)
Parent: [#23](../map.md)

Intended owner: agent
Triage reviewed: 2026-09-10
Triage rationale: Completed after the accepted specification 0.14.0 sequencing amendment and explicitly approved diagnostic-profile rebaseline. Full clean verification and all 109 admitted archive regression cases pass. Runtime readiness is established; formal family/release performance gates remain open on their owning tickets.

## Original issue body

## Objective

Provide required replaceable internal LZ4-frame and raw-LZ4 adapters with pinned native artifacts, bounded dispatch, deterministic behavior, and qualified Windows launch policy.

## Planning context

This is a child of [Implement and qualify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/23). It implements the accepted sequence and gates recorded in [Choose the implementation sequence and specification release gates](https://github.com/evildarkarchon/jbsa/issues/17) under [Specify the Java 25 Bethesda archive library and BSArch-compatible CLI](https://github.com/evildarkarchon/jbsa/issues/1).

Before implementation begins, trace this issue to the exact permanent requirements in docs/spec/requirements.yaml. Expected namespaces:

- JBSA-CODEC-*
- JBSA-PERF-*
- JBSA-LIC-*

## Acceptance

- Pin LWJGL and upstream LZ4 artifacts and checksums, notices, native classifiers, and explicit Java 25 native-access requirements.
- Implement separate internal LZ4-frame and raw-LZ4 profiles with lazy native loading, capability preflight, bounded input/output checks, and provider-neutral failures.
- Keep native handles, buffers, dispatch thresholds, and adapter types out of the public interface and make failure before destination effects deterministic.
- Pass codec corpus, malformed-frame/block, memory-credit, cancellation-delay, deterministic-output, and native-loading tests.
- Run targeted throughput, memory, output-size, and launch qualification before the adapters may block or enable Archive Family slices.

## Ownership

Agent-driven when unblocked and labelled ready-for-agent.

## Non-goals

- Do not expand this issue beyond its independently mergeable outcome or bypass a native blocker relationship.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary/local game assets.
- Do not add GUI behavior, non-Windows guarantees, Maven Central/GitHub Packages publication, or moving-reference compatibility.

## Comments

No comments at migration time.

### Readiness after #41

Reassessed on 2026-09-10: every listed prerequisite is closed. The acceptance
criteria remain applicable under specification 0.13.0 and this agent-owned
ticket is ready for implementation. No prerequisite link was removed.

### Implementation and qualification review — 2026-09-10

Prepared separate bounded raw-HC and streaming-frame adapters, lazy native
preflight, exact provider/profile pins, Windows launch inputs, dependency and
license evidence, and the corresponding test/harness integration. Fourteen
focused codec and fresh-process launch tests pass. Thirty-nine supplemental
measurements cover output identity/size, throughput, resource admission and
cancellation delays; all 73 performance harness checks pass.

The [review](../../../docs/reviews/issue43-lz4-runtime/review.md) records corrected
standards findings and the unresolved specification gate. The
[impact manifest](../../../docs/reviews/issue43-lz4-runtime/performance-impact.json)
retains all 336 affected LZ4 cases plus the 1,384-case full-profile trigger as
not qualified. Existing evidence cannot be silently rebound to the new profile.
The proposed sequencing amendment is documented for a maintainer decision and
has not been applied to the permanent specification. This ticket remains open;
dependent tickets remain blocked.

The [verification record](../../../docs/reviews/issue43-lz4-runtime/validation.md)
records full-suite execution, corrected failures, successful final reactor
verification and staged launch/audit results. The implementation is saved on
the current branch without claiming completion of the unresolved formal gate.

### Accepted sequencing and completion

On 2026-09-10 the maintainer resumed the implementation after the proposed
sequencing clarification was presented. Specification 0.14.0 materializes that
decision in JBSA-PERF-003: initial required-runtime integration before Interface
Freeze and the first release may finish after the named local adapter gates,
provided it introduces no Archive Family codec consumer and leaves existing
codec algorithms and archive behavior unchanged apart from profile identity.

The retained 39 observations and 14 focused tests establish this runtime-only
readiness. They do not establish Performance-v1 success. The 336 affected LZ4
cases and 1,384-case full-profile trigger remain not qualified. Affected family
consumers still require formal targeted qualification before merge and family
qualification; full qualification remains mandatory before release. This
exception does not promote the Final Profile Gate or authorize publication.

Affected implementation tickets are #43 (runtime integration), #44–#46
(family consumers), and #55 (full performance qualification). #44 also remains
blocked by open #42. The public interface and codec-profile bytes are unchanged
by this sequencing update, so the historical Interface Candidate record is not
rewritten. Specification-bound CV1 identities receive separately reviewed,
assertion-preserving successors; historical observations remain historical.

The maintainer subsequently explicitly approved golden packet SHA-256
`353e3843a95874f21a971d503f3e14fbc66179db067c17f721c4c0eafd125e0c`.
Its only expected-value change updates 27 diagnostic profile fields across 12
goldens to the independently pinned current profile. Independent review found
no other observation changes. The strict rebaseline chain audit passed; fresh
conformance is rerun after activation rather than copying historical passes.

### Completed outcome

The final seven-module `clean verify` passed, and fresh execution passed all
109 admitted BSA 067, Fallout 4 General BA2 and PC DDS BA2 cases with no failed
or invalid comparisons. The [current CV1 report](../../../docs/reviews/issue43-lz4-runtime/current-cv1.json)
binds the actual runtime artifacts, profile, specification and active catalog.
The [activation record](../../../docs/reviews/issue43-lz4-runtime/activation.json)
preserves the exact approved packets and approval-record digests. Independent
Standards and Spec reviews found no remaining blocking finding.

Ticket #43 is closed as an internal-runtime implementation under JBSA-PERF-003.
No all-family Automated Conformance, formal Performance-v1, Final Profile or
release pass is claimed. #44 was reassessed and remains blocked by open #42;
#45, #46 and #55 retain their formal qualification obligations.
