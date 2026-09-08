# Issue 39: proposed General BA2 CV1 evidence

## Approved activation

The maintainer explicitly approved activation on 2026-09-08. The active catalog
now contains all 33 reviewed successor cases. [Activation evidence](activation.json)
binds the approval, catalog, and 33 schema-valid rebaseline records under
`tests/conformance/rebaselines`. The rebaseline audit verified 45 golden-to-case
bindings because decode and encode cases can share a fixture's goldens.

The ordinary conformance runner passed all 33 active BA2 cases; the
[activation result](activation-result.json) records the case and artifact
identities. Other families retain their separate missing prerequisites.

Activation corrected golden labels to the schema's lowercase hyphenated form
and rebound the provenance manifest and descriptor digests. Approved case IDs,
fixture bytes, and expected golden bytes are unchanged. The original proposal,
pending records, and review results below remain historical evidence rather
than being rewritten to imply approval existed at review time.

## Original proposal record

**Untrusted pending explicit maintainer approval.** The active conformance catalog
and its accepted descriptor objects remain unchanged. `review.json` binds the
proposed catalog, independent fixture manifest and all 33 exact supersessions.
`pending-records.json` contains draft rebaseline records with `approval: null`.
A zero old digest means no accepted golden existed for the new case identity.

The final post-build review run compared **33 cases: 33 PASS, zero failures or
invalid results**. [The compact result](review-result.json) binds the final
candidate artifacts, codec profile, adapter registration, Java runtime and every
assertion result. Full raw observations are at
`target/ba2-cv1-review-final/review-report.json`. These are passing comparisons
against the proposed expectations; maintainer approval remains absent.

The proposal supplies 27 project-authored fixture recipes: six base codec
variants, nineteen malformed/safety scenarios, and name-table/zero-byte-entry
scenarios. The generator writes General BA2 bytes directly from the written
specification; a separate scanner authors expected metadata and diagnostics.
No golden is copied from current JBSA output. Both the original absent-binding
case IDs and their replacement IDs remain explicit below.

Successful cases compare public wire names and normalized identities, entry
order, BA2 hashes and extension bytes, chunk counts and sizes, compression state,
decoded-byte digests and stable diagnostics. Rejected cases retain failure kind,
structured diagnostics, intrinsic disposition and validation extent when an
assessment exists. Rejected encode/extract operations also compare their complete
before/after working tree, covering escaped files and residual directories.

Stored, zlib and mixed encode cases run both oracle directions and independent
validation. The oracle input for mixed encode uses global zlib; the candidate
explicitly stores its second entry. Each exact archive has its own metadata
expectation, including stored sizes, because compressed representation can vary
by provider. Both directions must preserve the same exact source payload tree.
Committed oracle hex and digest-pinned receipts contain only project-authored
content; the executable is not redistributed.

The following historical proposal-generation commands apply to the pre-activation
checkout at `b3d2fa9`; do not regenerate the archived proposal in this activated
checkout. Run after a Java 25 packaged build, with `JAVA_HOME` pointing to the real JDK
directory rather than a junction (the evidence harness rejects indirections):

```powershell
python build/prepare-ba2-cv1-review.py
pwsh -NoProfile -File build/run-ba2-cv1-review.ps1 -OutputDirectory target/ba2-cv1-review-final
```

The output directory must be new. `review-report.json` and `registrations.json`
retain exact artifact, codec-profile, executable and adapter identities and raw
case observations. The committed `review-result.json`, when present, is the
compact final comparison record tied to those identities. A `PASS` is agreement
with an unapproved proposed expectation; it does not award Automated Conformance,
qualified Binary Conformance, PV1 performance qualification or game acceptance.

