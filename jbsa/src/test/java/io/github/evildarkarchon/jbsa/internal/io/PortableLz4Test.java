package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Runtime.Provider;
import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.Random;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.xxhash.XXHashFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/**
 * Exercises the portable lz4-java provider explicitly on every operating system, including on
 * Windows where the process pins the native provider (JBSA-CODEC-014, JBSA-CODEC-015).
 */
final class PortableLz4Test {
  private static final IoContext CONTEXT = IoContext.of(Path.of("portable.bin"), Operation.OPEN);

  /** Heap for a 4 MiB raw block or a frame decoder's two 4 MiB blocks; native memory is zero. */
  private static final long HEAP_CEILING = 24L * 1024 * 1024;

  /** Independent frame-building helpers; tests may use lz4-java directly, production may not. */
  private static final LZ4Factory LZ4 = LZ4Factory.safeInstance();

  private static final XXHashFactory XXHASH = XXHashFactory.safeInstance();

  /** The portable provider must never load lz4-java's bundled JNI library in this process. */
  @AfterAll
  static void neverLoadsLz4JavaJni() {
    assertFalse(net.jpountz.util.Native.isLoaded(), "lz4-java loaded its JNI library");
  }

  /** Raw HC blocks round-trip deterministically with heap-only credits, including empty input. */
  @Test
  void rawBlocksRoundTripDeterministicallyWithoutNativeMemory() throws Exception {
    for (int size : new int[] {0, 5, 65536, 1_048_576, 4_194_304}) {
      byte[] data = sample(size, 43);
      try (var budget = budget()) {
        byte[] block = rawEncode(Provider.PORTABLE, data, budget);
        assertArrayEquals(block, rawEncode(Provider.PORTABLE, data, budget));
        assertArrayEquals(data, rawDecode(Provider.PORTABLE, block, size, budget));
        assertAllCreditsReturned(budget);
      }
    }
  }

  /** Malformed blocks, overlong output, and short output fail as FORMAT before any sink write. */
  @Test
  void rawDecodeRejectsMalformedAndMismatchedBlocks() throws Exception {
    byte[] hello;
    try (var budget = budget()) {
      hello = rawEncode(Provider.PORTABLE, "hello".getBytes(), budget);
    }
    for (byte[] invalid : new byte[][] {new byte[0], {0, 0, 0}, {16, 65}, hello}) {
      try (var budget = budget()) {
        ArchiveException failure =
            assertThrows(
                ArchiveException.class,
                () ->
                    Lz4Raw.decode(
                        Provider.PORTABLE,
                        source(invalid),
                        invalid.length,
                        invalid == hello ? 4 : 5,
                        (offset, bytes) -> fail("destination touched"),
                        () -> {},
                        budget,
                        CONTEXT));
        assertEquals(FailureKind.FORMAT, failure.primaryFailure().kind());
        assertEquals(
            Lz4Runtime.PORTABLE_PROFILE, failure.diagnostics().getFirst().values().get("profile"));
        assertAllCreditsReturned(budget);
      }
    }
  }

  /** The BSA frame carries exactly the JBSA-BSA-010 descriptor and one block per 64 KiB window. */
  @Test
  void bsaFrameUsesTheVersionedBsaDescriptor() throws Exception {
    byte[] data = sample(200_000, 44);
    byte[] frame = frameEncode(Provider.PORTABLE, data, true);
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
    assertArrayEquals(data, frameDecode(Provider.PORTABLE, frame, data.length));
  }

  /** Both frame profiles round-trip deterministically across window edges and empty content. */
  @Test
  void framesRoundTripAcrossWindowEdges() throws Exception {
    for (boolean bsa : new boolean[] {true, false}) {
      for (int size : new int[] {0, 1, 65535, 65536, 65537, 400_000}) {
        byte[] data = sample(size, 45);
        byte[] frame = frameEncode(Provider.PORTABLE, data, bsa);
        assertArrayEquals(frame, frameEncode(Provider.PORTABLE, data, bsa));
        assertArrayEquals(data, frameDecode(Provider.PORTABLE, frame, size));
        assertArrayEquals(data, streamDecode(frame, size, 777));
      }
    }
  }

