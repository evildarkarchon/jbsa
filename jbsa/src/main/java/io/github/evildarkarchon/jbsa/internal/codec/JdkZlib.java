package io.github.evildarkarchon.jbsa.internal.codec;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.*;
import java.util.zip.*;

/** Release-pinned RFC 1950 streaming adapter; every call owns independent bounded codec state. */
public final class JdkZlib {
  public static final long ENCODE_HEAP_BYTES = 132096;
  public static final long ENCODE_NATIVE_BYTES = 524288;
  public static final long DECODE_HEAP_BYTES = 66560;
  public static final long DECODE_NATIVE_BYTES = 65536;
  private static final int WINDOW = 65536;

  private JdkZlib() {}

  /**
   * Encodes exactly the declared source length at level 9, default strategy, without fallback. The
   * caller reserves ENCODE resource credits before invocation and owns source/sink cleanup.
   *
   * @param admitted the admitted zlib codec, whose profile identity a codec failure carries
   * @param lease the caller's reservation of those credits, borrowed and never closed here
   * @throws NullPointerException when {@code lease} is null
   */
  public static long encode(
      AdmittedCodec admitted,
      ReadableByteChannel source,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context)
      throws IOException {
    Objects.requireNonNull(lease, "lease");
    requireZlib(admitted);
    if (source == null) throw context.roleFailure("source.invalid-channel", null);
    byte[] input = new byte[WINDOW];
    byte[] output = new byte[WINDOW];
    try (Deflater codec = new Deflater(9, false)) {
      codec.setStrategy(Deflater.DEFAULT_STRATEGY);
      long read = 0, written = 0;
      int idle = 0;
      while (!codec.finished()) {
        invokeCaller(
            () -> {
              checkpoint.check();
              return 0;
            },
            context);
        if (codec.needsInput() && !codec.finished()) {
          int sourceWindow =
              (int) Math.min(WINDOW, decodedSize - read + (decodedSize == read ? 1 : 0));
          int count =
              invokeCaller(() -> source.read(ByteBuffer.wrap(input, 0, sourceWindow)), context);
          if (count < 0) {
            if (read != decodedSize) throw context.roleFailure("source.length-mismatch", null);
            codec.finish();
          } else if (count == 0) {
            if (++idle > 16) throw context.roleFailure("io.no-progress", null);
            continue;
          } else {
            idle = 0;
            if (count > decodedSize - read)
              throw context.roleFailure("source.length-mismatch", null);
            read += count;
            codec.setInput(input, 0, count);
          }
        }
        int count = codec.deflate(output);
        if (count > 0) {
          long outputPosition = written;
          invokeCaller(
              () -> {
                sink.write(outputPosition, ByteBuffer.wrap(output, 0, count));
                return 0;
              },
              context);
          written = Math.addExact(written, count);
        } else if (!codec.needsInput() && !codec.finished()) {
          throw failure(
              admitted,
              context,
              FailureKind.INTERNAL,
              "codec.compression-failed",
              "encode",
              decodedSize,
              read,
              null);
        }
      }
      return written;
    } catch (RuntimeException cause) {
      throw failure(
          admitted,
          context,
          FailureKind.INTERNAL,
          "codec.provider-fault",
          "encode",
          decodedSize,
          -1,
          cause);
    }
  }

  /**
   * Creates a decoder after its owner reserves DECODE resource credits; close releases zlib state.
   *
   * @param admitted the admitted zlib codec, whose profile identity a codec failure carries
   * @param lease the owner's reservation of those credits, which outlives the decoder and is never
   *     closed here
   * @throws NullPointerException when {@code lease} is null
   */
  public static Decoder decoder(
      AdmittedCodec admitted,
      ByteSource source,
      long storedSize,
      long decodedSize,
      ResourceBudget.Lease lease,
      IoContext context) {
    Objects.requireNonNull(lease, "lease");
    requireZlib(admitted);
    return new Decoder(admitted, source, storedSize, decodedSize, context);
  }

  /** Keeps user channels and operation callbacks outside provider-fault classification. */
  private static int invokeCaller(CallerCall call, IoContext context) throws IOException {
    try {
      return call.run();
    } catch (RuntimeException | AssertionError cause) {
      throw context.failure(FailureKind.INTERNAL, "operation.internal-failure", cause);
    }
  }

  /**
   * An invocation whose unchecked failure belongs to the operation rather than the codec provider.
   */
  @FunctionalInterface
  private interface CallerCall {
    /** Runs one borrowed operation callback without transferring resource ownership. */
    int run() throws IOException;
  }

