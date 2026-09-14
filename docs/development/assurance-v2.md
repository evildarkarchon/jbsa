# Assurance v2 implementation guide

This document preserves the pre-cutover design rationale. The authoritative
requirements now live in the normative [Assurance v2 specification](../spec/assurance-v2.md).

## JBSA-ASR-001

The Assurance Plan **MUST** consist of versioned compact capability, scenario,
workload, and trigger definitions plus deterministic generators. Given the same
definitions and generator version, generation **MUST** produce the same ordered
Assurance Scenario set and canonical plan digest. Each generated scenario
**MUST** have a stable identifier derived from its asserted behavior and
behavioral variant; duplicate identifiers, unknown rule values, contradictory
rules, or nondeterministic generation **MUST** make the plan `INVALID`.

The repository **MUST NOT** require a committed expansion of the complete
family, operation, codec, configuration, worker, and evidence product matrix as
the authoritative plan.

_Decision source: [accepted compact-plan and stable-identity model](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-002

The Assurance Plan **MUST** define these execution tiers and triggers:

| Tier | Required trigger and scope |
| --- | --- |
| `affected` | A change with a complete impact mapping; execute every directly or transitively affected scenario. |
| `full` | A shared archive, codec, execution, public-interface, plan-generator, or unknown-impact change, and every mainline assurance run; execute the complete generated automated conformance set and required non-release performance checks. |
| `release` | An exact packaged release candidate; execute the complete generated conformance set, every release performance lane, and produce the evidence required by release gates. |

An incomplete or unknown impact mapping **MUST** select `full`, never a smaller
tier. Every executed scenario **MUST** produce exactly one deterministic result:
`PASS`, `FAIL`, or `INVALID`; an unavailable required prerequisite **MUST**
produce `INVALID`. A selected tier passes only when every required scenario is
present and `PASS`; a missing result, `FAIL`, or `INVALID` **MUST NOT** be
represented as a partial pass, waiver, percentage, or aggregate score.

_Decision source: [accepted tier, trigger, and outcome model](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-003

Automated conformance scenarios **MUST** be generated from compact Archive
Family capabilities and reusable behavioral scenario definitions. The
generated set **MUST** cover every supported direction and materially distinct
codec, layout, profile behavior, sequential or parallel execution path, and
CLI surface, together with applicable malformed-input, resource-limit,
filesystem-safety, cancellation, rollback, deterministic-output, and
cross-feature interactions. Structurally inapplicable combinations **MUST** be
absent from the plan; unsupported values or operations **MUST** remain explicit
negative scenarios.

Surface-specific authority remains unchanged: normative specifications govern
product behavior, the pinned Reference Snapshot and digest-pinned Conformance
Oracle govern their applicable observed surfaces, and Independent Validators
corroborate without silently overriding those authorities. Every applicable
encode/decode differential and Independent Validator direction **MUST** remain
represented. A contradiction among applicable authorities or evidence **MUST**
produce `INVALID` until resolved.

_Decision source: [accepted generated-conformance coverage](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-004

A Semantic Expectation **MUST** bind only the stable Assurance Scenario,
behavioral variant, expected semantic observations, and content digests needed
to judge those observations. Its identity **MUST NOT** incorporate the complete
specification-set digest, candidate digest, toolchain, JVM, machine, run time,
or other execution identity. A changed expected behavior **MUST** create a new
Semantic Expectation identity; an editorial specification change or new run
identity with unchanged semantics **MUST** require a rerun but **MUST NOT**
require a semantic rebaseline.

Every run **MUST** bind the exact specification, plan, candidate, profile,
provider, fixture or corpus, validator or oracle, toolchain, JVM, platform, and
protocol identities that apply to execution separately in its Evidence
Capsule. Binary Conformance remains an optional, case-scoped claim and **MUST**
bind exact-byte expectations and all identities required for repeatability.

_Decision source: [accepted semantic/run identity separation](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-005

Performance qualification **MUST** select approximately 20–30 curated scenarios
across the throughput, random-access, peak-memory, parallel-scaling, and
output-size Performance Lanes. Selection **MUST** cover representative stored,
zlib, LZ4-frame, raw-LZ4, metadata-heavy, bulk, General BA2, and DDS paths plus
each other materially distinct implementation path or high-risk boundary, but
**MUST NOT** multiply scenarios solely because semantically equivalent family,
codec, configuration, worker, or workload permutations exist. The plan
**MUST** record the implementation path and risk represented by each selected
scenario so omission is reviewable.

A parallel-scaling scenario **MUST** execute and report its complete applicable
worker-count vector as one scenario. Output-size gates **MUST** consume the
validated output of the corresponding pack-throughput execution rather than
repeat that pack solely to create a size result. Workloads **MUST** remain
deterministically generated from compact recipes and content digests outside
timed regions.

After a first Performance Baseline exists, the current digest-pinned released
JBSA artifact **MUST** be the primary same-machine regression comparator. The
first release and later releases **MUST** retain a focused same-machine
Conformance Oracle canary set for surfaces where it provides a meaningful
comparison; oracle coverage **MUST NOT** expand into a Cartesian performance
matrix without a newly recorded risk.

_Decision source: [accepted risk-based performance lanes](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-006

Each assurance run **MUST** produce one canonical, content-addressed Evidence
Capsule containing the tier, plan and generator identities, selected scenario
identifiers, exact run identities required by [JBSA-ASR-004](#jbsa-asr-004),
individual outcomes, contradiction or invalidity details, and digests and
stable locations for retained raw evidence. The capsule **MUST** be sufficient
to determine why each selected scenario passed, failed, or was invalid and to
detect a missing, duplicate, stale, or foreign result. Candidate, runtime, JVM,
profile, corpus, and protocol identities common to a session **MUST** be
validated once before its scenarios execute and inherited by those scenarios
rather than redundantly revalidated per process.

The active repository **MUST** retain compact plan definitions, deterministic
recipes, Semantic Expectations, schemas, and concise accepted capsules.
Ordinary local raw output **MUST** remain under ignored build output. Hosted-CI
and release raw streams, traces, recordings, and large generated manifests
**MUST** be retained as content-addressed CI or GitHub Release artifacts linked
by the Evidence Capsule rather than committed as active source content.

_Decision source: [accepted evidence-capsule and retention boundary](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-007

Automated Assurance v2 evidence **MUST NOT** claim current game or official-tool
acceptance. Every writable Archive Family required for release **MUST** retain
separate manual Windows Release Qualification against the exact packaged
candidate and frozen profile, with the tool or game, environment, archive
configuration, input and output identities, observation, diagnostics, and human
sign-off recorded. Missing, stale, ambiguous, or failed manual evidence **MUST**
block the applicable Encode Conformance and public-release claim even when all
automated scenarios pass.

Release qualification **MUST** keep Automated Conformance, Decode Conformance,
Encode Conformance, case-level Binary Conformance, Performance Qualification,
and manual Release Qualification as separate claims bound to their exact
evidence.

_Decision source: [accepted automated/manual qualification boundary](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._

## JBSA-ASR-008

The `conformance-v1` and `performance-v1` specifications, catalogs, review
packets, and accepted results **MUST** be frozen as historical evidence and
**MUST NOT** receive new expansion or rebinding work. Completed historical
evidence **MAY** be mapped to Assurance v2 scenarios during transition, but it
**MUST NOT** qualify a changed candidate or be rewritten to imply Assurance v2
execution. Interrupted, unavailable, or incomplete work—including incomplete
`0x69` BSA CV1 or performance-v1 preparation—**MUST** remain explicitly
incomplete and **MUST NOT** be counted as `PASS`.

Before Assurance v2 becomes authoritative in automation, its generated
conformance coverage **MUST** be compared with the applicable current catalog,
every consolidation, removal, and semantic gap **MUST** be documented, and one
shadow run **MUST** complete. Every semantic, safety, malformed-input,
validator, or authority gap **MUST** be added to the compact plan or recorded as
an explicit accepted retirement. Expanded historical artifacts **MAY** move out
of the active tree only when an immutable digest index and stable evidence
location preserve their provenance and make restoration possible.

_Decision source: [accepted migration and historical-evidence policy](../../.scratch/jbsa-1-0/issues/61-implement-assurance-v2.md)._
