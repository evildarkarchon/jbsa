package io.github.evildarkarchon.jbsa.internal.ba2;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.dds.DdsEnvelope;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.JdkZlib;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Raw;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Runtime;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted;
import io.github.evildarkarchon.jbsa.internal.pack.Codec;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Pure input-to-output tests for the BA2 adapter: wire version selection, per-entry planning, DDS
 * chunking, layout, the header-last {@code tables} patches, and the readback declaration. Byte
 * identity against real archives stays with the BA2 conformance ITs and pack tests.
 */
class Ba2AdapterTest {
  private static final Path TARGET = Path.of("unused.ba2").toAbsolutePath();
  private static final IoContext CONTEXT = IoContext.of(TARGET, Operation.PACK);

  /**
   * Fallout 4 is always v1. Starfield is v2 unless raw LZ4 is in use (explicitly, by an override,
   * or as the DDS family default), which selects v3 and compression selector 3.
   */
  @Test
  void selectsTheWireVersionFromFamilyAndCodec() throws Exception {
    admit(ArchiveFamily.FO4_GENERAL_BA2, encoding(1, false, false), PackOptions.Compression.ZLIB);
    admit(
        ArchiveFamily.FO4_DDS_BA2,
        encoding(1, true, false),
        PackOptions.Compression.FAMILY_DEFAULT);
    admit(
        ArchiveFamily.STARFIELD_GENERAL_BA2,
        encoding(2, false, false),
        PackOptions.Compression.STORED);
    admit(
        ArchiveFamily.STARFIELD_GENERAL_BA2,
        encoding(3, false, true),
        PackOptions.Compression.LZ4_RAW);
    admit(
        ArchiveFamily.STARFIELD_DDS_BA2,
        encoding(3, true, true),
        PackOptions.Compression.FAMILY_DEFAULT);
    admit(ArchiveFamily.STARFIELD_DDS_BA2, encoding(2, true, false), PackOptions.Compression.ZLIB);

    for (var mismatch :
        List.of(
            // Fallout 4 never writes a Starfield version.
            new Object[] {ArchiveFamily.FO4_GENERAL_BA2, encoding(2, false, false)},
            // Raw LZ4 needs v3 with its compression selector.
            new Object[] {ArchiveFamily.STARFIELD_DDS_BA2, encoding(2, true, false)},
            // The subtype follows the family.
            new Object[] {ArchiveFamily.FO4_GENERAL_BA2, encoding(1, true, false)})) {
      var failure =
          assertThrows(
              ArchiveException.class,
              () ->
                  Ba2Adapter.INSTANCE.admit(
                      request(
                          (ArchiveFamily) mismatch[0],
                          (ArchiveEncoding) mismatch[1],
                          options(PackOptions.Compression.FAMILY_DEFAULT, Map.of())),
                      CONTEXT));
      assertEquals(Optional.of("archive.unsupported-encoding"), diagnostic(failure));
      assertEquals(FailureKind.UNSUPPORTED, failure.kind());
    }
  }

  /**
   * Admission only declares raw LZ4 for the pipeline to admit, so it succeeds on any host; a zlib
   * or stored archive declares no native codec.
   */
  @Test
  void declaresRawLz4WithoutAdmittingIt() throws Exception {
    assertEquals(
        Set.of(Codec.LZ4_RAW),
        admit(
                ArchiveFamily.STARFIELD_GENERAL_BA2,
                encoding(3, false, true),
                PackOptions.Compression.LZ4_RAW)
            .requiredCodecs());
    assertEquals(
        Set.of(Codec.LZ4_RAW),
        admit(
                ArchiveFamily.STARFIELD_DDS_BA2,
                encoding(3, true, true),
                PackOptions.Compression.FAMILY_DEFAULT)
            .requiredCodecs());
    assertEquals(
        Set.of(),
        admit(
                ArchiveFamily.FO4_GENERAL_BA2,
                encoding(1, false, false),
                PackOptions.Compression.ZLIB)
            .requiredCodecs());
    assertEquals(
        Set.of(),
        admit(
                ArchiveFamily.STARFIELD_GENERAL_BA2,
                encoding(2, false, false),
                PackOptions.Compression.STORED)
            .requiredCodecs());
  }

