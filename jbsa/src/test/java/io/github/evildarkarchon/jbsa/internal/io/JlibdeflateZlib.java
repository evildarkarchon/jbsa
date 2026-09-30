package io.github.evildarkarchon.jbsa.internal.io;

import com.fulcrumgenomics.jlibdeflate.DecompressionResult;
import com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor;
import com.fulcrumgenomics.jlibdeflate.LibdeflateDecompressor;
import com.fulcrumgenomics.jlibdeflate.LibdeflateException;
import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Qualification-only whole-buffer RFC 1950 candidate backed by jlibdeflate 0.1.0 (libdeflate 1.25)
 * at level 12. JBSA-CODEC-007 keeps this adapter out of production sources and the CLI image; it
 * exists so the issue 52 Final Profile Gate can compare it with {@link JdkZlib} at the codec seam.
 *
 * <p>Every call owns independent provider state and reserves its complete heap and native cost
 * before allocation. The adapter never falls back to another provider after it starts work.
 */
final class JlibdeflateZlib {
  /** Opaque candidate identity; it is not a release profile and never enters a manifest. */
  static final String CANDIDATE_PROFILE = "jbsa-jlibdeflate-candidate-v1";

  /**
   * The candidate level recorded by JBSA-CODEC-007 as a profile input, not public configuration.
   */
  static final int LEVEL = 12;

  /** jlibdeflate 0.1.0 {@code System.load}s from this directory before JAR extraction. */
  static final String LIBRARY_PATH_PROPERTY = "jlibdeflate.library.path";

  /**
   * Largest measured whole-buffer size. Beyond it the candidate has no evidence, so dispatch must
   * keep JDK streaming; the value is a qualification envelope rather than a selected threshold.
   */
  static final long DISPATCH_LIMIT = 16L * 1024 * 1024;

  /**
   * Conservative level-12 compressor state; issue 52 measured 9,033,088–9,033,216 committed bytes.
   */
  static final long ENCODE_NATIVE_BYTES = 9L * 1024 * 1024;

  /** Conservative decompressor state; issue 52 measured 8,320–13,632 committed bytes. */
  static final long DECODE_NATIVE_BYTES = 16L * 1024;

  /**
   * Java arrays cannot reach {@link Integer#MAX_VALUE}; every narrowing is checked against this.
   */
  private static final long MAX_ARRAY = Integer.MAX_VALUE - 8L;

  /** libdeflate 1.25 bounds each uncompressed fallback block at this many input bytes. */
  private static final long BOUND_BLOCK = 5000;

  private static boolean loaded;

  private JlibdeflateZlib() {}

  /**
   * Returns libdeflate 1.25's worst-case zlib output for {@code decodedSize} bytes: five block
   * header bytes per 5000-byte block (at least one) plus the two-byte header and Adler-32 trailer.
   * This mirrors {@code zlibCompressBound} without initializing the native provider.
   *
   * @throws IllegalArgumentException when the size is negative
   */
  static long storedBound(long decodedSize) {
    if (decodedSize < 0) throw new IllegalArgumentException("negative decoded size");
    long blocks = Math.max(1, (decodedSize + BOUND_BLOCK - 1) / BOUND_BLOCK);
    return Math.addExact(Math.addExact(decodedSize, Math.multiplyExact(5, blocks)), 6);
  }

  /** Returns the heap credit for the complete source array and worst-case output array. */
  static long encodeHeapBytes(long decodedSize) {
    return Math.addExact(decodedSize, storedBound(decodedSize));
  }

  /**
   * Returns the heap credit for the complete stored array and an output array with one probe byte.
   * The probe lets a single provider call distinguish over-expansion from an exact result.
   */
  static long decodeHeapBytes(long storedSize, long decodedSize) {
    return Math.addExact(Math.addExact(storedSize, decodedSize), 1);
  }

  /**
   * Rejects sizes outside the provider's {@code int} arrays or the measured envelope before any
   * narrowing, allocation, or output effect.
   */
  static void admitEncode(long decodedSize, IoContext context) throws ArchiveException {
    if (decodedSize < 0 || decodedSize > MAX_ARRAY || storedBound(decodedSize) > MAX_ARRAY)
      throw context.failure(FailureKind.POLICY, "codec.size-limit", null);
    if (decodedSize > DISPATCH_LIMIT)
      throw context.failure(FailureKind.POLICY, "codec.dispatch-limit", null);
  }

  /** Rejects stored and decoded sizes outside the {@code int}-bounded measured envelope. */
  static void admitDecode(long storedSize, long decodedSize, IoContext context)
      throws ArchiveException {
    if (storedSize < 0 || decodedSize < 0 || storedSize > MAX_ARRAY || decodedSize >= MAX_ARRAY)
      throw context.failure(FailureKind.POLICY, "codec.size-limit", null);
    if (decodedSize > DISPATCH_LIMIT || storedSize > storedBound(DISPATCH_LIMIT))
      throw context.failure(FailureKind.POLICY, "codec.dispatch-limit", null);
  }

