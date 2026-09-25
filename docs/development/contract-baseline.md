# Public archive Contract Baseline

This page records the historical baseline. The current
[Interface Candidate audit](interface-candidate.md) covers the four representative
families and the later evidence gate; its status supersedes capability-availability
notes below.

Issue [#33](https://github.com/evildarkarchon/jbsa/issues/33) establishes the **pre-1.0
Contract Baseline**, not Interface Candidate or Interface Freeze. Breaking corrections remain
expected as the representative Archive Families exercise these contracts. The sole production
JPMS export remains `io.github.evildarkarchon.jbsa` from the module of the same name.

## Scope and requirement trace

The permanent requirements were traced before implementation through
[`requirements.yaml`](../spec/requirements.yaml). Their normative owners remain unchanged:

| Requirements | Baseline evidence and boundary |
| --- | --- |
| `JBSA-LIB-001`, `002`, `005`, `006`, `007`, `009`, `010`, `011` | Concrete stateless module, Path-first signatures, immutable metadata/request/outcome values, long quantities, exact standard limits, controls, and public JPMS consumer tests. |
| `JBSA-LIB-008`, `012` | Sealed source values, ordered source retention, fresh-channel ownership contract, and name-identity derivation. Directory expansion, overlay replacement, and canonical-pack preflight now execute for TES3 in #37. |
| `JBSA-LIB-003`, `004` | Owned parent/entry/content signatures and documented lifetime/EOF contracts. The registry assigns backing-handle and close-race execution to #34. No simulated archive certifies these guarantees here. |
| `JBSA-OPS-001` through `010` | Checked failures and immutable public values. Issue #36 adds [shared operation execution](operation-semantics.md), deterministic selection/retention, cancellation acceptance, and observer delivery; family conformance remains downstream. |
| `JBSA-OPS-011` | Immutable artifact states and normalized absolute artifact paths. Safe publication, rollback, residual cleanup, and ownership execution remain #35. |
| `JBSA-DET-001` through `005` | Bounded binary recognition supports the module's detection entry point; no structural or payload conformance claim follows from detection. Family slices add their complete detection/conformance evidence. |

This table and the contract checks below record baseline evidence, not completion of every behavior
in a linked requirement. The registry and its conformance-catalog digest remain unchanged: updating
registry bookkeeping would invalidate the pinned golden dependency set even without a normative
behavior change. Native dependency ordering and downstream ownership are preserved.

## Available behavior

`BethesdaArchives.standard()` returns one resource-free, stateless module. `detect(Path)` opens and
closes its input, reads at most 36 bytes, retains observed selectors, and distinguishes unrecognized,
incomplete, supported-family, and unsupported-variant recognition. Extensions are irrelevant.
Source I/O errors use checked `ArchiveException` with `SOURCE` and the original cause.

Issue #34 supplies the [bounded positional I/O substrate](positional-io.md), including
Windows deny-write/delete detection handles and internal owned stored-content lifetimes.
Unavailable sharing capability fails explicitly; the later Archive Family slices integrate
their parsers with the eager index and lazy content implementation.

`inspect(Path)`, `inspect(Path, OpenOptions)`, `open(Path, OpenOptions)`,
`extract(ExtractRequest, OperationControl)`, and `pack(PackRequest, OperationControl)` are callable
baseline entry points. `inspect` and `open` validate source access and bounded recognition first,
reporting `SOURCE`, `FORMAT`, or `UNSUPPORTED` when that layer fails. Selectors outside TES3 and TES4 still
report `CAPABILITY` with `baseline.archive-operation-unavailable` until family parsers arrive.
Unimplemented mutation entry points apply pre-cancellation, preflight progress, observer isolation, and cleanup
semantics before returning their baseline capability failure, with no destination artifacts.
They do not return fabricated
inspections, reports, or open archives. Standard inspection options contain no Compatibility Profile,
the exact standard Resource Limits, and no reconstruction DDS Target.

All requests validate programmer shape errors before execution, preserve source order, and copy
collections. Generated payload factories return fresh, repeatable `ReadableByteChannel` instances;
JBSA owns each channel once returned. Caller callback and factory references remain the explicit
behavioral capabilities; callers are responsible for their own state and repeatability.

## Consumer example

Issue #37 exercises the [TES3 walking slice](tes3.md) end to end. Its ergonomics review adds
`ArchiveInspection.entries()` for detached list/dump consumers and
`OperationReport.archiveParts()` for the published path, byte size, and entry count of each packed
part. Existing constructors remain available. These additions preserve the single public module
and avoid extra archive opens merely to render a completed operation.

Issue #38 adds the [TES4 slice](tes4.md) and format-neutral per-entry compression
overrides in `PackOptions`. Existing six-argument construction retains its original
defaults; the seventh argument is an immutable map keyed by `NormalizedNameIdentity`.
Each format validates its permitted codecs, and TES3 rejects nonempty overrides.

Windows packing and extraction require `--enable-native-access=io.github.evildarkarchon.jbsa`
when run on the module path, or `--enable-native-access=ALL-UNNAMED` for a classpath application.
This grants the existing no-follow filesystem identity adapter access. The explicit `bsarch-1.0/v1`
profile also uses native access to snapshot the active Windows ANSI code page; default archive
name decoding remains Windows-1252.

```java
module example.archiveconsumer {
  requires io.github.evildarkarchon.jbsa;
}
```

```java
var archives = BethesdaArchives.standard();
ArchiveDetection detection = archives.detect(path);
// Recognition establishes selectors only, not structure, payload validity, or encode support.
```

The following usage executes for TES3 and becomes available for other families as each is implemented:

```java
try (OpenArchive archive = archives.open(path, OpenOptions.standard())) {
  ArchiveInspection inspection = archive.inspection();
  for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
    ArchiveEntry entry = archive.entry(ordinal);
    EntryMetadata metadata = entry.metadata();
    try (EntryContent content = entry.openContent()) {
      ByteBuffer window = ByteBuffer.allocate(8192);
      while (content.read(window) != -1) {
        window.flip();
        // Consume the bytes before clearing the bounded window.
        window.clear();
      }
      ArchiveAssessment payloadAssessment = content.assessment().orElseThrow();
    }
  }
}
```

Detached metadata remains usable after close. A parent owns all outstanding children; child close
affects only that child. Terminal EOF establishes a new payload-scoped assessment. Early close
does not. Decoded payloads never cross this boundary as whole-entry arrays. The `int` returned by
the inherited NIO `read(ByteBuffer)` method counts one buffer transfer; entry sizes and archive
counts remain `long`.

## Contract checks

`PublicModuleConsumerIT` compiles and runs isolated CLI-like and embedded named modules against
the packaged public JAR without `--add-exports`, opens, or classpath bypasses. It compiles the full
entry/content/extract/pack usage and executes the TES3 consumer paths.
`PublicArchiveContractIT`, `PublicMetadataContractIT`, `PublicRequestContractIT`, and
`PublicOutcomeContractIT` verify the public value and baseline behavior. Existing architecture
tests inspect exported signatures for third-party or internal types.

Run the focused contract checks against the packaged artifacts:

```powershell
.\gradlew.bat :jbsa-conformance-tests:integrationTest
```

The complete `.\gradlew.bat clean verify` build remains the final local gate. Neither these tests nor a successful
build constitutes Automated Conformance, Performance Qualification, or Release Qualification.