  /**
   * Entries keep Logical Plan Order with per-entry codecs, and the family declares its split cost,
   * set-wide raw sharing, disabled default splitting, and the DDS per-entry reserve.
   */
  @Test
  void plansEntriesInLogicalPlanOrderWithDeclaredCosts() throws Exception {
    var admitted =
        admit(
            ArchiveFamily.FO4_GENERAL_BA2,
            encoding(1, false, false),
            options(
                PackOptions.Compression.STORED,
                Map.of(new NormalizedNameIdentity("data\\b.bin"), PackOptions.Compression.ZLIB)));
    var planned = admitted.plan(List.of(entry("Data/C.bin", 3), entry("Data/b.bin", 2)), CONTEXT);
    assertEquals(
        List.of("Data/C.bin", "Data/b.bin"),
        planned.stream().map(entry -> entry.source().displayName()).toList());
    assertEquals(List.of(Codec.STORED, Codec.ZLIB), planned.stream().map(e -> e.codec()).toList());
    // A stored record's size is predictable before stabilization; an encoded one is not.
    assertEquals(3, planned.get(0).storedSize());
    assertEquals(Admitted.Planned.UNKNOWN_SIZE, planned.get(1).storedSize());
    for (var entry : planned) assertEquals(0, entry.frame().length);
    var key = planned.getFirst().key();
    assertArrayEquals("Data/C.bin".getBytes(StandardCharsets.US_ASCII), key.name());
    assertEquals(Ba2Names.identity(key.name()), key.identity());

    assertEquals(0, admitted.defaultSplitTarget());
    assertEquals(Admitted.Emission.STABILIZED, admitted.emission());
    assertEquals(
        new Admitted.Sharing(Admitted.Sharing.Scope.ARCHIVE_SET, Admitted.Sharing.Basis.RAW),
        admitted.sharing());
    var cost = admitted.splitCost();
    assertEquals(200, cost.fixed());
    assertEquals("Data/C.bin".length(), cost.nameCost().applyAsLong(key));
    assertEquals(Admitted.PayloadCost.UNIQUE_STORED, cost.payload());
    assertEquals(0, admitted.entryReserveBytes());
    assertEquals(0, admitted.chunkHeadBytes());
    assertEquals("ba2", admitted.diagnosticPrefix());

    var dds =
        admit(
            ArchiveFamily.STARFIELD_DDS_BA2,
            encoding(3, true, true),
            PackOptions.Compression.FAMILY_DEFAULT);
    assertEquals(2048, dds.entryReserveBytes());
    assertEquals(164, dds.chunkHeadBytes());
    assertEquals(
        Codec.LZ4_RAW, dds.plan(List.of(entry("Textures/A.dds", 200)), CONTEXT).getFirst().codec());
    var nonDds =
        assertThrows(
            ArchiveException.class, () -> dds.plan(List.of(entry("Textures/A.png", 1)), CONTEXT));
    assertEquals(Optional.of("dds.non-dds-entry"), diagnostic(nonDds));
  }

  /**
   * A legacy BGR24 texture is rewritten to BGRA32 before slicing, so its single slice is the
   * expanded payload; the header is metadata, not stored payload.
   */
  @Test
  void chunksBgr24TexturesThroughARewrite() throws Exception {
    var admitted =
        admit(ArchiveFamily.FO4_DDS_BA2, encoding(1, true, false), PackOptions.Compression.ZLIB);
    byte[] bgr = bgr24(1, 1);
    var entry = admitted.plan(List.of(entry("Textures/A.dds", bgr.length)), CONTEXT).getFirst();
    var chunks = admitted.chunk(entry, ByteBuffer.wrap(bgr, 0, 128).slice(), CONTEXT);
    assertEquals(Admitted.Rewrite.BGR24_TO_BGRA32, chunks.rewrite());
    assertEquals(128, chunks.payloadStart());
    assertEquals(List.of(new Admitted.Slice(0, 4)), chunks.slices());
    assertEquals(128, chunks.metadataBytes());
    var texture = chunks.key().texture();
    assertEquals(88, texture.dxgiFormat());
    long header = DdsEnvelope.canonicalHeader(1, 1, 1, 88, false, 0, DdsTarget.PC, CONTEXT).length;
    assertEquals(4 + header, chunks.decodedBytes());
  }

