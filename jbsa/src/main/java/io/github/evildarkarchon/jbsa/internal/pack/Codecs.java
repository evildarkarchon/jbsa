package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.internal.io.BsaLz4Frame;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.JdkZlib;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Frame;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Raw;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Runtime;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;

/**
 * The pipeline's private codec map. It is the only place that invokes a payload encoder and knows
 * its worst-case output bound and heap/native working set. Stored, zlib, the versioned BSA LZ4
 * frame, and Starfield raw LZ4 are routed here. One conservative zlib bound serves every family
 * (D3).
 */
final class Codecs {
  /** The versioned BSA 0x69 profile auto-flushes one LZ4 block per source window of this size. */
  private static final long LZ4_BLOCK_BYTES = 65536;

  /** The raw LZ4 HC level Starfield BA2 output is qualified against. */
  private static final int LZ4_RAW_LEVEL = 12;

  private Codecs() {}

  /**
   * One encoding's admission costs.
   *
   * @param bound the worst-case encoded size
   * @param heap heap the encoder allocates beyond the caller's own transfer window
   * @param nativeBytes native memory the encoder allocates
   */
  record Cost(long bound, long heap, long nativeBytes) {}

  /**
   * Returns the admission costs of encoding {@code decodedSize} bytes with {@code codec}.
   *
   * @throws ArithmeticException when the bound does not fit a signed long
   */
  static Cost cost(Codec codec, long decodedSize) {
    return switch (codec) {
      case STORED -> new Cost(decodedSize, 0, 0);
      case ZLIB ->
          new Cost(zlibBound(decodedSize), JdkZlib.ENCODE_HEAP_BYTES, JdkZlib.ENCODE_NATIVE_BYTES);
      case BSA_LZ4_FRAME -> {
        // Preflight has already pinned the provider, so this charges the encoder that will run.
        Lz4Runtime.Provider provider = Lz4Runtime.selected();
        yield new Cost(
            bsaLz4Bound(decodedSize),
            Lz4Frame.encodeHeapBytes(provider),
            Lz4Frame.encodeNativeBytes(provider));
      }
      // Lz4Raw admits its whole block, output bound, and HC state against the operation budget on
      // every call, so the map charges nothing up front; a precharge would count them twice.
      case LZ4_RAW -> new Cost(lz4RawBound(decodedSize), 0, 0);
    };
  }

  /**
   * Admits one codec's runtime capability before any source is planned. Stored and zlib need only
   * the JDK. Both LZ4 profiles pin their provider here, before any side effect: the native LWJGL
   * adapter where it loads (the Windows x64 boundary), and the portable lz4-java provider
   * everywhere else (JBSA-CODEC-015). The pin holds for the rest of the process, so an operation
   * never switches providers mid-encode.
   *
   * @throws ArchiveException {@code CAPABILITY codec.unavailable} only when neither LZ4 provider
   *     can be admitted
   */
  static void preflight(Codec codec, IoContext context) throws ArchiveException {
    switch (codec) {
      case STORED, ZLIB -> {
        // JDK-only encoders have no provider to admit.
      }
      case BSA_LZ4_FRAME -> BsaLz4Frame.preflight("encode", context);
      case LZ4_RAW -> Lz4Runtime.preflight("raw-lz4", "encode", context);
    }
  }

  /**
   * Returns whether a transform-stage worker may run this encoder. Raw LZ4 stays on the
   * coordinator, as BA2 always kept it: each call admits a whole block plus its multi-MiB buffers
   * (native under the LWJGL provider, heap under the portable one) against the operation budget
   * itself, so concurrent workers would multiply that peak outside the stage's headroom rule.
   */
  static boolean workerEncodes(Codec codec) {
    return codec != Codec.LZ4_RAW;
  }

  /** One raw LZ4 block's worst-case compressed size. */
  static long lz4RawBound(long size) {
    return Math.addExact(size, size / 255 + 16);
  }

  /**
   * zlib's conservative fixed-block bound, which covers any default-level source plus its wrapper.
   * It is the one zlib bound for every family (D3).
   */
  static long zlibBound(long size) {
    return Math.addExact(size, Math.addExact((size >> 3) + (size >> 8) + (size >> 9), 22));
  }

  /**
   * The pinned BSA LZ4 profile auto-flushes 64 KiB chunks without block checksums. Each chunk adds
   * at most one four-byte block header; the 64-byte allowance covers the frame header and end mark.
   */
  static long bsaLz4Bound(long size) {
    return Math.addExact(
        size, Math.addExact(4L * ((size + LZ4_BLOCK_BYTES - 1) / LZ4_BLOCK_BYTES), 64));
  }

  /**
   * Encodes one declared source into positional sink writes relative to the payload start. The same
   * call serves the coordinator and transform-stage workers; only the checkpoint and the admission
   * differ.
   *
   * @param budget the operation budget a borrowing encoder settles against
   * @param admission a reservation already covering {@link #cost}'s heap and native bytes; only the
   *     BSA LZ4 frame borrows it, and it may be null for the other codecs. Raw LZ4 admits its own
   *     working set against {@code budget} instead
   * @throws IOException a structured source-length, stall, codec, or sink failure
   */
  static void encode(
      Codec codec,
      ReadableByteChannel input,
      long decodedSize,
      PayloadWindows.Sink sink,
      PayloadWindows.Checkpoint checkpoint,
      ResourceBudget budget,
      ResourceBudget.Lease admission,
      IoContext processing)
      throws IOException {
    switch (codec) {
      case STORED -> PayloadWindows.transfer(input, decodedSize, sink, checkpoint, processing);
      case ZLIB -> JdkZlib.encode(input, decodedSize, sink::write, checkpoint::check, processing);
      case BSA_LZ4_FRAME -> {
        if (input == null)
          throw processing.failure(FailureKind.SOURCE, "source.invalid-channel", null);
        long[] read = {0};
        BsaLz4Frame.encodePrecharged(
            (offset, bytes) -> {
              // The encoder reads its source once, front to back; anything else is a JBSA bug.
              if (offset != read[0])
                throw processing.failure(FailureKind.INTERNAL, "operation.internal-failure", null);
              readExact(input, bytes, checkpoint, processing);
              read[0] += bytes.position();
            },
            decodedSize,
            sink::write,
            checkpoint::check,
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
            sink::write,
            checkpoint::check,
            budget,
            processing,
            LZ4_RAW_LEVEL);
        requireEnd(input, checkpoint, processing);
      }
    }
  }

  /** Fills one bounded codec window without letting a stalled generated channel spin forever. */
  private static void readExact(
      ReadableByteChannel input,
      ByteBuffer bytes,
      PayloadWindows.Checkpoint checkpoint,
      IoContext processing)
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
      ReadableByteChannel input, PayloadWindows.Checkpoint checkpoint, IoContext processing)
      throws IOException {
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
