package io.github.evildarkarchon.jbsa.internal.codec;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec.Cost;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Runtime.Provider;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import io.github.evildarkarchon.jbsa.internal.pack.TransformStage;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Random;
import java.util.SplittableRandom;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.function.Executable;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

/**
 * The payload codec module's contract (#92): every admitted {@link PayloadCodec}'s worst-case
 * bound, its heap and native cost per provider and direction, its Codec Profile identity, the
 * native ceilings derived from them, and the behaviour of both LZ4 adapters behind the internal
 * provider seam.
 *
 * <p>Every case, cost or adapter, chooses its provider through the one package-private factory,
 * {@link AdmittedCodec#forProvider}; adapter cases take the adapter that codec runs every call
 * through. That holds on Windows too, where the process itself pins the native provider
 * (JBSA-CODEC-014, JBSA-CODEC-015). Native cases run only on the Windows x64 baseline.
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

  /** Heap for a 4 MiB raw block or a frame decoder's two 4 MiB blocks, with no native memory. */
  private static final long HEAP_ONLY_CEILING = 24L * 1024 * 1024;

  /** Independent frame-building helpers; tests may use lz4-java directly, production may not. */
  private static final LZ4Factory LZ4 = LZ4Factory.safeInstance();

  private static final XXHashFactory XXHASH = XXHashFactory.safeInstance();

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

  /** Portable frames run on one fixed caller heap credit per call, at any size. */
  @Test
  void portableLz4FrameRunsOnAFixedCallerHeapCredit() throws Exception {
    for (int size : SIZES) assertFrame(Provider.PORTABLE, size, 401_685, 0, 8_392_704, 0);
  }

  /**
   * zlib round-trips through the admitted codec on leases of exactly its constant credits, and
   * stored content streams through the admitted codec's decoder at no credit at all, in windows of
   * at most 64 KiB, ending with -1 once its declared size is served.
   */
  @Test
  void jdkCodecsStreamThroughTheAdmittedCodecOnTheirCallersCredit() throws Exception {
    AdmittedCodec zlib = AdmittedCodec.admit(PayloadCodec.ZLIB, "decode", CONTEXT);
    AdmittedCodec stored = AdmittedCodec.admit(PayloadCodec.STORED, "decode", CONTEXT);
    for (int size : new int[] {0, SMALL, WINDOW + 1}) {
      byte[] data = sample(size);
      byte[] encoded =
          onLease(
              132_096,
              524_288,
              (lease, sink) ->
                  zlib.encode(
                      java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(data)),
                      size,
                      sink,
                      () -> {},
                      lease,
                      CONTEXT));
      byte[] inflated =
          onLease(
              66_560,
              65_536,
              (lease, sink) -> {
                try (var decoder =
                    zlib.decoder(source(encoded), encoded.length, size, lease, CONTEXT)) {
                  sink.write(0, ByteBuffer.wrap(drain(decoder, size, 4_096)));
                }
              });
      assertArrayEquals(data, inflated, "zlib " + size);
      List<Integer> windows = new java.util.ArrayList<>();
      byte[] copied =
          onLease(
              0,
              0,
              (lease, sink) -> {
                try (var decoder = stored.decoder(source(data), size, size, lease, CONTEXT)) {
                  assertEquals(0, decoder.read(ByteBuffer.allocate(0)));
                  ByteBuffer output = ByteBuffer.allocate(size + WINDOW);
                  for (int count; (count = decoder.read(output)) >= 0; ) windows.add(count);
                  assertEquals(-1, decoder.read(output));
                  sink.write(0, output.flip());
                }
              });
      assertArrayEquals(data, copied, "stored " + size);
      assertEquals(size <= WINDOW ? (size == 0 ? 0 : 1) : 2, windows.size(), "stored windows");
      assertTrue(windows.stream().allMatch(count -> count > 0 && count <= WINDOW));
    }
  }

  /**
   * Raw LZ4 alone decodes whole blocks: its caller decodes a complete block through {@code decode}
   * and holds the decoded copy, while every other codec streams through {@code decoder}. zlib alone
   * pulls its source from a channel; the LZ4 codecs read exact positional windows. Each entry
   * refuses a codec it does not serve before touching its source, sink or lease.
   */
  @Test
  void eachAdmittedEntryServesOnlyItsCodecs() throws Exception {
    assertEquals(
        java.util.EnumSet.of(PayloadCodec.LZ4_RAW),
        java.util.Arrays.stream(PayloadCodec.values())
            .filter(PayloadCodec::decodesWholeBlocks)
            .collect(
                java.util.stream.Collectors.toCollection(
                    () -> java.util.EnumSet.noneOf(PayloadCodec.class))));
    ByteSource unread = (offset, bytes) -> fail("source read by a refused entry");
    ByteSink unwritten = (offset, bytes) -> fail("sink written by a refused entry");
    try (var budget = budget(ROOMY, ROOMY);
        var lease = budget.reserve(0, 0, 0, 0)) {
      for (PayloadCodec codec : PayloadCodec.values())
        for (Provider provider : Provider.values()) {
          AdmittedCodec admitted = codec(codec, provider);
          if (codec.decodesWholeBlocks())
            assertThrows(
                IllegalArgumentException.class,
                () -> admitted.decoder(unread, 1, 1, lease, CONTEXT),
                codec + " decoder");
          else
            assertThrows(
                IllegalArgumentException.class,
                () -> admitted.decode(unread, 1, 1, unwritten, () -> {}, lease, CONTEXT),
                codec + " decode");
          if (codec != PayloadCodec.ZLIB)
            assertThrows(
                IllegalArgumentException.class,
                () ->
                    admitted.encode(
                        java.nio.channels.Channels.newChannel(
                            java.io.InputStream.nullInputStream()),
                        1,
                        unwritten,
                        () -> {},
                        lease,
                        CONTEXT),
                codec + " channel encode");
          if (codec == PayloadCodec.STORED || codec == PayloadCodec.ZLIB)
            assertThrows(
                IllegalArgumentException.class,
                () -> admitted.encode(unread, 1, unwritten, () -> {}, lease, CONTEXT),
                codec + " positional encode");
        }
    }
  }

  /**
   * A raw block's wire and dispatch sizes are refused before admission, so an oversized block
   * reports its codec limit rather than a capability or capacity failure. Only raw LZ4 has an
   * unsplittable dispatch limit; every other codec admits any size here.
   */
  @Test
  void onlyRawLz4RefusesBlockSizesBeforeAdmission() throws Exception {
    long bound = 16_843_025;
    assertSizeRefused(
        "codec.dispatch-limit",
        () -> AdmittedCodec.checkDecodeSize(PayloadCodec.LZ4_RAW, bound + 1, 1, CONTEXT));
    assertSizeRefused(
        "codec.dispatch-limit",
        () -> AdmittedCodec.checkDecodeSize(PayloadCodec.LZ4_RAW, 1, DISPATCH + 1L, CONTEXT));
    assertSizeRefused(
        "codec.size-limit",
        () -> AdmittedCodec.checkDecodeSize(PayloadCodec.LZ4_RAW, -1, 1, CONTEXT));
    // The pack codec map refuses a raw source through the raw profile's own encode admission.
    assertSizeRefused("codec.dispatch-limit", () -> Lz4Raw.admitEncode(DISPATCH + 1L, CONTEXT));
    AdmittedCodec.checkDecodeSize(PayloadCodec.LZ4_RAW, bound, DISPATCH, CONTEXT);
    Lz4Raw.admitEncode(DISPATCH, CONTEXT);
    for (PayloadCodec codec : PayloadCodec.values())
      if (codec != PayloadCodec.LZ4_RAW)
        AdmittedCodec.checkDecodeSize(codec, Long.MAX_VALUE, Long.MAX_VALUE, CONTEXT);
  }

  /** Asserts a POLICY size refusal with the given identifier. */
  private static void assertSizeRefused(String identifier, Executable call) {
    ArchiveException failure = assertThrows(ArchiveException.class, call);
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(identifier, failure.primaryFailure().diagnosticIdentifier().orElseThrow());
  }

  /**
   * Every encoder and decoder entry takes the caller's lease and rejects a missing one at the call,
   * before it reads its source, writes its sink or allocates codec state (#96). The check precedes
   * provider preflight, so every entry, and every adapter the factory returns, is exercised under
   * both providers on any host.
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
    entries.put(
        "admitted zlib encode",
        () ->
            zlib.encode(
                java.nio.channels.Channels.newChannel(java.io.InputStream.nullInputStream()),
                1,
                unwritten,
                unchecked,
                null,
                CONTEXT));
    entries.put("admitted zlib decoder", () -> zlib.decoder(unread, 1, 1, null, CONTEXT));
    entries.put(
        "admitted stored decoder",
        () ->
            AdmittedCodec.admit(PayloadCodec.STORED, "decode", CONTEXT)
                .decoder(unread, 1, 1, null, CONTEXT));
    entries.put(
        "general frame encode",
        () -> Lz4Frame.encodeGeneral(unread, 1, unwritten, unchecked, null, CONTEXT));
    for (Provider provider : Provider.values()) {
      AdmittedCodec raw = codec(PayloadCodec.LZ4_RAW, provider);
      AdmittedCodec frame = codec(PayloadCodec.BSA_LZ4_FRAME, provider);
      Lz4Adapter adapter = adapterOf(provider);
      entries.put(
          provider + " raw encode",
          () -> Lz4Raw.encode(raw, unread, 1, unwritten, unchecked, null, CONTEXT));
      entries.put(
          provider + " raw encode at a level",
          () -> Lz4Raw.encode(raw, unread, 1, unwritten, unchecked, null, CONTEXT, 12));
      entries.put(
          provider + " raw decode",
          () -> Lz4Raw.decode(raw, unread, 1, 1, unwritten, unchecked, null, CONTEXT));
      entries.put(
          provider + " versioned BSA frame encode",
          () -> Lz4Frame.encode(frame, unread, 1, unwritten, unchecked, null, CONTEXT));
      entries.put(
          provider + " versioned BSA frame decoder",
          () -> Lz4Frame.decoder(frame, unread, 1, 1, null, CONTEXT));
      entries.put(
          provider + " admitted raw encode",
          () -> raw.encode(unread, 1, unwritten, unchecked, null, CONTEXT));
      entries.put(
          provider + " admitted raw decode",
          () -> raw.decode(unread, 1, 1, unwritten, unchecked, null, CONTEXT));
      entries.put(
          provider + " admitted BSA frame encode",
          () -> frame.encode(unread, 1, unwritten, unchecked, null, CONTEXT));
      entries.put(
          provider + " admitted BSA frame decoder",
          () -> frame.decoder(unread, 1, 1, null, CONTEXT));
      entries.put(
          provider + " adapter frame encode",
          () -> adapter.encodeFrame(unread, 1, unwritten, unchecked, null, CONTEXT, true));
      entries.put(
          provider + " adapter frame decoder",
          () -> adapter.frameDecoder(unread, 1, 1, null, CONTEXT));
      entries.put(
          provider + " adapter raw encode",
          () -> adapter.encodeRaw(unread, 1, unwritten, unchecked, null, CONTEXT, 12));
      entries.put(
          provider + " adapter raw decode",
          () -> adapter.decodeRaw(unread, 1, 1, unwritten, unchecked, null, CONTEXT));
    }
    entries.forEach(
        (entry, call) ->
            assertEquals(
                "lease", assertThrows(NullPointerException.class, call, entry).getMessage()));
  }

  /**
   * Portable raw HC blocks round-trip deterministically with heap-only credits, including empty
   * input: the budget grants no native memory at all.
   */
  @Test
  void rawBlocksRoundTripDeterministicallyWithoutNativeMemory() throws Exception {
    for (int size : new int[] {0, 5, 65536, 1_048_576, 4_194_304}) {
      byte[] data = mixed(size, 43);
      try (var budget = heapOnlyBudget()) {
        byte[] block = rawEncode(Provider.PORTABLE, data, budget);
        assertArrayEquals(block, rawEncode(Provider.PORTABLE, data, budget));
        assertArrayEquals(data, rawDecode(Provider.PORTABLE, block, size, budget));
        assertAllHeapReturned(budget);
      }
    }
  }

  /**
   * Malformed blocks, overlong output and short output fail as FORMAT with the adapter's own
   * profile, before any sink write.
   */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void rawDecodeRejectsMalformedAndMismatchedBlocks(Provider provider) throws Exception {
    Lz4Adapter adapter = adapter(provider);
    byte[] hello;
    try (var budget = crossBudget()) {
      hello = rawEncode(provider, "hello".getBytes(), budget);
    }
    for (byte[] invalid : new byte[][] {new byte[0], {0, 0, 0}, {16, 65}, hello}) {
      int size = invalid == hello ? 4 : 5;
      try (var budget = crossBudget();
          var lease =
              lease(
                  budget, codec(PayloadCodec.LZ4_RAW, provider).decodeCost(invalid.length, size))) {
        ArchiveException failure =
            assertThrows(
                ArchiveException.class,
                () ->
                    adapter.decodeRaw(
                        source(invalid),
                        invalid.length,
                        size,
                        (offset, bytes) -> fail("destination touched"),
                        () -> {},
                        lease,
                        CONTEXT));
        assertEquals(FailureKind.FORMAT, failure.primaryFailure().kind());
        assertEquals(provider.profile(), failure.diagnostics().getFirst().values().get("profile"));
      }
    }
  }

  /**
   * Every adapter's BSA frame carries exactly the JBSA-BSA-010 descriptor and one block per 64 KiB
   * window, and decodes back through the same adapter.
   */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void bsaFrameUsesTheVersionedBsaDescriptor(Provider provider) throws Exception {
    adapter(provider);
    byte[] data = mixed(200_000, 44);
    byte[] frame = frameEncode(provider, data, true);
    ByteBuffer wire = ByteBuffer.wrap(frame).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x184D2204, wire.getInt());
    assertEquals(0x60, wire.get() & 0xFF, "version 01, independent blocks, no checksums or size");
    assertEquals(0x70, wire.get() & 0xFF, "4 MiB block maximum");
    int checksum = XXHASH.hash32().hash(new byte[] {0x60, 0x70}, 0, 2, 0);
    assertEquals((checksum >>> 8) & 0xFF, wire.get() & 0xFF);
    int blocks = 0;
    for (int header = wire.getInt(); header != 0; header = wire.getInt()) {
      int size = header & 0x7FFFFFFF;
      assertTrue(size <= 65536, "each auto-flushed block holds at most one window");
      wire.position(wire.position() + size);
      blocks++;
    }
    assertEquals(4, blocks);
    assertFalse(wire.hasRemaining(), "no content checksum follows the end mark");
    assertArrayEquals(data, frameDecode(provider, frame, data.length, 65536));
  }

  /** Both frame profiles round-trip deterministically across window edges and empty content. */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void framesRoundTripAcrossWindowEdges(Provider provider) throws Exception {
    adapter(provider);
    for (boolean bsa : new boolean[] {true, false}) {
      for (int size : new int[] {0, 1, 65535, 65536, 65537, 400_000}) {
        byte[] data = mixed(size, 45);
        byte[] frame = frameEncode(provider, data, bsa);
        assertArrayEquals(frame, frameEncode(provider, data, bsa));
        assertArrayEquals(data, frameDecode(provider, frame, size, 65536));
        assertArrayEquals(data, frameDecode(provider, frame, size, 777));
      }
    }
  }

  /** Upstream-shaped frames with 4 MiB blocks, checksums, and content size decode exactly. */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void decodesMaximumBlocksAndEveryOptionalFrameField(Provider provider) throws Exception {
    adapter(provider);
    byte[] data = mixed(5 * 1024 * 1024, 46);
    for (int flags : new int[] {0x60, 0x70, 0x68, 0x64, 0x7C}) {
      byte[] frame = frame(flags, 0x70, data, 4 * 1024 * 1024, true);
      assertArrayEquals(data, frameDecode(provider, frame, data.length, 8192), "FLG " + flags);
    }
    // A raw (incompressible-flagged) maximum block is copied rather than decompressed.
    byte[] raw = frame(0x60, 0x70, data, 4 * 1024 * 1024, false);
    assertArrayEquals(data, frameDecode(provider, raw, data.length, 65536));
  }

  /**
   * Header 0x80000000 is an empty uncompressed block, not an end mark. The native adapter, the
   * reference LZ4 1.10.0 decoder, skips it, and the portable adapter must agree so both accept the
   * same frames.
   */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void skipsAnEmptyUncompressedBlockLikeTheReferenceDecoder(Provider provider) throws Exception {
    adapter(provider);
    byte[] data = mixed(70_000, 51);
    byte[] frame = withEmptyRawBlockBeforeEndMark(frame(0x60, 0x40, data, 65536, false));
    assertArrayEquals(data, frameDecode(provider, frame, data.length, 4096));
    assertArrayEquals(data, frameDecode(provider, frame, data.length, 65536));
  }

  /**
   * Corruption, truncation, trailing bytes, reserved bits, and checksum or size mismatches fail as
   * FORMAT with the decoding adapter's own profile; nothing past the declared size is published.
   */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void rejectsMalformedFrames(Provider provider) throws Exception {
    adapter(provider);
    byte[] data = mixed(150_000, 47);
    byte[] general = frameEncode(provider, data, false);
    byte[] checksummed = frame(0x70, 0x40, data, 65536, true);
    byte[] badContent = general.clone();
    badContent[badContent.length - 1] ^= 1;
    byte[] badBlock = checksummed.clone();
    badBlock[badBlock.length - 5] ^= 1;
    byte[] badHeader = general.clone();
    badHeader[6 + 8] ^= 1;
    byte[] reserved = frame(0x62, 0x40, data, 65536, true);
    // Stored raw so the block is certainly larger than the declared 64 KiB maximum.
    byte[] tooLarge = frame(0x60, 0x40, data, 131072, false);
    byte[] skippable = general.clone();
    // 0x184D2A50 is a skippable frame: valid LZ4 transport, but never an archive payload.
    skippable[0] = 0x50;
    skippable[1] = 0x2A;
    byte[] wrongContentSize = frame(0x68, 0x40, data, 65536, true);
    ByteBuffer.wrap(wrongContentSize, 6, 8)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putLong(data.length + 1L);
    fixHeaderChecksum(wrongContentSize, 8);
    for (byte[] invalid :
        new byte[][] {
          new byte[0],
          new byte[20],
          badContent,
          badBlock,
          badHeader,
          reserved,
          tooLarge,
          skippable,
          wrongContentSize,
          Arrays.copyOf(general, general.length - 1),
          Arrays.copyOf(general, general.length + 1)
        }) {
      ArchiveException failure =
          assertThrows(
              ArchiveException.class, () -> frameDecode(provider, invalid, data.length, 65536));
      assertEquals(FailureKind.FORMAT, failure.primaryFailure().kind());
      assertEquals(provider.profile(), failure.diagnostics().getFirst().values().get("profile"));
    }
    for (long wrongSize : new long[] {data.length - 1, data.length + 1})
      assertEquals(
          "codec.size-mismatch",
          assertThrows(
                  ArchiveException.class, () -> frameDecode(provider, general, wrongSize, 65536))
              .primaryFailure()
              .diagnosticIdentifier()
              .orElseThrow());
  }

  /**
   * A valid linked-block frame is a portable-adapter capability limit, not corrupt data: it is
   * reported as {@code CAPABILITY codec.unavailable} and never silently mis-decoded.
   */
  @Test
  void reportsLinkedBlockFramesAsUnsupported() throws Exception {
    byte[] data = mixed(1000, 48);
    byte[] linked = frame(0x40, 0x40, data, 65536, true);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> frameDecode(Provider.PORTABLE, linked, data.length, 65536));
    assertEquals(FailureKind.CAPABILITY, failure.primaryFailure().kind());
    assertEquals(
        "codec.unavailable", failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    assertEquals(
        "dependent-blocks", failure.diagnostics().getFirst().values().get("capabilityCause"));
  }

  /**
   * Too little heap fails as POLICY before the source or sink is touched; nothing is native. The
   * caller's reservation of the portable cost is the only admission, so it is what refuses, and a
   * portable encoder offered no admission refuses before either callback runs.
   */
  @Test
  void refusesUnadmittedHeapBeforeEffects() throws Exception {
    AdmittedCodec frame = codec(PayloadCodec.BSA_LZ4_FRAME, Provider.PORTABLE);
    AdmittedCodec raw = codec(PayloadCodec.LZ4_RAW, Provider.PORTABLE);
    assertEquals(0, frame.encodeCost(1).nativeBytes());
    assertEquals(0, raw.encodeCost(1).nativeBytes());
    try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT, 4096, 0, 0)) {
      for (AdmittedCodec codec : new AdmittedCodec[] {frame, raw}) {
        ArchiveException refused =
            assertThrows(ArchiveException.class, () -> lease(budget, codec.encodeCost(1)));
        assertEquals(FailureKind.POLICY, refused.primaryFailure().kind());
      }
    }
    Lz4Adapter portable = adapterOf(Provider.PORTABLE);
    ByteSource untouched = (offset, bytes) -> fail("source touched");
    ByteSink unwritten = (offset, bytes) -> fail("sink touched");
    assertThrows(
        NullPointerException.class,
        () -> portable.encodeFrame(untouched, 1, unwritten, () -> {}, null, CONTEXT, true));
    assertThrows(
        NullPointerException.class,
        () -> portable.encodeRaw(untouched, 1, unwritten, () -> {}, null, CONTEXT, 12));
  }

  /** A decoder handed to another thread still decodes and closes exactly once. */
  @ParameterizedTest
  @EnumSource(Provider.class)
  void decoderMayBeConsumedAndClosedByAnotherThread(Provider provider) throws Exception {
    Lz4Adapter adapter = adapter(provider);
    byte[] data = mixed(200_000, 49);
    byte[] frame = frameEncode(provider, data, true);
    try (var budget = crossBudget();
        var lease =
            lease(
                budget,
                codec(PayloadCodec.BSA_LZ4_FRAME, provider)
                    .decodeCost(frame.length, data.length))) {
      var decoder = adapter.frameDecoder(source(frame), frame.length, data.length, lease, CONTEXT);
      byte[] decoded =
          java.util.concurrent.CompletableFuture.supplyAsync(
                  () -> {
                    try (decoder) {
                      return drain(decoder, data.length, 8192);
                    } catch (java.io.IOException failure) {
                      throw new java.util.concurrent.CompletionException(failure);
                    }
                  })
              .get(10, java.util.concurrent.TimeUnit.SECONDS);
      assertArrayEquals(data, decoded);
      assertThrows(
          java.nio.channels.ClosedChannelException.class,
          () -> decoder.read(ByteBuffer.allocate(1)));
    }
  }

  /**
   * Native and portable output decode through the other adapter in both directions. Neither
   * adapter's bytes are claimed identical to the other's (JBSA-CODEC-013); only content is.
   */
  @Test
  // Cross-decoding needs the native LWJGL adapter, a Windows x64 boundary.
  @EnabledOnOs(OS.WINDOWS)
  void crossDecodesNativeAndPortableOutputBothWays() throws Exception {
    adapter(Provider.NATIVE);
    for (int size : new int[] {0, 5, 65537, 1_048_576}) {
      byte[] data = mixed(size, 50);
      try (var budget = crossBudget()) {
        byte[] nativeBlock = rawEncode(Provider.NATIVE, data, budget);
        byte[] portableBlock = rawEncode(Provider.PORTABLE, data, budget);
        assertArrayEquals(data, rawDecode(Provider.PORTABLE, nativeBlock, size, budget));
        assertArrayEquals(data, rawDecode(Provider.NATIVE, portableBlock, size, budget));
      }
      for (boolean bsa : new boolean[] {true, false}) {
        byte[] nativeFrame = frameEncode(Provider.NATIVE, data, bsa);
        byte[] portableFrame = frameEncode(Provider.PORTABLE, data, bsa);
        if (bsa) assertArrayEquals(data, frameDecode(Provider.PORTABLE, nativeFrame, size, 65536));
        else
          // The native general profile links its blocks, which the portable adapter cannot read.
          assertEquals(
              FailureKind.CAPABILITY,
              assertThrows(
                      ArchiveException.class,
                      () -> frameDecode(Provider.PORTABLE, nativeFrame, size, 65536))
                  .primaryFailure()
                  .kind());
        assertArrayEquals(data, frameDecode(Provider.NATIVE, portableFrame, size, 65536));
      }
    }
  }

  /**
   * Returns the adapter under test through the package-private factory. The native adapter is
   * qualified and loaded only on the Windows x64 baseline, so elsewhere its cases are skipped; on
   * Windows it must load, so a broken native adapter fails rather than skips.
   */
  private static Lz4Adapter adapter(Provider provider) throws ArchiveException {
    if (provider == Provider.NATIVE) {
      assumeTrue(OS.WINDOWS.isCurrentOs(), "native LZ4 is a Windows x64 boundary");
      Lz4Runtime.nativePreflight("lz4-frame", "decode", CONTEXT);
    }
    return adapterOf(provider);
  }

  /**
   * Encodes one raw block at the Starfield level through the given adapter, on a lease of that
   * provider's encode cost drawn from {@code budget}.
   */
  private static byte[] rawEncode(Provider provider, byte[] data, ResourceBudget budget)
      throws Exception {
    var output = new ByteArrayOutputStream();
    try (var lease = lease(budget, codec(PayloadCodec.LZ4_RAW, provider).encodeCost(data.length))) {
      adapterOf(provider)
          .encodeRaw(source(data), data.length, sink(output), () -> {}, lease, CONTEXT, 12);
    }
    return output.toByteArray();
  }

  /**
   * Decodes one raw block through the given adapter, on a lease of that provider's decode cost
   * drawn from {@code budget}.
   */
  private static byte[] rawDecode(Provider provider, byte[] block, int size, ResourceBudget budget)
      throws Exception {
    var output = new ByteArrayOutputStream();
    try (var lease =
        lease(budget, codec(PayloadCodec.LZ4_RAW, provider).decodeCost(block.length, size))) {
      adapterOf(provider)
          .decodeRaw(source(block), block.length, size, sink(output), () -> {}, lease, CONTEXT);
    }
    return output.toByteArray();
  }

  /** Encodes one frame through the given adapter under a ledger that fits either provider. */
  private static byte[] frameEncode(Provider provider, byte[] data, boolean bsa) throws Exception {
    var output = new ByteArrayOutputStream();
    try (var budget = crossBudget();
        var lease =
            lease(budget, codec(PayloadCodec.BSA_LZ4_FRAME, provider).encodeCost(data.length))) {
      long count =
          adapterOf(provider)
              .encodeFrame(source(data), data.length, sink(output), () -> {}, lease, CONTEXT, bsa);
      assertEquals(output.size(), count);
    }
    return output.toByteArray();
  }

  /**
   * Decodes one whole frame through the given adapter's incremental decoder with a fixed
   * destination window, on a lease of exactly that provider's decoder cost.
   */
  private static byte[] frameDecode(Provider provider, byte[] frame, long size, int window)
      throws Exception {
    try (var budget = crossBudget();
        var lease =
            lease(
                budget,
                codec(PayloadCodec.BSA_LZ4_FRAME, provider).decodeCost(frame.length, size));
        var decoder =
            adapterOf(provider).frameDecoder(source(frame), frame.length, size, lease, CONTEXT)) {
      return drain(decoder, (int) Math.min(size, Integer.MAX_VALUE), window);
    }
  }

  /** Reads a decoder to terminal EOF through bounded windows. */
  private static byte[] drain(PayloadDecoder decoder, int size, int window)
      throws java.io.IOException {
    var output = new ByteArrayOutputStream(Math.max(0, size));
    ByteBuffer buffer = ByteBuffer.allocate(window);
    while (decoder.read(buffer.clear()) >= 0) output.write(buffer.array(), 0, buffer.position());
    return output.toByteArray();
  }

  /** Builds a heap-only ledger; zero native credit proves the portable adapter uses none. */
  private static ResourceBudget heapOnlyBudget() {
    return new ResourceBudget(ResourceLimits.standard(), CONTEXT, HEAP_ONLY_CEILING, 0, 0);
  }

  /** Builds a ledger that admits either adapter's credit, native direct buffers included. */
  private static ResourceBudget crossBudget() {
    return new ResourceBudget(ResourceLimits.standard(), CONTEXT, 64L << 20, 64L << 20, 0);
  }

  /** Requires every heap credit of a heap-only ledger to be available again. */
  private static void assertAllHeapReturned(ResourceBudget budget) throws Exception {
    try (var all = budget.reserve(HEAP_ONLY_CEILING, 0, 0, 0)) {
      assertNotNull(all);
    }
  }

  /** Reserves one call's cost from the ledger, as every codec caller must before the call. */
  private static ResourceBudget.Lease lease(ResourceBudget budget, Cost cost)
      throws ArchiveException {
    return budget.reserve(cost.heapBytes(), cost.nativeBytes(), 0, 0);
  }

  /** Returns deterministic, partly compressible sample content. */
  private static byte[] mixed(int size, long seed) {
    byte[] data = new byte[size];
    Random random = new Random(seed);
    for (int index = 0; index < size; index++)
      data[index] = (byte) (index % 3 == 0 ? random.nextInt() : index / 97);
    return data;
  }

  /**
   * Builds an LZ4 frame independently of the adapters under test. Content size and checksums follow
   * the FLG bits; {@code compress} false stores every block raw with the high bit set.
   */
  private static byte[] frame(int flg, int bd, byte[] data, int blockSize, boolean compress) {
    var output = new ByteArrayOutputStream();
    ByteBuffer header = ByteBuffer.allocate(15).order(ByteOrder.LITTLE_ENDIAN);
    header.putInt(0x184D2204).put((byte) flg).put((byte) bd);
    if ((flg & 0x08) != 0) header.putLong(data.length);
    int descriptor = header.position() - 4;
    header.put((byte) ((XXHASH.hash32().hash(header.array(), 4, descriptor, 0) >>> 8) & 0xFF));
    output.write(header.array(), 0, header.position());
    var compressor = LZ4.highCompressor(9);
    for (int offset = 0; offset < data.length; offset += blockSize) {
      int length = Math.min(blockSize, data.length - offset);
      byte[] block;
      int word;
      if (compress) {
        block = compressor.compress(Arrays.copyOfRange(data, offset, offset + length));
        word = block.length;
      } else {
        block = Arrays.copyOfRange(data, offset, offset + length);
        word = length | 0x80000000;
      }
      output.writeBytes(little(word));
      output.writeBytes(block);
      if ((flg & 0x10) != 0)
        output.writeBytes(little(XXHASH.hash32().hash(block, 0, block.length, 0)));
    }
    output.writeBytes(little(0));
    if ((flg & 0x04) != 0) output.writeBytes(little(XXHASH.hash32().hash(data, 0, data.length, 0)));
    return output.toByteArray();
  }

  /**
   * Inserts a 0x80000000 block header just before the end mark of a frame built without a content
   * checksum, whose end mark is therefore its final four bytes.
   */
  private static byte[] withEmptyRawBlockBeforeEndMark(byte[] frame) {
    var output = new ByteArrayOutputStream();
    output.write(frame, 0, frame.length - 4);
    output.writeBytes(little(0x80000000));
    output.writeBytes(little(0));
    return output.toByteArray();
  }

  /** Recomputes the descriptor checksum after a test edits the descriptor. */
  private static void fixHeaderChecksum(byte[] frame, int optionalBytes) {
    int descriptor = 2 + optionalBytes;
    frame[4 + descriptor] = (byte) ((XXHASH.hash32().hash(frame, 4, descriptor, 0) >>> 8) & 0xFF);
  }

  /** Encodes one little-endian 32-bit word. */
  private static byte[] little(int value) {
    return ByteBuffer.allocate(4).order(ByteOrder.LITTLE_ENDIAN).putInt(value).array();
  }

  /** Returns the codec as admitted under an explicit provider, whatever this process pinned. */
  private static AdmittedCodec codec(PayloadCodec codec, Provider provider) {
    return AdmittedCodec.forProvider(codec, provider);
  }

  /**
   * Returns a provider's LZ4 adapter through the same factory: the adapter that provider's admitted
   * LZ4 codecs run every call through. Both LZ4 codecs share one adapter per provider, so the raw
   * codec serves frame cases too. Unlike {@link #adapter}, this loads nothing first.
   */
  private static Lz4Adapter adapterOf(Provider provider) {
    return codec(PayloadCodec.LZ4_RAW, provider).adapter();
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
   * Runs one raw encode and the decode of its own output through the admitted codec's whole-block
   * entries, each on a lease of exactly the admitted codec's cost. The decode cost depends on the
   * stored size, so the encoded length of the fixed sample is pinned as well; the admitted encoder
   * runs at Starfield's qualified HC level 12, which these lengths were recorded at.
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
            (lease, sink) -> admitted.encode(source(data), size, sink, () -> {}, lease, CONTEXT));
    assertEquals(storedSize, stored.length, provider + " raw stored size " + size);
    byte[] decoded =
        onLease(
            decodeHeap,
            decodeNative,
            (lease, sink) ->
                admitted.decode(
                    source(stored), stored.length, size, sink, () -> {}, lease, CONTEXT));
    assertArrayEquals(data, decoded);
  }

  /**
   * Runs the BSA frame's encode and its incremental decoder through the admitted codec's entries,
   * each on a lease of exactly the admitted codec's cost.
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
        onLease(
            encodeHeap,
            encodeNative,
            (lease, sink) -> admitted.encode(source(data), size, sink, () -> {}, lease, CONTEXT));
    assertEquals(new Cost(decodeHeap, decodeNative), admitted.decodeCost(stored.length, size));
    byte[] streamed =
        onLease(
            decodeHeap,
            decodeNative,
            (lease, sink) -> {
              try (var decoder =
                  admitted.decoder(source(stored), stored.length, size, lease, CONTEXT)) {
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
