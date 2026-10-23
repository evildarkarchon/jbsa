package io.github.evildarkarchon.jbsa.internal.codec;

import static org.lwjgl.util.lz4.LZ4Frame.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Runtime.Provider;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.IOException;
import java.lang.foreign.Arena;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.Objects;
import org.lwjgl.PointerBuffer;
import org.lwjgl.util.lz4.LZ4;
import org.lwjgl.util.lz4.LZ4FDecompressOptions;
import org.lwjgl.util.lz4.LZ4FPreferences;
import org.lwjgl.util.lz4.LZ4HC;

/**
 * The native LZ4 adapter: the hidden LWJGL 3.4.3 binding of upstream LZ4 1.10.0, qualified on
 * Windows x64 (JBSA-CODEC-014). It is the only production class that references LWJGL, and nothing
 * reaches it until {@link Lz4Runtime} has found LWJGL loadable, so a process without the native
 * adapter never loads LWJGL.
 *
 * <p>Every call allocates its native buffers from its own arena after the caller's reservation of
 * the native cost {@link AdmittedCodec} publishes, and frees them on success, failure or
 * cancellation; nothing is shared between calls or retained globally.
 */
final class NativeLz4 implements Lz4Adapter {
  /** The one instance {@link Lz4Adapter#of} returns; creating it touches no LWJGL class. */
  static final NativeLz4 ADAPTER = new NativeLz4();

  private static final int WINDOW = 65536;

  private NativeLz4() {}

  /**
   * Returns the loaded native library's version number. {@link Lz4Runtime} calls this only after it
   * has found the LWJGL classes and native access, as the last native admission check.
   */
  static int versionNumber() {
    return LZ4.LZ4_versionNumber();
  }

  /** Has nothing left to load: the runtime pins this adapter only after LWJGL has loaded. */
  @Override
  public void admit() {
    // Native admission already ran in Lz4Runtime's selection, which is what pinned this adapter.
  }

  @Override
  public long encodeFrame(
      ByteSource source,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context,
      boolean bsaProfile)
      throws IOException {
    Objects.requireNonNull(lease, "lease");
    try (var state = new State(true, context)) {
      var preferences = state.preferences;
      preferences.compressionLevel(bsaProfile ? 12 : 9).autoFlush(true);
      if (bsaProfile) {
        preferences
            .frameInfo()
            .blockSizeID(LZ4F_max4MB)
            .blockMode(LZ4F_blockIndependent)
            .blockChecksumFlag(LZ4F_noBlockChecksum)
            .contentChecksumFlag(LZ4F_noContentChecksum)
            .contentSize(0)
            .dictID(0);
      } else {
        preferences
            .frameInfo()
            .blockSizeID(LZ4F_max64KB)
            .blockMode(LZ4F_blockLinked)
            .contentChecksumFlag(LZ4F_contentChecksumEnabled)
            .contentSize(decodedSize);
      }
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
        if (state.input.hasRemaining()) throw context.roleFailure("source.length-mismatch", null);
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

  @Override
  public Lz4Frame.Decoder frameDecoder(
      ByteSource source,
      long storedSize,
      long decodedSize,
      ResourceBudget.Lease lease,
      IoContext context)
      throws ArchiveException {
    Objects.requireNonNull(lease, "lease");
    return new Decoder(source, storedSize, decodedSize, context);
  }

  /** Per-content sequential native frame decoder with bounded direct input and output windows. */
  private static final class Decoder implements Lz4Frame.Decoder {
    private final ByteSource source;
    private final long storedSize, decodedSize;
    private final IoContext context;
    private final State state;
    private long supplied, produced;
    private boolean first = true, finished, closed;

    /** Allocates independent native state only after capability preflight and caller admission. */
    private Decoder(ByteSource source, long storedSize, long decodedSize, IoContext context)
        throws ArchiveException {
      this.source = source;
      this.storedSize = storedSize;
      this.decodedSize = decodedSize;
      this.context = context;
      state = new State(false, context);
      state.input.limit(0);
    }

    /** Returns one bounded decoded window and validates exact frame consumption at terminal EOF. */
    @Override
    public synchronized int read(ByteBuffer destination) throws IOException {
      if (closed) throw new java.nio.channels.ClosedChannelException();
      if (!destination.hasRemaining()) return 0;
      if (finished) return -1;
      try {
        while (true) {
          if (!state.input.hasRemaining()) {
            if (supplied == storedSize)
              throw invalid(context, "codec.invalid-data", decodedSize, produced);
            int count = (int) Math.min(WINDOW, storedSize - supplied);
            state.input.clear().limit(count);
            caller(() -> source.read(supplied, state.input), context);
            if (state.input.hasRemaining())
              throw context.roleFailure("source.length-mismatch", null);
            state.input.flip();
            if (first) {
              first = false;
              // Skippable frames are valid LZ4 transport but cannot represent an archive payload.
              if (count < 4 || state.input.order(ByteOrder.LITTLE_ENDIAN).getInt(0) != 0x184D2204)
                throw invalid(context, "codec.invalid-data", decodedSize, produced);
            }
            supplied += count;
          }
          int available = state.input.remaining();
          int capacity =
              (int)
                  Math.min(
                      Math.min(WINDOW, destination.remaining()),
                      decodedSize - produced + (produced == decodedSize ? 1 : 0));
          state.output.clear().limit(capacity);
          state.inputSize.put(0, available);
          state.outputSize.put(0, capacity);
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
                Provider.NATIVE,
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
          if (consumed < 0 || consumed > available || count < 0 || count > capacity)
            throw new IllegalStateException("Invalid provider counts");
          state.input.position(state.input.position() + consumed);
          if (count > decodedSize - produced)
            throw invalid(context, "codec.size-mismatch", decodedSize, produced + count);
          if (count > 0) {
            state.output.position(0).limit(count);
            destination.put(state.output);
            produced += count;
          }
          if (result == 0) {
            if (produced != decodedSize || supplied != storedSize || state.input.hasRemaining())
              throw invalid(context, "codec.size-mismatch", decodedSize, produced);
            finished = true;
          }
          if (count > 0) return count;
          if (finished) return -1;
          if (consumed == 0) throw invalid(context, "codec.invalid-data", decodedSize, produced);
        }
      } catch (ArchiveException failure) {
        throw failure;
      } catch (RuntimeException | LinkageError | AssertionError cause) {
        throw fault(context, "decode", decodedSize, produced, cause);
      }
    }

    /** Releases native frame state exactly once, including abandoned content reads. */
    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        state.close();
      }
    }
  }

