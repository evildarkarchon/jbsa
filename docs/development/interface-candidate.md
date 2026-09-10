# Interface Candidate audit

Issue [41](../../.scratch/jbsa-1-0/issues/41-establish-the-interface-candidate-after-representative-archive-families.md)
audits the public interface after TES3, TES4 / Oblivion BSA, Fallout 4 General
BA2, and Fallout 4 PC DDS BA2. This is an interface milestone, not Interface
Freeze, Automated Conformance across all families, or permission to release 1.0.

## Scope and decision

The maintainer explicitly deferred Xbox DDS functionality and qualification
until after 1.0 on 2026-09-10. `JBSA-SCOPE-009` owns that applicability change.
PC rejection of Xbox input stays in scope. `DdsTarget.XBOX` remains a reserved
future value; retaining its name does not qualify its implementation. Historical
Xbox cases and observations remain available for the later build.

Specification 0.13.0 includes this scope decision and the interface clarifications
below. Its changed digest requires a deliberate conformance rebaseline; old
approvals cannot certify new specification-bound golden identities. See the
[rebaseline packet](../reviews/issue41-interface/conformance-rebinding.md).

## Requirement trace and audit

The audit used the permanent registry before implementation. The sole supported
package remains `io.github.evildarkarchon.jbsa` in the module of the same name.
There is one stateless `BethesdaArchives` facade and no new production adapter.

| Requirements | Review and compatibility evidence |
| --- | --- |
| LIB-001, LIB-002, BUILD-003–006 | Public packages, names and immutable value signatures remain shared by all four families. `ModuleArchitectureIT` checks exports, classpath usability, generic signatures, annotations and third-party/internal type leakage. Its additional JDK allow-list rejects executor, pool, positional storage, filesystem, native memory and console types. |
| LIB-003–005; IO-001–005 | `OpenArchive` owns its entry capabilities and children; metadata and completed assessments are detached. `PublicModuleConsumerIT` checks normal EOF, parent-close child invalidation and metadata after close. Existing owned-I/O tests cover concurrent close/interruption. Semantic quantities remain `long`; the inherited NIO transfer count remains `int`. |
| LIB-006, LIB-012 | Family and wire selectors remain independent. Sealed archive/entry metadata describes TES3, Versioned BSA, General and DDS facts. `PublicMetadataContractIT` checks typed metadata, copied wire bytes and separate optional normalized identity. DDS internals do not cross the interface. |
| LIB-007–009; IO-006–009 | Immutable requests preserve caller policy, ordered sources, generated-channel ownership, semantic limits, DDS target, flags and compression choices. `PublicRequestContractIT` covers request construction, defaults, copy isolation and required/inapplicable targets. Family tests retain overlay, splitting, sharing, limits and publication preflight coverage. |
| LIB-010; OPS-001–010 | `OperationControl` contains only observer and cancellation capabilities; worker selection remains semantic. `PublicOutcomeContractIT` covers structured outcomes and immutable diagnostics. The compiled consumers exercise progress and pre-cancellation with no publication. Existing operation tests retain observer-failure and deterministic failure-order checks. |
| OPS-011; IO-010–015 | Mutations return `OperationReport` or checked `ArchiveException`, with artifact states, absolute paths, and packed-part metadata. Compiled consumers inspect successful reports and cancelled destinations. Publication tests retain rollback/residual-artifact coverage. No output transaction or staging configuration is exposed, and atomic publication does not promise crash durability. |
| LIB-011; REL-004, REL-020 | The only caller-owned payload buffer is the NIO read destination. Generated inputs transfer fresh channels to JBSA. Compiled embedded and CLI-like clients require only the library JAR. Corrections after a passing candidate must identify evidence, change the specification/registry and compatibility tests, and reset the affected gate before reevaluation. |

All IDs in this table have the `JBSA-` prefix. The I/O and operation rows cite
existing execution evidence as well as the new consumer checks; this audit does
not replace their independent conformance and qualification requirements.

## Findings and disposition

1. The named-module consumer test executed TES3 twice. It now executes distinct
   embedded and CLI-like workflows across all four representative families,
   including compressed BSA and General BA2 and canonical PC DDS reconstruction.
2. The signature checker accepted every `java.*` type. The public JDK type
   allow-list now rejects internal implementation mechanisms. A negative test
   failed before the guard and passed after it. Existing annotation leakage
   regression tests continue to exercise the complete signature traversal.
3. LIB-005's blanket count wording conflicted with required NIO `read` returning
   `int`; LIB-011's buffer wording was similarly broader than the required
   caller-owned destination. Both now explicitly preserve this channel contract.
4. LIB-002's immutable-value wording needed to distinguish copied semantic
   collections from callback/factory capabilities and retained `Throwable`
   identity. The specification now matches those existing public contracts.
5. Xbox qualification was previously deferred informally. SCOPE-009 now makes
   its post-1.0 applicability explicit while preserving PC negative coverage.

No accidental public provider, storage, executor, buffer pool, transaction,
native or third-party seam was found in production. No production signature
was removed or renamed. Existing constructor compatibility remains covered by
the public request/metadata/outcome tests.

The [compiled public signature snapshot](interface-candidate-api.txt) records
the exact types and members reviewed here (`javap -public` over the exported
package, excluding package-private anchors). Its SHA-256 is
`5cf3b7d39f0aa1ec6c4424b2132aa52d126ae9b96621696ab8ee42c7f03a4442`
(UTF-8 without BOM, LF line endings).

## Deletion and depth checks

`PublicModuleConsumerIT` compiles each named consumer with only the library JAR
on its module path and runs with that JAR plus the consumer classes. The CLI,
test-support and benchmark implementations are absent. The embedded client
generates channel sources, packs, reads and repacks; the CLI-like client detects,
inspects, extracts and observes cancellation. Both use the same facade and
request/result vocabulary for the four structures. This is an executable
dependency-deletion test without modifying the source checkout.

The existing module architecture checks additionally require no CLI exports,
no exported internals, and no third-party types in the public signatures.
Internal parser/provider/storage adapters remain replaceable behind that one
library boundary. These tests establish module depth, not a second production
storage adapter.

## Gate record

Status: **OPEN** pending final immutable-candidate execution.
Evaluator: Codex, 2026-09-10. Procedure: issue41-interface-audit-v1.
Product version: `0.1.0-SNAPSHOT`. Specification: `0.13.0`.
Starting commit: `86d6b797d0eb99aabbff440f8b5f9e036782b692`.

The maintainer approved the exact 109-object rebaseline packet during this
implementation. [Activation evidence](evidence/issue41-activation.json) records
the review digest, approval records and successful rebaseline validation. The
final immutable implementation commit and fresh test/evidence results must be
recorded before changing this record to PASS. The current Contract Baseline
remains the last declared interface milestone until then.

The focused architecture run passed all 16 checks. The compiled consumer check
passed all four families in both consumer modes. The JDK-mechanism rejection
test first failed because the old boundary accepted an executor, then passed
with the allow-list. These results establish the interface safeguards only.
All seven TES3 slice checks, including the pinned local oracle differential and
independent validator, also passed with no skipped cases. The complete CV1
matrix remains a later gate; this milestone reevaluates the 32 BSA 067, 33
General BA2 and 44 PC DDS admitted cases alongside the accepted TES3 slice.

Run the focused contracts and architecture checks with:

```powershell
.\mvnw.cmd -B -ntp -C '-Dgroups=contract,architecture' verify
```

Run `mvnw.cmd -B -ntp -C clean verify` for the full final build after admitted
catalog bindings match the new specification. An expected digest rejection is
an open evidence gate, not a passing conformance result.
