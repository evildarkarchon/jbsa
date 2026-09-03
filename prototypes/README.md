# JBSA archive-library interface prototypes

These are throwaway, compile-checked Java 25 interfaces for resolving
“Prototype alternative archive library interfaces.” They do not implement a
Bethesda Archive codec and are not production scaffolding.

Run the only useful mechanical check with:

```powershell
mvn compile
```

Every prototype covers the same required surface: detection, inspection,
listing, individual-entry access, extraction, creation/packing, ordered
later-source-wins merging, metadata, progress, cancellation, explicit ownership,
and payloads larger than 2 GiB. Each keeps third-party and Archive Family
implementation types behind the external seam.

## A. Path-first facade

[`PathFirstFacadePrototype.java`](src/main/java/prototype/facade/PathFirstFacadePrototype.java)
makes common operations direct and gives repeated/random entry access an owned
`OpenArchive` lifetime. Advanced work uses immutable request records.

- Best caller leverage for the thin CLI and ordinary embedded consumers.
- Familiar ownership: close the entry channel, then the `OpenArchive`.
- `long` sizes plus `ReadableByteChannel` avoid whole-entry arrays.
- Main risk: facade overloads and option records can expand into a shallow
  “everything bag.”

## B. Command workspace

[`CommandWorkspacePrototype.java`](src/main/java/prototype/workspace/CommandWorkspacePrototype.java)
opens one effective overlay view and interprets `Inspect`, `Transfer`, and `Pack`
commands.

- Strong locality for source merging, indexing, and resource ownership.
- Callback-scoped payload channels are difficult to leak or retain incorrectly.
- A small method count hides a broad command/selection/target/outcome algebra.
- Common callers must learn the interpreter model before listing one archive.

## C. Operation algebra with resource ports

[`OperationAlgebraPrototype.java`](src/main/java/prototype/algebra/OperationAlgebraPrototype.java)
performs typed operations over reusable input, source, and output ports.

- Highest flexibility for memory, generated, fault-injecting, or unusual storage.
- Cursors and fresh channel factories make large-data ownership explicit.
- The largest public interface and the highest setup cost for a `Path` caller.
- General ports are premature unless a second real production adapter exists.

## D. Capability-leased positional ports

[`PositionalPortsPrototype.java`](src/main/java/prototype/ports/PositionalPortsPrototype.java)
places the external seam at positional byte reads and transactional multi-part
writes.

- Strongest control over borrowed/owned storage and sparse large-file tests.
- Naturally uses `long` positions and bounded `ByteBuffer` values.
- Leaks storage choreography into every consumer.
- Positional entry reads can accidentally promise cheap random access for
  compressed payloads.

## Comparison

| Criterion | A: facade | B: workspace | C: algebra | D: positional ports |
| --- | --- | --- | --- | --- |
| Common caller | Best | Abstract | Verbose | Most verbose |
| Ownership | Conventional parent/child | Safest callback scope | Explicit cursors/channels/sinks | Explicit leases/transactions |
| Large files | Streaming channels | Callback channels | Channel factories and cursors | Positional bounded buffers |
| Testability | Real temp NTFS through public seam | Generated source/receiver | In-memory and faulting adapters | Sparse-memory adapters |
| Depth | High if facade delegates | High operationally | High for unusual consumers | Format logic deep, I/O seam broad |
| Compatibility risk | Growing options | Growing type algebra | Policy/metadata bags | Premature I/O guarantees |

## Recommended decision

Adopt an A-centered hybrid:

1. Use a concrete, Path-first `BethesdaArchives` module with an owned
   `OpenArchive` for repeated reads.
2. Use immutable `ExtractRequest` and `PackRequest` values for advanced calls;
   ordered `PackSource` values express later-source-wins overlays.
3. Move payloads through bounded `ReadableByteChannel` values with `long` sizes.
4. Borrow B's callback-scoped, library-owned channel only for caller-generated
   packing inputs, where a real second source adapter exists.
5. Keep C/D's generic storage ports, positional entry reads, policy maps, and
   destination transactions behind internal seams until later I/O and
   concurrency decisions prove they belong in the public interface.

This recommendation maximizes leverage and locality for the known callers—the
CLI, conformance suite, benchmarks, and ordinary Java consumers—without making
the first release promise storage abstractions it does not yet need.

## Reaction needed

Choose the recommended hybrid or one alternative, then identify any interface
element that must change. In particular:

- Should the routine operations be static, or methods on one concrete
  `BethesdaArchives.standard()` instance?
- Is callback-scoped generated content worth exposing in the first public
  interface, or should the first release accept only `Path` sources?
- Does any known consumer require non-`Path` archive input or output now?
