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
 * process's pinned provider: native LWJGL on Windows x64, portable lz4-java elsewhere.
 */
final class Lz4FrameTest {
  private static final IoContext CONTEXT = IoContext.of(Path.of("frame.bin"), Operation.OPEN);

  /** Covers either provider: the portable decoder's two 4 MiB blocks or native direct memory. */
  private static final long HEAP_CEILING = 16L * 1024 * 1024;

  private static final long NATIVE_CEILING = 12L * 1024 * 1024;

  /** Reserves one frame decoder's cost for the provider admission pins, as its owner must. */
  private static ResourceBudget.Lease reserveDecoder(
      ResourceBudget budget, long storedSize, long decodedSize) throws ArchiveException {
    AdmittedCodec.Cost cost =
        AdmittedCodec.admit(PayloadCodec.BSA_LZ4_FRAME, "decode", CONTEXT)
            .decodeCost(storedSize, decodedSize);
    return budget.reserve(cost.heapBytes(), cost.nativeBytes(), 0, 0);
  }

  /** Reserves one frame encode's cost for the provider admission pins, as its caller must. */
  private static ResourceBudget.Lease reserveEncoder(ResourceBudget budget, long decodedSize)
      throws ArchiveException {
    AdmittedCodec.Cost cost =
        AdmittedCodec.admit(PayloadCodec.BSA_LZ4_FRAME, "encode", CONTEXT).encodeCost(decodedSize);
    return budget.reserve(cost.heapBytes(), cost.nativeBytes(), 0, 0);
  }

  /**
   * Reserves one one-shot frame decode's credit: the decoder's cost, plus the 64 KiB heap window
   * the portable one-shot copies output through.
   */
  private static ResourceBudget.Lease reserveOneShot(
      ResourceBudget budget, long storedSize, long decodedSize) throws ArchiveException {
    AdmittedCodec admitted = AdmittedCodec.admit(PayloadCodec.BSA_LZ4_FRAME, "decode", CONTEXT);
    AdmittedCodec.Cost cost = admitted.decodeCost(storedSize, decodedSize);
    long window = admitted.provider() == Lz4Runtime.Provider.PORTABLE ? 65536 : 0;
    return budget.reserve(cost.heapBytes() + window, cost.nativeBytes(), 0, 0);
  }

  @Test
  void decoderStateMayBeConsumedAndClosedByAnotherThread() throws Exception {
    byte[] data = new byte[200_000];
    new Random(45).nextBytes(data);
    byte[] frame = encode(data);
    try (ResourceBudget budget = budget();
        ResourceBudget.Lease lease = reserveDecoder(budget, frame.length, data.length)) {
      var decoder = Lz4Frame.decoder(source(frame), frame.length, data.length, lease, CONTEXT);
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
   * A cancelled decode leaves its caller's lease open and holds no credit of its own, so every
   * credit returns once the caller closes the lease; a callback failure keeps operation identity.
   */
  @Test
  void releasesDecoderCreditsAfterCancellationAndPreservesCallbackFailure() throws Exception {
    byte[] frame = encode(new byte[400000]);
    AtomicInteger checks = new AtomicInteger();
    IOException cancellation = new IOException("cancelled");
    try (ResourceBudget budget = budget()) {
      var lease = reserveOneShot(budget, frame.length, 400000);
      assertSame(
          cancellation,
          assertThrows(
              IOException.class,
              () ->
                  Lz4Frame.decode(
                      source(frame),
                      frame.length,
                      400000,
                      (offset, bytes) -> {},
                      () -> {
                        if (checks.incrementAndGet() == 3) throw cancellation;
                      },
                      lease,
                      CONTEXT)));
      // The borrowed lease is still the caller's until it closes it.
      assertThrows(
          ArchiveException.class, () -> budget.reserve(HEAP_CEILING, NATIVE_CEILING, 0, 0));
      lease.close();
      try (ResourceBudget.Lease returned = budget.reserve(HEAP_CEILING, NATIVE_CEILING, 0, 0)) {
        assertNotNull(returned);
      }
      try (var failing = reserveOneShot(budget, frame.length, 400000)) {
        ArchiveException failure =
            assertThrows(
                ArchiveException.class,
                () ->
                    Lz4Frame.decode(
                        source(frame),
                        frame.length,
                        400000,
                        (offset, bytes) -> {
                          throw new IllegalStateException("caller failure");
                        },
                        () -> {},
                        failing,
                        CONTEXT));
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
            Lz4Frame.encode(
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
                  Lz4Frame.encode(
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
          Lz4Frame.encode(source(data), data.length, sink(output), () -> {}, lease, CONTEXT);
      assertEquals(output.size(), count);
    }
    return output.toByteArray();
  }

  /** Decodes on a lease of the one-shot decode credit from an isolated bounded ledger. */
  private static byte[] decode(byte[] frame, long size) throws Exception {
    var output = new ByteArrayOutputStream();
    try (ResourceBudget budget = budget();
        var lease = reserveOneShot(budget, frame.length, size)) {
      assertEquals(
          size,
          Lz4Frame.decode(
              source(frame), frame.length, size, sink(output), () -> {}, lease, CONTEXT));
    }
    return output.toByteArray();
  }
}
