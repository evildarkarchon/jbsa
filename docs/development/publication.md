# Staging and publication substrate

Issue [#35](https://github.com/evildarkarchon/jbsa/issues/35) implements the
filesystem substrate for the later archive-format slices. Before implementation,
the issue was traced to `JBSA-IO-007` through `JBSA-IO-015` and `JBSA-OPS-011`
in `docs/spec/requirements.yaml`. Cancellation at Publication Commit also follows
`JBSA-OPS-009`. The normative registry and its pinned catalog digest are unchanged.

| Permanent requirement | Implementation and regression evidence |
| --- | --- |
| `JBSA-IO-007` | `SpillBuffer` uses a fixed 64 KiB heap window and spills to an operation-owned file. It admits retained extent before writes, supports bounded positional replay and backpatching, and seals a representation for reuse. `SpillBufferTest` checks replay, exact limits, lifetime, and real Windows cleanup denial. |
| `JBSA-IO-008` | `PublicationTransaction` accepts a finalized output plan, admits its count and internal storage before adjacent staging, applies the split sibling naming rule, stages all archive parts, and lends one positional file writer per part. `PublicationFailuresTest` checks names, output limits, staging limits, and long reservations above 4 GiB without materializing a large file. |
| `JBSA-IO-009` | `ExtractionPaths` validates the complete selection before host path conversion, rejects traversal, ADS, reserved names, Windows case collisions, prefix collisions, equal native identities, and descendant reparse points. `WindowsPathIdentity` pins the root and supplies stable no-follow identities. `ExtractionPathsTest` and `WindowsPathIdentityTest` cover junctions, changed identities, pin lifetimes, and name eligibility. |
| `JBSA-IO-010` | Explicit `FAIL` rejects existing targets in preflight; `REPLACE` moves each predecessor into staging before installation. The existing public request defaults remain `FAIL`. Publication tests verify unchanged predecessors and restoration. |
| `JBSA-IO-011`, `JBSA-OPS-009` | Cancellation is sampled before effects, at bounded staged writes, and before each applicable commit. No cancellation is accepted inside an archive/split commit or current-file commit. Existing-tree cancellation retains completed siblings. |
| `JBSA-IO-012` | A private same-volume probe checks atomic file or directory moves before processing. New extraction roots publish with one move; archive parts and existing-tree files publish individually in Logical Plan Order. A failed atomic capability is never replaced by a non-atomic fallback. |
| `JBSA-IO-013` | Split failure rolls back the set; existing-tree failure rolls back only the currently committing file. Restoration failures retain the predecessor backup for the caller. Identity rechecks prevent rollback from deleting an observed external replacement. |
| `JBSA-IO-014`, `JBSA-OPS-011` | Owned paths are cleaned children first, and unremoved paths are reported exactly as normalized absolute Residual Artifacts. `FailureRetention` preserves the primary outcome, bounded ordered secondary failures, nested residuals, and truncation evidence. Cleanup after successful publication becomes a destination failure with published states retained. |
| `JBSA-IO-015` | No forced durability, write-through rename, journal, startup scavenging, mapping, or crash-recovery mechanism is introduced. Rollback covers synchronously observed in-process failures. |

## Integration contracts

These classes stay in the unexported `internal.io` package. `PublicationTransaction`
is synchronous and owns its transaction, staging files, channels, resource budget,
and containment pin. A writer borrows `StagedFile` only for its callback and uses
positional `reserve`/`write` operations. Reservations do not allocate proportional
buffers. A successful callback must have supplied all meaningful output bytes;
reserved gaps are not a format-level promise of zero padding.

Archive-format code must establish source consistency and complete Logical Plan
Order before entering this seam. When encoded sizes determine split membership,
it first stabilizes bounded content with `SpillBuffer` outside the destination,
then passes the complete finalized part writers to `archives`. The publication
seam therefore cannot create destination staging during that stabilization gate.
Callers must close scratch before their outer operation returns, and merge any
scratch failure through `FailureRetention` with the publication artifacts.

Extraction callers supply complete decoded names whose wire encoding and
Normalized Name Identity have already been established by archive metadata
validation; synthetic missing-name placeholders must never be passed here.
The containment layer independently rejects structurally unsafe Unicode names
and Windows host-ineligible segments. A missing extraction root requires an
existing immediate parent. An existing drive or share root stages beside its
planned files within that root. Existing-tree extraction creates and records only
planned missing directories, and never replaces the whole existing tree.

The transaction reports artifacts and checked outcomes to its caller. Progress,
diagnostic-policy evaluation, operation-wide failure scheduling, and family
decoding/encoding remain the operation/family integration slices. Public
`BethesdaArchives.extract` and `pack` retain their Contract Baseline capability
failure until those family implementations are connected; these substrate tests
do not claim archive-format Automated Conformance or Release Qualification.

## Windows capability

Temurin 25.0.4.1 on Windows returns no NIO `fileKey`, and ordinary `FileChannel`
opening does not supply the required directory pin. The internal backend uses
Java 25 FFM with Windows `CreateFileW`, `GetFileInformationByHandle`,
`GetFileInformationByHandleEx(FileIdInfo)`, and `CloseHandle`. File IDs contain
the volume identity and all 128 file-ID bits. No-follow handles inspect a reparse
point itself. A root pin requests directory-listing access and denies delete
sharing; the real Windows test verifies that metadata-only access would not
enforce the required rename denial.

Consumers of this internal Windows publication path need
`--enable-native-access=io.github.evildarkarchon.jbsa` on the module path
(`ALL-UNNAMED` for classpath deployment). The library unit-test configuration
grants access only to that module. Missing native access, unsupported filesystem
identity, or unavailable required atomic moves produces `CAPABILITY`.
There is no timestamp/path fallback for identity.

Kernel32 is supplied by Windows and loaded by its fixed system-library name.
No DLL is added to the library, release inputs, or native-payload inventory;
third-party codec inventories and promotion gates remain unchanged. Native
downcall memory and handle headroom are bounded separately from payload scratch.
The original [positional I/O slice](positional-io.md) continues to use only NIO
for archive reads and keeps its documented source-planning capability boundary.

Private staging means unique, unadvertised, operation-owned names under the
non-hostile destination-tree contract. It is not a confidentiality or privileged
namespace-race guarantee. No new ACL, non-Windows, crash recovery, or durability
guarantee is made.

## Verification

Run on Java 25 / Windows:

```powershell
.\mvnw.cmd -B -ntp -C -pl jbsa -am '-Dtest=SpillBufferTest,ResourceBudgetTest,WindowsPathIdentityTest,ExtractionPathsTest,PublicationTransactionTest,PublicationFailuresTest,PublicationDriveRootTest' '-Dsurefire.failIfNoSpecifiedTests=false' test
.\mvnw.cmd -B -ntp -C clean verify
```

The tests use authored byte sequences and disposable filesystem fixtures. Fault
injection is limited to the internal filesystem mutation boundary; real Windows
tests establish identity, junction, sharing, and cleanup behavior. No proprietary
assets or Reference Snapshot writes are involved.