After explicit maintainer approval, activate exactly the reviewed new identities
and approval records through the immutable-rebaseline workflow in
[the harness guide](../../conformance-harness.md#immutable-inputs-and-rebaselines).
Regenerate runtime registrations whenever a bound adapter or executable changes,
then use the ordinary conformance runner. Unrelated unimplemented families still
prevent a whole-release conformance claim.

Approval is required by [JBSA-CONF-007](../../spec/conformance-v1.md#jbsa-conf-007):
“Golden creation or replacement **MUST** occur only through a separate,
deliberately selected rebaseline operation,” with “explicit maintainer approval.”

## Exact successor mapping

Every case ID has the form
`CV1-fo4-gnrl-v1.<operation>.<fixture>.<codec>.standard-v1`.
Each row replaces only the fixture component shown, leaving operation, codec and
configuration fixed. `review.json` retains the complete IDs byte-for-byte.

| Operation | Original fixture | Proposed fixture | Codec |
| --- | --- | --- | --- |
| decode | `base-fo4-gnrl-v1-lz4-frame` | `fo4-general-base-fo4-gnrl-v1-lz4-frame-v1` | lz4-frame |
| decode | `base-fo4-gnrl-v1-mixed` | `fo4-general-base-fo4-gnrl-v1-mixed-v1` | mixed |
| decode | `base-fo4-gnrl-v1-raw-deflate` | `fo4-general-base-fo4-gnrl-v1-raw-deflate-v1` | raw-deflate |
| decode | `base-fo4-gnrl-v1-raw-lz4` | `fo4-general-base-fo4-gnrl-v1-raw-lz4-v1` | raw-lz4 |
| decode | `base-fo4-gnrl-v1-stored` | `fo4-general-base-fo4-gnrl-v1-stored-v1` | stored |
| decode | `base-fo4-gnrl-v1-zlib` | `fo4-general-base-fo4-gnrl-v1-zlib-v1` | zlib |
| decode | `malformed-arithmetic-overflow` | `fo4-general-malformed-arithmetic-overflow-v1` | stored |
| decode | `malformed-decompression-mismatch` | `fo4-general-malformed-decompression-mismatch-v1` | stored |
| decode | `malformed-equal-name-identities` | `fo4-general-malformed-equal-name-identities-v1` | stored |
| decode | `malformed-exact-shared-spans` | `fo4-general-malformed-exact-shared-spans-v1` | stored |
| decode | `malformed-harmless-trailing-bytes` | `fo4-general-malformed-harmless-trailing-bytes-v1` | stored |
| decode | `malformed-ignorable-constants` | `fo4-general-malformed-ignorable-constants-v1` | stored |
| decode | `malformed-illegal-tuples` | `fo4-general-malformed-illegal-tuples-v1` | stored |
| decode | `malformed-impossible-counts` | `fo4-general-malformed-impossible-counts-v1` | stored |
| decode | `malformed-missing-name-tables` | `fo4-general-malformed-missing-name-tables-v1` | stored |
| decode | `malformed-out-of-range-spans` | `fo4-general-malformed-out-of-range-spans-v1` | stored |
| decode | `malformed-owning-dispositions` | `fo4-general-malformed-owning-dispositions-v1` | stored |
| decode | `malformed-partial-overlap` | `fo4-general-malformed-partial-overlap-v1` | stored |
| decode | `malformed-truncated-spans` | `fo4-general-malformed-truncated-spans-v1` | stored |
| decode | `malformed-undecodable-wire-names` | `fo4-general-malformed-undecodable-wire-names-v1` | stored |
| decode | `malformed-unsafe-absolute-names` | `fo4-general-malformed-unsafe-absolute-names-v1` | stored |
| decode | `malformed-unsafe-traversal-names` | `fo4-general-malformed-unsafe-traversal-names-v1` | stored |
| decode | `malformed-usable-name-hash-mismatch` | `fo4-general-malformed-usable-name-hash-mismatch-v1` | stored |
| decode | `malformed-windows-invalid-names` | `fo4-general-malformed-windows-invalid-names-v1` | stored |
| encode | `base-fo4-gnrl-v1-lz4-frame` | `fo4-general-base-fo4-gnrl-v1-lz4-frame-v1` | lz4-frame |
| encode | `base-fo4-gnrl-v1-mixed` | `fo4-general-base-fo4-gnrl-v1-mixed-v1` | mixed |
| encode | `base-fo4-gnrl-v1-raw-deflate` | `fo4-general-base-fo4-gnrl-v1-raw-deflate-v1` | raw-deflate |
| encode | `base-fo4-gnrl-v1-raw-lz4` | `fo4-general-base-fo4-gnrl-v1-raw-lz4-v1` | raw-lz4 |
| encode | `base-fo4-gnrl-v1-stored` | `fo4-general-base-fo4-gnrl-v1-stored-v1` | stored |
| encode | `base-fo4-gnrl-v1-zlib` | `fo4-general-base-fo4-gnrl-v1-zlib-v1` | zlib |
| extract | `malformed-unsafe-name-extraction` | `fo4-general-malformed-unsafe-name-extraction-v1` | stored |
| scenario | `gnrl-name-tables` | `fo4-general-gnrl-name-tables-v1` | stored |
| scenario | `input-zero-length` | `fo4-general-input-zero-length-v1` | stored |
