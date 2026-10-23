package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.codec.BsaLz4Frame;
import io.github.evildarkarchon.jbsa.internal.codec.ByteSink;
import io.github.evildarkarchon.jbsa.internal.codec.Checkpoint;
import io.github.evildarkarchon.jbsa.internal.codec.JdkZlib;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Raw;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Collections;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/**
 * The pipeline's private codec map. It is the only place that invokes a payload encoder. Stored,
 * zlib, the versioned BSA LZ4 frame, and Starfield raw LZ4 are routed here, each through the {@link
 * AdmittedCodec} the request admitted once, which owns its worst-case output bound and heap/native
 * working set. One conservative zlib bound serves every family (D3).
 */
final class Codecs {
  /** The raw LZ4 HC level Starfield BA2 output is qualified against. */
  private static final int LZ4_RAW_LEVEL = 12;

  /** Every codec this request admitted, keyed by the codec an adapter names. */
  private final Map<PayloadCodec, AdmittedCodec> admitted;

  private Codecs(Map<PayloadCodec, AdmittedCodec> admitted) {
    this.admitted = admitted;
  }

  /**
   * One encoding's admission costs.
   *
   * @param bound the worst-case encoded size
   * @param heap heap the encoder allocates beyond the caller's own transfer window
   * @param nativeBytes native memory the encoder allocates
   */
  record Cost(long bound, long heap, long nativeBytes) {}

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

  /** Returns every admitted codec, for declarations such as readback that cost a decode. */
  Map<PayloadCodec, AdmittedCodec> all() {
    return admitted;
  }

  /**
   * Returns the admission costs of encoding {@code decodedSize} bytes with an admitted codec.
   *
   * @throws ArithmeticException when the bound does not fit a signed long
   */
  Cost cost(PayloadCodec codec, long decodedSize) {
    return cost(admitted(codec), decodedSize);
  }

  /**
   * Returns the admission costs of encoding {@code decodedSize} bytes with {@code codec}: its bound
   * and encode cost, both owned by the admitted codec.
   *
   * @throws ArithmeticException when the bound does not fit a signed long
   */
  static Cost cost(AdmittedCodec codec, long decodedSize) {
    long bound = codec.bound(decodedSize);
    // Lz4Raw admits its whole block, output bound, and HC state against the operation budget on
    // every call, so the map charges nothing up front; a precharge would count them twice.
    if (codec.codec() == PayloadCodec.LZ4_RAW) return new Cost(bound, 0, 0);
    AdmittedCodec.Cost encode = codec.encodeCost(decodedSize);
    return new Cost(bound, encode.heapBytes(), encode.nativeBytes());
  }

  /**
   * Returns whether a transform-stage worker may run this encoder. Raw LZ4 stays on the
   * coordinator, as BA2 always kept it: each call admits a whole block plus its multi-MiB buffers
   * (native under the LWJGL provider, heap under the portable one) against the operation budget
   * itself, so concurrent workers would multiply that peak outside the stage's headroom rule.
   */
  static boolean workerEncodes(PayloadCodec codec) {
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
   * @param budget the operation budget a borrowing encoder settles against
   * @param admission a reservation already covering {@link #cost}'s heap and native bytes; only the
   *     BSA LZ4 frame borrows it, and it may be null for the other codecs. Raw LZ4 admits its own
   *     working set against {@code budget} instead
   * @throws IOException a structured source-length, stall, codec, or sink failure
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
    switch (codec.codec()) {
      case STORED -> PayloadWindows.transfer(input, decodedSize, sink, checkpoint, processing);
      case ZLIB -> JdkZlib.encode(codec, input, decodedSize, sink, checkpoint, processing);
      case BSA_LZ4_FRAME -> {
        if (input == null)
          throw processing.failure(FailureKind.SOURCE, "source.invalid-channel", null);
        long[] read = {0};
        BsaLz4Frame.encodePrecharged(
            codec,
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
            budget,
            admission,
            processing);
        requireEnd(input, checkpoint, processing);
      }
      case LZ4_RAW -> {
        if (input == null)
          throw processing.failure(FailureKind.SOURCE, "source.invalid-channel", null);
        Lz4Raw.encode(
            (offset, bytes) -> {
              // Raw LZ4 reads its whole block once from offset zero; anything else is a JBSA bug.
              if (offset != 0)
                throw processing.failure(FailureKind.INTERNAL, "operation.internal-failure", null);
              readExact(input, bytes, checkpoint, processing);
            },
            decodedSize,
            sink,
            checkpoint,
            budget,
            processing,
            LZ4_RAW_LEVEL);
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
