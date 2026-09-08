# General BA2 development coverage and CV1 boundaries

The maintainer approved the [33-case activation](../reviews/issue39-cv1/activation.json)
on 2026-09-08. The mapping below preserves the original development case names;
the [successor mapping](../reviews/issue39-cv1/README.md#exact-successor-mapping)
identifies the cases now in the active catalog.

The immutable conformance catalog has 33 `fo4-gnrl-v1` cases. This document maps
their scenarios to issue #39 development evidence. It does not fill missing
catalog fixture bindings, approve goldens, or award formal CV1 qualification.
The new corpus lives in `tests/fixtures/fo4-general`; every hexadecimal artifact
is reproducible from a digest-identified independent Python generator.
The separate [33-case successor proposal](../reviews/issue39-cv1/README.md)
materializes reviewable fixtures, expectations and executable registrations for
these scenarios while preserving the active catalog until maintainer approval.

All case identifiers below start with `CV1-fo4-gnrl-v1.` and end with
`.standard-v1`. Brace alternatives denote separate catalog cases.

| Catalog case middle | Development observation |
| --- | --- |
| `{decode,encode}.base-fo4-gnrl-v1-stored.stored` | `Ba2ConformanceIT.readsAndExtractsIndependentWireVectors`, `independentlyValidatesPackedArchives`; optional `pinnedLocalOracleCrossDecodesBothDirections` |
| `{decode,encode}.base-fo4-gnrl-v1-zlib.zlib` | Same three tests; complete zlib input consumption and exact source bytes, with no compressed byte-identity claim |
| `{decode,encode}.base-fo4-gnrl-v1-mixed.mixed` | Independent mixed vector plus explicit per-entry stored override in `independentlyValidatesPackedArchives`; optional oracle differential currently uses uniform stored/zlib modes |
| `decode.base-fo4-gnrl-v1-{raw-deflate,raw-lz4,lz4-frame}.{raw-deflate,raw-lz4,lz4-frame}` | Three corresponding independent wrong-codec vectors in `rejectsIndependentMalformedPayloads`; codec names pair positionally |
| `encode.base-fo4-gnrl-v1-{raw-deflate,raw-lz4,lz4-frame}.{raw-deflate,raw-lz4,lz4-frame}` | Public encoding enum admits no raw-DEFLATE choice; family codec selection rejects LZ4 requests before publication. These negative catalog observations still need their formal adapter binding |
| `decode.malformed-decompression-mismatch.stored` | Independent decoded-size mutation in `rejectsIndependentMalformedPayloads`; reader terminal validation tests |
| `decode.malformed-{arithmetic-overflow,impossible-counts,out-of-range-spans,truncated-spans}.stored` | `Ba2ReaderTest.rejectsInvalidStructuralSpans`; independent truncated-payload span in `rejectsIndependentMalformedPayloads` |
| `decode.malformed-equal-name-identities.stored` | `Ba2ReaderTest.validatesSharedSpansAndDuplicateNames` |
| `decode.malformed-exact-shared-spans.stored` | Same reader test; `Ba2PackTest.sharesEarliestCompressedRepresentationAndRoundTripsAnExpandingPayload` |
| `decode.malformed-harmless-trailing-bytes.stored` | Independent trailing-data vector and `distinguishesToleratedConstantsAndRejectedRecordShapes` |
| `decode.malformed-ignorable-constants.stored` | Independent sentinel mutation and `Ba2ReaderTest.diagnosesNoncanonicalRecordFieldsAndTrailingBytes` |
| `decode.malformed-illegal-tuples.stored` | Independent invalid chunk-count vector and `distinguishesToleratedConstantsAndRejectedRecordShapes` |
| `decode.malformed-missing-name-tables.stored` | Independent absent-table vector, archive-scoped diagnostic count and absent wire-name/identity assertions; `Ba2ReaderTest.preservesMissingNameTableIdentity` |
| `decode.malformed-owning-dispositions.stored` | Explicit `CONFORMING`, `TOLERATED_NONCANONICAL` and structure/payload `FORMAT` assertions across the conformance tests |
| `decode.malformed-partial-overlap.stored` | Independent overlapping record mutation in `distinguishesToleratedConstantsAndRejectedRecordShapes` |
| `decode.malformed-undecodable-wire-names.stored` | `Ba2ReaderTest.retainsUndecodableAndNonAsciiNames` |
| `decode.malformed-{unsafe-absolute-names,unsafe-traversal-names,windows-invalid-names}.stored` | `Ba2ReaderTest.refusesUnsafeNamesBeforeDestinationEffects`; formal named malformed-fixture observations remain unbound |
| `decode.malformed-usable-name-hash-mismatch.stored` | Independent hash mutation and stable field diagnostic in `distinguishesToleratedConstantsAndRejectedRecordShapes` |
| `extract.malformed-unsafe-name-extraction.stored` | `Ba2ReaderTest.refusesUnsafeNamesBeforeDestinationEffects`; destination safety is separate from intrinsic archive disposition |
| `scenario.gnrl-name-tables.stored` | Independent name tables, absent-table identity, record-order and case-preservation tests |
| `scenario.input-zero-length.stored` | `Ba2ReaderTest.opensZeroLengthStoredEntry`; empty archive encoding remains rejected and empty-archive decode disposition is fixture-dependent |

`Ba2PerformanceCheckpointIT.recordsCurrentMachineCheckpoint` additionally measures
stored, zlib, mixed per-entry compression and shared/split output over a seeded
18 MiB corpus containing both compressible and incompressible content and one
duplicate. Each scenario has a discarded warmup and three recorded observations.
Every extracted byte is checked outside the timed region. CSV rows retain total
archive size and part count, pack/extract/inspect durations, 64 entry-prefix reads,
summed heap-pool peaks and the configured scratch ceiling. The scratch ceiling is
not an observed usage peak; summed pool peaks are not simultaneous process memory.

The local Conformance Oracle adapter records its pinned executable identity and
raw observations in both directions. The independent validator records its Python
executable and scanner digests, source archive digest and semantic projection.
Neither local semantic cross-decode nor synthetic stored-byte agreement satisfies
the five-run, second-CPU Binary Conformance protocol or manual game acceptance.
