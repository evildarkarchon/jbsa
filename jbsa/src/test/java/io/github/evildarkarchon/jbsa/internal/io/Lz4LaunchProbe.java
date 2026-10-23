package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.codec.BsaLz4Frame;
import io.github.evildarkarchon.jbsa.internal.codec.ByteSink;
import io.github.evildarkarchon.jbsa.internal.codec.ByteSource;
import io.github.evildarkarchon.jbsa.internal.codec.JdkZlib;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Raw;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Runtime;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.file.Path;
import java.util.Arrays;

/** Fresh-process probe; never linked from production or the public archive interface. */
public final class Lz4LaunchProbe {
  private Lz4LaunchProbe() {}

  /**
   * Verifies lazy zlib independence, then reports the native adapter's normalized admission result
   * and the provider preflight pinned, round-tripping data through that provider without
   * destination effects. Prints {@code <native result> <provider>}, where the provider is {@code
   * UNAVAILABLE} when neither LZ4 provider can be admitted.
   *
   * @throws AssertionError if lz4-java's JNI library was loaded (JBSA-CODEC-014)
   */
  public static void main(String[] args) throws Exception {
    var context = IoContext.of(Path.of("probe.bin"), Operation.OPEN);
    BethesdaArchives.standard();
    long size;
    // A budget's native ceilings come from LZ4-free module facts, so charging zlib loads no LZ4.
    try (var budget = new ResourceBudget(ResourceLimits.standard(), context);
        var lease = budget.reserve(JdkZlib.ENCODE_HEAP_BYTES, JdkZlib.ENCODE_NATIVE_BYTES, 0, 0)) {
      size =
          JdkZlib.encode(
              // Admitting zlib has no side effect, so the zlib-only launch still never touches LZ4.
              AdmittedCodec.admit(PayloadCodec.ZLIB, "encode", context),
              Channels.newChannel(new ByteArrayInputStream(new byte[] {42})),
              1,
              (offset, bytes) -> bytes.position(bytes.limit()),
              () -> {},
              lease,
              context);
    }
    if (size == 0) throw new AssertionError("zlib disabled");
    if (args[0].startsWith("profiles")) {
      System.out.println(profiles(context));
      return;
    }
    if (args[0].equals("zlib-only")) {
      System.out.println("ZLIB_OK");
      return;
    }
    String nativeResult;
    try {
      Lz4Runtime.nativePreflight("raw-lz4", "encode", context);
      Lz4Runtime.nativePreflight("lz4-frame", "decode", context);
      nativeResult = "LZ4_OK";
    } catch (ArchiveException failure) {
      if (failure.primaryFailure().kind() != FailureKind.CAPABILITY) throw failure;
      nativeResult =
          "CAPABILITY:" + failure.diagnostics().getFirst().values().get("capabilityCause");
    }
    String provider;
    try {
      provider = Lz4Runtime.preflight("raw-lz4", "encode", context).name();
      roundTrip(context);
    } catch (ArchiveException failure) {
      if (failure.primaryFailure().kind() != FailureKind.CAPABILITY) throw failure;
      provider = "UNAVAILABLE";
    }
    // Only check once lz4-java is known to be present; its Native class is otherwise unresolvable.
    if (!provider.equals("UNAVAILABLE") && net.jpountz.util.Native.isLoaded())
      throw new AssertionError("lz4-java loaded its JNI library");
    System.out.println(nativeResult + " " + provider);
  }

  /**
   * Fails a zlib decode before any LZ4 call, then preflights LZ4, and reports both profiles as
   * {@code ZLIB <profile> LZ4 <profile>}. The LZ4 part is {@code UNAVAILABLE:<profile>} when the
   * pinned provider cannot be admitted, carrying the capability failure's own profile.
   *
   * @throws AssertionError if invalid zlib data decodes, or lz4-java loaded its JNI library
   */
  private static String profiles(IoContext context) throws Exception {
    byte[] invalid = {1, 2, 3, 4};
    String zlib;
    try (var budget = new ResourceBudget(ResourceLimits.standard(), context);
        var lease = budget.reserve(JdkZlib.DECODE_HEAP_BYTES, JdkZlib.DECODE_NATIVE_BYTES, 0, 0);
        var decoder =
            JdkZlib.decoder(
                AdmittedCodec.admit(PayloadCodec.ZLIB, "decode", context),
                source(invalid),
                invalid.length,
                8,
                lease,
                context)) {
      decoder.read(ByteBuffer.allocate(8));
      throw new AssertionError("invalid zlib decoded");
    } catch (ArchiveException failure) {
      if (!failure.diagnostics().getFirst().identifier().equals("codec.invalid-data"))
        throw failure;
      zlib = failure.diagnostics().getFirst().values().get("profile");
    }
    String lz4;
    try {
      lz4 = Lz4Runtime.preflight("raw-lz4", "encode", context).profile();
    } catch (ArchiveException failure) {
      if (failure.primaryFailure().kind() != FailureKind.CAPABILITY) throw failure;
      lz4 = "UNAVAILABLE:" + failure.diagnostics().getFirst().values().get("profile");
    }
    // Only check once lz4-java is known to be present; its Native class is otherwise unresolvable.
    if (!lz4.startsWith("UNAVAILABLE") && net.jpountz.util.Native.isLoaded())
      throw new AssertionError("lz4-java loaded its JNI library");
    return "ZLIB " + zlib + " LZ4 " + lz4;
  }

