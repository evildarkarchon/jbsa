package io.github.evildarkarchon.jbsa.internal.codec;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;

/**
 * Exercises the internal raw-block qualification seam with independently authored wire bytes,
 * through the admitted codec's adapter for the process's pinned provider: native LWJGL on Windows
 * x64, portable lz4-java elsewhere.
 */
final class Lz4RawTest {
  private static final IoContext CONTEXT = IoContext.of(Path.of("raw.ba2"), Operation.OPEN);

  /**
   * Covers one 16 MiB block under either provider: native buffers under LWJGL, or the same buffers
   * plus the HC tables on the heap under lz4-java.
   */
  private static final long CEILING = 40_000_000;

  /** Returns raw LZ4 admitted for one direction, so each call runs on the provider it pinned. */
  private static AdmittedCodec raw(String direction) throws ArchiveException {
    return AdmittedCodec.admit(PayloadCodec.LZ4_RAW, direction, CONTEXT);
  }

  /**
   * Borrowed callback faults retain operation identity, and the encoder leaves credit to its
   * caller: on failure or cancellation it neither releases the caller's lease nor holds any credit
   * of its own, so once the caller closes its lease every credit is returned.
   */
  @Test
  void normalizesCallbackFaultsAndReturnsCreditOnCancellation() throws Exception {
    try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT, CEILING, CEILING, 0)) {
      var lease = budget.reserve(CEILING, CEILING, 0, 0);
      var failure =
          assertThrows(
              ArchiveException.class,
              () ->
                  Lz4Raw.encode(
                      raw("encode"),
                      (offset, bytes) -> {
                        throw new IllegalStateException("caller");
                      },
                      1,
                      (offset, bytes) -> fail("destination touched"),
                      () -> {},
                      lease,
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
                      raw("encode"),
                      (offset, bytes) -> bytes.put((byte) 42),
                      1,
                      (offset, bytes) -> fail("destination touched"),
                      () -> {
                        if (checkpoints.incrementAndGet() == 3) throw cancelled;
                      },
                      lease,
                      CONTEXT)));
      // The borrowed lease is still the caller's: the budget stays full until it closes.
      assertThrows(ArchiveException.class, () -> budget.reserve(1, 0, 0, 0));
      lease.close();
      try (var all = budget.reserve(CEILING, CEILING, 0, 0)) {
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
      // The whole budget is lent, so any reservation the codec made of its own would fail.
      try (var budget =
              new ResourceBudget(ResourceLimits.standard(), CONTEXT, CEILING, CEILING, 0);
          var lease = budget.reserve(CEILING, CEILING, 0, 0)) {
        for (int repeat = 0; repeat < 2; repeat++) {
          var encoded = new java.io.ByteArrayOutputStream();
          Lz4Raw.encode(
              raw("encode"),
              (offset, bytes) -> bytes.put(original, (int) offset, bytes.remaining()),
              size,
              (offset, bytes) -> {
                byte[] chunk = new byte[bytes.remaining()];
                bytes.get(chunk);
                encoded.write(chunk);
              },
              () -> {},
              lease,
              CONTEXT);
          byte[] block = encoded.toByteArray();
          if (previous != null) assertArrayEquals(previous, block);
          previous = block;
          ByteBuffer decoded = ByteBuffer.allocate(size);
          Lz4Raw.decode(
              raw("decode"),
              (offset, bytes) -> bytes.put(block, (int) offset, bytes.remaining()),
              block.length,
              size,
              (offset, bytes) -> decoded.put(bytes),
              () -> {},
              lease,
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
    try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT, CEILING, CEILING, 0);
        var lease = budget.reserve(CEILING, CEILING, 0, 0)) {
      Lz4Raw.decode(
          raw("decode"),
          (offset, bytes) -> bytes.put(encoded, (int) offset, bytes.remaining()),
          encoded.length,
          5,
          (offset, bytes) -> output.put(bytes),
          () -> {},
          lease,
          CONTEXT);
    }
    assertArrayEquals(new byte[] {104, 101, 108, 108, 111}, output.array());
  }

  /** Admission failures must leave both callbacks untouched, including long-to-int overflow. */
  @Test
  void rejectsOversizedUnsplittableBlocksBeforeEffects() throws Exception {
    try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT);
        var lease = budget.reserve(0, 0, 0, 0)) {
      for (long size : new long[] {-1, 16777217L, 0x7E000001L, Long.MAX_VALUE}) {
        var failure =
            assertThrows(
                ArchiveException.class,
                () ->
                    Lz4Raw.encode(
                        raw("encode"),
                        (offset, bytes) -> fail("source touched"),
                        size,
                        (offset, bytes) -> fail("destination touched"),
                        () -> {},
                        lease,
                        CONTEXT));
        assertEquals(FailureKind.POLICY, failure.primaryFailure().kind());
      }
    }
  }

  /** Malformed matches and declared-size mismatch must never reach the destination. */
  @Test
  void rejectsInvalidBlocksAndSizeMismatch() throws Exception {
    for (byte[] encoded : new byte[][] {new byte[0], {0, 0, 0}, {16, 65}}) {
      try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT);
          var lease = budget.reserve(0, 0, 0, 0)) {
        var failure =
            assertThrows(
                ArchiveException.class,
                () ->
                    Lz4Raw.decode(
                        raw("decode"),
                        (offset, bytes) -> bytes.put(encoded),
                        encoded.length,
                        5,
                        (offset, bytes) -> fail("destination touched"),
                        () -> {},
                        lease,
                        CONTEXT));
        assertEquals(FailureKind.FORMAT, failure.primaryFailure().kind());
      }
    }
  }
}
