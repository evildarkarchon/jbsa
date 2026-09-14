# Assurance v2

Assurance v2 is the normative verification plan for JBSA. It replaces the
expanded `conformance-v1` and `performance-v1` catalogs with compact,
deterministic rules while preserving their behavioral and safety obligations.

## JBSA-ASR-001

The Assurance Plan **MUST** consist of versioned capability, scenario, workload,
and impact-rule definitions plus deterministic generators. Identical definitions
and generator versions **MUST** produce the same ordered Assurance Scenarios and
canonical plan digest. Duplicate or unstable identifiers, unknown rule values,
contradictory rules, or nondeterministic generation **MUST** make the plan
`INVALID`. The complete expanded product matrix **MUST NOT** be committed as the
authoritative plan.

_Decision source: [Assurance v2 approval](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-002

The Assurance Plan **MUST** define `affected`, `full`, and `release` tiers.
`affected` **MUST** include every directly or transitively impacted Assurance
Scenario. Shared archive, codec, execution, public-interface, generator, unknown,
or incomplete impact **MUST** select `full`; mainline **MUST** select `full`; and
release qualification **MUST** select `release`. The full and release tiers
**MUST** contain the complete applicable generated conformance matrix, while
release additionally contains every release Performance Lane.

Every selected scenario **MUST** produce exactly one `PASS`, `FAIL`, or `INVALID`.
Missing, duplicate, stale, foreign, or unavailable required evidence **MUST** be
`INVALID`; only an exact set of `PASS` results passes a tier.

_Decision source: [Assurance v2 approval](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-003

Generated conformance **MUST** cover every supported direction and materially
distinct codec, layout, profile, sequential or parallel path, and CLI surface,
plus applicable malformed-input, resource-limit, extraction-containment,
cancellation, rollback, deterministic-output, and interaction behavior.
Unsupported values and operations **MUST** remain explicit negative scenarios.

Normative specifications govern product behavior. The digest-pinned Conformance
Oracle and Reference Snapshot govern their applicable observed surfaces, and an
Independent Validator corroborates without replacing that authority. Applicable
encode/decode differentials and Independent Validator directions **MUST** remain
represented; unresolved contradictions **MUST** produce `INVALID`.

_Decision source: [Assurance v2 approval](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-004

A Semantic Expectation **MUST** bind only its stable Assurance Scenario,
behavioral variant, expected semantic observations, and required content
digests. Candidate, specification-set, profile, provider, toolchain, JVM,
platform, machine, and run identities **MUST NOT** change that expectation.
Changing asserted behavior **MUST** change its identity.

Each run **MUST** bind the exact specification, plan, generator, candidate,
profile, provider, fixture or corpus, validator or oracle, toolchain, JVM,
platform, and protocol identities separately in its Evidence Capsule. Binary
Conformance is an optional case-scoped claim and **MUST** bind every identity
needed for repeatability.

_Decision source: [Assurance v2 approval](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-005

Performance qualification **MUST** select approximately 20–30 reviewable
scenarios covering throughput, random access, peak memory, parallel scaling,
and output size. The plan **MUST** represent stored, zlib, LZ4-frame, raw-LZ4,
metadata-heavy, bulk, General BA2, and DDS paths and record the implementation
path and risk represented by every scenario.

A scaling scenario **MUST** carry its complete worker vector. Output-size
evaluation **MUST** consume the corresponding validated pack output rather than
repeat the operation. Inputs **MUST** be materialized deterministically from
compact Benchmark Corpus recipes outside timed regions. Once a released JBSA
Performance Baseline exists, it **MUST** be the primary same-machine comparator;
the Conformance Oracle **MUST** remain a focused canary rather than a Cartesian
matrix.

_Decision source: [Assurance v2 approval](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-006

Each run **MUST** produce one canonical content-addressed Evidence Capsule that
binds the tier, plan and generator identities, selected scenarios, session
identities, individual outcomes, invalidity details, and stable raw-evidence
locations. Common candidate, runtime, JVM, profile, corpus, provider, and
protocol identities **MUST** be validated once per session and inherited by its
results.

The repository **MUST** retain compact plans, recipes, Semantic Expectations,
schemas, accepted concise capsules, and the historical digest index. Generated
manifests and ordinary raw output **MUST** remain under ignored build output;
hosted and release raw evidence **MUST** be retained as CI or release artifacts
linked from the capsule.

_Decision source: [Assurance v2 approval](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-007

Automated Assurance **MUST NOT** claim current game or official-tool acceptance.
Each writable Archive Family required for release **MUST** retain separate manual
Windows Release Qualification for the exact packaged candidate and profiles.
Automated Conformance, Decode Conformance, Encode Conformance, optional case-level
Binary Conformance, Performance Qualification, and manual Release Qualification
**MUST** remain distinct claims.

_Decision source: [Assurance v2 approval](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-008

The `conformance-v1` and `performance-v1` catalogs, specifications, results, and
review packets **MUST** remain frozen historical evidence and **MUST NOT**
qualify a changed candidate. Assurance v2 **MUST** compare its generated coverage
with the digest-pinned applicable historical catalog before cutover, documenting
each consolidation, removal, or gap. Every unresolved semantic or safety gap
**MUST** remain blocking.

Historical material **MAY** leave the active tree only when its immutable digest
index and restoration location preserve its provenance. Interrupted work
**MUST** remain incomplete rather than being counted as `PASS`.

_Decision source: [Assurance v2 approval](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._
