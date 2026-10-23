package io.github.evildarkarchon.jbsa.internal.codec;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Runtime.Provider;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import io.github.evildarkarchon.jbsa.internal.pack.TransformStage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.file.Path;
import java.util.SplittableRandom;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Characterizes the heap and native credits each payload codec charges today, per provider and
 * direction, for a small, a window-sized and a maximum-dispatch-sized payload (#93). Every expected
 * value is a literal recorded from a real run, never recomputed from a production formula, so a
 * restructured codec (#92) must reproduce these exact numbers.
 *
 * <p>A self-reserving path is measured by ceiling probing: the call succeeds under a budget whose
 * heap and native ceilings equal the charge, and fails {@code io.resource-capacity} one byte below
 * either. A caller-charged path is measured by its published credit and by succeeding with no spare
 * credit at all, which proves the codec adds no reservation of its own.
 */
final class PayloadCodecCostsTest {
  private static final IoContext CONTEXT = IoContext.of(Path.of("costs.bin"), Operation.OPEN);

  /** The small, window-sized and maximum-dispatch-sized payloads every row is recorded for. */
  private static final int SMALL = 5, WINDOW = 65_536, DISPATCH = 16_777_216;

  private static final int[] SIZES = {SMALL, WINDOW, DISPATCH};

  /** Room for any one call below; only the probed dimension is ever tight. */
  private static final long ROOMY = 256L << 20;

  /** The portable provider must never load lz4-java's bundled JNI library in this process. */
  @AfterAll
  static void neverLoadsLz4JavaJni() {
    assertFalse(net.jpountz.util.Native.isLoaded(), "lz4-java loaded its JNI library");
  }

  /**
   * zlib never reserves: its callers pre-charge one constant encode or decode credit regardless of
   * payload size, and the codec signatures take no budget at all.
   */
  @Test
  void zlibIsChargedByItsCallersAtConstantCredits() {
    assertEquals(132_096, JdkZlib.ENCODE_HEAP_BYTES);
    assertEquals(524_288, JdkZlib.ENCODE_NATIVE_BYTES);
    assertEquals(66_560, JdkZlib.DECODE_HEAP_BYTES);
    assertEquals(65_536, JdkZlib.DECODE_NATIVE_BYTES);
  }

  /**
   * The pack side's caller pre-charge per codec and size: zlib's constant credit, the BSA frame's
   * credit for the pinned provider, and nothing for raw LZ4, which reserves on every call itself.
   */
  @Test
  void packPrechargesFollowTheCodecAndThePinnedProvider() throws Exception {
    // Rows are {output bound, heap, native} for the SMALL, WINDOW and DISPATCH payloads.
    long[][] zlib = {
      {27, 132_096, 524_288}, {74_134, 132_096, 524_288}, {18_972_694, 132_096, 524_288}
    };
    long[][] raw = {{21, 0, 0}, {65_809, 0, 0}, {16_843_025, 0, 0}};
    long[][] frame =
        switch (Lz4Runtime.selected()) {
          case NATIVE ->
              new long[][] {
                {73, 4_096, 2_293_760}, {65_604, 4_096, 2_293_760}, {16_778_304, 4_096, 2_293_760}
              };
          case PORTABLE ->
              new long[][] {{73, 401_685, 0}, {65_604, 401_685, 0}, {16_778_304, 401_685, 0}};
        };
    try (var budget = budget(ROOMY, ROOMY)) {
      assertNull(TransformStage.encoding(PayloadCodec.STORED, WINDOW, false, budget));
      for (int index = 0; index < SIZES.length; index++) {
        assertEncoding(zlib[index], PayloadCodec.ZLIB, SIZES[index], budget);
        assertEncoding(frame[index], PayloadCodec.BSA_LZ4_FRAME, SIZES[index], budget);
        assertEncoding(raw[index], PayloadCodec.LZ4_RAW, SIZES[index], budget);
      }
    }
  }

  /** The LZ4 frame credits callers reserve before borrowing a lease or creating a decoder. */
  @Test
  void lz4FramePublishesItsCallerCreditsPerProvider() {
    assertEquals(4_096, Lz4Frame.encodeHeapBytes(Provider.NATIVE));
    assertEquals(2_293_760, Lz4Frame.encodeNativeBytes(Provider.NATIVE));
    assertEquals(4_096, Lz4Frame.decodeHeapBytes(Provider.NATIVE));
    assertEquals(9_633_792, Lz4Frame.decodeNativeBytes(Provider.NATIVE));
    assertEquals(401_685, Lz4Frame.encodeHeapBytes(Provider.PORTABLE));
    assertEquals(0, Lz4Frame.encodeNativeBytes(Provider.PORTABLE));
    assertEquals(8_392_704, Lz4Frame.decodeHeapBytes(Provider.PORTABLE));
    assertEquals(0, Lz4Frame.decodeNativeBytes(Provider.PORTABLE));
  }

  /** Raw LZ4's largest decode credit, which BA2 readback declares per provider. */
  @Test
  void rawLz4PublishesItsLargestDecodeCreditPerProvider() {
    assertEquals(0, Lz4Raw.maxDecodeHeapBytes(Provider.NATIVE));
    assertEquals(33_620_257, Lz4Raw.maxDecodeNativeBytes(Provider.NATIVE));
    assertEquals(33_620_257, Lz4Raw.maxDecodeHeapBytes(Provider.PORTABLE));
    assertEquals(0, Lz4Raw.maxDecodeNativeBytes(Provider.PORTABLE));
  }

  /** Native raw LZ4 reserves its whole block, output bound and HC state on every call. */
  // Native LZ4 is qualified and loaded only on the Windows x64 baseline.
  @EnabledOnOs(OS.WINDOWS)
  @Test
  void nativeRawLz4SelfReservesEachWholeBlock() throws Exception {
    assertRaw(Provider.NATIVE, SMALL, 4_096, 524_346, 6, 4_096, 27);
    assertRaw(Provider.NATIVE, WINDOW, 4_096, 655_665, 65_794, 4_096, 131_346);
    assertRaw(Provider.NATIVE, DISPATCH, 4_096, 34_144_561, 16_842_862, 4_096, 33_620_094);
  }

  /** Portable raw LZ4 reserves the same working set on the heap and no native memory. */
  @Test
  void portableRawLz4SelfReservesEachWholeBlockOnTheHeap() throws Exception {
    assertRaw(Provider.PORTABLE, SMALL, 270_362, 0, 6, 4_123, 0);
    assertRaw(Provider.PORTABLE, WINDOW, 401_681, 0, 65_794, 135_442, 0);
    assertRaw(Provider.PORTABLE, DISPATCH, 33_890_577, 0, 16_842_862, 33_624_190, 0);
  }

  /** Native frames self-reserve one fixed credit per call when no lease is lent, at any size. */
  // Native LZ4 is qualified and loaded only on the Windows x64 baseline.
  @EnabledOnOs(OS.WINDOWS)
  @Test
  void nativeLz4FrameSelfReservesOrBorrowsAFixedCredit() throws Exception {
    for (int size : SIZES) assertFrame(Provider.NATIVE, size, 4_096, 2_293_760, 4_096, 9_633_792);
  }

  /** Portable frames self-reserve one fixed heap credit per call when no lease is lent. */
  @Test
  void portableLz4FrameSelfReservesOrBorrowsAFixedHeapCredit() throws Exception {
    for (int size : SIZES) assertFrame(Provider.PORTABLE, size, 401_685, 0, 8_458_240, 0);
  }

  /** Checks one pack pre-charge row: worst-case output bound, heap and native credit. */
  private static void assertEncoding(
      long[] expected, PayloadCodec codec, int size, ResourceBudget budget) {
    var encoding = TransformStage.encoding(codec, size, false, budget);
    assertArrayEquals(
        expected,
        new long[] {encoding.outputBound(), encoding.heapBytes(), encoding.nativeBytes()},
        codec + " at " + size);
  }

  /**
   * Probes one raw encode and the decode of its own output. The decode charge depends on the stored
   * size, so the encoded length of the fixed sample is pinned as well.
   */
  private static void assertRaw(
      Provider provider,
      int size,
      long encodeHeap,
      long encodeNative,
      long storedSize,
      long decodeHeap,
      long decodeNative)
      throws Exception {
    byte[] data = sample(size);
    byte[] stored =
        assertCharge(
            provider + " raw encode " + size,
            encodeHeap,
            encodeNative,
            (budget, sink) ->
                Lz4Raw.encode(provider, source(data), size, sink, () -> {}, budget, CONTEXT, 12));
    assertEquals(storedSize, stored.length, provider + " raw stored size " + size);
    byte[] decoded =
        assertCharge(
            provider + " raw decode " + size,
            decodeHeap,
            decodeNative,
            (budget, sink) ->
                Lz4Raw.decode(
                    provider,
                    source(stored),
                    stored.length,
                    size,
                    sink,
                    () -> {},
                    budget,
                    CONTEXT));
    assertArrayEquals(data, decoded);
  }

  /**
   * Probes the BSA frame's self-reserving encode and one-shot decode, then shows the lease and
   * decoder paths add nothing beyond the caller's published credit.
   */
  private static void assertFrame(
      Provider provider,
      int size,
      long encodeHeap,
      long encodeNative,
      long decodeHeap,
      long decodeNative)
      throws Exception {
    byte[] data = sample(size);
    byte[] stored =
        assertCharge(
            provider + " frame encode " + size,
            encodeHeap,
            encodeNative,
            (budget, sink) ->
                Lz4Frame.encode(
                    provider, source(data), size, sink, () -> {}, budget, null, CONTEXT, true));
    // Lease path: the caller's whole published credit fills the budget, so any codec reservation
    // on top of the borrowed lease would fail.
    long leaseHeap = Lz4Frame.encodeHeapBytes(provider);
    long leaseNative = Lz4Frame.encodeNativeBytes(provider);
    try (var budget = budget(leaseHeap, leaseNative);
        var lease = budget.reserve(leaseHeap, leaseNative, 0, 0)) {
      var borrowed = new ByteArrayOutputStream();
      Lz4Frame.encode(
          provider, source(data), size, sink(borrowed), () -> {}, budget, lease, CONTEXT, true);
      assertArrayEquals(stored, borrowed.toByteArray());
    }
    byte[] decoded =
        assertCharge(
            provider + " frame one-shot decode " + size,
            decodeHeap,
            decodeNative,
            (budget, sink) ->
                Lz4Frame.decode(
                    provider,
                    source(stored),
                    stored.length,
                    size,
                    sink,
                    () -> {},
                    budget,
                    CONTEXT));
    assertArrayEquals(data, decoded);
    // Decoder path: it takes no budget, so the caller's decode credit is its entire charge.
    try (var decoder = Lz4Frame.decoder(provider, source(stored), stored.length, size, CONTEXT)) {
      ByteBuffer output = ByteBuffer.allocate(size + 1);
      while (decoder.read(output) >= 0) {
        // Drains to terminal EOF, which also validates exact frame consumption.
      }
      assertEquals(size, output.position());
    }
  }

  /**
   * Proves a call charges exactly {@code heap} and {@code nativeBytes} at its peak: it succeeds
   * under those ceilings and fails {@code io.resource-capacity} one byte below either nonzero one.
   */
  private static byte[] assertCharge(String row, long heap, long nativeBytes, Budgeted call)
      throws Exception {
    byte[] output;
    try (var budget = budget(heap, nativeBytes)) {
      var sink = new ByteArrayOutputStream();
      call.run(budget, sink(sink));
      output = sink.toByteArray();
    }
    if (heap > 0) assertCapacity(row + " heap", budget(heap - 1, nativeBytes), call);
    if (nativeBytes > 0) assertCapacity(row + " native", budget(heap, nativeBytes - 1), call);
    return output;
  }

  /** Requires the call to fail internal capacity admission under this budget, before any write. */
  private static void assertCapacity(String row, ResourceBudget budget, Budgeted call) {
    try (budget) {
      ArchiveException failure =
          assertThrows(
              ArchiveException.class,
              () -> call.run(budget, (offset, bytes) -> fail(row + " wrote before admission")),
              row);
      assertEquals(FailureKind.POLICY, failure.primaryFailure().kind(), row);
      assertEquals("io.resource-capacity", failure.diagnostics().getFirst().identifier(), row);
    }
  }

  /** One codec call under a supplied budget, writing its output to a supplied sink. */
  @FunctionalInterface
  private interface Budgeted {
    /** Runs the call; credits it reserves must be released on return. */
    void run(ResourceBudget budget, JdkZlib.ByteSink sink) throws Exception;
  }

  /** Creates a budget with exact heap and native ceilings and no handles. */
  private static ResourceBudget budget(long heap, long nativeBytes) {
    return new ResourceBudget(ResourceLimits.standard(), CONTEXT, heap, nativeBytes, 0);
  }

  /** Deterministic incompressible bytes, so encoded sizes stay near their worst case. */
  private static byte[] sample(int size) {
    byte[] data = new byte[size];
    new SplittableRandom(93).nextBytes(data);
    return data;
  }

  /** Supplies exactly each requested bounded window. */
  private static JdkZlib.ByteSource source(byte[] data) {
    return (offset, bytes) -> bytes.put(data, Math.toIntExact(offset), bytes.remaining());
  }

  /** Collects positional writes in order. */
  private static JdkZlib.ByteSink sink(ByteArrayOutputStream output) {
    return (offset, bytes) -> {
      assertEquals(output.size(), offset);
      byte[] chunk = new byte[bytes.remaining()];
      bytes.get(chunk);
      output.write(chunk);
    };
  }
}
