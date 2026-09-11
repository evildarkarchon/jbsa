package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Random;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/** Exercises frame dispatch, corruption rejection, cancellation and resource return. */
final class Lz4FrameTest {
  private static final IoContext CONTEXT = IoContext.of(Path.of("frame.bin"), Operation.OPEN);

  @Test
  void streamsUpstreamFourMegabyteBlocksThroughSmallOutputWindows() throws Exception {
    byte[] data = new byte[5 * 1024 * 1024];
    new Random(44).nextBytes(data);
    Lz4Runtime.preflight("lz4-frame", "decode", CONTEXT);
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

  @Test
  void releasesDecoderCreditsAfterCancellationAndPreservesCallbackFailure() throws Exception {
    byte[] frame = encode(new byte[400000]);
    AtomicInteger checks = new AtomicInteger();
    IOException cancellation = new IOException("cancelled");
    try (ResourceBudget budget = budget()) {
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
                      budget,
                      CONTEXT)));
      try (ResourceBudget.Lease returned = budget.reserve(4096, 12 * 1024 * 1024, 0, 0)) {
        assertNotNull(returned);
      }
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
                      budget,
                      CONTEXT));
      assertEquals(
          "operation.internal-failure",
          failure.primaryFailure().diagnosticIdentifier().orElseThrow());
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

  @Test
  void refusesUnadmittedMemoryBeforeReadingOrWriting() throws Exception {
    try (ResourceBudget budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT, 0, 0, 0)) {
      ArchiveException failure =
          assertThrows(
              ArchiveException.class,
              () ->
                  Lz4Frame.encode(
                      (offset, bytes) -> fail("source touched"),
                      1,
                      (offset, bytes) -> fail("sink touched"),
                      () -> {},
                      budget,
                      CONTEXT));
      assertEquals(FailureKind.POLICY, failure.primaryFailure().kind());
    }
  }

  @Test
  void returnsCreditsAfterCancellationAndChecksBetweenWindows() throws Exception {
    byte[] data = new byte[400000];
    AtomicInteger checkpoints = new AtomicInteger();
    IOException cancellation = new IOException("cancelled");
    try (ResourceBudget budget = budget()) {
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
                      budget,
                      CONTEXT)));
      assertEquals(3, checkpoints.get());
      try (ResourceBudget.Lease returned = budget.reserve(4096, 12 * 1024 * 1024, 0, 0)) {
        assertNotNull(returned);
      }
    }
  }

  /** Supplies exactly each requested bounded window. */
  private static JdkZlib.ByteSource source(byte[] data) {
    return (offset, bytes) -> bytes.put(data, Math.toIntExact(offset), bytes.remaining());
  }

  private static ResourceBudget budget() {
    return new ResourceBudget(ResourceLimits.standard(), CONTEXT, 4096, 12 * 1024 * 1024, 0);
  }

  /** Collects frame output while verifying that windows remain bounded. */
  private static JdkZlib.ByteSink sink(ByteArrayOutputStream output) {
    return (offset, bytes) -> {
      assertEquals(output.size(), offset);
      assertTrue(bytes.remaining() <= 131072);
      byte[] chunk = new byte[bytes.remaining()];
      bytes.get(chunk);
      output.write(chunk);
    };
  }

  /** Encodes under an isolated bounded ledger. */
  private static byte[] encode(byte[] data) throws Exception {
    var output = new ByteArrayOutputStream();
    try (ResourceBudget budget = budget()) {
      long count =
          Lz4Frame.encode(source(data), data.length, sink(output), () -> {}, budget, CONTEXT);
      assertEquals(output.size(), count);
    }
    return output.toByteArray();
  }

  /** Decodes under an isolated bounded ledger. */
  private static byte[] decode(byte[] frame, long size) throws Exception {
    var output = new ByteArrayOutputStream();
    try (ResourceBudget budget = budget()) {
      assertEquals(
          size,
          Lz4Frame.decode(
              source(frame), frame.length, size, sink(output), () -> {}, budget, CONTEXT));
    }
    return output.toByteArray();
  }
}
