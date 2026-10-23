package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.codec.ByteSink;
import io.github.evildarkarchon.jbsa.internal.codec.Checkpoint;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Planned;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Collections;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * The pipeline's private codec map. It is the only place that invokes a payload encoder. Stored,
 * zlib, the versioned BSA LZ4 frame, and Starfield raw LZ4 are routed here, each through the {@link
 * AdmittedCodec} the request admitted once, which owns its worst-case output bound and heap/native
 * working set. One conservative zlib bound, {@link AdmittedCodec#bound}, serves every family (D3).
 */
final class Codecs {
  /** Every codec this request admitted, keyed by the codec an adapter names. */
  private final Map<PayloadCodec, AdmittedCodec> admitted;

  private Codecs(Map<PayloadCodec, AdmittedCodec> admitted) {
    this.admitted = admitted;
  }

  /**
   * Admits every codec a request can select before any source is planned. Stored and zlib need only
   * the JDK, so they are always admitted and admitting them has no side effect. Each codec in
   * {@code required} is admitted once: both LZ4 profiles pin their provider here, before any side
   * effect: the native LWJGL adapter where it loads (the Windows x64 boundary), and the portable
   * lz4-java provider everywhere else (JBSA-CODEC-015). The pin holds for the rest of the process,
   * so an operation never switches providers mid-encode.
   *
   * @param required the adapter's {@link Admitted#requiredCodecs()}
   * @throws ArchiveException {@code CAPABILITY codec.unavailable} only when neither LZ4 provider
   *     can be admitted
   */
  static Codecs admit(Set<PayloadCodec> required, IoContext context) throws ArchiveException {
    var codecs = new EnumMap<PayloadCodec, AdmittedCodec>(PayloadCodec.class);
    codecs.put(PayloadCodec.STORED, AdmittedCodec.admit(PayloadCodec.STORED, "encode", context));
    codecs.put(PayloadCodec.ZLIB, AdmittedCodec.admit(PayloadCodec.ZLIB, "encode", context));
    for (PayloadCodec codec : required)
      if (!codecs.containsKey(codec))
        codecs.put(codec, AdmittedCodec.admit(codec, "encode", context));
    return new Codecs(Collections.unmodifiableMap(codecs));
  }

  /**
   * Returns the admitted form of a codec an adapter selected.
   *
   * @throws IllegalStateException when the adapter selects an LZ4 codec it did not declare in
   *     {@link Admitted#requiredCodecs()}, which is a JBSA bug
   */
  AdmittedCodec admitted(PayloadCodec codec) {
    AdmittedCodec found = admitted.get(codec);
    if (found == null)
      throw new IllegalStateException(codec + " was selected without being a required codec");
    return found;
  }

  /**
   * Returns the largest encode cost the coordinator's all-or-nothing reservation must cover for
   * {@code entries}: the largest heap and the largest native cost of any entry whose codec it
   * precharges. The coordinator runs one encoder at a time, so the reservation covers the heaviest
   * one, not their sum; the two maxima may come from different entries. Entries the coordinator
   * does not encode must be filtered out by the caller.
   *
   * @return the largest costs, or zero when no entry is precharged
   */
  AdmittedCodec.Cost largestPrecharge(List<? extends Planned<?>> entries) {
    long heap = 0;
    long nativeBytes = 0;
    for (Planned<?> entry : entries) {
      // Raw LZ4 is coordinator-encoded but reserved per call by encode, kept apart from this
      // reservation so its exhaustion outcomes stay where they were (#96); a codec encode
      // reserves per call must not be charged here a second time.
      if (!coordinatorPrecharges(entry.codec())) continue;
      AdmittedCodec.Cost cost = admitted(entry.codec()).encodeCost(entry.source().size());
      heap = Math.max(heap, cost.heapBytes());
      nativeBytes = Math.max(nativeBytes, cost.nativeBytes());
    }
    return new AdmittedCodec.Cost(heap, nativeBytes);
  }

  /**
   * Returns the codec credits one part's readback charges, under the codecs this request admitted,
   * so readback charges the same rule, under the same provider, as the read. The reader opens one
   * entry at a time, so one codec is live at once: the credit is the largest decode cost of any
   * declared codec, plus the decoded copy its reader holds of the part's largest whole block
   * ({@link PayloadCodec#decodedCopyBytes}, which only a whole-block codec holds).
   *
   * <p>Each codec is costed for the declared block sizes, as the reader costs each decode call: the
   * streaming codecs cost the same at any size, and raw LZ4 costs the part's largest actual block.
   * Charging raw LZ4's dispatch-limit worst case instead put about 32 MiB on the portable
   * provider's heap for any raw-LZ4 part, so readback failed whenever the heap ceiling was below
   * that, however small the part (#102).
   *
   * @throws IllegalStateException when a declared codec was not admitted, which is a JBSA bug
   */
  AdmittedCodec.Cost readbackCost(Admitted.Readback readback) {
    long heap = 0, nativeBytes = 0;
    for (PayloadCodec codec : readback.decodes()) {
      AdmittedCodec found = admitted.get(codec);
      if (found == null)
        throw new IllegalStateException(codec + " is read back without being admitted");
      AdmittedCodec.Cost decode =
          found.decodeCost(readback.largestStoredBlock(), readback.largestDecodedBlock());
      heap =
          Math.max(
              heap, decode.heapBytes() + codec.decodedCopyBytes(readback.largestDecodedBlock()));
      nativeBytes = Math.max(nativeBytes, decode.nativeBytes());
    }
    return new AdmittedCodec.Cost(heap, nativeBytes);
  }

  /**
   * Returns whether a transform-stage worker may run this encoder. Raw LZ4 stays on the
   * coordinator, as BA2 always kept it: each call holds a whole block plus its multi-MiB buffers
   * (native under the LWJGL provider, heap under the portable one), so concurrent workers would
   * multiply that peak outside the stage's headroom rule.
   */
  static boolean workerEncodes(PayloadCodec codec) {
    return codec != PayloadCodec.LZ4_RAW;
  }

  /**
   * Returns whether the coordinator's all-or-nothing reservation covers this codec's encode cost
   * ({@link #largestPrecharge}). Raw LZ4 is the exception: {@link #encode} reserves its cost
   * separately, per call, just before the encoder runs. Folding it into the up-front reservation
   * would change reported outcomes. That reservation is costed from the whole source, so an
   * oversized raw block, or a DX10 texture whose chunks are each encoded separately, would fail
   * {@code io.resource-capacity} up front instead of {@code codec.dispatch-limit} at its entry, or
   * instead of succeeding chunk by chunk; and a raw exhaustion would preempt failures of entries
   * stabilized before it.
   */
  private static boolean coordinatorPrecharges(PayloadCodec codec) {
    return codec != PayloadCodec.LZ4_RAW;
  }

  /**
   * Encodes one declared source with a codec this request admitted, as {@link
   * #encode(AdmittedCodec, ReadableByteChannel, long, ByteSink, Checkpoint, ResourceBudget,
   * ResourceBudget.Lease, IoContext)} does.
   */
  void encode(
      PayloadCodec codec,
      ReadableByteChannel input,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget budget,
      ResourceBudget.Lease admission,
      IoContext processing)
      throws IOException {
    encode(admitted(codec), input, decodedSize, sink, checkpoint, budget, admission, processing);
  }

  /**
   * Encodes one declared source into positional sink writes relative to the payload start. The same
   * call serves the coordinator and transform-stage workers; only the checkpoint and the admission
   * differ.
   *
   * @param codec the admitted codec, whose provider and profile identity the encoder uses
   * @param budget the operation budget raw LZ4's per-call reservation draws on
   * @param admission a reservation already covering the codec's encode cost, which the encoder
   *     borrows; for raw LZ4 it covers none of it, because this method reserves raw LZ4's cost
   *     against {@code budget} for the call (see {@link #coordinatorPrecharges})
   * @throws IOException a structured source-length, stall, codec, or sink failure
   * @throws NullPointerException when {@code admission} is null
   */
  static void encode(
      AdmittedCodec codec,
      ReadableByteChannel input,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget budget,
      ResourceBudget.Lease admission,
      IoContext processing)
      throws IOException {
    java.util.Objects.requireNonNull(admission, "admission");
    switch (codec.codec()) {
      case STORED -> PayloadWindows.transfer(input, decodedSize, sink, checkpoint, processing);
      case ZLIB -> codec.encode(input, decodedSize, sink, checkpoint, admission, processing);
      case BSA_LZ4_FRAME -> {
        if (input == null)
          throw processing.failure(FailureKind.SOURCE, "source.invalid-channel", null);
        long[] read = {0};
        codec.encode(
            (offset, bytes) -> {
              // The encoder reads its source once, front to back; anything else is a JBSA bug.
              if (offset != read[0])
                throw processing.failure(FailureKind.INTERNAL, "operation.internal-failure", null);
              readExact(input, bytes, checkpoint, processing);
              read[0] += bytes.position();
            },
            decodedSize,
            sink,
            checkpoint,
            admission,
            processing);
        requireEnd(input, checkpoint, processing);
      }
      case LZ4_RAW -> {
        if (input == null)
          throw processing.failure(FailureKind.SOURCE, "source.invalid-channel", null);
        // The reservation keeps the order the self-reserving encoder had: dispatch admission
        // first, so an oversized block reports codec.dispatch-limit rather than the capacity
        // failure its cost would raise, then cancellation, then the charge.
        AdmittedCodec.checkRawEncodeSize(decodedSize, processing);
        checkpoint.check();
        AdmittedCodec.Cost cost = codec.encodeCost(decodedSize);
        try (var lease = budget.reserve(cost.heapBytes(), cost.nativeBytes(), 0, 0)) {
          // The admitted raw encoder runs at Starfield's qualified HC level 12.
          codec.encode(
              (offset, bytes) -> {
                // Raw LZ4 reads its whole block once from offset zero; anything else is a JBSA
                // bug.
                if (offset != 0)
                  throw processing.failure(
                      FailureKind.INTERNAL, "operation.internal-failure", null);
                readExact(input, bytes, checkpoint, processing);
              },
              decodedSize,
              sink,
              checkpoint,
              lease,
              processing);
        }
        requireEnd(input, checkpoint, processing);
      }
    }
  }

  /** Fills one bounded codec window without letting a stalled generated channel spin forever. */
  private static void readExact(
      ReadableByteChannel input, ByteBuffer bytes, Checkpoint checkpoint, IoContext processing)
      throws IOException {
    int idle = 0;
    while (bytes.hasRemaining()) {
      checkpoint.check();
      int count = input.read(bytes);
      if (count < 0) throw processing.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      if (count == 0) {
        if (++idle > 16) throw processing.failure(FailureKind.SOURCE, "io.no-progress", null);
      } else idle = 0;
    }
  }

  /**
   * Probes once beyond the declared length. The LZ4 encoder pulls exact windows, so without this
   * probe it could not notice a generated source that yields more bytes than it declared.
   */
  private static void requireEnd(
      ReadableByteChannel input, Checkpoint checkpoint, IoContext processing) throws IOException {
    ByteBuffer probe = ByteBuffer.allocate(1);
    int idle = 0;
    while (true) {
      checkpoint.check();
      int count = input.read(probe);
      if (count < 0) return;
      if (count > 0) throw processing.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      if (++idle > 16) throw processing.failure(FailureKind.SOURCE, "io.no-progress", null);
    }
  }
}