  /** Upstream-shaped frames with 4 MiB blocks, checksums, and content size decode exactly. */
  @Test
  void decodesMaximumBlocksAndEveryOptionalFrameField() throws Exception {
    byte[] data = sample(5 * 1024 * 1024, 46);
    for (int flags : new int[] {0x60, 0x70, 0x68, 0x64, 0x7C}) {
      byte[] frame = frame(flags, 0x70, data, 4 * 1024 * 1024, true);
      assertArrayEquals(data, streamDecode(frame, data.length, 8192), "FLG " + flags);
    }
    // A raw (incompressible-flagged) maximum block is copied rather than decompressed.
    byte[] raw = frame(0x60, 0x70, data, 4 * 1024 * 1024, false);
    assertArrayEquals(data, streamDecode(raw, data.length, 65536));
  }

  /**
   * Header 0x80000000 is an empty uncompressed block, not an end mark; the portable decoder skips
   * it as the reference LZ4 1.10.0 decoder does, so both providers accept the same frames.
   */
  @Test
  void skipsAnEmptyUncompressedBlockLikeTheReferenceDecoder() throws Exception {
    byte[] data = sample(70_000, 51);
    byte[] frame = withEmptyRawBlockBeforeEndMark(frame(0x60, 0x40, data, 65536, false));
    assertArrayEquals(data, streamDecode(frame, data.length, 4096));
    assertArrayEquals(data, frameDecode(Provider.PORTABLE, frame, data.length));
  }

  /** The native provider, the reference decoder, accepts the same empty uncompressed block. */
  @Test
  // Decoding through the native LWJGL adapter is a Windows x64 boundary.
  @EnabledOnOs(OS.WINDOWS)
  void nativeDecoderAlsoSkipsAnEmptyUncompressedBlock() throws Exception {
    Lz4Runtime.nativePreflight("lz4-frame", "decode", CONTEXT);
    byte[] data = sample(70_000, 51);
    byte[] frame = withEmptyRawBlockBeforeEndMark(frame(0x60, 0x40, data, 65536, false));
    assertArrayEquals(data, frameDecode(Provider.NATIVE, frame, data.length));
  }

