package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.io.*;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Layout;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Patch;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Placed;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Planned;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;

/**
 * The {@link Admitted.Emission.Streamed} Emission (TES3): each part reads its sources while it
 * stages, after every part's layout and the complete target set were admitted during preflight.
 * Content Sharing is per part, on the raw bytes of stored, unframed payloads.
 */
final class StreamedEmitter {
  private StreamedEmitter() {}

  /**
   * Streams each part and publishes the archive set. The order is fixed: the stored, unframed entry
   * check, split, every part's layout and its metadata charge, target preflight for the parts
   * formed, the coordinator window reservation, the writers, then a streamed archive-set plan
   * published inside that reservation. Every planned entry must be stored and unframed, which is
   * checked once before splitting.
   *
   * @param planner the pack's split planner, for this Emission's payload cost
   * @param planned every planned entry in output order
   * @return the artifacts publication settled and the staged Archive Parts
   * @throws IllegalStateException when the adapter planned a compressed or framed entry, which the
   *     operation template reports as INTERNAL {@code operation.internal-failure}
   * @throws IOException a failure before publication; publication accepts its own failures
   */
  static <K> OperationSession.Published emit(
      Run run, Admitted<K> admitted, SplitPlanner<K> planner, List<Planned<K>> planned)
      throws IOException {
    requireStoredUnframed(planned);
    PackRequest request = run.request();
    List<List<Planned<K>>> parts = planner.split(planned);
    List<Layout> layouts = PartStaging.layouts(admitted, parts, run);
    PublicationTransaction.preflightArchiveTargets(
        request.destination(),
        parts.size(),
        request.targetPolicy(),
        request.resourceLimits(),
        run.context());
    // D8: admit the coordinator windows before any writer allocates them.
    try (var windows = reserveWindows(request.options().sharing(), run.budget())) {
      var writers = new ArrayList<PublicationTransaction.Writer>();
      var completed = new ArrayList<OperationReport.ArchivePart>();
      long nextOrdinal = 0;
      for (int index = 0; index < parts.size(); index++) {
        List<Planned<K>> part = parts.get(index);
        Layout layout = layouts.get(index);
        long firstOrdinal = nextOrdinal;
        int number = index + 1;
        writers.add(
            PartStaging.writer(
                output -> {
                  stream(admitted, part, layout, output, firstOrdinal, run, windows);
                  completed.add(PartStaging.archivePart(request, number, output, part));
                },
                admitted.readback(part, layout),
                run));
        nextOrdinal += part.size();
      }
      // Streamed parts read and close their own sources, so the plan holds no source to close.
      var plan =
          PublicationTransaction.Plan.ArchiveSet.streamed(
              request.destination(), writers, request.targetPolicy());
      return new OperationSession.Published(
          PublicationTransaction.publish(run.operation(), run.budget(), plan), completed);
    }
  }

  /**
   * Rejects a streamed plan holding anything but stored, unframed payloads. Streamed placement
   * neither writes frames nor encodes, and no Content Sharing rule is defined for a compressed
   * streamed entry, so such a plan is a JBSA bug. It is checked before any source is read.
   *
   * @throws IllegalStateException for the first entry with a codec other than {@link
   *     PayloadCodec#STORED} or a non-empty frame
   */
  private static void requireStoredUnframed(List<? extends Planned<?>> planned) {
    for (Planned<?> entry : planned) {
      if (entry.codec() != PayloadCodec.STORED)
        throw new IllegalStateException(
            "Streamed emission planned " + entry.codec() + " for " + entry.source().displayName());
      if (entry.frame().length > 0)
        throw new IllegalStateException(
            "Streamed emission planned a frame for " + entry.source().displayName());
    }
  }

  /**
   * Reserves the peak coordinator windows a streamed part holds at once: two while comparing a
   * sharing candidate, otherwise one transfer window. Streamed payloads are stored, and the stored
   * codec's encode cost is zero, so no encoder working set is added.
   */
  private static ResourceBudget.Lease reserveWindows(boolean sharing, ResourceBudget budget)
      throws ArchiveException {
    return budget.reserve(PayloadWindows.perPartSharingHeap(sharing), 0, 0, 0);
  }

