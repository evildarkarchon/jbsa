# Issue 40: PC DDS qualification review

## Approved activation

The maintainer explicitly approved this 44-case PC packet on 2026-09-08.
[Activation evidence](activation.json) binds that approval, the active catalog,
and all 44 approved rebaseline records. The rebaseline audit verified 56
golden-to-case bindings, including the goldens shared by decode/encode fixtures.
The [ordinary runner result](activation-result.json) records the 44 active PC
cases and their exact artifact identities. Xbox remains deferred, and unrelated
conformance and PV1 prerequisites remain separate gates.

Activation normalized only provenance golden labels to the lowercase hyphenated
labels required by the approval-record schema. It uses a separate
`activation-manifest.json` and rebound descriptor objects; all approved case
identities, fixture bytes, and golden bytes are unchanged. The original review
packet below remains unchanged as historical evidence.

## Original proposal record

**Pending explicit maintainer approval.** This packet proposes 44 materialized
PC DDS CV1 successor cases. It does not activate their goldens, modify the active
catalog, or claim complete release qualification.

The final [review result](review-result.json) records 44 passing cases. It binds
the rebuilt library and CLI, physical JDK executable, codec profile, registrations,
independent validators, raw observations, and exact proposed catalog digest.
[review.json](review.json) contains every old-to-new case mapping and the draft
fixture/golden manifest; [pending-records.json](pending-records.json) contains
the proposed rebaseline records with `approval: null`.

| Operation | Cases |
| --- | ---: |
| Decode, including malformed and noncanonical input | 24 |
| Encode and unsupported-input rejection | 8 |
| Unsafe-name extraction rejection | 1 |
| PC formats, geometry, partition, reconstruction, and CLI scenarios | 11 |

## Scope selected by the maintainer

On 2026-09-08 the maintainer deferred Xbox compatibility and identified its
separate DDS tooling. The original cases for positive Xbox encoding, Xbox-target
input validation, and combined PC/Xbox reconstruction selection remain unchanged
and outstanding in the proposed catalog. They are listed in `review.json` under
`deferred_cases` and are excluded from this 44-case review. PC rejection of an
Xbox DDS input remains covered. A Windows `texdiag` rejection is not treated as
proof of Xbox incompatibility or as an approved exception to the Xbox contract.

## Independent authority and evidence

The fixture builder constructs BA2 records from the written specification and
project-authored opaque DDS bytes. The expectation scanner independently parses
the records, derives canonical headers, and hashes complete reconstructed DDS
files and individual mip chunks. No golden was copied from JBSA output.

Input fixtures deliberately include level-6 zlib streams. Expected canonical
repacking uses the specified level 9, independently recompressed by Python zlib.
The odd multichunk case distinguishes those sizes while retaining identical
opaque content. This corrected an expectation error without changing the input
or reducing its coverage.

Positive encode cases execute both BSArch directions. Oracle-generated archives
and receipts contain only synthetic DDS content. The independent scanner
corroborates positive output, and all positive PC scenarios carry DirectXTex
corroboration. The validator retains each invocation, executable digest, DDS
input digest, exit status, raw streams, and normalized result in the harness
evidence. The DirectXTex executable is not redistributed.

Qualification also found and fixed a real archive-source defect:
`PackSources.metadataExtent()` omitted variable DDS BA2 records. The public
`repacksDdsArchiveThroughDetectedSource` regression failed before the fix and
passes with explicit DDS metadata accounting.

## Reproduce before activation

These historical proposal-generation commands apply to the pre-activation
checkout at `47d803f`. Do not regenerate this archived proposal in the activated
checkout. Use the ordinary conformance runner with the approved active catalog.

Use the physical JDK directory rather than the `C:/Program Files/jdk` junction;
CV1 deliberately rejects indirections in executable bindings. After packaging
the reactor and compiling the observation adapter:

```powershell
python build/prepare-dds-cv1-review.py
$env:JAVA_HOME = 'D:\Programs\store\OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1'
pwsh -NoProfile -File build/run-dds-cv1-review.ps1 `
  -OutputDirectory target/dds-pc-cv1-review-new
python build/summarize-dds-cv1-review.py `
  --report target/dds-pc-cv1-review-new/review-report.json `
  --output docs/reviews/issue40-cv1/review-result.json
```

The output directory must be new. After approval and activation, archive this
proposal-generation workflow rather than regenerating successor identities from
an already activated catalog. Activation must use exactly the reviewed case
identities, hashes, and schema-valid approval records, then run the ordinary
conformance runner.

[JBSA-CONF-007](../../spec/conformance-v1.md#jbsa-conf-007) requires golden
creation or replacement through a “separate, deliberately selected rebaseline
operation” with “explicit maintainer approval.” Passing comparisons do not grant
that approval.

## Performance qualification remains separate

The PV1 preparation work binds the actual immutable codec-profile bytes, verifies
the required corpora and JDK, and supplies a public DDS JMH adapter and an
independent streaming validator. No formal performance measurements are claimed.
The [performance preparation notes](../../development/dds.md) retain the missing
global CV1 prerequisites, shipping launcher/worker bindings, and environment
attestation requirements. Xbox deferral does not silently waive any of those
PC performance gates.
