# Bounded positional I/O substrate

Issue [#34](https://github.com/evildarkarchon/jbsa/issues/34) adds the internal
stored-content substrate beneath the pre-1.0 Contract Baseline. The permanent
requirements were traced in `docs/spec/requirements.yaml` before implementation.
The normative registry and its pinned conformance-catalog digest are unchanged.

| Requirements | Implementation evidence |
| --- | --- |
| `JBSA-IO-001` | `ExactIo` checks long spans and multiplication, caps positional transfers at 64 KiB, loops on short transfers, and fails after 16 consecutive zero-progress transfers. Tests cover overflow, short EOF, partial writes, zero progress, and a sparse offset above 2 GiB. |
| `JBSA-IO-002` | `ArchiveInput` owns one `FileChannel` opened with `NOSHARE_WRITE` and `NOSHARE_DELETE`, permitting compatible readers. Real Windows tests verify write, delete, and rename denial, conflicting-writer rejection, and release after close. Public `detect` uses this opener and reads at most 36 bytes. |
| `JBSA-IO-003`, `JBSA-LIB-003`, `JBSA-LIB-004` | `OwnedArchive` eagerly completes and validates its index before returning, creates fresh sequential stored-content cursors, and keeps payload bytes lazy. Tests observe the public parent/entry/content interfaces through an internal test-only index loader. Detached metadata survives close; only terminal EOF establishes a payload assessment. |
| `JBSA-IO-004`, `JBSA-OPS-010` | No mappings, locks, or entry-local interruption are used. Barrier-controlled tests cover close/read and close/open races, late EOF, sibling isolation, and caller interruption invalidating shared state. |
| `JBSA-IO-005`, `JBSA-LIB-009` | `ResourceBudget` admits index and encoded metadata before allocation and charges heap, native memory, handles, and peak scratch reservations atomically. Semantic limits report `operation.resource-limit` with `field`, `ceiling`, and `observed`; internal capacity exhaustion reports `io.resource-capacity`. Equality, overflow, failed admission, concurrent reservations, and release have focused tests. |
| `JBSA-IO-006` | `SourceFile` retains no planned handles, rejects non-regular/no-follow inputs, requires stable identity, and revalidates before opening, under sharing denial, and after consumption. Provider-neutral consistency tests use a non-exported attribute seam. The Windows identity capability boundary below remains explicit. |

## Integration and ownership

The new classes live in the unexported `internal.io` package. The sole JPMS export
is unchanged. The module requires `jdk.unsupported` only for OpenJDK's qualified
extended sharing options; no reflection, native library, native-access grant, or
public provider adapter is introduced.

The subsequent [publication slice](publication.md) adds an internal Windows FFM
identity backend and an explicit native-access grant for publication tests; the
archive-read path described here remains unchanged.

A future format loader calls `OwnedArchive.load`, declares its entry count before
constructing entry records, reads encoded headers/tables through bounded
`readMetadata` windows and names through `readName`, and adds checked stored
ranges in archive order. The loader must charge each encoded region once and
must not manufacture uncharged names or parsed records. `readName` validates the
complete span and heap credit before narrowing its length or allocating bytes.
The index is never returned partially constructed. Failed loading closes the
input and its resource budget.

Metadata/index credits remain held until parent close. Each child has its own
cursor and a retained heap reservation, shares the one parent handle, and owns
no native buffer or scratch file. Current stored-input budgets admit no native
memory; later codec integrations must establish their own qualified bounded
costs. Internal buffer sizes and capacity ceilings are implementation choices,
not public Resource Limits or tuning options.

Read admission and completion share a short lifetime lock with close; file reads
run outside that lock. This lets close invalidate an in-flight read even when the
provider completes it successfully. A racing read reports
`AsynchronousCloseException`, later reads report `ClosedChannelException`, and
direct caller interruption reports `ClosedByInterruptException` and invalidates
siblings. Child close only releases its own cursor credit. Provider exceptions
are retained as causes, never copied into stable diagnostic values.

## Capability and delivery boundaries

On the tested Temurin 25.0.4.1 Windows NIO provider, `BasicFileAttributes.fileKey()`
is null even for a regular NTFS file. `SourceFile.plan` consequently fails closed
with `CAPABILITY` / `source.identity-unavailable`. A path, size, and timestamp do
not substitute for stable identity. The packing slice needs a qualified internal
identity check before loose-file consumption can succeed on that provider.
The tests explicitly distinguish this real capability failure from simulated
identity/revalidation coverage; they do not qualify Windows source identity.

Public `open` and `inspect` still report the honest baseline capability failure
until an Archive Family parser is integrated. This issue does not claim TES3
Decode Conformance from a synthetic supplied index. Compressed streams, DDS
reconstruction, generated-source replay, publication/staging, full operation
failure selection, and worker scheduling remain their downstream slices.
Exact positional writes are available for later bounded backpatching; they do
not establish publication or durability guarantees.

## Verification

Run the focused substrate checks on Windows with Java 17 or newer; Gradle supplies the Temurin Java
25 toolchain:

```powershell
.\gradlew.bat :jbsa:test
```

The final gate is the complete `.\gradlew.bat clean verify`, including packaged public
consumer, architecture, compliance, and existing harness checks. The I/O tests
use project-authored byte sequences and temporary sparse files; no local game
assets or Reference Snapshot writes are involved.

Bounded-transfer and resource tests are regression evidence, not Performance
Qualification. The existing performance-v1 catalog is still unbound until the
Archive Family slices supply conformed executables and inputs. I/O performance
qualification must be run against those bindings before a release claim; this
substrate does not fabricate results for unavailable archive operations.