  /**
   * A block-compressed 512x512 texture splits its first mip from the rest of the chain, and needs
   * no rewrite.
   */
  @Test
  void chunksBlockCompressedMipChains() throws Exception {
    var admitted =
        admit(ArchiveFamily.FO4_DDS_BA2, encoding(1, true, false), PackOptions.Compression.ZLIB);
    int payload = 131072 + 32768 + 8192;
    byte[] bc1 = bc1(512, 512, 3, payload);
    var entry = admitted.plan(List.of(entry("Textures/B.dds", bc1.length)), CONTEXT).getFirst();
    var chunks = admitted.chunk(entry, ByteBuffer.wrap(bc1, 0, 164).slice(), CONTEXT);
    assertEquals(Admitted.Rewrite.NONE, chunks.rewrite());
    assertEquals(128, chunks.payloadStart());
    assertEquals(
        List.of(new Admitted.Slice(0, 131072), new Admitted.Slice(131072, 32768 + 8192)),
        chunks.slices());
    assertEquals(3, chunks.key().texture().mipCount());
  }

  /**
   * General layout: the header, 36-byte records, then payloads; the trailing name table counts as
   * metadata. Tables write the records, the name table at the payload end, then the header. A
   * shared owner repeats its position, and only a compressed owner reports a packed size.
   */
  @Test
  void generalTablesWriteRecordsThenNamesThenHeader() throws Exception {
    var admitted =
        admit(
            ArchiveFamily.FO4_GENERAL_BA2, encoding(1, false, false), PackOptions.Compression.ZLIB);
    var planned =
        admitted.plan(
            List.of(entry("Data/A.bin", 10), entry("Data/B.bin", 10), entry("Data/C.bin", 5)),
            CONTEXT);
    // A and B share owner 0 (6 stored bytes); C is its own stored owner.
    var a = planned.get(0).segmented(planned.get(0).key(), List.of(segment(10, 6, Codec.ZLIB, 0)));
    var b = planned.get(1).segmented(planned.get(1).key(), List.of(segment(10, 6, Codec.ZLIB, 0)));
    var c = planned.get(2).segmented(planned.get(2).key(), List.of(segment(5, 5, Codec.STORED, 1)));
    var part = List.of(a, b, c);
    var layout = admitted.layout(part, CONTEXT);
    long names = 3 * (2 + "Data/A.bin".length());
    assertEquals(new Admitted.Layout(24 + 3 * 36, 24 + 3 * 36 + names), layout);

    long start = layout.payloadStart();
    var placed = List.of(placed(a, start), placed(b, start), placed(c, start + 6));
    var patches = admitted.tables(placed, layout);
    assertEquals(
        List.of(24L, 60L, 96L, start + 11, 0L),
        patches.stream().map(Admitted.Patch::position).toList(),
        "records, then the name table at the payload end, then the header");

    var wire = apply(patches, (int) (start + 11 + names)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x58445442, wire.getInt(0));
    assertEquals(1, wire.getInt(4));
    assertEquals(0x4c524e47, wire.getInt(8));
    assertEquals(3, wire.getInt(12));
    assertEquals(start + 11, wire.getLong(16));
    var identity = Ba2Names.identity("Data/A.bin".getBytes(StandardCharsets.US_ASCII));
    assertEquals((int) identity.baseNameHash(), wire.getInt(24));
    assertEquals((int) identity.directoryHash(), wire.getInt(32));
    assertEquals(1, wire.get(37));
    assertEquals(16, wire.getShort(38));
    assertEquals(start, wire.getLong(40));
    assertEquals(6, wire.getInt(48));
    assertEquals(10, wire.getInt(52));
    assertEquals(0xbaadf00d, wire.getInt(56));
    assertEquals(start, wire.getLong(60 + 16), "B shares A's owner");
    assertEquals(start + 6, wire.getLong(96 + 16));
    assertEquals(0, wire.getInt(96 + 24), "a stored owner reports no packed size");
    assertEquals(5, wire.getInt(96 + 28));
    int table = (int) (start + 11);
    assertEquals(10, wire.getShort(table));
    assertEquals("Data/A.bin", ascii(wire, table + 2, 10));
    assertEquals("Data/C.bin", ascii(wire, table + 2 * 12 + 2, 10));
  }

