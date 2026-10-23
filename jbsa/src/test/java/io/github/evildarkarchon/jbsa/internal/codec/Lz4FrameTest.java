package io.github.evildarkarchon.jbsa.internal.codec;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Exercises frame dispatch, corruption rejection, cancellation and resource return through the
 * module's frame entry points and the process's pinned provider: native LWJGL on Windows x64,
 * portable lz4-java elsewhere. Frames come from the qualification-only general encoder and decode
 * through the admitted BSA frame codec's decoder, which reads any independent or linked frame.
 */
final class Lz4FrameTest {
  private static final IoContext CONTEXT = IoContext.source(Path.of("frame.bin"), Operation.OPEN);

  /** Covers either provider: the portable decoder's two 4 MiB blocks or native direct memory. */
  private static final long HEAP_CEILING = 16L * 1024 * 1024;

  private static final long NATIVE_CEILING = 12L * 1024 * 1024;

  /** Returns the BSA frame codec admitted for decoding under the provider this process pins. */
  private static AdmittedCodec decoding() throws ArchiveException {
    return AdmittedCodec.admit(PayloadCodec.BSA_LZ4_FRAME, "decode", CONTEXT);
  }

  /** Reserves one frame decoder's cost for the provider admission pins, as its owner must. */
  private static ResourceBudget.Lease reserveDecoder(
      ResourceBudget budget, long storedSize, long decodedSize) throws ArchiveException {
    AdmittedCodec.Cost cost = decoding().decodeCost(storedSize, decodedSize);
    return budget.reserve(cost.heapBytes(), cost.nativeBytes(), 0, 0);
  }

  /** Reserves one frame encode's cost for the provider admission pins, as its caller must. */
  private static ResourceBudget.Lease reserveEncoder(ResourceBudget budget, long decodedSize)
      throws ArchiveException {
    AdmittedCodec.Cost cost =
        AdmittedCodec.admit(PayloadCodec.BSA_LZ4_FRAME, "encode", CONTEXT).encodeCost(decodedSize);
    return budget.reserve(cost.heapBytes(), cost.nativeBytes(), 0, 0);
  }