  /**
   * Corruption, truncation, trailing bytes, reserved bits, and checksum or size mismatches fail as
   * FORMAT with the portable profile; nothing past the declared size is published.
   */
  @Test
  void rejectsMalformedFrames() throws Exception {
    byte[] data = sample(150_000, 47);
    byte[] general = frameEncode(Provider.PORTABLE, data, false);
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
              ArchiveException.class, () -> frameDecode(Provider.PORTABLE, invalid, data.length));
      assertEquals(FailureKind.FORMAT, failure.primaryFailure().kind());
      assertEquals(
          Lz4Runtime.PORTABLE_PROFILE, failure.diagnostics().getFirst().values().get("profile"));
    }
    assertEquals(
        "codec.size-mismatch",
        assertThrows(
                ArchiveException.class,
                () -> frameDecode(Provider.PORTABLE, general, data.length - 1))
            .primaryFailure()
            .diagnosticIdentifier()
            .orElseThrow());
    assertEquals(
        "codec.size-mismatch",
        assertThrows(
                ArchiveException.class,
                () -> frameDecode(Provider.PORTABLE, general, data.length + 1))
            .primaryFailure()
            .diagnosticIdentifier()
            .orElseThrow());
  }

  /**
   * A valid linked-block frame is a provider capability limit, not corrupt data: it is reported as
   * {@code CAPABILITY codec.unavailable} and never silently mis-decoded.
   */
  @Test
  void reportsLinkedBlockFramesAsUnsupported() throws Exception {
    byte[] data = sample(1000, 48);
    byte[] linked = frame(0x40, 0x40, data, 65536, true);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class, () -> frameDecode(Provider.PORTABLE, linked, data.length));
    assertEquals(FailureKind.CAPABILITY, failure.primaryFailure().kind());
    assertEquals(
        "codec.unavailable", failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    assertEquals(
        "dependent-blocks", failure.diagnostics().getFirst().values().get("capabilityCause"));
  }

  /** Too little heap fails as POLICY before the source or sink is touched; nothing is native. */
  @Test
  void refusesUnadmittedHeapBeforeEffects() throws Exception {
    try (var budget = new ResourceBudget(ResourceLimits.standard(), CONTEXT, 4096, 0, 0)) {
      ArchiveException frame =
          assertThrows(
              ArchiveException.class,
              () ->
                  Lz4Frame.encode(
                      Provider.PORTABLE,
                      (offset, bytes) -> fail("source touched"),
                      1,
                      (offset, bytes) -> fail("sink touched"),
                      () -> {},
                      budget,
                      null,
                      CONTEXT,
                      true));
      assertEquals(FailureKind.POLICY, frame.primaryFailure().kind());
      ArchiveException raw =
          assertThrows(
              ArchiveException.class,
              () ->
                  Lz4Raw.encode(
                      Provider.PORTABLE,
                      (offset, bytes) -> fail("source touched"),
                      1,
                      (offset, bytes) -> fail("sink touched"),
                      () -> {},
                      budget,
                      CONTEXT,
                      12));
      assertEquals(FailureKind.POLICY, raw.primaryFailure().kind());
    }
  }

  /** A decoder handed to another thread still decodes and closes exactly once. */
  @Test
  void decoderMayBeConsumedAndClosedByAnotherThread() throws Exception {
    byte[] data = sample(200_000, 49);
    byte[] frame = frameEncode(Provider.PORTABLE, data, true);
    var decoder =
        Lz4Frame.decoder(Provider.PORTABLE, source(frame), frame.length, data.length, CONTEXT);
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
        java.nio.channels.ClosedChannelException.class, () -> decoder.read(ByteBuffer.allocate(1)));
  }

  /**
   * Native and portable output decode through the other provider in both directions. Neither
   * provider's bytes are claimed identical to the other's (JBSA-CODEC-013); only content is.
   */
  @Test
  // Cross-decoding needs the native LWJGL adapter, a Windows x64 boundary.
  @EnabledOnOs(OS.WINDOWS)
  void crossDecodesNativeAndPortableOutputBothWays() throws Exception {
    Lz4Runtime.nativePreflight("raw-lz4", "encode", CONTEXT);
    for (int size : new int[] {0, 5, 65537, 1_048_576}) {
      byte[] data = sample(size, 50);
      try (var budget = crossBudget()) {
        byte[] nativeBlock = rawEncode(Provider.NATIVE, data, budget);
        byte[] portableBlock = rawEncode(Provider.PORTABLE, data, budget);
        assertArrayEquals(data, rawDecode(Provider.PORTABLE, nativeBlock, size, budget));
        assertArrayEquals(data, rawDecode(Provider.NATIVE, portableBlock, size, budget));
      }
      for (boolean bsa : new boolean[] {true, false}) {
        byte[] nativeFrame = frameEncode(Provider.NATIVE, data, bsa);
        byte[] portableFrame = frameEncode(Provider.PORTABLE, data, bsa);
        if (bsa) assertArrayEquals(data, frameDecode(Provider.PORTABLE, nativeFrame, size));
        else
          // The native general profile links its blocks, which the portable provider cannot read.
          assertEquals(
              FailureKind.CAPABILITY,
              assertThrows(
                      ArchiveException.class,
                      () -> frameDecode(Provider.PORTABLE, nativeFrame, size))
                  .primaryFailure()
                  .kind());
        assertArrayEquals(data, frameDecode(Provider.NATIVE, portableFrame, size));
      }
    }
  }

  /** Returns deterministic, partly compressible sample content. */
  private static byte[] sample(int size, long seed) {
    byte[] data = new byte[size];
    Random random = new Random(seed);
    for (int index = 0; index < size; index++)
      data[index] = (byte) (index % 3 == 0 ? random.nextInt() : index / 97);
    return data;
  }

  /** Builds a heap-only ledger; zero native credit proves the portable provider uses none. */
  private static ResourceBudget budget() {
    return new ResourceBudget(ResourceLimits.standard(), CONTEXT, HEAP_CEILING, 0, 0);
  }

  /** Builds a ledger that also admits the native provider's direct buffers. */
  private static ResourceBudget crossBudget() {
    return new ResourceBudget(ResourceLimits.standard(), CONTEXT, 64L << 20, 64L << 20, 0);
  }

  /** Requires every heap credit to be available again. */
  private static void assertAllCreditsReturned(ResourceBudget budget) throws Exception {
    try (var all = budget.reserve(HEAP_CEILING, 0, 0, 0)) {
      assertNotNull(all);
    }
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

  /** Encodes one raw block at the Starfield level through the given provider. */
  private static byte[] rawEncode(Provider provider, byte[] data, ResourceBudget budget)
      throws Exception {
    var output = new ByteArrayOutputStream();
    Lz4Raw.encode(provider, source(data), data.length, sink(output), () -> {}, budget, CONTEXT, 12);
    return output.toByteArray();
  }

  /** Decodes one raw block through the given provider. */
  private static byte[] rawDecode(Provider provider, byte[] block, int size, ResourceBudget budget)
      throws Exception {
    var output = new ByteArrayOutputStream();
    Lz4Raw.decode(
        provider, source(block), block.length, size, sink(output), () -> {}, budget, CONTEXT);
    return output.toByteArray();
  }

  /** Encodes one frame through the given provider under a ledger that fits either provider. */
  private static byte[] frameEncode(Provider provider, byte[] data, boolean bsa) throws Exception {
    var output = new ByteArrayOutputStream();
    try (var budget = crossBudget()) {
      long count =
          Lz4Frame.encode(
              provider,
              source(data),
              data.length,
              sink(output),
              () -> {},
              budget,
              null,
              CONTEXT,
              bsa);
      assertEquals(output.size(), count);
    }
    return output.toByteArray();
  }

  /** Decodes one whole frame through the given provider. */
  private static byte[] frameDecode(Provider provider, byte[] frame, long size) throws Exception {
    var output = new ByteArrayOutputStream();
    try (var budget = crossBudget()) {
      assertEquals(
          size,
          Lz4Frame.decode(
              provider,
              source(frame),
              frame.length,
              size,
              sink(output),
              () -> {},
              budget,
              CONTEXT));
    }
    return output.toByteArray();
  }

  /** Decodes through the incremental portable decoder with a fixed destination window. */
  private static byte[] streamDecode(byte[] frame, int size, int window) throws Exception {
    try (var decoder =
        Lz4Frame.decoder(Provider.PORTABLE, source(frame), frame.length, size, CONTEXT)) {
      return drain(decoder, size, window);
    }
  }

  /** Reads a decoder to terminal EOF through bounded windows. */
  private static byte[] drain(Lz4Frame.Decoder decoder, int size, int window)
      throws java.io.IOException {
    var output = new ByteArrayOutputStream(size);
    ByteBuffer buffer = ByteBuffer.allocate(window);
    while (decoder.read(buffer.clear()) >= 0) output.write(buffer.array(), 0, buffer.position());
    return output.toByteArray();
  }

  /**
   * Builds an LZ4 frame independently of the adapter under test. Content size and checksums follow
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
}
