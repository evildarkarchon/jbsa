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
   6. the decoded-size limit, charged once in Logical Plan Order.
3. The split loop and target preflight. Preflight always runs, for the part count the plan knows.
4. The parallel ordered-transform stage, the Content Sharing index, and the private codec map.
5. Payload placement, then the adapter's positional table patches.
6. The `OperationReport` and its Archive Parts, then the single `FailureRetention` epilogue.

`FamilyAdapter.admit` returns an immutable `Admitted<K>` holding all request-derived state. It exposes data (name charset, diagnostic prefix, default split target, emission kind, split cost, sharing scope and basis) and pure functions (`plan`, `layout`, `tables`). Adapters perform no I/O, own no resources, and never observe cancellation. Each method is therefore testable as input → output. Family dispatch in `BethesdaArchives.pack` is an exhaustive `switch` over `ArchiveFamily`.

## What stays per family, and why

These choices determine output bytes pinned by Binary Conformance fixtures, so they stay with each family's adapter:

- **Sort order.** Entry order fixes table order and payload positions. TES3 orders by name hash (low word, high word, then name bytes); versioned BSA by folder then file hash.
- **Sharing key and scope.** They decide which payloads are stored once. TES3 shares per Archive Part on raw bytes. Versioned BSA compares the complete stored record, including embedded-name framing and the stored/compressed discriminator. BA2 decides owners once for the whole archive set.
- **Split cost.** It decides part membership. The families charge different fixed allowances and name costs, and BA2 charges a shared payload only for its first owner within a part.
- **Framing.** Embedded names and decoded-size prefixes are part of the stored bytes.
- **DDS chunking and normalization, diagnostic IDs, and admission order.** These are family semantics that the Conformance Contract pins.

The pipeline only interprets these declarations. For example, `SplitCost(fixed, nameCost, PayloadCost)` states the formula, and the pipeline runs the one split loop. Part-owner tracking for sharing-aware costs stays inside the pipeline.

## Shape for the remaining families

TES3 is the first family on the pipeline: it streams its parts, stores payloads only, and shares raw bytes per part. The interface was sketched against the versioned BSA and BA2 packers so it can grow for them:

- **Stabilized emission.** Versioned BSA and BA2 stabilize every payload before splitting, because their split cost depends on encoded size. The `STABILIZED` emission kind and the `STORED` and `UNIQUE_STORED` payload costs are declared now. The pipeline rejects them until versioned BSA migrates (#71).
- **Codecs.** Adapters name a `Codec` per entry. The pipeline's private codec map is the only place that invokes encoders and knows their worst-case bounds and heap/native costs. Only stored payloads route through it so far.
- **Sharing.** The `ContentSharing` index is independent of scope and basis. The pipeline creates one per part or one per set, and digests either the raw bytes or the framed stored record.
- **DDS and readback.** BA2 adds a pure `chunk` function returning DDS slices and a `Rewrite` from the envelope head, and a `readback` declaration (validation credits and a nonconforming diagnostic ID). The pipeline performs the readback I/O. Both arrive with #72.
- **The transform stage.** `TransformStage` replaces `ParallelSources` now. It absorbs `BsaTransformedSources` in #71.

## Consequences

Adding or fixing lifecycle behavior happens once, not three times. Admission order and the decoded-size limit are uniform across families. TES3 table writes now happen after payload placement rather than before; that is allowed because Progress Snapshot timing is not a semantic guarantee. A family whose wire rules really need a new lifecycle step must extend the pipeline and its `Admitted` contract, rather than branch around them.

_Decision source: [#68](https://github.com/evildarkarchon/jbsa/issues/68), first delivered by [#70](https://github.com/evildarkarchon/jbsa/issues/70)._