  /** Per-content sequential stream; synchronized close cannot free native state during inflate. */
  public static final class Decoder implements PayloadDecoder {
    private final AdmittedCodec admitted;
    private final ByteSource source;
    private final long storedSize, decodedSize;
    private final IoContext context;
    private final Inflater codec = new Inflater(false);
    private final byte[] input = new byte[WINDOW];
    private long supplied, produced;
    private boolean closed;

    private Decoder(
        AdmittedCodec admitted,
        ByteSource source,
        long storedSize,
        long decodedSize,
        IoContext context) {
      this.admitted = admitted;
      this.source = source;
      this.storedSize = storedSize;
      this.decodedSize = decodedSize;
      this.context = context;
    }

    /**
     * Returns a bounded decoded window; EOF establishes exact size and complete stream consumption.
     */
    public synchronized int read(ByteBuffer destination) throws IOException {
      if (closed) throw new java.nio.channels.ClosedChannelException();
      if (!destination.hasRemaining()) return 0;
      try {
        while (true) {
          if (codec.finished()) {
            if (produced != decodedSize || codec.getBytesRead() != storedSize)
              throw invalid("codec.size-mismatch", null);
            return -1;
          }
          if (codec.needsDictionary()) throw invalid("codec.invalid-data", null);
          if (codec.needsInput()) {
            if (supplied == storedSize) throw invalid("codec.invalid-data", null);
            int count = (int) Math.min(WINDOW, storedSize - supplied);
            source.read(supplied, ByteBuffer.wrap(input, 0, count));
            supplied += count;
            codec.setInput(input, 0, count);
          }
          // A one-byte probe after the declared output catches over-expansion without publishing
          // it.
          boolean probe = produced == decodedSize;
          ByteBuffer output =
              probe
                  ? ByteBuffer.allocate(1)
                  : destination
                      .slice()
                      .limit(
                          (int)
                              Math.min(
                                  Math.min(destination.remaining(), WINDOW),
                                  decodedSize - produced));
          long before = codec.getBytesRead();
          int count = codec.inflate(output);
          produced += count;
          if (produced > decodedSize) throw invalid("codec.size-mismatch", null);
          if (count > 0) {
            destination.position(destination.position() + count);
            return count;
          }
          if (codec.getBytesRead() == before
              && !codec.finished()
              && !codec.needsInput()
              && !codec.needsDictionary()) throw invalid("codec.invalid-data", null);
        }
      } catch (DataFormatException cause) {
        throw invalid("codec.invalid-data", cause);
      } catch (RuntimeException cause) {
        throw failure(
            admitted,
            context,
            FailureKind.INTERNAL,
            "codec.provider-fault",
            "decode",
            decodedSize,
            produced,
            cause);
      }
    }

    /** Creates stable format evidence without including a provider message in public values. */
    private ArchiveException invalid(String identifier, Throwable cause) {
      return failure(
          admitted,
          context,
          FailureKind.FORMAT,
          identifier,
          "decode",
          decodedSize,
          produced,
          cause);
    }

    /** Releases native state exactly once, including abandoned content and parent closure. */
    @Override
    public synchronized void close() {
      if (!closed) {
        closed = true;
        codec.end();
      }
    }
  }

  /** Rejects a codec admission that is not zlib's, before any codec state exists. */
  private static void requireZlib(AdmittedCodec admitted) {
    if (admitted.codec() != PayloadCodec.ZLIB)
      throw new IllegalArgumentException("zlib needs the admitted zlib codec");
  }

  /**
   * Adds codec direction, profile and exact size evidence to the owning archive location. The
   * profile is the admitted codec's: the pinned LZ4 provider set's, which asking for pins if
   * nothing has yet.
   */
  private static ArchiveException failure(
      AdmittedCodec admitted,
      IoContext context,
      FailureKind kind,
      String identifier,
      String direction,
      long expected,
      long actual,
      Throwable cause) {
    ArchiveException base = context.failure(kind, identifier, cause);
    Diagnostic original = base.diagnostics().getFirst();
    var values = new TreeMap<String, String>();
    values.put("codec", "zlib");
    values.put("direction", direction);
    // Asked for only on this failure path, so successful zlib never touches the LZ4 runtime
    // (Lz4LaunchTest's zlib-only launch); AdmittedCodec.profile() explains the lazy pin.
    values.put("profile", admitted.profile());
    values.put("expected", Long.toString(expected));
    if (actual >= 0) values.put("actual", Long.toString(actual));
    return new ArchiveException(
        identifier,
        base.primaryFailure(),
        List.of(
            new Diagnostic(
                identifier,
                DiagnosticSeverity.ERROR,
                context.operation(),
                context.phase(),
                original.location(),
                values,
                Optional.empty())),
        List.of(),
        Optional.empty(),
        List.of());
  }
}
