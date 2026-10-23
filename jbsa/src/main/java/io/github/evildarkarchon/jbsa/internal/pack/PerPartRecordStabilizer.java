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
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalLong;

/**
 * Stabilization for the {@link Admitted.Emission.PerPartRecords} emission (versioned BSA): each
 * payload becomes one complete framed stored record in the stable spool, and each part replays its
 * records with per-part Content Sharing on those stored records.
 *
 * @param admitted the family state whose stored-size rule and tables apply
 */
record PerPartRecordStabilizer<K>(Admitted<K> admitted) implements StabilizedEmitter.Stabilizer<K> {
  /** Requires the family state. */
  PerPartRecordStabilizer {
    Objects.requireNonNull(admitted, "admitted");
  }

  /**
   * Writes every record and indexes it by entry identity for replay. Each part's replay shares only
   * records already placed in that part.
   */
  @Override
  public Stabilized<K> stabilize(Run run, List<Planned<K>> planned, SpillBuffer stable)
      throws IOException {
    List<Stable<K>> records = stabilizeRecords(run, planned, stable);
    // Parts hold the sized entries themselves, so identity locates each one's stabilized record.
    Map<Planned<K>, Long> offsets = new IdentityHashMap<>();
    List<Planned<K>> sized = new ArrayList<>(records.size());
    for (Stable<K> record : records) {
      offsets.put(record.planned(), record.offset());
      sized.add(record.planned());
    }
    boolean sharing = run.request().options().sharing();
    return new Stabilized<>(
        sized, (part, layout, output) -> replay(part, offsets, layout, output, stable, sharing));
  }

  /** Replay holds one window, or two while confirming a sharing candidate; reserve the peak. */
  @Override
  public long replayHeap(boolean sharing) {
    return (sharing ? 2L : 1L) * PayloadWindows.WINDOW_BYTES;
  }

  /**
   * Writes each entry's framed stored record into the operation's stable spool in output order and
   * measures it. Workers encode independently through the merged transform stage; the coordinator
   * alone orders records, so their offsets never depend on worker completion order.
   */
  private List<Stable<K>> stabilizeRecords(Run run, List<Planned<K>> planned, SpillBuffer stable)
      throws IOException {
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
              // Stored records keep the raw spool; encoded ones need only the encoder's output.
              return TransformStage.encoding(
                  run.codecs().admitted(entry.codec()), entry.source().size(), false, run.budget());
            });
    List<Stable<K>> records = new ArrayList<>(planned.size());
    Throwable pending = null;
    try {
      // Worker handle leases must not starve the coordinator's ordered stable spool.
      if (stage.parallel()) stable.reserveSpillHandle();
      try (var coordinator =
          reserveCoordinator(planned, stage.parallel(), run.codecs(), run.budget())) {
        for (int ordinal = 0; ordinal < planned.size(); ordinal++) {
          try (TransformStage.Input staged = stage.next()) {
            Planned<K> entry = planned.get(ordinal);
            IoContext processing = entry.source().processing(ordinal);
            OptionalLong location = processing.ordinal();
            Checkpoint checkpoint =
                () -> run.operation().checkpoint(OperationPhase.PROCESSING, location);
            long offset = stable.size();
            byte[] frame = entry.frame();
            if (frame.length > 0) stable.write(offset, ByteBuffer.wrap(frame));
            long start = offset + frame.length;
            ByteSink sink = (relative, bytes) -> stable.write(start + relative, bytes);
            if (staged == null)
              PackSources.consume(
                  entry.source(),
                  input ->
                      run.codecs()
                          .encode(
                              entry.codec(),
                              input,
                              entry.source().size(),
                              sink,
                              checkpoint,
                              run.budget(),
                              coordinator,
                              processing),
                  processing);
            else {
              boolean encoded = staged.hasEncoded();
              try (ReadableByteChannel input =
                  encoded ? staged.encodedChannel() : staged.channel()) {
                PayloadWindows.transfer(
                    input,
                    encoded ? staged.encodedSize() : entry.source().size(),
                    sink,
                    checkpoint,
                    processing);
              }
            }
            Planned<K> measured = entry.stabilized(stable.size() - offset);
            admitted.checkStored(measured, processing);
            records.add(new Stable<>(measured, offset));
          }
        }
      }
    } catch (Throwable failure) {
      pending = failure;
    }
    stage.finish(pending);
    return records;
  }

  /**
   * Reserves the coordinator's stabilization working set. With parallel workers it only copies
   * finished results through one window. Alone it also encodes, but never while a transfer window
   * is live, so the peak is the larger of the window and the heaviest codec.
   */
  private static ResourceBudget.Lease reserveCoordinator(
      List<? extends Planned<?>> planned, boolean parallel, Codecs codecs, ResourceBudget budget)
      throws ArchiveException {
    long heap = PayloadWindows.WINDOW_BYTES;
    long nativeBytes = 0;
    if (!parallel) {
      AdmittedCodec.Cost encoder = codecs.largestPrecharge(planned);
      heap = Math.max(heap, encoder.heapBytes());
      nativeBytes = encoder.nativeBytes();
    }
    return budget.reserve(heap, nativeBytes, 0, 0);
  }

  /**
   * Replays one stabilized part: each record either references the earliest byte-identical record
   * already placed in this part or is copied to the next payload position. The stored/compressed
   * discriminator keeps a stored record from aliasing a compressed one with identical framed bytes.
   */
  private void replay(
      List<Planned<K>> part,
      Map<Planned<K>, Long> offsets,
      Layout layout,
      PublicationTransaction.StagedFile output,
      SpillBuffer stable,
      boolean sharing)
      throws IOException {
    // Per-part scope: a part can reference only records already placed in this file.
    ContentSharing<Owner> index = new ContentSharing<>();
    List<Placed<K>> placed = new ArrayList<>(part.size());
    long next = layout.payloadStart();
    for (Planned<K> entry : part) {
      output.checkpoint();
      long source = offsets.get(entry);
      long size = entry.storedSize();
      Owner owner = null;
      String key = null;
      if (sharing) {
        key =
            ContentSharing.key(
                size,
                PayloadWindows.digest(stable::read, source, size, output::checkpoint),
                entry.codec() != PayloadCodec.STORED);
        owner =
            index.find(
                key,
                candidate ->
                    PayloadWindows.equal(
                        stable::read,
                        candidate.spoolOffset(),
                        stable::read,
                        source,
                        size,
                        output::checkpoint));
      }
      if (owner == null) {
        long position = next;
        PayloadWindows.replay(
            stable::read, source, size, (offset, bytes) -> output.write(position + offset, bytes));
        if (sharing) index.add(key, new Owner(source, position));
        placed.add(new Placed<>(entry, position, size));
        next = Math.addExact(next, size);
      } else placed.add(new Placed<>(entry, owner.position(), size));
      output.completedEntry(entry.source().size());
    }
    for (Patch patch : admitted.tables(placed, layout))
      output.write(patch.position(), ByteBuffer.wrap(patch.bytes()));
  }

  /**
   * One stabilized record: the entry with its exact stored size, and where its framed bytes start
   * in the stable spool.
   */
  private record Stable<K>(Planned<K> planned, long offset) {}

  /** A placed Content Sharing owner: its stable spool offset and its position in the part. */
  private record Owner(long spoolOffset, long position) {}
}
