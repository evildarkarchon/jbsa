# Pure Archive Family adapters behind the Pack Pipeline

JBSA packs every Archive Family through one Pack Pipeline module (`internal/pack`). Each family contributes a stateless, pure adapter that holds only wire knowledge. The pipeline owns all I/O, lifecycle, Content Sharing, and failure handling. Before this decision, `Tes3Packer`, `BsaPacker`, and `Ba2Packer` each re-implemented the whole operation lifecycle, and those copies had already drifted apart. The drift is recorded as approved behavior changes D1–D10, Q10, and Q14 in #68.

The pipeline owns these steps, in this order:

1. Operation session, `IoContext`, and `ResourceBudget` setup and teardown.
2. Admission and planning:
   1. `adapter.admit`;
   2. source planning under the adapter's name charset;
   3. `admitted.plan`;
   4. `pack.unmatched-entry-compression`;
   5. `<prefix>.empty-entry-set`;
   6. the decoded-size limit, charged once in Logical Plan Order. A chunking family (DX10 BA2) charges each entry during stabilization instead, still in Logical Plan Order, because only its envelope knows the decoded size.
3. The split loop and target preflight. Preflight always runs, for the part count the plan knows.
4. The parallel ordered-transform stage, the Content Sharing index, and the private codec map.
5. Payload placement, then the adapter's positional table patches.
6. The `OperationReport` and its Archive Parts, then the single `FailureRetention` epilogue.

`FamilyAdapter.admit` returns an immutable `Admitted<K>` holding all request-derived state. It exposes data (name charset, diagnostic prefix, default split target, emission kind, split cost, sharing scope and basis, a per-entry reserve) and pure functions (`plan`, `checkStored`, `chunk`, `layout`, `tables`, `readback`). Adapters perform no I/O, own no resources, and never observe cancellation. Each method is therefore testable as input → output. Family dispatch in `BethesdaArchives.pack` is an exhaustive `switch` over `ArchiveFamily`.

## What stays per family, and why

These choices determine output bytes pinned by Binary Conformance fixtures, so they stay with each family's adapter:

- **Sort order.** Entry order fixes table order and payload positions. TES3 orders by name hash (low word, high word, then name bytes); versioned BSA by folder then file hash.
- **Sharing key and scope.** They decide which payloads are stored once. TES3 shares per Archive Part on raw bytes. Versioned BSA compares the complete stored record, including embedded-name framing and the stored/compressed discriminator. BA2 decides owners once for the whole archive set.
- **Split cost.** It decides part membership. The families charge different fixed allowances and name costs, and BA2 charges a shared payload only for its first owner within a part.
- **Framing.** Embedded names and decoded-size prefixes are part of the stored bytes.
- **DDS chunking and normalization, diagnostic IDs, and admission order.** These are family semantics that the Conformance Contract pins.

The pipeline only interprets these declarations. For example, `SplitCost(fixed, nameCost, PayloadCost)` states the formula, and the pipeline runs the one split loop. Part-owner tracking for sharing-aware costs stays inside the pipeline.

## How each family uses the pipeline

TES3 was the first family on the pipeline: it streams its parts, stores payloads only, and shares raw bytes per part. Versioned BSA followed (#71): it stabilizes every payload, compresses with zlib or the BSA LZ4 frame, and shares complete stored records per part. BA2 completed the migration (#72): it stabilizes General and DX10 payloads, compresses with zlib or raw LZ4, shares raw bytes across the whole archive set, and reads every staged part back. All three family packers are now adapters.

- **Stabilized emission.** Versioned BSA and BA2 stabilize every payload before splitting, because their split cost depends on encoded size. Stabilization is processing work, so progress enters PROCESSING before the first source is read, and publication continues in that phase. It has two shapes, chosen by the sharing declaration:
  - *Per-part stored records* (versioned BSA). Each framed record is written to one ordered spool and measured; each part then shares byte-identical records while it replays them.
  - *Archive-set raw owners* (BA2). Each entry's raw bytes are written to the spool first and resolved to the earliest byte-identical raw owner in the whole set. Only a new owner is encoded. Each entry records its owners as `Segment`s; each part emits an owner once, at its first reference. The `UNIQUE_STORED` split cost charges an owner only at its first reference within a part.
- **Target preflight.** It always runs before any source is read. A stabilized plan whose stored sizes are all predictable checks its exact part set; otherwise it checks only the parts it is certain to publish, which is at least the first.
- **Codecs.** Adapters name a `Codec` per entry. The pipeline's private codec map is the only place that invokes encoders and knows their worst-case bounds and heap/native costs. Stored, zlib, the BSA LZ4 frame, and raw LZ4 route through it. It holds the one conservative zlib bound every family uses. An adapter declares the codecs a request can select in `requiredCodecs`, and the pipeline admits each one's runtime capability immediately after `admit`, before source planning. The adapter therefore never loads a native provider and stays pure, and testable, on every platform. Raw LZ4 stays on the coordinator, because each call admits its whole block and native buffers against the budget itself.
- **Sharing.** The `ContentSharing` index is independent of scope and basis. The pipeline creates one per part or one per set, and digests either the raw bytes or the framed stored record. A stored-record key also carries a stored/compressed discriminator, so a stored record never aliases a compressed one with identical bytes.
- **Header-last layout.** BA2's header points at a name table that follows the payloads. That is still only positional `tables` patches: records, the name table at the payload end, then the header. No family branches on header-first or header-last.
- **DDS and readback.** BA2 adds a pure `chunk` function returning DDS slices, a `Rewrite` (BGR24 to BGRA32), the canonical decoded size, and header metadata, all from the envelope head. The pipeline performs the rewrite and each slice's sharing and encoding. A `readback` declaration (validation credits and a nonconforming diagnostic ID) makes the pipeline reopen and fully decode every staged part before publication.
- **The transform stage.** `TransformStage` is the only parallel stabilization path; it replaced both `ParallelSources` and `BsaTransformedSources`. It maps every worker failure the same way for all families. A skipped outcome is INTERNAL, an unstructured I/O failure is SOURCE `operation.source-io`, and any other non-fatal throwable is INTERNAL. It admits workers under one headroom rule: the sum of the codec bounds must fit a sixth of the scratch ceiling.

## Consequences

Adding or fixing lifecycle behavior happens once, not three times. Admission order and the decoded-size limit are uniform across families. TES3 table writes now happen after payload placement rather than before; that is allowed because Progress Snapshot timing is not a semantic guarantee. A family whose wire rules really need a new lifecycle step must extend the pipeline and its `Admitted` contract, rather than branch around them.

_Decision source: [#68](https://github.com/evildarkarchon/jbsa/issues/68), first delivered by [#70](https://github.com/evildarkarchon/jbsa/issues/70), extended to versioned BSA by [#71](https://github.com/evildarkarchon/jbsa/issues/71) and to BA2 by [#72](https://github.com/evildarkarchon/jbsa/issues/72)._