  /**
   * Checks platform, the provider's library-path override and native access, then initializes the
   * provider once for the process lifetime. The override is checked on every call, before the
   * loaded shortcut, so a later host property change fails closed instead of being ignored.
   *
   * @throws ArchiveException with {@code CAPABILITY} and a stable {@code capabilityCause}
   */
  static synchronized void preflight(String direction, IoContext context) throws ArchiveException {
    String reason = "provider-unavailable";
    try {
      if (System.getProperty(LIBRARY_PATH_PROPERTY) != null) {
        reason = "native-configuration";
        throw new IllegalStateException();
      }
      if (loaded) return;
      if (!System.getProperty("os.name", "").startsWith("Windows")
          || !Set.of("amd64", "x86_64").contains(System.getProperty("os.arch", ""))) {
        reason = "platform";
        throw new IllegalStateException();
      }
      ClassLoader loader = JlibdeflateZlib.class.getClassLoader();
      // Resolve without initializing: class initialization is what extracts and loads the DLL.
      Class<?> compressor =
          Class.forName("com.fulcrumgenomics.jlibdeflate.LibdeflateCompressor", false, loader);
      if (!compressor.getModule().isNativeAccessEnabled()) {
        reason = "native-access";
        throw new IllegalStateException();
      }
      // jlibdeflate exposes no runtime version query, unlike LZ4_versionNumber, so the exact
      // provider identity rests on dependency verification of the pinned JAR bytes alone.
      Class.forName(compressor.getName(), true, loader);
      Class.forName("com.fulcrumgenomics.jlibdeflate.LibdeflateDecompressor", true, loader);
      loaded = true;
    } catch (ReflectiveOperationException | LinkageError | RuntimeException cause) {
      ArchiveException base =
          failure(context, FailureKind.CAPABILITY, "codec.unavailable", direction, -1, -1, cause);
      Diagnostic diagnostic = base.diagnostics().getFirst();
      var values = new TreeMap<>(diagnostic.values());
      values.put("capabilityCause", reason);
      throw new ArchiveException(
          "codec.unavailable",
          base.primaryFailure(),
          List.of(
              new Diagnostic(
                  diagnostic.identifier(),
                  diagnostic.severity(),
                  context.operation(),
                  context.phase(),
                  diagnostic.location(),
                  values,
                  Optional.empty())),
          List.of(),
          Optional.empty(),
          List.of());
    }
  }

  /**
   * Encodes exactly {@code decodedSize} source bytes with one level-12 provider call and writes one
   * sink window. The complete source, worst-case output and compressor state are reserved first;
   * the source and sink stay caller-owned.
   */
  static long encode(
      JdkZlib.ByteSource source,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    admitEncode(decodedSize, context);
    preflight("encode", context);
    caller(checkpoint::check, context);
    long bound = storedBound(decodedSize);
    try (var lease = budget.reserve(encodeHeapBytes(decodedSize), ENCODE_NATIVE_BYTES, 0, 0)) {
      byte[] input = new byte[(int) decodedSize];
      byte[] output = new byte[(int) bound];
      ByteBuffer window = ByteBuffer.wrap(input);
      caller(() -> source.read(0, window), context);
      if (window.hasRemaining())
        throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      caller(checkpoint::check, context);
      int count;
      try (var compressor = new LibdeflateCompressor(LEVEL)) {
        count = compressor.zlibCompress(input, 0, input.length, output, 0, output.length);
      } catch (LibdeflateException cause) {
        // jlibdeflate reports an undersized output buffer this way; storedBound makes it a defect.
        throw failure(
            context,
            FailureKind.INTERNAL,
            "codec.compression-failed",
            "encode",
            decodedSize,
            -1,
            cause);
      } catch (RuntimeException | LinkageError | AssertionError cause) {
        throw failure(
            context,
            FailureKind.INTERNAL,
            "codec.provider-fault",
            "encode",
            decodedSize,
            -1,
            cause);
      }
      if (count <= 0 || count > bound)
        throw failure(
            context,
            FailureKind.INTERNAL,
            "codec.compression-failed",
            "encode",
            decodedSize,
            count,
            null);
      caller(checkpoint::check, context);
      int written = count;
      caller(() -> sink.write(0, ByteBuffer.wrap(output, 0, written)), context);
      return count;
    }
  }

