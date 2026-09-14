# Implement compact generated conformance and risk-based performance assurance

Status: none
State: closed
Type: task
Source: local
Author: maintainer
Created: 2026-09-13
Labels: none
Assignees: none
Blocked by: none
Parent: [#23](../map.md)

Intended owner: agent
Closed: 2026-09-14
Triage reviewed: 2026-09-14
Triage rationale: Closed after the complete generated comparison, authoritative gate cutover, normative requirement migration, frozen-history index, and report-derived Evidence Capsule passed.

## Objective

Replace the committed expanded CV1 and performance-v1 proof catalogs with an
Assurance v2 system that retains behavioral and safety coverage while reducing
generated repository material, rebaseline churn, process invocations, and AI
context cost.

## Accepted decision — 2026-09-13

The maintainer approved Assurance v2. The existing CV1 and performance-v1
catalogs are frozen as historical evidence; they are not the completion
authority for ongoing archive-family work and no new expanded proof or
rebaseline packets are required.

Assurance v2 has two compact sources of truth:

- deterministic conformance generation from capability and semantic-scenario
  rules; and
- a curated, risk-based performance plan organized by distinct implementation
  paths and release hot paths.

Correctness and safety obligations remain. Independent wire validators, oracle
differentials, malformed-input behavior, extraction containment, cancellation,
resource bounds, determinism, and applicable interaction coverage must still be
exercised. Binary Conformance becomes an optional case-level claim that may be
earned where useful; it is not a release-wide mandatory gate.

## Acceptance

- Define compact, reviewable capability and scenario sources that generate the
  applicable conformance matrix deterministically at run time.
- Give scenarios stable behavioral identities that do not change merely because
  the complete specification set, profile digest, candidate, JVM, or toolchain
  identity changes.
- Preserve semantic, malformed-input, filesystem-safety, resource, cancellation,
  determinism, interaction, independent-validator, and bidirectional-oracle
  coverage represented by the current assurance requirements.
- Run impacted generated cases on ordinary changes, fail closed when impact is
  unknown, and run the complete applicable generated matrix for shared-core,
  mainline, and release qualification changes.
- Replace the expanded performance product matrix with approximately 20–30
  curated scenarios covering representative stored, zlib, LZ4-frame, raw-LZ4,
  metadata-heavy, bulk, General BA2, DDS, random-access, memory, and scaling
  implementation paths.
- Represent a worker-scaling experiment once with its worker vector, and derive
  output-size evaluation from the associated pack run instead of rerunning the
  same operation.
- Validate candidate, runtime, JVM, profile, corpus, and protocol identity once
  per session where possible. Keep compact corpus recipes, scenario definitions,
  digests, and result capsules in version control; keep generated manifests and
  voluminous raw run evidence in build or CI artifacts.
- Update the normative assurance, performance, traceability, and release-gate
  documentation and requirement mappings so Assurance v2 clearly supersedes
  conflicting CV1/performance-v1 wording.
- Compare generated Assurance v2 coverage with the relevant current catalog,
  document intentional consolidations or removals, and complete a shadow run
  before making Assurance v2 authoritative in automation.
- Provide a reversible disposition for historical CV1/performance-v1 catalogs
  and review packets, retaining a compact digest index sufficient to identify
  the archived evidence.
- Update affected archive-family completion checks, including BSA 0x69, and the
  conformance, performance, and final release tickets to consume the new gate.

## Non-goals

- Do not weaken archive correctness, malformed-input handling, extraction
  containment, deterministic output, cancellation, or resource-safety coverage.
- Do not require every historical expanded case or performance assignment to be
  migrated one-for-one when a compact rule or consolidated experiment preserves
  the same assurance property.
- Never modify the pinned TES5Edit Reference Snapshot or commit proprietary game
  assets.

## Comments

### Approval record — 2026-09-13

The maintainer approved this replacement after reassessing CV1/performance-v1 as
disproportionately expensive for an AI-authored project. The BSA 0x69 CV1 and
performance-v1 work had been interrupted before completion; that incomplete
packet is historical context, not a prerequisite for this migration.

### Initial executable slice — 2026-09-13

Added the compact plan validator and expander, an `automatedAssurance` Gradle
shadow task, a frozen-history verifier, and a deterministic legacy comparison.
The current plan expands 10 executable rows for previously qualified TES3,
BSA 0x67, and BSA 0x68 capabilities plus seven compact performance lanes.

BSA 0x69 has six generated candidate rows covering decode, embedded names,
LZ4-frame round trip, malformed-input safety, the pinned-oracle differential,
and the focused performance checkpoint. It also has one incomplete,
non-release LZ4 performance lane bound to the existing measurement and condition
records. These rows remain `incomplete` and cannot enter executable or release
results.

The first shadow comparison scoped 155 legacy cases, conservatively mapped 87,
and left 68 explicit gaps. It therefore records `equivalent: false`; Assurance
v2 has not replaced the normative 0.16.0 gate. The historical gate and its
rebaseline audit remain active while the unmapped behavior is consolidated or
explicitly retired under this ticket.

### BSA 0x69 family qualification — 2026-09-14

BSA 0x69 is now the first completed family-level migration. Its 16 executable
scenarios account for all 32 historical rows: 31 map to evidence-backed compact
archetypes and one inapplicable archive-trailing-bytes row is explicitly
retired. No BSA 0x69 row remains unmapped, and ticket #44 is closed with fresh
focused oracle and performance execution. The global migration remains open:
the current comparison still reports 59 unmapped cases across the other scoped
families and therefore does not claim whole-plan equivalence.

### Historical-note clarification — 2026-09-14

The repeated BSA 0x69 family-qualification note immediately above records the
pre-cutover state and does not reopen this ticket. The later authoritative
cutover accounts for the remaining 59 rows and governs the final state.

### Assurance v2 authoritative cutover — 2026-09-14

Assurance v2 is now the specification `0.17.0` authority. The compact plan
expands 39 qualified Assurance Scenarios with content-addressed Semantic
Expectations and 24 curated performance lanes. Hosted, local, and release
environment applicability keeps local-oracle and focused checkpoint work
explicit without treating unavailable prerequisites as a hosted pass.

The digest-pinned comparison accounts for all 155 historical cases in the four
qualified Archive Families: 149 map to executable behavioral archetypes, five
structurally inapplicable TES3 decode/codec products and the inapplicable BSA
0x69 archive-trailing-bytes case are retired, and none are
unmapped. The generated report records every consolidation and refuses a
different legacy catalog digest.

The authoritative Gradle gate validates impact selection, the plan, comparison,
history index, result/capsule integrity, normative registry, and owning library,
CLI, and Archive Family tests in one task graph. Pull requests select affected
families and fail closed to `full`; mainline and complete verification run
`full`. Evidence Capsules accept results only when real JUnit reports contain
each selected method and bind common candidate, toolchain, JVM, profile, corpus,
provider, protocol, and generator identities once per session.
The accepted hosted shadow capsule is retained at
`tests/assurance/shadow-capsule.json` with digest
`sha256:a7ba8756069d8910fc4143c37ff6109f6cd6c5faaf9c1fa029e36226c12c3f36`.

The 19 `JBSA-CONF-*` and 22 `JBSA-PERF-*` requirements and their expanded
catalogs are frozen historical provenance under a compact digest index.
Assurance requirements `JBSA-ASR-001` through `008` and the updated release
gates are normative. Release-wide Binary Conformance is no longer mandatory;
any Binary Conformance claim remains exact and case-scoped. A reversible Git
restoration instruction and stable hashes identify all frozen material.

Ticket #61 is complete. Follow-on Archive Family, Automated Conformance,
performance-baseline, approval, and publication tickets now consume Assurance
v2.

### BSA 0x69 family qualification — 2026-09-14

BSA 0x69 is now the first completed family-level migration. Its 16 executable
scenarios account for all 32 historical rows: 31 map to evidence-backed compact
archetypes and one inapplicable archive-trailing-bytes row is explicitly
retired. No BSA 0x69 row remains unmapped, and ticket #44 is closed with fresh
focused oracle and performance execution. The global migration remains open:
the current comparison still reports 59 unmapped cases across the other scoped
families and therefore does not claim whole-plan equivalence.

### Final-state clarification — 2026-09-14

The immediately preceding duplicate family note is retained as historical
conversation and does not describe the final state. The authoritative cutover
above maps those remaining 59 cases, records whole-plan equivalence, and closes
this ticket.