  /** Encodes and decodes one raw block and one BSA frame through the pinned provider. */
  private static void roundTrip(IoContext context) throws Exception {
    byte[] data = new byte[200_000];
    for (int index = 0; index < data.length; index++) data[index] = (byte) (index % 251);
    try (var budget =
        new ResourceBudget(ResourceLimits.standard(), context, 64L << 20, 64L << 20, 0)) {
      // Each call runs on a lease of the cost its admitted codec publishes, as production's do.
      AdmittedCodec rawEncoder = AdmittedCodec.admit(PayloadCodec.LZ4_RAW, "encode", context);
      byte[] block;
      try (var lease = reserve(budget, rawEncoder.encodeCost(data.length))) {
        block =
            collect(
                sink -> Lz4Raw.encode(source(data), data.length, sink, () -> {}, lease, context));
      }
      ByteBuffer decoded = ByteBuffer.allocate(data.length);
      AdmittedCodec rawDecoder = AdmittedCodec.admit(PayloadCodec.LZ4_RAW, "decode", context);
      try (var lease = reserve(budget, rawDecoder.decodeCost(block.length, data.length))) {
        Lz4Raw.decode(
            source(block),
            block.length,
            data.length,
            (offset, bytes) -> decoded.put(bytes),
            () -> {},
            lease,
            context);
      }
      if (!Arrays.equals(data, decoded.array())) throw new AssertionError("raw round trip");
      AdmittedCodec encoder = AdmittedCodec.admit(PayloadCodec.BSA_LZ4_FRAME, "encode", context);
      byte[] frame;
      try (var lease = reserve(budget, encoder.encodeCost(data.length))) {
        frame =
            collect(
                sink ->
                    BsaLz4Frame.encode(
                        encoder, source(data), data.length, sink, () -> {}, lease, context));
      }
      AdmittedCodec decoding = AdmittedCodec.admit(PayloadCodec.BSA_LZ4_FRAME, "decode", context);
      try (var lease = reserve(budget, decoding.decodeCost(frame.length, data.length));
          var decoder =
              BsaLz4Frame.decoder(
                  decoding, source(frame), frame.length, data.length, lease, context)) {
        // One spare byte keeps the destination non-full, so the decoder can report terminal EOF
        // (which also validates exact frame consumption) instead of a zero-length read.
        ByteBuffer output = ByteBuffer.allocate(data.length + 1);
        while (decoder.read(output) >= 0) {
          // Drains to terminal EOF.
        }
        if (output.position() != data.length
            || !Arrays.equals(data, Arrays.copyOf(output.array(), data.length)))
          throw new AssertionError("frame round trip");
      }
    }
  }

  /** Reserves one codec call's published cost, which the call then borrows as its lease. */
  private static ResourceBudget.Lease reserve(ResourceBudget budget, AdmittedCodec.Cost cost)
      throws ArchiveException {
    return budget.reserve(cost.heapBytes(), cost.nativeBytes(), 0, 0);
  }

  /** Supplies exactly each requested bounded window. */
  private static ByteSource source(byte[] data) {
    return (offset, bytes) -> bytes.put(data, Math.toIntExact(offset), bytes.remaining());
  }

  /** Runs one encoder into a growable buffer and returns its bytes. */
  private static byte[] collect(Encoder encoder) throws Exception {
    var output = new ByteArrayOutputStream();
    encoder.encode(
        (offset, bytes) -> {
          byte[] chunk = new byte[bytes.remaining()];
          bytes.get(chunk);
          output.write(chunk);
        });
    return output.toByteArray();
  }

  /** One encoder invocation writing to the supplied sink. */
  @FunctionalInterface
  private interface Encoder {
    /** Encodes into the sink. */
    void encode(ByteSink sink) throws Exception;
  }
}