  @Test
  void decoderStateMayBeConsumedAndClosedByAnotherThread() throws Exception {
    byte[] data = new byte[200_000];
    new Random(45).nextBytes(data);
    byte[] frame = encode(data);
    try (ResourceBudget budget = budget();
        ResourceBudget.Lease lease = reserveDecoder(budget, frame.length, data.length)) {
      var decoder =
          Lz4Frame.decoder(decoding(), source(frame), frame.length, data.length, lease, CONTEXT);
      var result =
          CompletableFuture.supplyAsync(
              () -> {
                try (decoder) {
                  var output = new ByteArrayOutputStream();
                  ByteBuffer window = ByteBuffer.allocate(8192);
                  while (decoder.read(window) >= 0) {
                    output.write(window.array(), 0, window.position());
                    window.clear();
                  }
                  return output.toByteArray();
                } catch (IOException failure) {
                  throw new CompletionException(failure);
                }
              });
      assertArrayEquals(data, result.get(10, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  @Test
  // Builds its fixture with the native LWJGL LZ4F encoder, a Windows x64 boundary.
  @EnabledOnOs(OS.WINDOWS)
  void streamsUpstreamFourMegabyteBlocksThroughSmallOutputWindows() throws Exception {
    byte[] data = new byte[5 * 1024 * 1024];
    new Random(44).nextBytes(data);
    Lz4Runtime.nativePreflight("lz4-frame", "decode", CONTEXT);
    try (var arena = java.lang.foreign.Arena.ofConfined()) {
      var preferences =
          new org.lwjgl.util.lz4.LZ4FPreferences(
              arena.allocate(org.lwjgl.util.lz4.LZ4FPreferences.SIZEOF, 8).asByteBuffer());
      preferences.frameInfo().blockSizeID(org.lwjgl.util.lz4.LZ4Frame.LZ4F_max4MB);
      var input = arena.allocate(data.length, 8).asByteBuffer();
      input.put(data).flip();
      var output =
          arena
              .allocate(
                  org.lwjgl.util.lz4.LZ4Frame.LZ4F_compressFrameBound(data.length, preferences), 8)
              .asByteBuffer();
      long count = org.lwjgl.util.lz4.LZ4Frame.LZ4F_compressFrame(output, input, preferences);
      assertFalse(org.lwjgl.util.lz4.LZ4Frame.LZ4F_isError(count));
      byte[] frame = new byte[Math.toIntExact(count)];
      output.get(frame);
      assertArrayEquals(data, decode(frame, data.length));
    }
  }

  @Test
  void decodesIndependentWireFixturesWithoutRelyingOnItsEncoder() throws Exception {
    assertArrayEquals(new byte[0], decode(HexFormat.of().parseHex("04224d1860408200000000"), 0));
    assertArrayEquals(
        "hello".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
        decode(HexFormat.of().parseHex("04224d186040820500008068656c6c6f00000000"), 5));
  }

  /**
   * A decoder whose borrowed source is cancelled mid-frame leaves its owner's lease open and holds
   * no credit of its own, so every credit returns once the owner closes the decoder and the lease;
   * a source callback failure keeps operation identity.
   */
  @Test
  void releasesDecoderCreditsAfterCancellationAndPreservesCallbackFailure() throws Exception {
    // Incompressible content keeps the frame larger than one 64 KiB source window, so the decoder
    // must come back to its source mid-frame.
    byte[] data = new byte[400000];
    new Random(46).nextBytes(data);
    byte[] frame = encode(data);
    AtomicInteger reads = new AtomicInteger();
    IOException cancellation = new IOException("cancelled");
    ByteSource cancelling =
        (offset, bytes) -> {
          if (reads.incrementAndGet() == 2) throw cancellation;
          source(frame).read(offset, bytes);
        };
    try (ResourceBudget budget = budget()) {
      var lease = reserveDecoder(budget, frame.length, 400000);
      try (var decoder =
          Lz4Frame.decoder(decoding(), cancelling, frame.length, 400000, lease, CONTEXT)) {
        assertSame(cancellation, assertThrows(IOException.class, () -> drain(decoder, 8192)));
      }
      // The borrowed lease is still the caller's until it closes it.
      assertThrows(
          ArchiveException.class, () -> budget.reserve(HEAP_CEILING, NATIVE_CEILING, 0, 0));
      lease.close();
      try (ResourceBudget.Lease returned = budget.reserve(HEAP_CEILING, NATIVE_CEILING, 0, 0)) {
        assertNotNull(returned);
      }
      ByteSource failingSource =
          (offset, bytes) -> {
            throw new IllegalStateException("caller failure");
          };
      try (var failing = reserveDecoder(budget, frame.length, 400000);
          var decoder =
              Lz4Frame.decoder(decoding(), failingSource, frame.length, 400000, failing, CONTEXT)) {
        ArchiveException failure = assertThrows(ArchiveException.class, () -> drain(decoder, 8192));
        assertEquals(
            "operation.internal-failure",
            failure.primaryFailure().diagnosticIdentifier().orElseThrow());
      }
    }
  }

  @Test
  void streamsDeterministicallyAcrossWindowsIncludingEmptyContent() throws Exception {
    for (int size : new int[] {0, 1, 65535, 65536, 65537, 400000}) {
      byte[] data = new byte[size];
      new Random(43).nextBytes(data);
      byte[] frame = encode(data);
      assertArrayEquals(frame, encode(data));
      assertArrayEquals(data, decode(frame, size));
    }
  }

  @Test
  void rejectsCorruptionTruncationTrailingFramesAndWrongOutputSize() throws Exception {
    byte[] data = new byte[150000];
    Arrays.fill(data, (byte) 42);
    byte[] frame = encode(data);
    byte[] damaged = frame.clone();
    damaged[damaged.length - 1] ^= 1;
    for (byte[] invalid :
        new byte[][] {
          new byte[0],
          new byte[20],
          damaged,
          Arrays.copyOf(frame, frame.length - 1),
          Arrays.copyOf(frame, frame.length + 1)
        }) {
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(ArchiveException.class, () -> decode(invalid, data.length))
              .primaryFailure()
              .kind());
    }
    assertThrows(ArchiveException.class, () -> decode(frame, data.length - 1));
    assertThrows(ArchiveException.class, () -> decode(frame, data.length + 1));
  }

  /**
   * Memory is admitted only by the caller's reservation of the encode cost: with no capacity that
   * admission is refused, and an encoder offered no admission refuses before touching its source or
   * sink.
   */
  @Test
  void refusesUnadmittedMemoryBeforeReadingOrWriting() throws Exception {
    try (ResourceBudget budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT, 0, 0, 0)) {
      ArchiveException failure =
          assertThrows(ArchiveException.class, () -> reserveEncoder(budget, 1));
      assertEquals(FailureKind.POLICY, failure.primaryFailure().kind());
    }
    assertThrows(
        NullPointerException.class,
        () ->
            Lz4Frame.encodeGeneral(
                (offset, bytes) -> fail("source touched"),
                1,
                (offset, bytes) -> fail("sink touched"),
                () -> {},
                null,
                CONTEXT));
  }

  /**
   * Cancellation is observed between windows, and the cancelled encoder leaves its caller's lease
   * open and holds no credit of its own, so every credit returns once the caller closes the lease.
   */
  @Test
  void returnsCreditsAfterCancellationAndChecksBetweenWindows() throws Exception {
    byte[] data = new byte[400000];
    AtomicInteger checkpoints = new AtomicInteger();
    IOException cancellation = new IOException("cancelled");
    try (ResourceBudget budget = budget()) {
      var lease = reserveEncoder(budget, data.length);
      assertSame(
          cancellation,
          assertThrows(
              IOException.class,
              () ->
                  Lz4Frame.encodeGeneral(
                      source(data),
                      data.length,
                      (offset, bytes) -> {},
                      () -> {
                        if (checkpoints.incrementAndGet() == 3) throw cancellation;
                      },
                      lease,
                      CONTEXT)));
      assertEquals(3, checkpoints.get());
      // The borrowed lease is still the caller's until it closes it.
      assertThrows(
          ArchiveException.class, () -> budget.reserve(HEAP_CEILING, NATIVE_CEILING, 0, 0));
      lease.close();
      try (ResourceBudget.Lease returned = budget.reserve(HEAP_CEILING, NATIVE_CEILING, 0, 0)) {
        assertNotNull(returned);
      }
    }
  }

  /** Supplies exactly each requested bounded window. */
  private static ByteSource source(byte[] data) {
    return (offset, bytes) -> bytes.put(data, Math.toIntExact(offset), bytes.remaining());
  }

  private static ResourceBudget budget() {
    return new ResourceBudget(ResourceLimits.standard(), CONTEXT, HEAP_CEILING, NATIVE_CEILING, 0);
  }

  /** Collects frame output while verifying that windows remain bounded. */
  private static ByteSink sink(ByteArrayOutputStream output) {
    return (offset, bytes) -> {
      assertEquals(output.size(), offset);
      assertTrue(bytes.remaining() <= 131072);
      byte[] chunk = new byte[bytes.remaining()];
      bytes.get(chunk);
      output.write(chunk);
    };
  }

  /** Encodes on a lease of the encode cost from an isolated bounded ledger. */
  private static byte[] encode(byte[] data) throws Exception {
    var output = new ByteArrayOutputStream();
    try (ResourceBudget budget = budget();
        var lease = reserveEncoder(budget, data.length)) {
      long count =
          Lz4Frame.encodeGeneral(source(data), data.length, sink(output), () -> {}, lease, CONTEXT);
      assertEquals(output.size(), count);
    }
    return output.toByteArray();
  }

  /**
   * Decodes through the admitted decoder on a lease of its decode cost from an isolated bounded
   * ledger, draining it to terminal EOF.
   */
  private static byte[] decode(byte[] frame, long size) throws Exception {
    try (ResourceBudget budget = budget();
        var lease = reserveDecoder(budget, frame.length, size);
        var decoder =
            Lz4Frame.decoder(decoding(), source(frame), frame.length, size, lease, CONTEXT)) {
      byte[] decoded = drain(decoder, 65536);
      assertEquals(size, decoded.length);
      return decoded;
    }
  }

  /** Reads a decoder to terminal EOF through one bounded destination window. */
  private static byte[] drain(Lz4Frame.Decoder decoder, int window) throws IOException {
    var output = new ByteArrayOutputStream();
    ByteBuffer buffer = ByteBuffer.allocate(window);
    while (decoder.read(buffer.clear()) >= 0) output.write(buffer.array(), 0, buffer.position());
    return output.toByteArray();
  }
}