  /**
   * A DX10 record is 24 bytes plus one 24-byte chunk record per slice, carrying each owner's
   * position, packed and raw sizes, and its mip range; Starfield headers grow to 36 bytes on v3.
   */
  @Test
  void ddsTablesWriteChunkRecords() throws Exception {
    var admitted =
        admit(
            ArchiveFamily.STARFIELD_DDS_BA2,
            encoding(3, true, true),
            PackOptions.Compression.FAMILY_DEFAULT);
    int payload = 131072 + 32768 + 8192;
    byte[] bc1 = bc1(512, 512, 3, payload);
    var entry = admitted.plan(List.of(entry("Textures/B.dds", bc1.length)), CONTEXT).getFirst();
    var chunks = admitted.chunk(entry, ByteBuffer.wrap(bc1, 0, 164).slice(), CONTEXT);
    var sized =
        entry.segmented(
            chunks.key(),
            List.of(segment(131072, 100, Codec.LZ4_RAW, 0), segment(40960, 50, Codec.LZ4_RAW, 1)));
    var layout = admitted.layout(List.of(sized), CONTEXT);
    long record = 24 + 2 * 24;
    assertEquals(36 + record, layout.payloadStart());
    assertEquals(36 + record + 2 + "Textures/B.dds".length(), layout.metadataBytes());

    long start = layout.payloadStart();
    var placed = new Admitted.Placed<>(sized, start, 150, List.of(start, start + 100));
    var patches = admitted.tables(List.of(placed), layout);
    var wire = apply(patches, (int) (start + 150 + 16)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(3, wire.getInt(4));
    assertEquals(0x30315844, wire.getInt(8));
    assertEquals(start + 150, wire.getLong(16));
    assertEquals(1, wire.getLong(24));
    assertEquals(3, wire.getInt(32));
    int dx10 = 36;
    assertEquals(2, wire.get(dx10 + 13), "chunk count");
    assertEquals(24, wire.getShort(dx10 + 14));
    assertEquals(512, wire.getShort(dx10 + 16));
    assertEquals(512, wire.getShort(dx10 + 18));
    assertEquals(3, wire.get(dx10 + 20));
    int second = dx10 + 24 + 24;
    assertEquals(start + 100, wire.getLong(second));
    assertEquals(50, wire.getInt(second + 8));
    assertEquals(40960, wire.getInt(second + 12));
    assertEquals(1, wire.getShort(second + 16));
    assertEquals(2, wire.getShort(second + 18));
    assertEquals(0xbaadf00d, wire.getInt(second + 20));
  }

  /**
   * Readback is data: the reader's peak credits for the part and the INTERNAL nonconforming ID. A
   * part holding a raw-LZ4 entry charges the raw decoder instead of zlib's.
   */
  @Test
  void declaresReadbackCreditsAndTheNonconformingId() throws Exception {
    var zlib =
        admit(
            ArchiveFamily.FO4_GENERAL_BA2, encoding(1, false, false), PackOptions.Compression.ZLIB);
    var part = zlib.plan(List.of(entry("Data/A.bin", 10), entry("Data/B.bin", 700)), CONTEXT);
    var layout = new Admitted.Layout(96, 120);
    assertEquals(
        new Admitted.Readback(
            "ba2.noncanonical-staged-output",
            512L * 2 + 8L * 120 + 65536 + JdkZlib.DECODE_HEAP_BYTES,
            JdkZlib.DECODE_NATIVE_BYTES,
            1),
        zlib.readback(part, layout));

    var raw =
        admit(
            ArchiveFamily.STARFIELD_GENERAL_BA2,
            encoding(3, false, true),
            PackOptions.Compression.LZ4_RAW);
    var rawPart = raw.plan(List.of(entry("Data/A.bin", 10), entry("Data/B.bin", 700)), CONTEXT);
    assertEquals(
        new Admitted.Readback(
            "ba2.noncanonical-staged-output",
            // Raw block buffers are native under LWJGL and heap under the portable provider.
            512L * 2
                + 8L * 120
                + 65536
                + 4096
                + 700
                + Lz4Raw.maxDecodeHeapBytes(Lz4Runtime.selected()),
            Lz4Raw.maxDecodeNativeBytes(Lz4Runtime.selected()),
            1),
        raw.readback(rawPart, layout));
  }

  /** Admits a request for the family with the given global compression and no overrides. */
  private static Admitted<Ba2Adapter.Key> admit(
      ArchiveFamily family, ArchiveEncoding encoding, PackOptions.Compression compression)
      throws ArchiveException {
    return admit(family, encoding, options(compression, Map.of()));
  }

  /** Admits a request for the family with the given options. */
  private static Admitted<Ba2Adapter.Key> admit(
      ArchiveFamily family, ArchiveEncoding encoding, PackOptions options) throws ArchiveException {
    return Ba2Adapter.INSTANCE.admit(request(family, encoding, options), CONTEXT);
  }

  /** A standard request replacing only its family, encoding, and options. */
  private static PackRequest request(
      ArchiveFamily family, ArchiveEncoding encoding, PackOptions options) {
    boolean dds = family == ArchiveFamily.FO4_DDS_BA2 || family == ArchiveFamily.STARFIELD_DDS_BA2;
    var standard =
        PackRequest.standard(
            TARGET,
            family,
            encoding,
            List.of(),
            dds ? Optional.of(DdsTarget.PC) : Optional.empty());
    return new PackRequest(
        standard.destination(),
        standard.family(),
        standard.encoding(),
        standard.compatibilityProfile(),
        standard.sources(),
        standard.targetPolicy(),
        standard.diagnosticPolicy(),
        standard.resourceLimits(),
        standard.workerSelection(),
        options,
        standard.ddsTarget());
  }

  /** Sharing options with automatic flags and disabled splitting. */
  private static PackOptions options(
      PackOptions.Compression compression,
      Map<NormalizedNameIdentity, PackOptions.Compression> entryCompression) {
    return new PackOptions(
        List.of(),
        compression,
        true,
        new PackOptions.Splitting.FamilyDefault(),
        FlagSelection.AUTOMATIC,
        FlagSelection.AUTOMATIC,
        entryCompression);
  }

  /** The BA2 selector tuple for a wire version, subtype, and raw-LZ4 compression selector. */
  private static ArchiveEncoding encoding(long version, boolean dds, boolean rawLz4) {
    return new ArchiveEncoding(
        Optional.of(new WireVersion(version)),
        Optional.of(dds ? Ba2Subtype.DX10 : Ba2Subtype.GNRL),
        rawLz4 ? OptionalLong.of(3) : OptionalLong.empty());
  }

  /** A planned source whose payload pure adapter methods never consume. */
  private static PackSources.Entry entry(String display, long size) {
    String identity = display.replace('/', '\\').toLowerCase(Locale.ROOT);
    return new PackSources.Entry(
        display,
        identity,
        identity.getBytes(StandardCharsets.US_ASCII),
        0,
        size,
        reader -> {
          throw new AssertionError("Adapters must not read payloads");
        });
  }

  /** One resolved archive-set owner. */
  private static Admitted.Segment segment(long raw, long stored, Codec codec, int owner) {
    return new Admitted.Segment(raw, stored, codec, owner);
  }

  /** Places a single-segment entry at the given owner position. */
  private static Admitted.Placed<Ba2Adapter.Key> placed(
      Admitted.Planned<Ba2Adapter.Key> entry, long position) {
    return new Admitted.Placed<>(entry, position, entry.storedSize(), List.of(position));
  }

  /** Applies positional patches to a zeroed buffer, failing if any lands outside it. */
  private static ByteBuffer apply(List<Admitted.Patch> patches, int size) {
    byte[] bytes = new byte[size];
    for (var patch : patches)
      System.arraycopy(patch.bytes(), 0, bytes, (int) patch.position(), patch.bytes().length);
    return ByteBuffer.wrap(bytes);
  }

  /** Decodes an ASCII range. */
  private static String ascii(ByteBuffer wire, int position, int length) {
    byte[] bytes = new byte[length];
    wire.get(position, bytes);
    return new String(bytes, StandardCharsets.US_ASCII);
  }

  /** Returns the failure's Conformance Diagnostic identifier. */
  private static Optional<String> diagnostic(ArchiveException failure) {
    return failure.primaryFailure().diagnosticIdentifier();
  }

  /** A legacy 24-bit BGR texture of the given size, three payload bytes per pixel. */
  private static byte[] bgr24(int width, int height) {
    var source = ByteBuffer.allocate(128 + 3 * width * height).order(ByteOrder.LITTLE_ENDIAN);
    source
        .putInt(0, 0x20534444)
        .putInt(4, 124)
        .putInt(12, height)
        .putInt(16, width)
        .putInt(76, 32)
        .putInt(80, 0x40)
        .putInt(88, 24)
        .putInt(92, 0xff0000)
        .putInt(96, 0xff00)
        .putInt(100, 0xff);
    return source.array();
  }

  /** A legacy DXT1 (BC1) texture header with a patterned payload. */
  private static byte[] bc1(int width, int height, int mips, int payloadSize) {
    var source = ByteBuffer.allocate(128 + payloadSize).order(ByteOrder.LITTLE_ENDIAN);
    source.putInt(0, 0x20534444).putInt(4, 124).putInt(8, 0x21007);
    source.putInt(12, height).putInt(16, width).putInt(28, mips);
    source.putInt(76, 32).putInt(80, 4).putInt(84, 0x31545844).putInt(108, 0x1000);
    for (int i = 128; i < source.capacity(); i++) source.put(i, (byte) (i * 31));
    return source.array();
  }
}
