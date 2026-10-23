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
import org.junit.jupiter.api.function.Executable;

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
 * <p>Every codec follows one charging rule (#96): the caller reserves the admitted codec's cost and
 * lends the lease to the stream, which never reserves. A codec call is therefore measured by its
 * published credit and by succeeding with no spare credit at all, under a budget whose ceilings
 * equal that credit and which the caller's lease fills, which proves the codec adds no reservation
 * of its own.
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
   * The pack side's caller pre-charge per codec and size: zlib's constant credit, and the BSA
   * frame's and raw LZ4's credit for the pinned provider. Raw LZ4 no longer reserves for itself, so
   * the map now publishes its real whole-block cost (#96).
   */
  @Test
  void packPrechargesFollowTheCodecAndThePinnedProvider() throws Exception {
    // Rows are {output bound, heap, native} for the SMALL, WINDOW and DISPATCH payloads.
    long[][] zlib = {
      {27, 132_096, 524_288}, {74_134, 132_096, 524_288}, {18_972_694, 132_096, 524_288}
    };
    AdmittedCodec admittedRaw = AdmittedCodec.admit(PayloadCodec.LZ4_RAW, "encode", CONTEXT);
    long[][] raw =
        switch (admittedRaw.provider()) {
          case NATIVE ->
              new long[][] {
                {21, 4_096, 524_346}, {65_809, 4_096, 655_665}, {16_843_025, 4_096, 34_144_561}
              };
          case PORTABLE ->
              new long[][] {{21, 270_362, 0}, {65_809, 401_681, 0}, {16_843_025, 33_890_577, 0}};
        };
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

  /**
   * Native raw LZ4 runs entirely on the caller's lease for its whole block, output bound and HC
   * state.
   */
  // Native LZ4 is qualified and loaded only on the Windows x64 baseline.
  @EnabledOnOs(OS.WINDOWS)
  @Test
  void nativeRawLz4RunsOnTheCallersWholeBlockLease() throws Exception {
    assertRaw(Provider.NATIVE, SMALL, 4_096, 524_346, 6, 4_096, 27);
    assertRaw(Provider.NATIVE, WINDOW, 4_096, 655_665, 65_794, 4_096, 131_346);
    assertRaw(Provider.NATIVE, DISPATCH, 4_096, 34_144_561, 16_842_862, 4_096, 33_620_094);
  }

  /** Portable raw LZ4 runs on a lease for the same working set on the heap and no native memory. */
  @Test
  void portableRawLz4RunsOnTheCallersWholeBlockHeapLease() throws Exception {
    assertRaw(Provider.PORTABLE, SMALL, 270_362, 0, 6, 4_123, 0);
    assertRaw(Provider.PORTABLE, WINDOW, 401_681, 0, 65_794, 135_442, 0);
    assertRaw(Provider.PORTABLE, DISPATCH, 33_890_577, 0, 16_842_862, 33_624_190, 0);
  }

  /** Native frames run on one fixed caller credit per call, at any size. */
  // Native LZ4 is qualified and loaded only on the Windows x64 baseline.
  @EnabledOnOs(OS.WINDOWS)
  @Test
  void nativeLz4FrameRunsOnAFixedCallerCredit() throws Exception {
    for (int size : SIZES) assertFrame(Provider.NATIVE, size, 4_096, 2_293_760, 4_096, 9_633_792);
  }

  /**
   * Portable frames run on one fixed caller heap credit per call. The one-shot decoder's credit is
   * the decoder's plus the one 64 KiB window it copies output through.
   */
  @Test
  void portableLz4FrameRunsOnAFixedCallerHeapCredit() throws Exception {
    for (int size : SIZES) assertFrame(Provider.PORTABLE, size, 401_685, 0, 8_458_240, 0);
  }

  /**
   * Every encoder and decoder entry takes the caller's lease and rejects a missing one at the call,
   * before it reads its source, writes its sink or allocates codec state (#96). The check precedes
   * provider preflight, so every entry is exercised under both providers on any host.
   */
  @Test
  void everyStreamEntryRejectsAMissingLease() throws Exception {
    ByteSource unread = (offset, bytes) -> fail("source read without a lease");
    ByteSink unwritten = (offset, bytes) -> fail("sink written without a lease");
    Checkpoint unchecked = () -> fail("checkpoint reached without a lease");
    AdmittedCodec zlib = AdmittedCodec.admit(PayloadCodec.ZLIB, "encode", CONTEXT);
    var entries = new java.util.LinkedHashMap<String, Executable>();
    entries.put(
        "zlib encode",
        () ->
            JdkZlib.encode(
                zlib,
                java.nio.channels.Channels.newChannel(java.io.InputStream.nullInputStream()),
                1,
                unwritten,
                unchecked,
                null,
                CONTEXT));
    entries.put("zlib decoder", () -> JdkZlib.decoder(zlib, unread, 1, 1, null, CONTEXT));
    entries.put("raw encode", () -> Lz4Raw.encode(unread, 1, unwritten, unchecked, null, CONTEXT));
    entries.put(
        "raw encode at a level",
        () -> Lz4Raw.encode(unread, 1, unwritten, unchecked, null, CONTEXT, 12));
    entries.put(
        "raw decode", () -> Lz4Raw.decode(unread, 1, 1, unwritten, unchecked, null, CONTEXT));
    entries.put(
        "frame encode", () -> Lz4Frame.encode(unread, 1, unwritten, unchecked, null, CONTEXT));
    entries.put(
        "BSA frame encode",
        () -> Lz4Frame.encodeBsa(unread, 1, unwritten, unchecked, null, CONTEXT));
    entries.put("frame decoder", () -> Lz4Frame.decoder(unread, 1, 1, null, CONTEXT));
    entries.put(
        "frame one-shot decode",
        () -> Lz4Frame.decode(unread, 1, 1, unwritten, unchecked, null, CONTEXT));
    for (Provider provider : Provider.values()) {
      AdmittedCodec frame = codec(PayloadCodec.BSA_LZ4_FRAME, provider);
      entries.put(
          provider + " raw encode",
          () -> Lz4Raw.encode(provider, unread, 1, unwritten, unchecked, null, CONTEXT, 12));
      entries.put(
          provider + " raw decode",
          () -> Lz4Raw.decode(provider, unread, 1, 1, unwritten, unchecked, null, CONTEXT));
      entries.put(
          provider + " frame encode",
          () -> Lz4Frame.encode(provider, unread, 1, unwritten, unchecked, null, CONTEXT, true));
      entries.put(
          provider + " frame decoder",
          () -> Lz4Frame.decoder(provider, unread, 1, 1, null, CONTEXT));
      entries.put(
          provider + " frame one-shot decode",
          () -> Lz4Frame.decode(provider, unread, 1, 1, unwritten, unchecked, null, CONTEXT));
      entries.put(
          provider + " versioned BSA frame encode",
          () -> BsaLz4Frame.encode(frame, unread, 1, unwritten, unchecked, null, CONTEXT));
      entries.put(
          provider + " versioned BSA frame decoder",
          () -> BsaLz4Frame.decoder(frame, unread, 1, 1, null, CONTEXT));
    }
    entries.forEach(
        (entry, call) ->
            assertEquals(
                "lease", assertThrows(NullPointerException.class, call, entry).getMessage()));
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
   * Runs one raw encode and the decode of its own output, each on a lease of exactly the admitted
   * codec's cost. The decode cost depends on the stored size, so the encoded length of the fixed
   * sample is pinned as well.
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
        onLease(
            encodeHeap,
            encodeNative,
            (lease, sink) ->
                Lz4Raw.encode(provider, source(data), size, sink, () -> {}, lease, CONTEXT, 12));
    assertEquals(storedSize, stored.length, provider + " raw stored size " + size);
    byte[] decoded =
        onLease(
            decodeHeap,
            decodeNative,
            (lease, sink) ->
                Lz4Raw.decode(
                    provider, source(stored), stored.length, size, sink, () -> {}, lease, CONTEXT));
    assertArrayEquals(data, decoded);
  }

  /**
   * Runs the BSA frame's encode, its one-shot decode and its incremental decoder, each on a lease
   * of exactly its credit: the admitted codec's cost, or for the one-shot decode the given credit.
   */
  private static void assertFrame(
      Provider provider,
      int size,
      long encodeHeap,
      long encodeNative,
      long oneShotHeap,
      long oneShotNative)
      throws Exception {
    AdmittedCodec admitted = codec(PayloadCodec.BSA_LZ4_FRAME, provider);
    assertEquals(new Cost(encodeHeap, encodeNative), admitted.encodeCost(size));
    byte[] data = sample(size);
    byte[] stored =
        onLease(
            encodeHeap,
            encodeNative,
            (lease, sink) ->
                Lz4Frame.encode(
                    provider, source(data), size, sink, () -> {}, lease, CONTEXT, true));
    byte[] decoded =
        onLease(
            oneShotHeap,
            oneShotNative,
            (lease, sink) ->
                Lz4Frame.decode(
                    provider, source(stored), stored.length, size, sink, () -> {}, lease, CONTEXT));
    assertArrayEquals(data, decoded);
    Cost decode = admitted.decodeCost(stored.length, size);
    byte[] streamed =
        onLease(
            decode.heapBytes(),
            decode.nativeBytes(),
            (lease, sink) -> {
              try (var decoder =
                  Lz4Frame.decoder(provider, source(stored), stored.length, size, lease, CONTEXT)) {
                ByteBuffer output = ByteBuffer.allocate(size + 1);
                while (decoder.read(output) >= 0) {
                  // Drains to terminal EOF, which also validates exact frame consumption.
                }
                sink.write(0, output.flip());
              }
            });
    assertArrayEquals(data, streamed);
  }

  /**
   * Runs one codec call on a lease that fills a budget whose ceilings are exactly {@code heap} and
   * {@code nativeBytes}. Success proves the call needs no credit beyond the caller's lease, so the
   * codec reserves nothing of its own.
   *
   * @return the bytes the call wrote
   */
  private static byte[] onLease(long heap, long nativeBytes, Leased call) throws Exception {
    try (var budget = budget(heap, nativeBytes);
        var lease = budget.reserve(heap, nativeBytes, 0, 0)) {
      var sink = new ByteArrayOutputStream();
      call.run(lease, sink(sink));
      return sink.toByteArray();
    }
  }

  /** One codec call on a caller's lease, writing its output to a supplied sink. */
  @FunctionalInterface
  private interface Leased {
    /** Runs the call, which borrows the lease and must not close it. */
    void run(ResourceBudget.Lease lease, ByteSink sink) throws Exception;
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
