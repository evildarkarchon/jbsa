# Shared operation execution

Issue [#36](https://github.com/evildarkarchon/jbsa/issues/36) was traced before
implementation to `JBSA-OPS-001` through `JBSA-OPS-010` in
[`requirements.yaml`](../spec/requirements.yaml). Both native dependencies,
#34 and #35, were closed. `JBSA-OPS-011` remains owned by #35; the
`JBSA-SCHED-*` requirements remain owned by #48. This change supplies their
shared outcome and observation mechanisms without introducing a worker runner.
The normative registry and its pinned conformance-catalog digest are unchanged.

| Permanent requirements | Implementation and evidence |
| --- | --- |
| `JBSA-OPS-001`, `004`, `006` | `FailureRetention` selects observed operational failures by phase, present-first ordinal, identifier, and structured location. `OperationSession` serializes acceptance against cancellation. `FailureRetentionTest`, `OperationSessionTest`, and the publication failure tests check bounded secondary records, causes, cleanup, and cancellation races. |
| `JBSA-OPS-002`, `003` | Public `inspect` and `open` perform bounded source/recognition validation before reporting unavailable parser capability. `ArchiveValidation` adds all applicable unsafe-name warnings without changing intrinsic disposition. Owned index construction establishes detached structural evidence; selected-content failure establishes only its justified payload extent. `QueryValidationTest` and `OwnedArchiveValidationTest` cover these boundaries. |
| `JBSA-OPS-005` | Warning policy is evaluated before retention, including the reserved truncation summary. Reports retain warnings and latest assessment. `FailureRetentionTest` and `PublicationProgressTest` cover exact counts, policy rejection at a one-record ceiling, successful reports, and retained existing-tree siblings. |
| `JBSA-OPS-007`, `008` | `OperationSession` delivers the applicable phase/metric pairs with initial zero and exact successful terminal snapshots. Delivery is synchronous and outside its outcome lock; a throwing observer is disabled. `OperationSemanticsTest` and `PublicationProgressTest` cover public preflight failures, zero-unit phases, logical counters, post-commit cancellation, and observer failure during cleanup. |
| `JBSA-OPS-009` | Explicit state and a commit fence serialize cancellation against accepted failures and publication. Bounded staged writes and explicit writer checkpoints observe stop state. `OperationSessionTest` exercises controlled overlap; existing publication tests exercise staged-write cancellation, multipart rollback, and per-file settlement. |
| `JBSA-OPS-010` | `OwnedArchive` retains previous structural evidence on source/policy failures and produces rejected selected-payload evidence on late format failure. Earlier returned bytes remain consumed, only the failed child closes, and the parent stays usable. Owned-content and race tests retain the distinct NIO close/interruption outcomes. |

## Family integration

All execution helpers remain in the unexported `internal.io` package. Real family
parsers, codecs, and writers remain subsequent slices; this work adds no fabricated
archive parser or separate whole-archive validation operation. Stored-content test
loaders exercise the public `OpenArchive`, `ArchiveEntry`, and `EntryContent`
contracts. Publication fixtures exercise real Windows staging through the internal
format-writer adapter and assert public reports, diagnostics, progress, and artifacts.
They do not qualify an Archive Family's encode or decode behavior.

A mutation coordinator creates one `OperationSession` from the operation,
`ResourceLimits`, `DiagnosticPolicy`, and `OperationControl`. It begins preflight
before operational discovery and counts every considered source candidate through
`PREFLIGHT/ENTRIES`, including candidates subsequently filtered or overlaid. It
establishes assessments and admits warnings before discarding any diagnostic
records. It passes that same session to the `PublicationTransaction` report
overload after source-shape, configuration, capability, and finalized output-plan
validation. The adapter completes destination preflight and owns publication and
cleanup. Outer scratch ownership must also be settled before the operation returns.

Pack writers call `StagedFile.completedEntry(decodedBytes)` once per final logical
entry. Encoded framing, backpatches, compression, sharing, and replay never add
logical units. Extraction counts each completed entry writer and its uncompressed
output extent once. Future format adapters must preserve this logical boundary
when performing transformations. Writers admit late warnings through
`StagedFile.diagnostic` and observe cancellation around codec calls through
`StagedFile.checkpoint`, in addition to checkpoints in bounded positional writes.

Existing-tree extraction publishes in logical order and reports its commits under
`PROCESSING/ARTIFACTS`. Atomic archive sets and new roots report publication only
after processing succeeds. An observer failure after settled publication retains
the committed artifacts. Cleanup failure retains exact residual paths and transfers
their ownership through the existing #35 artifact contract.

## Verification boundary

Run focused tests with the pinned wrapper and `-pl jbsa -am`, then run the full
`clean verify` reactor before committing. The public packaged-module checks remain
part of that final build. These tests establish shared implementation behavior;
Archive Family conformance, parallel scheduling, native-codec cancellation,
performance, and release qualification remain their separately assigned gates.
