package io.github.evildarkarchon.jbsa.internal.codec;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec.Cost;
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
 * The payload codec module's contract (#92): every admitted {@link PayloadCodec}'s worst-case
 * bound, its heap and native cost per provider and direction, its Codec Profile identity, and the
 * native ceilings derived from them.
 *
 * <p>Every expected value is a literal, never recomputed from a production formula. The cost
 * literals are the ones Payload Codec 01 (#93) recorded from a real run of today's codecs, for a
 * small, a window-sized and a maximum-dispatch-sized payload, so the admitted codec must reproduce
 * those exact numbers. Pure cost arithmetic runs for both providers on every host; a codec call
 * runs only under a provider the host can load.
 *
 * <p>A self-reserving path is measured by ceiling probing: the call succeeds under a budget whose
 * heap and native ceilings equal the charge, and fails {@code io.resource-capacity} one byte below
 * either. A caller-charged path is measured by its published credit and by succeeding with no spare
 * credit at all, which proves the codec adds no reservation of its own.
 */
final class PayloadCodecTest {
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

  /** Every codec's worst-case encoded size, which no provider changes. */
  @Test
  void boundsArePinnedPerCodec() {
    // Rows are the SMALL, WINDOW and DISPATCH bounds.
    long[] stored = {5, 65_536, 16_777_216};
    long[] zlib = {27, 74_134, 18_972_694};
    long[] frame = {73, 65_604, 16_778_304};
    long[] raw = {21, 65_809, 16_843_025};
    for (Provider provider : Provider.values())
      for (int index = 0; index < SIZES.length; index++) {
        int size = SIZES[index];
        assertEquals(stored[index], codec(PayloadCodec.STORED, provider).bound(size));
        assertEquals(zlib[index], codec(PayloadCodec.ZLIB, provider).bound(size));
        assertEquals(frame[index], codec(PayloadCodec.BSA_LZ4_FRAME, provider).bound(size));
        assertEquals(raw[index], codec(PayloadCodec.LZ4_RAW, provider).bound(size));
      }
  }

  /**
   * One encode call's heap and native cost. Stored and zlib cost the same under either provider;
   * both LZ4 profiles put their working set in native memory under LWJGL and on the heap under
   * lz4-java.
   */
  @Test
  void encodeCostsArePinnedPerCodecAndProvider() {
    for (Provider provider : Provider.values())
      for (int size : SIZES) {
        assertEquals(new Cost(0, 0), codec(PayloadCodec.STORED, provider).encodeCost(size));
        assertEquals(
            new Cost(132_096, 524_288), codec(PayloadCodec.ZLIB, provider).encodeCost(size));
      }
    for (int size : SIZES) {
      assertEquals(
          new Cost(4_096, 2_293_760),
          codec(PayloadCodec.BSA_LZ4_FRAME, Provider.NATIVE).encodeCost(size));
      assertEquals(
          new Cost(401_685, 0),
          codec(PayloadCodec.BSA_LZ4_FRAME, Provider.PORTABLE).encodeCost(size));
    }
    AdmittedCodec nativeRaw = codec(PayloadCodec.LZ4_RAW, Provider.NATIVE);
    assertEquals(new Cost(4_096, 524_346), nativeRaw.encodeCost(SMALL));
    assertEquals(new Cost(4_096, 655_665), nativeRaw.encodeCost(WINDOW));
    assertEquals(new Cost(4_096, 34_144_561), nativeRaw.encodeCost(DISPATCH));
    AdmittedCodec portableRaw = codec(PayloadCodec.LZ4_RAW, Provider.PORTABLE);
    assertEquals(new Cost(270_362, 0), portableRaw.encodeCost(SMALL));
    assertEquals(new Cost(401_681, 0), portableRaw.encodeCost(WINDOW));
    assertEquals(new Cost(33_890_577, 0), portableRaw.encodeCost(DISPATCH));
  }

  /**
   * One decode call's heap and native cost. Raw LZ4 holds its whole stored and decoded block, so it
   * is pinned at the stored sizes Payload Codec 01 recorded for its incompressible samples.
   */
  @Test
  void decodeCostsArePinnedPerCodecAndProvider() {
    for (Provider provider : Provider.values())
      for (int size : SIZES) {
        assertEquals(new Cost(0, 0), codec(PayloadCodec.STORED, provider).decodeCost(size, size));
        assertEquals(
            new Cost(66_560, 65_536), codec(PayloadCodec.ZLIB, provider).decodeCost(size, size));
      }
    for (int size : SIZES) {
      assertEquals(
          new Cost(4_096, 9_633_792),
          codec(PayloadCodec.BSA_LZ4_FRAME, Provider.NATIVE).decodeCost(size, size));
      assertEquals(
          new Cost(8_392_704, 0),
          codec(PayloadCodec.BSA_LZ4_FRAME, Provider.PORTABLE).decodeCost(size, size));
    }
    AdmittedCodec nativeRaw = codec(PayloadCodec.LZ4_RAW, Provider.NATIVE);
    assertEquals(new Cost(4_096, 27), nativeRaw.decodeCost(6, SMALL));
    assertEquals(new Cost(4_096, 131_346), nativeRaw.decodeCost(65_794, WINDOW));
    assertEquals(new Cost(4_096, 33_620_094), nativeRaw.decodeCost(16_842_862, DISPATCH));
    AdmittedCodec portableRaw = codec(PayloadCodec.LZ4_RAW, Provider.PORTABLE);
    assertEquals(new Cost(4_123, 0), portableRaw.decodeCost(6, SMALL));
    assertEquals(new Cost(135_442, 0), portableRaw.decodeCost(65_794, WINDOW));
    assertEquals(new Cost(33_624_190, 0), portableRaw.decodeCost(16_842_862, DISPATCH));
  }

  /**
   * The largest cost a single decode call can charge, which BA2 readback declares. Raw LZ4's is one
   * dispatch-limit block: Payload Codec 01 recorded its working set as 33 620 257 bytes beyond the
   * 4 096-byte bookkeeping, native under LWJGL and heap under lz4-java.
   */
  @Test
  void largestDecodeCostsArePinnedPerCodecAndProvider() {
    for (Provider provider : Provider.values()) {
      assertEquals(new Cost(0, 0), codec(PayloadCodec.STORED, provider).maxDecodeCost());
      assertEquals(new Cost(66_560, 65_536), codec(PayloadCodec.ZLIB, provider).maxDecodeCost());
    }
    assertEquals(
        new Cost(4_096, 9_633_792),
        codec(PayloadCodec.BSA_LZ4_FRAME, Provider.NATIVE).maxDecodeCost());
    assertEquals(
        new Cost(8_392_704, 0),
        codec(PayloadCodec.BSA_LZ4_FRAME, Provider.PORTABLE).maxDecodeCost());
    assertEquals(
        new Cost(4_096, 33_620_257), codec(PayloadCodec.LZ4_RAW, Provider.NATIVE).maxDecodeCost());
    assertEquals(
        new Cost(4_096 + 33_620_257, 0),
        codec(PayloadCodec.LZ4_RAW, Provider.PORTABLE).maxDecodeCost());
  }

  /**
   * The read and mutation budgets' native ceilings are the largest native cost of one call per
   * direction: a native raw-LZ4 dispatch-limit block in each.
   */
  @Test
  void nativeCeilingsAreTheLargestSingleCallPerDirection() {
    assertEquals(34_144_561, AdmittedCodec.MAX_ENCODE_NATIVE_BYTES);
    assertEquals(33_620_257, AdmittedCodec.MAX_DECODE_NATIVE_BYTES);
  }

  /**
   * The BSA frame carries the Versioned BSA family-layered identity of its provider, raw LZ4 the
   * provider's own, and the JDK-only codecs the identity of the provider set the process pinned.
   */
  @Test
  void profilesNameTheAdmittedProviderSet() throws Exception {
    assertEquals(
        "jbsa-bsa-069-lz4-v1", codec(PayloadCodec.BSA_LZ4_FRAME, Provider.NATIVE).profile());
    assertEquals(
        "jbsa-bsa-069-lz4-portable-v1",
        codec(PayloadCodec.BSA_LZ4_FRAME, Provider.PORTABLE).profile());
    assertEquals("jbsa-lz4-v1", codec(PayloadCodec.LZ4_RAW, Provider.NATIVE).profile());
    assertEquals("jbsa-lz4-portable-v1", codec(PayloadCodec.LZ4_RAW, Provider.PORTABLE).profile());

    String pinned =
        switch (AdmittedCodec.admit(PayloadCodec.LZ4_RAW, "decode", CONTEXT).provider()) {
          case NATIVE -> "jbsa-lz4-v1";
          case PORTABLE -> "jbsa-lz4-portable-v1";
        };
    String frame =
        switch (AdmittedCodec.admit(PayloadCodec.BSA_LZ4_FRAME, "decode", CONTEXT).provider()) {
          case NATIVE -> "jbsa-bsa-069-lz4-v1";
          case PORTABLE -> "jbsa-bsa-069-lz4-portable-v1";
        };
    assertEquals(
        frame, AdmittedCodec.admit(PayloadCodec.BSA_LZ4_FRAME, "encode", CONTEXT).profile());
    assertEquals(pinned, AdmittedCodec.admit(PayloadCodec.ZLIB, "encode", CONTEXT).profile());
    assertEquals(pinned, AdmittedCodec.admit(PayloadCodec.STORED, "decode", CONTEXT).profile());
  }

  /** Admission names its codec and rejects a direction other than encode or decode. */
  @Test
  void admissionKeepsItsCodecAndRejectsUnknownDirections() throws Exception {
    for (PayloadCodec codec : PayloadCodec.values()) {
      assertEquals(codec, AdmittedCodec.admit(codec, "decode", CONTEXT).codec());
      assertThrows(
          IllegalArgumentException.class, () -> AdmittedCodec.admit(codec, "inflate", CONTEXT));
    }
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
    AdmittedCodec admittedFrame =
        AdmittedCodec.admit(PayloadCodec.BSA_LZ4_FRAME, "encode", CONTEXT);
    long[][] frame =
        switch (admittedFrame.provider()) {
          case NATIVE ->
              new long[][] {
                {73, 4_096, 2_293_760}, {65_604, 4_096, 2_293_760}, {16_778_304, 4_096, 2_293_760}
              };
          case PORTABLE ->
              new long[][] {{73, 401_685, 0}, {65_604, 401_685, 0}, {16_778_304, 401_685, 0}};
        };
    AdmittedCodec admittedZlib = AdmittedCodec.admit(PayloadCodec.ZLIB, "encode", CONTEXT);
    AdmittedCodec admittedRaw = AdmittedCodec.admit(PayloadCodec.LZ4_RAW, "encode", CONTEXT);
    try (var budget = budget(ROOMY, ROOMY)) {
      assertNull(
          TransformStage.encoding(
              AdmittedCodec.admit(PayloadCodec.STORED, "encode", CONTEXT), WINDOW, false, budget));
      for (int index = 0; index < SIZES.length; index++) {
        assertEncoding(zlib[index], admittedZlib, SIZES[index], budget);
        assertEncoding(frame[index], admittedFrame, SIZES[index], budget);
        assertEncoding(raw[index], admittedRaw, SIZES[index], budget);
      }
    }
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

  /** Returns the codec as admitted under an explicit provider, whatever this process pinned. */
  private static AdmittedCodec codec(PayloadCodec codec, Provider provider) {
    return AdmittedCodec.forProvider(codec, provider);
  }

  /** Checks one pack pre-charge row: worst-case output bound, heap and native credit. */
  private static void assertEncoding(
      long[] expected, AdmittedCodec codec, int size, ResourceBudget budget) {
    var encoding = TransformStage.encoding(codec, size, false, budget);
    assertArrayEquals(
        expected,
        new long[] {encoding.outputBound(), encoding.heapBytes(), encoding.nativeBytes()},
        codec.codec() + " at " + size);
  }

  /**
   * Probes one raw encode and the decode of its own output, and requires the admitted codec's cost
   * to name exactly the charge each call made. The decode charge depends on the stored size, so the
   * encoded length of the fixed sample is pinned as well.
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
    AdmittedCodec admitted = codec(PayloadCodec.LZ4_RAW, provider);
    assertEquals(new Cost(encodeHeap, encodeNative), admitted.encodeCost(size));
    assertEquals(new Cost(decodeHeap, decodeNative), admitted.decodeCost(storedSize, size));
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
   * decoder paths add nothing beyond the admitted codec's cost.
   */
  private static void assertFrame(
      Provider provider,
      int size,
      long encodeHeap,
      long encodeNative,
      long decodeHeap,
      long decodeNative)
      throws Exception {
    AdmittedCodec admitted = codec(PayloadCodec.BSA_LZ4_FRAME, provider);
    assertEquals(new Cost(encodeHeap, encodeNative), admitted.encodeCost(size));
    byte[] data = sample(size);
    byte[] stored =
        assertCharge(
            provider + " frame encode " + size,
            encodeHeap,
            encodeNative,
            (budget, sink) ->
                Lz4Frame.encode(
                    provider, source(data), size, sink, () -> {}, budget, null, CONTEXT, true));
    // Lease path: the admitted codec's whole encode cost fills the budget, so any codec
    // reservation on top of the borrowed lease would fail.
    Cost encode = admitted.encodeCost(size);
    try (var budget = budget(encode.heapBytes(), encode.nativeBytes());
        var lease = budget.reserve(encode.heapBytes(), encode.nativeBytes(), 0, 0)) {
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
    void run(ResourceBudget budget, ByteSink sink) throws Exception;
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
  private static ByteSource source(byte[] data) {
    return (offset, bytes) -> bytes.put(data, Math.toIntExact(offset), bytes.remaining());
  }

  /** Collects positional writes in order. */
  private static ByteSink sink(ByteArrayOutputStream output) {
    return (offset, bytes) -> {
      assertEquals(output.size(), offset);
      byte[] chunk = new byte[bytes.remaining()];
      bytes.get(chunk);
      output.write(chunk);
    };
  }
}
