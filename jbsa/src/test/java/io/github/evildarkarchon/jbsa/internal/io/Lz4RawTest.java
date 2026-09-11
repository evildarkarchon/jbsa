package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/** Exercises the internal raw-block qualification seam with independently authored wire bytes. */
final class Lz4RawTest {
  private static final IoContext CONTEXT = IoContext.of(Path.of("raw.ba2"), Operation.OPEN);

  /** Borrowed callback faults retain operation identity and every admitted credit is returned. */
  @Test
  void normalizesCallbackFaultsAndReturnsCreditOnCancellation() throws Exception {
    try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT, 4096, 40000000, 0)) {
      var failure =
          assertThrows(
              ArchiveException.class,
              () ->
                  Lz4Raw.encode(
                      (offset, bytes) -> {
                        throw new IllegalStateException("caller");
                      },
                      1,
                      (offset, bytes) -> fail("destination touched"),
                      () -> {},
                      budget,
                      CONTEXT));
      assertEquals("operation.internal-failure", failure.diagnostics().getFirst().identifier());
      var cancelled = new java.io.IOException("cancelled");
      var checkpoints = new java.util.concurrent.atomic.AtomicInteger();
      assertSame(
          cancelled,
          assertThrows(
              java.io.IOException.class,
              () ->
                  Lz4Raw.encode(
                      (offset, bytes) -> bytes.put((byte) 42),
                      1,
                      (offset, bytes) -> fail("destination touched"),
                      () -> {
                        if (checkpoints.incrementAndGet() == 3) throw cancelled;
                      },
                      budget,
                      CONTEXT)));
      try (var all = budget.reserve(4096, 40000000, 0, 0)) {
        assertNotNull(all);
      }
    }
  }

  /**
   * HC encoding is deterministic and decodes through the pinned safe decoder, including empty
   * input.
   */
  @Test
  void encodesDeterministicallyAndReturnsCredits() throws Exception {
    for (int size : new int[] {0, 5, 65536, 1048576, 16777216}) {
      byte[] original = new byte[size];
      new java.util.Random(43).nextBytes(original);
      byte[] previous = null;
      try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT, 4096, 40000000, 0)) {
        for (int repeat = 0; repeat < 2; repeat++) {
          var encoded = new java.io.ByteArrayOutputStream();
          Lz4Raw.encode(
              (offset, bytes) -> bytes.put(original, (int) offset, bytes.remaining()),
              size,
              (offset, bytes) -> {
                byte[] chunk = new byte[bytes.remaining()];
                bytes.get(chunk);
                encoded.write(chunk);
              },
              () -> {},
              budget,
              CONTEXT);
          byte[] block = encoded.toByteArray();
          if (previous != null) assertArrayEquals(previous, block);
          previous = block;
          ByteBuffer decoded = ByteBuffer.allocate(size);
          Lz4Raw.decode(
              (offset, bytes) -> bytes.put(block, (int) offset, bytes.remaining()),
              block.length,
              size,
              (offset, bytes) -> decoded.put(bytes),
              () -> {},
              budget,
              CONTEXT);
          assertArrayEquals(original, decoded.array());
        }
      }
    }
  }

  /** A literal-only block must decode without treating it as a frame or exposing provider types. */
  @Test
  void decodesIndependentLiteralBlock() throws Exception {
    byte[] encoded = HexFormat.of().parseHex("5068656c6c6f");
    ByteBuffer output = ByteBuffer.allocate(5);
    try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT, 65536, 40000000, 0)) {
      Lz4Raw.decode(
          (offset, bytes) -> bytes.put(encoded, (int) offset, bytes.remaining()),
          encoded.length,
          5,
          (offset, bytes) -> output.put(bytes),
          () -> {},
          budget,
          CONTEXT);
    }
    assertArrayEquals(new byte[] {104, 101, 108, 108, 111}, output.array());
  }

  /** Admission failures must leave both callbacks untouched, including long-to-int overflow. */
  @Test
  void rejectsOversizedUnsplittableBlocksBeforeEffects() {
    try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT)) {
      for (long size : new long[] {-1, 16777217L, 0x7E000001L, Long.MAX_VALUE}) {
        var failure =
            assertThrows(
                ArchiveException.class,
                () ->
                    Lz4Raw.encode(
                        (offset, bytes) -> fail("source touched"),
                        size,
                        (offset, bytes) -> fail("destination touched"),
                        () -> {},
                        budget,
                        CONTEXT));
        assertEquals(FailureKind.POLICY, failure.primaryFailure().kind());
      }
    }
  }

  /** Malformed matches and declared-size mismatch must never reach the destination. */
  @Test
  void rejectsInvalidBlocksAndSizeMismatch() {
    for (byte[] encoded : new byte[][] {new byte[0], {0, 0, 0}, {16, 65}}) {
      try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT)) {
        var failure =
            assertThrows(
                ArchiveException.class,
                () ->
                    Lz4Raw.decode(
                        (offset, bytes) -> bytes.put(encoded),
                        encoded.length,
                        5,
                        (offset, bytes) -> fail("destination touched"),
                        () -> {},
                        budget,
                        CONTEXT));
        assertEquals(FailureKind.FORMAT, failure.primaryFailure().kind());
      }
    }
  }
}
