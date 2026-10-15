# No Read Pipeline: shared disposition rules in IndexSession

JBSA reads every Archive Family through one `IndexSession` (`internal/io`), which owns the Archive Disposition rules all readers share. Each reader (`Tes3Reader`, `BsaReader`, `Ba2Reader`, `DdsBa2Reader`) is a wire walk plus its family's rules. Before this decision, each reader re-implemented the same scaffolding around its index parse: the FORMAT → REJECTED `load` wrapper, the undecodable-name fallback and duplicate-identity check, the span-overlap loop and trailing-data check, a private `Diagnostic` builder, and the Archive Assessment and Archive Detection synthesis. Those copies had already drifted into two contract breaks. Nonzero `0x69` folder padding made an archive a Tolerated Noncanonical Archive without any Conformance Diagnostic, and each reader rebuilt its detection from 4 magic bytes, so inspection and detection reported different evidence. Both were fixed first, by #75 and #76.

The session owns these mechanisms:

1. **Failure handling.** The read's `FailureRetention` and Diagnostic Policy. A FORMAT failure from the walk gains a REJECTED `Structure` assessment carrying every diagnostic retained so far.
2. **Disposition.** `noncanonical(id, location, values)` is the only way to mark an archive noncanonical, so a noncanonical outcome always carries a diagnostic. `warning(id, location, values)` records a diagnostic that leaves the disposition unchanged.
3. **Names.** `decodeName` and `decodeComponent` apply Archive Name Encoding (`ArchiveNames.encoding`, shared with the pack adapters). They emit `archive-name.undecodable-wire-bytes` for undecodable wire bytes and fall back to a synthetic display name with no identity. `identify` registers each Normalized Name Identity and rejects a repeat with the family's identifier.
4. **Spans.** A payload span registry with the partial-overlap check, an optional reserved metadata region, and the trailing-data check.
5. **Entries.** One `add(metadata, offset, size, PayloadCodec)` for ordinary payloads, plus `addDds` for DX10 chunk lists, which have a different shape.
6. **`finish`.** It builds the Archive Assessment from the upstream `ArchiveDetection` unchanged, then runs the `ArchiveValidation.structure` name-safety pass in the same retention. Reader diagnostics and validation diagnostics therefore share one semantic order and one set of retention ceilings. A policy-rejected reader warning ends the read before validation runs.

`ArchiveReaders.open` dispatches with an exhaustive `switch` over `ArchiveFamily`, mirroring `BethesdaArchives.pack`. Its one explicit fallback arm reads a Starfield version 3 BA2 whose method is not raw LZ4, and only under the qualified Compatibility Profile.

## What stays per family, and why

These choices determine Conformance Diagnostics that the format specs pin, so they stay with each family's reader. The session supplies mechanisms only and never names a family's diagnostic identifier.

- **Diagnostic identifiers and their value sets.** For example, TES3 trailing data carries `{stored, expected}` and BA2 trailing data carries none.
- **Hash formatting.** TES3 and Versioned BSA hashes use `%016X`; BA2 hashes use `%08X`.
- **Hash eligibility.** The three format specs word the predicate differently, so unifying it is a spec question, not a refactor.
- **Display folding.** BA2 folds `/` to `\` before deriving the Normalized Name Identity, so folding decides which names collide.
- **Noncanonical classification.** Which diagnostics make an archive noncanonical. Every BA2 warning does; TES3 and Versioned BSA classify per identifier. Versioned BSA also defines no trailing-data rule.
- **Check order and timing.** Where each check sits in the walk, such as General BA2 checking each payload against the index per entry while DX10 checks after its variable-length records, decides the Primary Failure among several faults.

## How each family uses the session

Each reader receives the session from dispatch and returns only its `ArchiveMetadata`. TES3 walks its fixed tables and name block; its used extent includes empty entries, so an empty entry at end of file claims no trailing data. Versioned BSA decodes the folder and basename components separately through `decodeComponent`, under its hash-based synthetic name, and registers the joined name's identity. General and DX10 BA2 decode complete names through `decodeName` with BA2 folding, and reserve the filename table against payload overlap. DX10 also checks every chunk, empty or not, against the end of its records.

## Consequences

Adding or fixing a shared disposition rule happens once, not four times, and a noncanonical outcome without a diagnostic can no longer be written. A family whose wire rules need a new shared mechanism must extend the session rather than rebuild it locally.

There is deliberately no Read Pipeline mirroring the Pack Pipeline of [ADR-0003](0003-pure-family-adapters-behind-the-pack-pipeline.md). Readers are effectful cursors, not pure adapters: each read is span-checked and charged against Resource Limits before the next read decides what to read, so a reader cannot be reduced to a pure function of bytes it has already been given. `OwnedArchive` already owns the read lifecycle (the input handle, the resource budget, lazy payload decoding and child lifetimes), and the session is confined to the index load inside it.

_Decision source: [#74](https://github.com/evildarkarchon/jbsa/issues/74), first delivered by [#77](https://github.com/evildarkarchon/jbsa/issues/77)._
