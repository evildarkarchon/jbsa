package io.github.evildarkarchon.jbsa.internal.io;

import static org.lwjgl.util.lz4.LZ4Frame.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.lz4.LZ4FDecompressOptions;
import org.lwjgl.util.lz4.LZ4FPreferences;

/** Release-pinned streaming frame adapter with independently owned, admitted native state. */
public final class Lz4Frame {
  public static final long HEAP_BYTES = 4096;
  public static final long ENCODE_NATIVE_BYTES = 2 * 1024 * 1024 + 3 * 65536;
  public static final long DECODE_NATIVE_BYTES = 9 * 1024 * 1024 + 3 * 65536;
  private static final int WINDOW = 65536;

  private Lz4Frame() {}

  /**
   * Encodes an exact positional source in bounded windows. Callbacks are borrowed synchronously;
   * all memory credits and native state are released on success, failure, or cancellation.
   */
  public static long encode(
      JdkZlib.ByteSource source,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    if (decodedSize < 0) throw new IllegalArgumentException("Negative decoded size");
    Lz4Runtime.preflight("lz4-frame", "encode", context);
    try (var lease = budget.reserve(HEAP_BYTES, ENCODE_NATIVE_BYTES, 0, 0);
        var state = new State(true, context)) {
      var preferences = state.preferences;
      preferences.compressionLevel(9).autoFlush(true);
      preferences
          .frameInfo()
          .blockSizeID(LZ4F_max64KB)
          .blockMode(LZ4F_blockLinked)
          .contentChecksumFlag(LZ4F_contentChecksumEnabled)
          .contentSize(decodedSize);
      caller(checkpoint::check, context);
      long written =
          emit(
              sink,
              0,
              state.output,
              checked(LZ4F_compressBegin(state.handle, state.output, preferences), true, context),
              context);
      for (long read = 0; read < decodedSize; ) {
        caller(checkpoint::check, context);
        int count = (int) Math.min(WINDOW, decodedSize - read);
        state.input.clear().limit(count);
        long offset = read;
        caller(() -> source.read(offset, state.input), context);
        if (state.input.hasRemaining())
          throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
        state.input.flip();
        state.output.clear();
        int encoded =
            checked(
                LZ4F_compressUpdate(state.handle, state.output, state.input, null), true, context);
        written = emit(sink, written, state.output, encoded, context);
        read += count;
      }
      caller(checkpoint::check, context);
      state.output.clear();
      return emit(
          sink,
          written,
          state.output,
          checked(LZ4F_compressEnd(state.handle, state.output, null), true, context),
          context);
    } catch (RuntimeException | LinkageError | AssertionError cause) {
      throw fault(context, "encode", decodedSize, -1, cause);
    }
  }

  /**
   * Decodes one ordinary frame, requiring exact compressed and decoded lengths. Native calls and
   * output publication are bounded; excess decoded bytes are detected without publishing them.
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
    if (storedSize < 0 || decodedSize < 0)
      throw new IllegalArgumentException("Negative codec size");
    Lz4Runtime.preflight("lz4-frame", "decode", context);
    try (var lease = budget.reserve(HEAP_BYTES, DECODE_NATIVE_BYTES, 0, 0);
        var state = new State(false, context)) {
      long supplied = 0, produced = 0;
      state.input.limit(0);
      while (true) {
        caller(checkpoint::check, context);
        if (!state.input.hasRemaining() && supplied < storedSize) {
          int count = (int) Math.min(WINDOW, storedSize - supplied);
          state.input.clear().limit(count);
          long offset = supplied;
          caller(() -> source.read(offset, state.input), context);
          if (state.input.hasRemaining())
            throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
          state.input.flip();
          // Skippable frames are valid LZ4 transport but cannot represent an archive payload.
          if (supplied == 0
              && (count < 4 || state.input.order(ByteOrder.LITTLE_ENDIAN).getInt(0) != 0x184D2204))
            throw invalid(context, "codec.invalid-data", decodedSize, produced);
          supplied += count;
        }
        int available = state.input.remaining();
        state
            .output
            .clear()
            .limit(
                (int) Math.min(WINDOW, decodedSize - produced + (produced == decodedSize ? 1 : 0)));
        state.inputSize.put(0, available);
        state.outputSize.put(0, state.output.remaining());
        long result =
            LZ4F_decompress(
                state.handle,
                state.output,
                state.outputSize,
                state.input,
                state.inputSize,
                state.decodeOptions);
        if (LZ4F_isError(result))
          throw Lz4Runtime.failure(
              context,
              FailureKind.FORMAT,
              "codec.invalid-data",
              "lz4-frame",
              "decode",
              decodedSize,
              produced,
              providerError(result));
        int consumed = Math.toIntExact(state.inputSize.get(0));
        int count = Math.toIntExact(state.outputSize.get(0));
        if (consumed < 0 || consumed > available || count < 0 || count > state.output.remaining())
          throw new IllegalStateException("Invalid provider counts");
        state.input.position(state.input.position() + consumed);
        if (count > decodedSize - produced)
          throw invalid(context, "codec.size-mismatch", decodedSize, produced + count);
        produced = emit(sink, produced, state.output, count, context);
        if (result == 0) {
          if (produced != decodedSize || supplied != storedSize || state.input.hasRemaining())
            throw invalid(context, "codec.size-mismatch", decodedSize, produced);
          return produced;
        }
        if (consumed == 0 && count == 0)
          throw invalid(context, "codec.invalid-data", decodedSize, produced);
      }
    } catch (RuntimeException | LinkageError | AssertionError cause) {
      throw fault(context, "decode", decodedSize, -1, cause);
    }
  }

  /** Normalizes provider return codes without exposing native messages or codes. */
  private static int checked(long result, boolean encode, IoContext context)
      throws ArchiveException {
    if (LZ4F_isError(result))
      throw Lz4Runtime.failure(
          context,
          FailureKind.INTERNAL,
          encode ? "codec.compression-failed" : "codec.provider-fault",
          "lz4-frame",
          encode ? "encode" : "decode",
          -1,
          -1,
          providerError(result));
    return Math.toIntExact(result);
  }

