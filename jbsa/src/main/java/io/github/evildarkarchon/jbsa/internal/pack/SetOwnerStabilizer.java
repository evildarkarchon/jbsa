package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.codec.ByteSink;
import io.github.evildarkarchon.jbsa.internal.codec.Checkpoint;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.io.*;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Layout;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Patch;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Placed;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Planned;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Stabilization for the {@link Admitted.Emission.SetOwners} Emission (BA2): each payload, or each
 * slice a chunking family cuts from it, resolves to a Content Sharing owner decided once for the
 * whole archive set on raw bytes, and each part emits an owner once, at its first reference.
 *
 * @param admitted the family state whose stored-size rule and tables apply
 * @param chunker the chunker the set-wide owner Emission declares, or empty when every payload is
 *     one unsliced owner
 */
record SetOwnerStabilizer<K>(Admitted<K> admitted, Optional<Admitted.Chunker<K>> chunker)
    implements StabilizedEmitter.Stabilizer<K> {
  /** Requires the family state and the chunker's stated presence. */
  SetOwnerStabilizer {
    Objects.requireNonNull(admitted, "admitted");
    Objects.requireNonNull(chunker, "chunker");
  }

  /**
   * Resolves every entry to its archive-set owners. Each part's replay emits the owners its entries
   * reference.
   */
  @Override
  public Stabilized<K> stabilize(Run run, List<Planned<K>> planned, SpillBuffer stable)
      throws IOException {
    List<SetOwner> owners = new ArrayList<>();
    List<Planned<K>> sized = stabilizeSet(run, planned, stable, owners);
    return new Stabilized<>(
        sized, (part, layout, output) -> emitOwners(part, layout, output, stable, owners));
  }

  /** One replay window and one readback window, plus bounded record and header buffers. */
  @Override
  public long replayHeap(boolean sharing) {
    // Owners were already shared across the whole set during stabilization, so replay never
    // compares candidates and the sharing flag cannot change this peak.
    return 2L * PayloadWindows.WINDOW_BYTES + 1024;
  }

  /**
   * Stabilizes every payload in output order for archive-set raw-basis sharing. Each entry's raw
   * bytes are written to the stable spool first; a chunking family's envelope is then chunked (and
   * rewritten when asked), and each slice, or the whole payload, is resolved to the earliest
   * byte-identical raw owner in the set. Only a new owner is encoded, so sharing never depends on
   * the codec. A chunked entry's decoded size is charged here, in Logical Plan Order.
   *
   * @param owners receives every distinct owner in creation order; a segment's owner identity is
   *     its index here
   * @return the entries, each resolved to its segments
   */
  private List<Planned<K>> stabilizeSet(
      Run run, List<Planned<K>> planned, SpillBuffer stable, List<SetOwner> owners)
      throws IOException {
    boolean chunked = chunker.isPresent();
    TransformStage stage =
        new TransformStage(
            run.operation(),
            planned.stream().map(Planned::source).toList(),
            run.workers(),
            run.budget(),
            run.context(),
            0,
            ordinal -> {
              Planned<K> entry = planned.get(ordinal);
              // Chunks are sliced from the stabilized envelope, so only the coordinator can encode
              // them.
              if (chunked || !Codecs.workerEncodes(entry.codec())) return null;
              // Raw bytes stay staged: sharing is decided on them before the owner is encoded.
              return TransformStage.encoding(
                  run.codecs().admitted(entry.codec()), entry.source().size(), true, run.budget());
            });
    boolean sharing = run.request().options().sharing();
    ContentSharing<SetOwner> index = new ContentSharing<>();
    List<Planned<K>> sized = new ArrayList<>(planned.size());
    var decoded = new DecodedLimit(run.request().resourceLimits());
    Throwable pending = null;
    try {
      // Worker handle leases must not starve the coordinator's ordered stable spool.
      if (stage.parallel()) stable.reserveSpillHandle();
      try (var coordinator =
          reserveSetCoordinator(planned, stage.parallel(), chunked, run.codecs(), run.budget())) {
        for (int ordinal = 0; ordinal < planned.size(); ordinal++) {
          try (TransformStage.Input staged = stage.next()) {
            Planned<K> entry = planned.get(ordinal);
            IoContext processing = entry.source().processing(ordinal);
            OptionalLong location = processing.ordinal();
            Checkpoint checkpoint =
                () -> run.operation().checkpoint(OperationPhase.PROCESSING, location);
            long size = entry.source().size();
            long rawOffset = stable.size();
            ByteSink raw = (relative, bytes) -> stable.write(rawOffset + relative, bytes);
            if (staged == null)
              PackSources.consume(
                  entry.source(),
                  input -> PayloadWindows.transfer(input, size, raw, checkpoint, processing),
                  processing);
            else {
              try (ReadableByteChannel input = staged.channel()) {
                PayloadWindows.transfer(input, size, raw, checkpoint, processing);
              }
            }
            // BA2 has always observed cancellation during sharing comparisons without an entry
            // ordinal; keeping that preserves the reported failure location.
            Checkpoint comparison =
                () -> run.operation().checkpoint(OperationPhase.PROCESSING, OptionalLong.empty());
            var owner =
                new OwnerResolver(
                    stable,
                    index,
                    owners,
                    sharing,
                    checkpoint,
                    comparison,
                    run.codecs(),
                    run.budget(),
                    coordinator,
                    processing);
            List<Admitted.Segment> segments = new ArrayList<>();
            K key = entry.key();
            long chargedDecoded = -1;
            if (chunked) {
              Admitted.Chunker<K> declared = chunker.orElseThrow();
              ByteBuffer head = ByteBuffer.allocate((int) Math.min(declared.headBytes(), size));
              stable.read(rawOffset, head);
              Admitted.Chunks<K> chunks = declared.chunk(entry, head.flip(), processing);
              run.budget().metadata(chunks.metadataBytes());
              long payload = rawOffset + chunks.payloadStart();
              if (chunks.rewrite() == Admitted.Rewrite.BGR24_TO_BGRA32)
                payload = expandBgr24(stable, payload, size - chunks.payloadStart(), checkpoint);
              for (Admitted.Slice slice : chunks.slices())
                segments.add(
                    owner.resolve(payload + slice.offset(), slice.size(), entry.codec(), null));
              key = chunks.key();
              chargedDecoded = chunks.decodedBytes();
            } else segments.add(owner.resolve(rawOffset, size, entry.codec(), staged));
            Planned<K> measured = entry.segmented(key, segments);
            admitted.checkStored(measured, processing);
            if (chunked) decoded.charge(chargedDecoded, processing);
            sized.add(measured);
          }
        }
      }
    } catch (Throwable failure) {
      pending = failure;
    }
    stage.finish(pending);
    return sized;
  }

  /**
   * Resolves raw payload ranges of one entry to archive-set Content Sharing owners, encoding a new
   * owner into the stable spool. The spool, index, owner list, codec map, budget, and coordinator
   * lease are run-wide; only the checkpoints and the PROCESSING location belong to the entry.
   *
   * @param checkpoint observes cancellation at the entry's ordinal while transferring or encoding
   * @param comparison observes cancellation while digesting and comparing sharing candidates
   */
  private record OwnerResolver(
      SpillBuffer stable,
      ContentSharing<SetOwner> index,
      List<SetOwner> owners,
      boolean sharing,
      Checkpoint checkpoint,
      Checkpoint comparison,
      Codecs codecs,
      ResourceBudget budget,
      ResourceBudget.Lease coordinator,
      IoContext processing) {
    /**
     * Returns the segment for one stabilized raw range: the earliest byte-identical owner when
     * sharing finds one, otherwise a new owner. A stored owner is the raw range itself; an encoded
     * one is the worker's finished result when there is one, else encoded here from the spool.
     *
     * @param staged the entry's worker result, or null when the coordinator must encode
     */
    Admitted.Segment resolve(
        long rawStart, long rawSize, PayloadCodec codec, TransformStage.Input staged)
        throws IOException {
      String key = null;
      if (sharing) {
        key =
            ContentSharing.key(
                rawSize, PayloadWindows.digest(stable::read, rawStart, rawSize, comparison), false);
        SetOwner match =
            index.find(
                key,
                candidate ->
                    PayloadWindows.equal(
                        stable::read,
                        candidate.rawOffset(),
                        stable::read,
                        rawStart,
                        rawSize,
                        comparison));
        if (match != null) return match.segment(rawSize);
      }
      long storedOffset = rawStart;
      long storedSize = rawSize;
      if (codec != PayloadCodec.STORED) {
        storedOffset = stable.size();
        long start = storedOffset;
        ByteSink sink = (relative, bytes) -> stable.write(start + relative, bytes);
        if (staged != null && staged.hasEncoded()) {
          try (ReadableByteChannel encoded = staged.encodedChannel()) {
            PayloadWindows.transfer(encoded, staged.encodedSize(), sink, checkpoint, processing);
          }
        } else {
          try (ReadableByteChannel input =
              PayloadWindows.channel(stable::read, rawStart, rawSize)) {
            codecs.encode(codec, input, rawSize, sink, checkpoint, budget, coordinator, processing);
          }
        }
        storedSize = stable.size() - storedOffset;
      }
      SetOwner owner = new SetOwner(owners.size(), rawStart, storedOffset, storedSize, codec);
      owners.add(owner);
      // Digest collisions cannot establish sharing; only byte-distinct owners are indexed.
      if (sharing) index.add(key, owner);
      return owner.segment(rawSize);
    }
  }

  /**
   * Appends a BGR24 payload rewritten to BGRA32 and returns where the rewrite starts. Appending
   * keeps the source and destination from overlapping during expansion. Input windows hold a whole
   * number of three-byte pixels, so each expands into exactly one output window.
   */
  private static long expandBgr24(SpillBuffer stable, long start, long size, Checkpoint checkpoint)
      throws IOException {
    long normalized = stable.size();
    ByteBuffer input = ByteBuffer.allocate(49152);
    ByteBuffer output = ByteBuffer.allocate(65536);
    for (long position = 0, written = 0; position < size; ) {
      checkpoint.check();
      int count = (int) Math.min(input.capacity(), size - position);
      input.clear().limit(count);
      stable.read(start + position, input);
      input.flip();
      output.clear();
      while (input.hasRemaining())
        output.put(input.get()).put(input.get()).put(input.get()).put((byte) 255);
      int expanded = output.position();
      stable.write(normalized + written, output.flip());
      position += count;
      written += expanded;
    }
    return normalized;
  }

  /**
   * Reserves the coordinator's set-wide stabilization working set. It holds at most three windows
   * at once (a transfer, a sharing comparison's two, or a BGR24 rewrite's input and output). When
   * it also encodes, the encoder's heap joins a two-window comparison peak instead.
   */
  private static ResourceBudget.Lease reserveSetCoordinator(
      List<? extends Planned<?>> planned,
      boolean parallel,
      boolean chunked,
      Codecs codecs,
      ResourceBudget budget)
      throws ArchiveException {
    // Only the entries the coordinator encodes itself; a worker's encoding is admitted by its own
    // lease, and a stored entry needs no encoder.
    AdmittedCodec.Cost encoder =
        codecs.largestPrecharge(
            planned.stream()
                .filter(
                    entry ->
                        entry.codec() != PayloadCodec.STORED
                            && (chunked || !parallel || !Codecs.workerEncodes(entry.codec())))
                .toList());
    long heap =
        Math.max(
            3L * PayloadWindows.WINDOW_BYTES,
            encoder.heapBytes() + 2L * PayloadWindows.WINDOW_BYTES);
    return budget.reserve(heap, encoder.nativeBytes(), 0, 0);
  }

  /**
   * Emits one part of a set-wide stabilized plan. Owners were decided for the whole set during
   * stabilization; each is copied once per part, at the first reference in output order, and every
   * later reference in the part repeats that position.
   */
  private void emitOwners(
      List<Planned<K>> part,
      Layout layout,
      PublicationTransaction.StagedFile output,
      SpillBuffer stable,
      List<SetOwner> owners)
      throws IOException {
    output.reserve(layout.payloadStart());
    Map<Integer, Long> emitted = new HashMap<>();
    List<Placed<K>> placed = new ArrayList<>(part.size());
    long next = layout.payloadStart();
    for (Planned<K> entry : part) {
      output.checkpoint();
      List<Long> positions = new ArrayList<>(entry.segments().size());
      for (Admitted.Segment segment : entry.segments()) {
        Long position = emitted.get(segment.owner());
        if (position == null) {
          SetOwner owner = owners.get(segment.owner());
          long destination = next;
          PayloadWindows.replay(
              stable::read,
              owner.storedOffset(),
              owner.storedSize(),
              (offset, bytes) -> output.write(destination + offset, bytes));
          position = destination;
          emitted.put(segment.owner(), position);
          next = Math.addExact(next, owner.storedSize());
        }
        positions.add(position);
      }
      placed.add(
          new Placed<>(
              entry,
              positions.isEmpty() ? next : positions.getFirst(),
              entry.storedSize(),
              positions));
      output.completedEntry(entry.source().size());
    }
    for (Patch patch : admitted.tables(placed, layout))
      output.write(patch.position(), ByteBuffer.wrap(patch.bytes()));
  }

  /**
   * One archive-set Content Sharing owner in the stable spool.
   *
   * @param id the owner's identity, its index in creation order
   * @param rawOffset where its raw bytes start, which later candidates are compared against
   * @param storedOffset where its stored bytes start; equal to {@code rawOffset} when stored
   * @param storedSize the stored length every part emits once
   * @param codec the encoding its stored bytes use
   */
  private record SetOwner(
      int id, long rawOffset, long storedOffset, long storedSize, PayloadCodec codec) {
    /** Returns a segment of {@code rawSize} raw bytes that this owner stores. */
    Admitted.Segment segment(long rawSize) {
      return new Admitted.Segment(rawSize, storedSize, codec, id);
    }
  }
}
