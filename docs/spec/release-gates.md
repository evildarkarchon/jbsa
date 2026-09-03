# Release gates

This specification owns the evidence state and dependency order that turn
implemented behavior into bounded release claims. It does not redefine behavior
owned by the format, library, CLI, compatibility, conformance, performance,
distribution, or compliance specifications. A linked owning requirement or
case decides whether its evidence passes; this file decides when that evidence
is sufficient to advance a candidate.

## JBSA-REL-001

Every gate evaluation **MUST** identify one immutable candidate by Git commit,
specification-set version, product version, compatibility-profile identity,
codec-profile identifier and digest, complete packaging-input identity, and the
detached release-candidate artifact-manifest and human-readable-checksum digests.
A gate **MUST** record `PASS` or `OPEN` plus its evaluator, time, procedure
version, inputs, and stable evidence locations. A failure, missing result, stale
result, invalid run, expected failure, percentage threshold, or waiver **MUST**
leave the gate `OPEN`; it **MUST NOT** be represented as a partial pass.

_Source decisions: [accepted evidence-gated sequence](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [first-release approval acceptance](https://github.com/evildarkarchon/jbsa/issues/59)._

## JBSA-REL-002

The Specification Gate **MUST** pass before production implementation begins.
It **MUST** verify that the complete normative specification set exists under
the authority of [the specification framework](README.md); every obligation has
one permanent owner and one valid `requirements.yaml` entry; source decisions,
lifecycle, verification class, implementing issue, and available evidence are
traceable; and every known contradiction, unknown, supersession, and deferred
input is explicit rather than silently resolved. The gate **MUST NOT** claim the
human approval required for specification version `1.0.0`.

_Source decisions: [accepted normative-specification gate](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [specification materialization acceptance](https://github.com/evildarkarchon/jbsa/issues/27)._

## JBSA-REL-003

The Contract Baseline **MUST** require the Specification Gate and a compilable
public library interface that represents the normative types, operations,
ownership, failures, and configuration. Its evidence **MUST** identify the
public-interface snapshot used by implementation and consumers. Passing this
milestone **MUST NOT** claim source or binary compatibility; breaking
conformance-driven corrections remain permitted until Interface Freeze.

_Source decision: [accepted Contract Baseline milestone](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-REL-004

The Interface Candidate Gate **MUST** remain open until the TES3, TES4 BSA,
Fallout 4 General BA2, and Fallout 4 DDS BA2 vertical slices each satisfy
[JBSA-SCOPE-005](scope.md#jbsa-scope-005) and their complete applicable automated
evidence through the same public interface. It **MUST** record the candidate
interface and all known corrective changes revealed by those representative
stored, compressed, General, DDS, BSA, and BA2 structures. Passing it **MUST NOT**
freeze the interface or imply coverage of remaining Archive Families.

_Source decision: [accepted Interface Candidate milestone and representative-slice order](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-REL-005

The Automated Conformance Gate **MUST** require every applicable mandatory
`conformance-v1` case to report an independent pass for the exact candidate in
both sequential and applicable parallel modes. Its evidence **MUST** include the
safe default, the complete immutable `bsarch-1.0/v1` profile, every Archive
Family and direction, CLI behavior, malformed and resource-limit behavior,
filesystem safety, cancellation, rollback, deterministic output, and all
required differential and independent-validator directions defined by the
[conformance-v1 specification](conformance-v1.md). Only this gate **MAY** carry
the hosted-CI Automated Conformance claim governed by
[JBSA-SCOPE-007](scope.md#jbsa-scope-007).

_Source decisions: [accepted complete automated-conformance gate](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [mandatory matrix acceptance](https://github.com/evildarkarchon/jbsa/issues/50)._

## JBSA-REL-006

The Interface Freeze Gate **MUST** require the Automated Conformance Gate plus
completed coverage of every Archive Family, bounded sequential and parallel
scheduling, the complete CLI, and the final standard codec capability through
the public interface. It **MUST** include an audit of every exported package,
type, invariant, ordering rule, failure, configuration, ownership contract, and
performance characteristic; source and binary compatibility baselines; compiled
CLI-like and embedded consumers; and proof that third-party and internal
storage, provider, scheduler, transaction, buffer, pool, native, and dispatch
details remain absent from the interface.

_Source decisions: [accepted Interface Freeze milestone](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [public-interface freeze acceptance](https://github.com/evildarkarchon/jbsa/issues/51)._

## JBSA-REL-007

After Interface Freeze, a breaking public-interface change **MUST** remain
blocked until an explicit decision supplies a compatibility assessment and a
new specification revision. Applying the change **MUST** reset the interface
baseline and every affected implementation, consumer, conformance,
performance, packaging, documentation, qualification, approval, and
publication gate; retaining an earlier `PASS` against the changed interface is
prohibited.

_Source decisions: [accepted post-freeze change policy](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [public-interface freeze acceptance](https://github.com/evildarkarchon/jbsa/issues/51)._

## JBSA-REL-008

The Final Profile Gate **MUST** follow Interface Freeze and select or explicitly
defer every optional provider using the conformance, deterministic-output,
performance, memory, native-loading, packaging, and notice evidence required by
[JBSA-CODEC-007](codecs.md#jbsa-codec-007) and
[JBSA-CODEC-013](codecs.md#jbsa-codec-013). It **MUST** freeze the exact
compatibility profile, codec-profile manifest, provider versions, parameters,
dispatch and fallback rules, native configuration, runtime inputs, and
requalification identity consumed by final packaging. Deferral **MUST** retain
the already-qualified baseline profile without silently mutating it.

_Source decisions: [accepted provider qualification sequence](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [optional-provider acceptance](https://github.com/evildarkarchon/jbsa/issues/52)._

## JBSA-REL-009

The Packaging Gate **MUST** follow the Final Profile Gate and pass every active
requirement in [Distribution](distribution.md), together with the applicable
[Modules and build](modules-and-build.md), CLI, codec, and compliance inputs, for
one exact release-candidate artifact set. It **MUST** establish the final
application image, ZIP, library artifacts, runtime and native inventory,
process behavior, clean-machine result, checksums, and detached final digests
for the artifact manifest and human-readable checksum file that every downstream
qualification consumes.

_Source decisions: [accepted final packaging sequence](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [application-image acceptance](https://github.com/evildarkarchon/jbsa/issues/53)._

## JBSA-REL-010

The Documentation and Provenance Gate **MUST** consume the frozen interface,
profiles, and packaged artifact identities. It **MUST** verify operator and
library documentation, public examples, limitations, safe operation,
native-access and resource behavior, cancellation and residual cleanup,
compatibility deviations, codec-profile manifest, dependency and native
notices, fixture provenance, SBOM and provenance procedures, checksums, and
evidence locations against permanent requirement identifiers and current
evidence. No claim **MAY** overstate Automated, Decode, Encode, Binary, or
Release Qualification.

_Source decisions: [accepted release documentation gate](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [documentation and provenance acceptance](https://github.com/evildarkarchon/jbsa/issues/54)._

## JBSA-REL-011

The Performance Gate **MUST** require the Automated Conformance and Packaging
Gates and run the complete [performance-v1 specification](performance-v1.md)
against the final candidate, Conformance Oracle, Benchmark Corpus, JVM,
runtime, codec profile, protocol, and qualification machine identities. Every
applicable throughput, random-access latency, peak-memory, parallel-scaling,
output-size, and regression outcome **MUST** pass independently; an invalid or
noisy run **MUST** be rerun after correction and **MUST NOT** be averaged into a
pass. The first public release **MUST** publish the resulting immutable first
Performance Baseline with its complete identity and raw evidence.

_Source decisions: [accepted full-performance gate and first baseline](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [first-release performance acceptance](https://github.com/evildarkarchon/jbsa/issues/55)._

## JBSA-REL-012

The Binary Conformance Confirmation Gate **MUST** follow the Automated
Conformance and Packaging Gates and evaluate only cases designated by the
[conformance-v1 specification](conformance-v1.md). It **MUST** bind every
published case-level result to the final ordered-file configuration, fixtures,
codec profile, toolchain, candidate digests, five fresh identical Conformance
Oracle runs, required cross-decoding, and both the primary qualification machine
and a second Windows x64 CPU. A case that does not earn repeatable byte identity
**MUST** be omitted from the Binary Conformance claim without weakening its
semantic conformance requirements.

_Source decisions: [accepted case-scoped Binary Conformance gate](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [second-CPU qualification acceptance](https://github.com/evildarkarchon/jbsa/issues/56)._

## JBSA-REL-013

The Compliance Gate **MUST** follow Packaging and audit the exact final bytes
under every active requirement in [Compliance](compliance.md), including
[JBSA-LIC-009](compliance.md#jbsa-lic-009) and
[JBSA-LIC-011](compliance.md#jbsa-lic-011). Its evidence **MUST** reconcile each
library, POM, source and Javadoc JAR, application-image file, ZIP entry, native
library, license, notice, SBOM item, provenance record, fixture, and checksum to
an approved inventory and authorization. Any unapproved adaptation,
proprietary or local corpus material, opaque binary, credential, build-machine
residue, or unresolved counsel-dependent question **MUST** leave the gate open.

_Source decisions: [accepted release-byte audit gate](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [exact-byte compliance acceptance](https://github.com/evildarkarchon/jbsa/issues/57)._

## JBSA-REL-014

The writable-family Release Qualification Gate **MUST** use the exact packaged
candidate and frozen codec/profile identity to obtain current human-observed
Windows evidence from the relevant game or official tool for TES3, every
writable BSA version, Fallout 4 BA2 v1 General and DDS, and Starfield BA2 v2/v3
General and DDS as applicable. Each result **MUST** record the tool/game and
environment, archive configuration, fixture and output hashes, commands,
observed acceptance, diagnostics, and operator sign-off while keeping
proprietary inputs and resulting game assets outside version control. A missing,
stale, ambiguous, or failed result **MUST** block the complete Encode Conformance
claim and public release as required by
[JBSA-SCOPE-006](scope.md#jbsa-scope-006).

_Source decisions: [accepted manual Release Qualification gate](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [writable-family qualification acceptance](https://github.com/evildarkarchon/jbsa/issues/58)._

## JBSA-REL-015

Release Candidate status **MUST** require passing Packaging, Documentation and
Provenance, Performance, and Compliance against the same candidate identity.
The status **MUST** record the precise Automated, Decode, Encode, and case-level
Binary Conformance claims then supported, but **MUST NOT** authorize a public
release or imply that pending human qualification or approval has passed.

_Source decision: [accepted Release Candidate milestone](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241)._

## JBSA-REL-016

The JBSA 1.0 Approval Gate **MUST** require Release Candidate status, the Binary
Conformance Confirmation Gate, the writable-family Release Qualification Gate,
and complete evidence for every active requirement registry row. The human
approver **MUST** review and explicitly accept the exact specification `1.0.0`,
public-interface baseline, immutable compatibility and codec profiles,
deviations, limitations, documentation, artifact digests, conformance claim set,
independently passing performance metrics and baseline, packaging, compliance,
and manual qualification. The recorded result **MUST** either approve one exact
candidate or name the gates returned to `OPEN` and required remediation.

_Source decisions: [accepted human approval gate](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [JBSA 1.0 approval acceptance](https://github.com/evildarkarchon/jbsa/issues/59)._

## JBSA-REL-017

The Publication Gate **MUST** require the JBSA 1.0 Approval Gate and create the
version tag and GitHub Release from the exact approved commit, artifact manifest,
and digests. Attached assets and release notes **MUST** be limited to the
approved anonymous-release set owned by
[JBSA-BUILD-009](modules-and-build.md#jbsa-build-009), the precise qualified
claim set, profiles, requirements, evidence, limitations, and support or upgrade
information. The gate **MUST NOT** publish to Maven Central or GitHub Packages or
attach a local oracle, proprietary corpus, unapproved binary, superseded
artifact, or asset rebuilt after approval.

_Source decisions: [accepted publication terminus](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [first-public-release acceptance](https://github.com/evildarkarchon/jbsa/issues/60)._

## JBSA-REL-018

Publication verification **MUST** download every asset anonymously from the
created GitHub Release, verify its name and size, verify every inventoried asset
against both inventories, and verify the artifact manifest and human-readable
checksum file against their detached Packaging Gate digests. It **MUST** recheck
release-note and evidence links and repeat the clean-machine launch smoke on the
downloaded ZIP. A mismatch or unavailable asset **MUST** leave publication
incomplete and **MUST** return approval and every affected gate to `OPEN`;
replacing an approved asset in place **MUST NOT** be used as remediation.

_Source decision: [post-publication verification acceptance](https://github.com/evildarkarchon/jbsa/issues/60)._

## JBSA-REL-019

Every public qualification statement **MUST** name its exact scope and evidence:
Automated Conformance, Decode Conformance, Encode Conformance, case-level Binary
Conformance, Performance Qualification, and writable-family Release
Qualification **MUST** remain distinct. Passing one **MUST NOT** imply another,
and a result **MUST NOT** be generalized beyond its recorded Archive Family,
direction, operation, profile, codec, worker mode, fixture, platform, toolchain,
machine, or artifact identity.

_Source decisions: [accepted separate visible qualification gates](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [Binary claim-scope acceptance](https://github.com/evildarkarchon/jbsa/issues/56), [approval claim-scope acceptance](https://github.com/evildarkarchon/jbsa/issues/59), [publication claim-scope acceptance](https://github.com/evildarkarchon/jbsa/issues/60)._

## JBSA-REL-020

Any normative requirement, implementation, profile, dependency, toolchain,
runtime-module set, qualification procedure, evidence input, or release byte
change **MUST** identify and reset every gate whose candidate identity,
procedure, assertion, or output it affects. Every rebuilt artifact **MUST**
receive fresh Packaging and Compliance evidence; every behaviorally affected
artifact **MUST** receive fresh applicable conformance, performance, packaged
behavior, Binary Conformance, documentation, and manual qualification evidence.
Approval and Publication **MUST** always reset when an approved candidate commit,
specification version, artifact manifest, or digest changes.

_Source decisions: [accepted affected-gate reset policy](https://github.com/evildarkarchon/jbsa/issues/17#issuecomment-5521832241), [application-image invalidation acceptance](https://github.com/evildarkarchon/jbsa/issues/53), [fresh-audit acceptance](https://github.com/evildarkarchon/jbsa/issues/57), [approval remediation acceptance](https://github.com/evildarkarchon/jbsa/issues/59)._

## Gate dependency summary

This table is informative; the owning requirements above define the gates.
Independent rows after Packaging may run in parallel, but all must bind to the
same immutable candidate.

| Stage | Gate or milestone | Prerequisites |
| --- | --- | --- |
| 1 | Specification Gate | Accepted decisions |
| 2 | Contract Baseline | Specification Gate |
| 3 | Interface Candidate | Contract Baseline and representative slice evidence |
| 4 | Automated Conformance | Complete implementation and mandatory `conformance-v1` matrix |
| 5 | Interface Freeze | Automated Conformance and complete consumers/families/scheduling |
| 6 | Final Profile | Interface Freeze and provider qualification or deferral |
| 7 | Packaging | Final Profile |
| 8a | Documentation and Provenance | Packaging and frozen interface/profiles |
| 8b | Performance | Packaging and Automated Conformance |
| 8c | Binary Conformance Confirmation | Packaging and Automated Conformance |
| 8d | Compliance | Packaging |
| 8e | Writable-family Release Qualification | Packaging |
| 9 | Release Candidate | Packaging, Documentation and Provenance, Performance, Compliance |
| 10 | JBSA 1.0 Approval | Release Candidate, Binary confirmation, writable-family qualification |
| 11 | Publication | JBSA 1.0 Approval |