  /**
   * Streams one part: places each payload after the layout's payload start in output order, then
   * applies the adapter's table patches. Worker results are consumed in Logical Plan Order.
   */
  private static <K> void stream(
      Admitted<K> admitted,
      List<Planned<K>> part,
      Layout layout,
      PublicationTransaction.StagedFile output,
      long firstOrdinal,
      Run run,
      ResourceBudget.Lease windows)
      throws IOException {
    // Per-part scope: a streamed part can reference only copies already staged in this file.
    ContentSharing<Long> sharing = new ContentSharing<>();
    List<Placed<K>> placed = new ArrayList<>(part.size());
    long next = layout.payloadStart();
    TransformStage stage =
        new TransformStage(
            run.operation(),
            part.stream().map(Planned::source).toList(),
            run.workers(),
            run.budget(),
            run.context(),
            firstOrdinal,
            ignored -> null);
    Throwable pending = null;
    try {
      for (int index = 0; index < part.size(); index++) {
        try (TransformStage.Input staged = stage.next()) {
          Planned<K> entry = part.get(index);
          IoContext processing = entry.source().processing(firstOrdinal + index);
          Placed<K> result =
              run.request().options().sharing()
                  ? placeShared(entry, staged, next, output, sharing, processing, run, windows)
                  : place(entry, staged, next, output, processing, run, windows);
          // Shared copies always lie before `next`; only a zero-length match can equal it, and
          // advancing by its zero size is a no-op, so equality identifies a fresh placement.
          if (result.offset() == next) next = Math.addExact(next, result.storedSize());
          placed.add(result);
          output.completedEntry(entry.source().size());
        }
      }
    } catch (Throwable failure) {
      pending = failure;
    }
    stage.finish(pending);
    for (Patch patch : admitted.tables(placed, layout))
      output.write(patch.position(), ByteBuffer.wrap(patch.bytes()));
  }

  /**
   * Writes one unshared stored payload directly at the next payload position. The bytes still pass
   * through the codec map's stored path, so its declared-length and end checks fail exactly as
   * before.
   */
  private static <K> Placed<K> place(
      Planned<K> entry,
      TransformStage.Input staged,
      long position,
      PublicationTransaction.StagedFile output,
      IoContext processing,
      Run run,
      ResourceBudget.Lease windows)
      throws IOException {
    long[] extent = {0};
    consume(
        entry.source(),
        staged,
        input ->
            run.codecs()
                .encode(
                    PayloadCodec.STORED,
                    input,
                    entry.source().size(),
                    (offset, bytes) -> {
                      extent[0] = Math.max(extent[0], offset + bytes.remaining());
                      output.write(position + offset, bytes);
                    },
                    output::checkpoint,
                    run.budget(),
                    windows,
                    processing),
        processing);
    return new Placed<>(entry, position, extent[0]);
  }

  /**
   * Copies one stored payload into part-owned scratch while digesting its raw bytes, then either
   * references the earliest byte-identical copy in this part or replays it at the next payload
   * position. A streamed payload is stored and unframed, so its raw bytes are its stored record.
   */
  private static <K> Placed<K> placeShared(
      Planned<K> entry,
      TransformStage.Input staged,
      long position,
      PublicationTransaction.StagedFile output,
      ContentSharing<Long> sharing,
      IoContext processing,
      Run run,
      ResourceBudget.Lease windows)
      throws IOException {
    SpillBuffer scratch = output.scratch();
    IOException primary = null;
    try {
      MessageDigest digest = ContentSharing.sha256();
      // The stored path keeps the declared-length and end checks the transfer has always had.
      consume(
          entry.source(),
          staged,
          input ->
              run.codecs()
                  .encode(
                      PayloadCodec.STORED,
                      input,
                      entry.source().size(),
                      (offset, bytes) -> {
                        digest.update(bytes.duplicate());
                        scratch.write(offset, bytes);
                      },
                      output::checkpoint,
                      run.budget(),
                      windows,
                      processing),
          processing);
      scratch.seal();
      long size = scratch.size();
      // Every streamed copy is stored, so no stored/compressed discriminator is needed.
      String key = ContentSharing.key(size, digest.digest(), false);
      Long match =
          sharing.find(
              key,
              candidate ->
                  PayloadWindows.equal(
                      scratch::read, 0, output::read, candidate, size, output::checkpoint));
      if (match != null) return new Placed<>(entry, match, size);
      PayloadWindows.replay(
          scratch::read, 0, size, (offset, bytes) -> output.write(position + offset, bytes));
      sharing.add(key, position);
      return new Placed<>(entry, position, size);
    } catch (IOException failure) {
      primary = failure;
      throw failure;
    } finally {
      try {
        scratch.close();
      } catch (ArchiveException cleanup) {
        if (primary == null) throw cleanup;
        // Keep cleanup as structured secondary evidence, including any exact residual path. It
        // goes straight into the session and the primary is rethrown unchanged, since the primary
        // may be the session's stop signal, which carries no evidence to fold the cleanup into.
        run.operation().accept(cleanup);
        throw primary;
      }
    }
  }

  /** Uses a staged worker result or the established direct stream for one logical source. */
  private static void consume(
      PackSources.Entry entry,
      TransformStage.Input staged,
      PackSources.Reader reader,
      IoContext processing)
      throws IOException {
    if (staged == null) PackSources.consume(entry, reader, processing);
    else {
      try (ReadableByteChannel input = staged.channel()) {
        reader.read(input);
      }
    }
  }
}