  @Override
  public long encodeRaw(
      ByteSource source,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context,
      int compressionLevel)
      throws IOException {
    Objects.requireNonNull(lease, "lease");
    long bound = AdmittedCodec.rawLz4Bound(decodedSize);
    caller(checkpoint::check, context);
    // Explicit external HC state avoids an unaccounted provider allocation inside compression.
    try (Arena arena = Arena.ofConfined()) {
      ByteBuffer input =
          arena.allocate(Math.max(1, decodedSize), 8).asByteBuffer().limit((int) decodedSize);
      ByteBuffer output = arena.allocate(bound, 8).asByteBuffer();
      ByteBuffer state = arena.allocate(524288, 8).asByteBuffer();
      caller(() -> source.read(0, input), context);
      if (input.hasRemaining()) throw context.roleFailure("source.length-mismatch", null);
      input.flip();
      caller(checkpoint::check, context);
      int count;
      try {
        if (LZ4HC.LZ4_sizeofStateHC() > state.capacity())
          throw new IllegalStateException("HC state exceeds credit");
        count = LZ4HC.LZ4_compress_HC_extStateHC(state, input, output, compressionLevel);
      } catch (RuntimeException | LinkageError | AssertionError cause) {
        throw Lz4Runtime.failure(
            Provider.NATIVE,
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
            Provider.NATIVE,
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

  @Override
  public long decodeRaw(
      ByteSource source,
      long storedSize,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context)
      throws IOException {
    Objects.requireNonNull(lease, "lease");
    caller(checkpoint::check, context);
    try (Arena arena = Arena.ofConfined()) {
      ByteBuffer input =
          arena.allocate(Math.max(1, storedSize)).asByteBuffer().limit((int) storedSize);
      ByteBuffer output =
          arena.allocate(Math.max(1, decodedSize)).asByteBuffer().limit((int) decodedSize);
      caller(() -> source.read(0, input), context);
      if (input.hasRemaining()) throw context.roleFailure("source.length-mismatch", null);
      input.flip();
      caller(checkpoint::check, context);
      int count;
      try {
        count = LZ4.LZ4_decompress_safe(input, output);
      } catch (RuntimeException | LinkageError | AssertionError cause) {
        throw Lz4Runtime.failure(
            Provider.NATIVE,
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
            Provider.NATIVE,
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

  /** Normalizes provider return codes without exposing native messages or codes. */
  private static int checked(long result, boolean encode, IoContext context)
      throws ArchiveException {
    if (LZ4F_isError(result))
      throw Lz4Runtime.failure(
          Provider.NATIVE,
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
      ByteSink sink, long offset, ByteBuffer bytes, int count, IoContext context)
      throws IOException {
    if (count > 0) {
      bytes.position(0).limit(count);
      caller(() -> sink.write(offset, bytes), context);
    }
    return Math.addExact(offset, count);
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

  private static ArchiveException invalid(
      IoContext context, String id, long expected, long actual) {
    return Lz4Runtime.failure(
        Provider.NATIVE,
        context,
        FailureKind.FORMAT,
        id,
        "lz4-frame",
        "decode",
        expected,
        actual,
        null);
  }

  private static ArchiveException fault(
      IoContext context, String direction, long expected, long actual, Throwable cause) {
    return Lz4Runtime.failure(
        Provider.NATIVE,
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
    private final Arena arena;
    private ByteBuffer input, output;
    private PointerBuffer inputSize, outputSize;
    private LZ4FPreferences preferences;
    private LZ4FDecompressOptions decodeOptions;
    private long handle;
    private final boolean encode;

    /** Allocates only after the caller's complete worst-case reservation has succeeded. */
    State(boolean encode, IoContext context) throws ArchiveException {
      this.encode = encode;
      // Lazy content channels can move between consumer threads; synchronized decoder methods
      // provide serialization, while encoder state never leaves its invocation thread.
      arena = encode ? Arena.ofConfined() : Arena.ofShared();
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
