package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import org.lwjgl.util.lz4.LZ4;
import org.lwjgl.util.lz4.LZ4HC;

/** Unsplittable raw-block profile with checked admission and independently owned native buffers. */
public final class Lz4Raw {
  private static final long MAX_INPUT = 0x7E000000L;
  private static final long DISPATCH_LIMIT = 16L * 1024 * 1024;
  public static final long ENCODE_NATIVE_BYTES =
      DISPATCH_LIMIT + DISPATCH_LIMIT + DISPATCH_LIMIT / 255 + 16 + 524288 + 32;
  public static final long DECODE_NATIVE_BYTES =
      DISPATCH_LIMIT + DISPATCH_LIMIT + DISPATCH_LIMIT / 255 + 16 + 16;

  private Lz4Raw() {}

  /**
   * Encodes one admitted raw HC block at the shared level 9 without fallback or splitting. The
   * complete source, worst-case output and HC state are reserved before allocation; callbacks
   * remain caller-owned.
   */
  public static long encode(
      JdkZlib.ByteSource source,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    return encode(source, decodedSize, sink, checkpoint, budget, context, 9);
  }

  /** Encodes one admitted raw HC block at the family-qualified compression level. */
  public static long encode(
      JdkZlib.ByteSource source,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context,
      int compressionLevel)
      throws IOException {
    if (compressionLevel < 1 || compressionLevel > 12)
      throw new IllegalArgumentException("Raw LZ4 HC level must be between 1 and 12");
    checkSize(decodedSize, DISPATCH_LIMIT, context);
    Lz4Runtime.preflight("raw-lz4", "encode", context);
    long bound = decodedSize + decodedSize / 255 + 16;
    caller(checkpoint::check, context);
    // Explicit external HC state avoids an unaccounted provider allocation inside compression.
    try (var lease = budget.reserve(4096, decodedSize + bound + 524288 + 32, 0, 0);
        Arena arena = Arena.ofConfined()) {
      ByteBuffer input =
          arena.allocate(Math.max(1, decodedSize), 8).asByteBuffer().limit((int) decodedSize);
      ByteBuffer output = arena.allocate(bound, 8).asByteBuffer();
      ByteBuffer state = arena.allocate(524288, 8).asByteBuffer();
      caller(() -> source.read(0, input), context);
      if (input.hasRemaining())
        throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      input.flip();
      caller(checkpoint::check, context);
      int count;
      try {
        if (LZ4HC.LZ4_sizeofStateHC() > state.capacity())
          throw new IllegalStateException("HC state exceeds credit");
        count = LZ4HC.LZ4_compress_HC_extStateHC(state, input, output, compressionLevel);
      } catch (RuntimeException | LinkageError | AssertionError cause) {
        throw Lz4Runtime.failure(
            context,
            FailureKind.INTERNAL,
            "codec.provider-fault",
            "raw-lz4",
            "encode",
            decodedSize,
            -1,
            cause);
      }
      if (count <= 0)
        throw Lz4Runtime.failure(
            context,
            FailureKind.INTERNAL,
            "codec.compression-failed",
            "raw-lz4",
            "encode",
            decodedSize,
            -1,
            null);
      caller(checkpoint::check, context);
      caller(() -> sink.write(0, output.limit(count)), context);
      return count;
    }
  }

  /**
   * Decodes one complete raw block after reserving its entire memory cost. Source and sink are
   * borrowed; the caller supplies a wire-bounded block and owns destination publication.
   */
  public static long decode(
      JdkZlib.ByteSource source,
      long storedSize,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    admitDecode(storedSize, decodedSize, context);
    Lz4Runtime.preflight("raw-lz4", "decode", context);
    caller(checkpoint::check, context);
    try (var lease = budget.reserve(4096, storedSize + decodedSize + 16, 0, 0);
        Arena arena = Arena.ofConfined()) {
      ByteBuffer input =
          arena.allocate(Math.max(1, storedSize)).asByteBuffer().limit((int) storedSize);
      ByteBuffer output =
          arena.allocate(Math.max(1, decodedSize)).asByteBuffer().limit((int) decodedSize);
      caller(() -> source.read(0, input), context);
      if (input.hasRemaining())
        throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      input.flip();
      caller(checkpoint::check, context);
      int count;
      try {
        count = LZ4.LZ4_decompress_safe(input, output);
      } catch (RuntimeException | LinkageError | AssertionError cause) {
        throw Lz4Runtime.failure(
            context,
            FailureKind.INTERNAL,
            "codec.provider-fault",
            "raw-lz4",
            "decode",
            decodedSize,
            -1,
            cause);
      }
      if (count < 0 || count != decodedSize)
        throw Lz4Runtime.failure(
            context,
            FailureKind.FORMAT,
            count < 0 ? "codec.invalid-data" : "codec.size-mismatch",
            "raw-lz4",
            "decode",
            decodedSize,
            count,
            null);
      caller(checkpoint::check, context);
      caller(() -> sink.write(0, output.limit(count)), context);
      return count;
    }
  }

  /** Rejects raw-block wire and dispatch sizes before any decoder allocation or output effect. */
  public static void admitDecode(long storedSize, long decodedSize, IoContext context)
      throws ArchiveException {
    // Incompressible blocks grow by the upstream compressBound allowance.
    checkSize(storedSize, DISPATCH_LIMIT + DISPATCH_LIMIT / 255 + 16, context);
    checkSize(decodedSize, DISPATCH_LIMIT, context);
  }

  /**
   * Rejects unsplittable provider and qualified-dispatch limits before narrowing or output effects.
   */
  private static void checkSize(long size, long dispatchLimit, IoContext context)
      throws ArchiveException {
    if (size < 0 || size > MAX_INPUT)
      throw context.failure(FailureKind.POLICY, "codec.size-limit", null);
    if (size > dispatchLimit)
      throw context.failure(FailureKind.POLICY, "codec.dispatch-limit", null);
  }

  /**
   * Preserves callback failure identity instead of attributing user code to the native provider.
   */
  private static void caller(Callback callback, IoContext context) throws IOException {
    try {
      callback.run();
    } catch (RuntimeException | AssertionError cause) {
      throw context.failure(FailureKind.INTERNAL, "operation.internal-failure", cause);
    }
  }

  /** A synchronous borrowed operation callback whose resources remain caller-owned. */
  @FunctionalInterface
  private interface Callback {
    /** Executes one callback without extending its lifetime. */
    void run() throws IOException;
  }
}