  /** Retains native evidence exclusively in the non-public failure cause. */
  private static Throwable providerError(long result) {
    return new IllegalStateException(
        "LZ4 frame error " + result + ": " + LZ4F_getErrorName(result));
  }

  /** Publishes only the valid prefix; positional arithmetic remains checked and wide. */
  private static long emit(
      JdkZlib.ByteSink sink, long offset, ByteBuffer bytes, int count, IoContext context)
      throws IOException {
    if (count > 0) {
      bytes.position(0).limit(count);
      caller(() -> sink.write(offset, bytes), context);
    }
    return Math.addExact(offset, count);
  }

  /** Distinguishes borrowed callback faults from codec provider failures. */
  private static void caller(Callback callback, IoContext context) throws IOException {
    try {
      callback.run();
    } catch (RuntimeException | AssertionError cause) {
      throw context.failure(FailureKind.INTERNAL, "operation.internal-failure", cause);
    }
  }

  @FunctionalInterface
  private interface Callback {
    /** Runs one synchronous borrowed callback. */
    void run() throws IOException;
  }

  private static ArchiveException invalid(
      IoContext context, String id, long expected, long actual) {
    return Lz4Runtime.failure(
        context, FailureKind.FORMAT, id, "lz4-frame", "decode", expected, actual, null);
  }

  private static ArchiveException fault(
      IoContext context, String direction, long expected, long actual, Throwable cause) {
    return Lz4Runtime.failure(
        context,
        FailureKind.INTERNAL,
        "codec.provider-fault",
        "lz4-frame",
        direction,
        expected,
        actual,
        cause);
  }

  /** Owns every explicit native allocation and frees partial construction in reverse order. */
  private static final class State implements AutoCloseable {
    private final Arena arena = Arena.ofConfined();
    private ByteBuffer input, output;
    private PointerBuffer inputSize, outputSize;
    private LZ4FPreferences preferences;
    private LZ4FDecompressOptions decodeOptions;
    private long handle;
    private final boolean encode;

    /** Allocates only after the caller's complete worst-case reservation has succeeded. */
    State(boolean encode, IoContext context) throws ArchiveException {
      this.encode = encode;
      try {
        input = arena.allocate(WINDOW, 8).asByteBuffer();
        output = arena.allocate(WINDOW * 2, 8).asByteBuffer();
        inputSize = PointerBuffer.create(arena.allocate(8, 8).asByteBuffer());
        outputSize = PointerBuffer.create(arena.allocate(8, 8).asByteBuffer());
        if (encode)
          preferences =
              new LZ4FPreferences(arena.allocate(LZ4FPreferences.SIZEOF, 8).asByteBuffer());
        else
          decodeOptions =
              new LZ4FDecompressOptions(
                  arena.allocate(LZ4FDecompressOptions.SIZEOF, 8).asByteBuffer());
        inputSize.put(0, 0);
        long result =
            encode
                ? LZ4F_createCompressionContext(inputSize, LZ4F_VERSION)
                : LZ4F_createDecompressionContext(inputSize, LZ4F_VERSION);
        handle = inputSize.get(0);
        checked(result, encode, context);
      } catch (Throwable cause) {
        close();
        throw cause;
      }
    }

    /** Ends one invocation; no state is shared with concurrent calls or retained globally. */
    @Override
    public void close() {
      if (handle != 0) {
        if (encode) LZ4F_freeCompressionContext(handle);
        else LZ4F_freeDecompressionContext(handle);
        handle = 0;
      }
      arena.close();
    }
  }
}