  /**
   * Decodes one complete stream and publishes it only after both the consumed input and produced
   * output match the wire sizes exactly. jlibdeflate's exact-size methods silently accept short
   * output and trailing bytes, so the adapter uses the {@code Ex} form with a one-byte probe.
   */
  static long decode(
      JdkZlib.ByteSource source,
      long storedSize,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    admitDecode(storedSize, decodedSize, context);
    preflight("decode", context);
    caller(checkpoint::check, context);
    try (var lease =
        budget.reserve(decodeHeapBytes(storedSize, decodedSize), DECODE_NATIVE_BYTES, 0, 0)) {
      byte[] input = new byte[(int) storedSize];
      byte[] output = new byte[(int) decodedSize + 1];
      ByteBuffer window = ByteBuffer.wrap(input);
      caller(() -> source.read(0, window), context);
      if (window.hasRemaining())
        throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      caller(checkpoint::check, context);
      DecompressionResult result;
      try (var decompressor = new LibdeflateDecompressor()) {
        result = decompressor.zlibDecompressEx(input, 0, input.length, output, 0, output.length);
      } catch (LibdeflateException cause) {
        throw failure(
            context,
            classifyKind(cause),
            classifyIdentifier(cause),
            "decode",
            decodedSize,
            -1,
            cause);
      } catch (RuntimeException | LinkageError | AssertionError cause) {
        throw failure(
            context,
            FailureKind.INTERNAL,
            "codec.provider-fault",
            "decode",
            decodedSize,
            -1,
            cause);
      }
      // Trailing input matches JdkZlib's getBytesRead check: the record must be one exact stream.
      if (result.outputBytesProduced() != decodedSize || result.inputBytesConsumed() != storedSize)
        throw failure(
            context,
            FailureKind.FORMAT,
            "codec.size-mismatch",
            "decode",
            decodedSize,
            result.outputBytesProduced(),
            null);
      caller(checkpoint::check, context);
      caller(() -> sink.write(0, ByteBuffer.wrap(output, 0, (int) decodedSize)), context);
      return decodedSize;
    }
  }

  /**
   * Applies the candidate decode dispatch rule: in-envelope records use the whole-buffer provider,
   * and larger records keep JDK streaming. JBSA-CODEC-015 selects JDK fallback only when candidate
   * preflight reports the provider unavailable; once the candidate starts, invalid data, size
   * mismatch, or a provider fault is final and is never retried through JDK zlib.
   */
  static long dispatchDecode(
      JdkZlib.ByteSource source,
      long storedSize,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    boolean candidate = decodedSize <= DISPATCH_LIMIT && storedSize <= storedBound(DISPATCH_LIMIT);
    if (candidate) {
      try {
        preflight("decode", context);
      } catch (ArchiveException unavailable) {
        if (unavailable.primaryFailure().kind() != FailureKind.CAPABILITY) throw unavailable;
        candidate = false;
      }
    }
    if (candidate)
      return decode(source, storedSize, decodedSize, sink, checkpoint, budget, context);
    return jdkDecode(source, storedSize, decodedSize, sink, checkpoint, budget, context);
  }

  /** Streams the baseline decoder in bounded windows under its own declared credits. */
  static long jdkDecode(
      JdkZlib.ByteSource source,
      long storedSize,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    try (var lease = budget.reserve(JdkZlib.DECODE_HEAP_BYTES, JdkZlib.DECODE_NATIVE_BYTES, 0, 0);
        var decoder = JdkZlib.decoder(source, storedSize, decodedSize, context)) {
      ByteBuffer window = ByteBuffer.allocate(65536);
      long written = 0;
      while (true) {
        caller(checkpoint::check, context);
        window.clear();
        int count = decoder.read(window);
        if (count < 0) return written;
        window.flip();
        long position = written;
        caller(() -> sink.write(position, window), context);
        written += count;
      }
    }
  }

  /**
   * Maps jlibdeflate's only failure channel, its message, onto the JdkZlib identifiers. An
   * unrecognized message is treated as a provider fault rather than guessed to be bad data.
   */
  private static String classifyIdentifier(LibdeflateException cause) {
    String message = String.valueOf(cause.getMessage());
    if (message.startsWith("Invalid or corrupt compressed data")) return "codec.invalid-data";
    if (message.startsWith("Output buffer too small")) return "codec.size-mismatch";
    return "codec.provider-fault";
  }

  /** Keeps the Failure Kind consistent with {@link #classifyIdentifier(LibdeflateException)}. */
  private static FailureKind classifyKind(LibdeflateException cause) {
    return classifyIdentifier(cause).equals("codec.provider-fault")
        ? FailureKind.INTERNAL
        : FailureKind.FORMAT;
  }

  /**
   * Records stable codec evidence under the candidate identity without provider messages. This and
   * {@link #caller} deliberately copy the private JdkZlib and Lz4Runtime helpers so the unpromoted
   * candidate adds nothing to production sources (JBSA-CODEC-007).
   */
  private static ArchiveException failure(
      IoContext context,
      FailureKind kind,
      String identifier,
      String direction,
      long expected,
      long actual,
      Throwable cause) {
    ArchiveException base = context.failure(kind, identifier, cause);
    var values = new TreeMap<String, String>();
    values.put("codec", "zlib");
    values.put("direction", direction);
    values.put("profile", CANDIDATE_PROFILE);
    if (expected >= 0) values.put("expected", Long.toString(expected));
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
                base.diagnostics().getFirst().location(),
                values,
                Optional.empty())),
        List.of(),
        Optional.empty(),
        List.of());
  }

  /** Keeps borrowed callback failures from being attributed to the native provider. */
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
