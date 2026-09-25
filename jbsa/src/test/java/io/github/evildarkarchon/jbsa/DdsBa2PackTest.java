package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** DDS packing behavior through the public archive API and independently specified wire fields. */
@EnabledOnOs(OS.WINDOWS)
class DdsBa2PackTest {
  @TempDir Path temporary;

  /** Starfield DDS defaults to raw LZ4 while explicit zlib selects the v2 wire envelope. */
  @Test
  void selectsStarfieldCodecEnvelopeAndRoundTrips() throws Exception {
    byte[] source = bc1(5, 7, 1, 32);
    for (PackOptions.Compression compression :
        List.of(
            PackOptions.Compression.FAMILY_DEFAULT,
            PackOptions.Compression.LZ4_RAW,
            PackOptions.Compression.ZLIB)) {
      boolean raw = compression != PackOptions.Compression.ZLIB;
      Path target = temporary.resolve("starfield-" + compression.name() + ".ba2");
      BethesdaArchives.standard()
          .pack(starfieldRequest(target, source, compression), OperationControl.standard());
      ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
      assertEquals(raw ? 3 : 2, wire.getInt(4));
      assertEquals(0x30315844, wire.getInt(8));
      assertEquals(1, wire.getLong(24));
      if (raw) assertEquals(3, wire.getInt(32));
      try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard());
          var content = archive.entry(0).openContent()) {
        assertEquals(ArchiveFamily.STARFIELD_DDS_BA2, archive.inspection().metadata().family());
        byte[] reconstructed = Channels.newInputStream(content).readAllBytes();
        assertArrayEquals(
            Arrays.copyOfRange(source, 128, source.length),
            Arrays.copyOfRange(reconstructed, 128, reconstructed.length));
      }
    }
  }

  /** Mixed Starfield DDS chunk codecs fail before a source factory or destination is touched. */
  @Test
  void rejectsMixedStarfieldChunkCodecsBeforeSourceEffects() throws Exception {
    Path target = temporary.resolve("mixed-codecs.ba2");
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    byte[] source = bc1(4, 4, 1, 8);
    PackRequest base = starfieldRequest(target, source, PackOptions.Compression.LZ4_RAW);
    PackOptions mixed =
        new PackOptions(
            List.of(),
            PackOptions.Compression.LZ4_RAW,
            true,
            new PackOptions.Splitting.FamilyDefault(),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC,
            Map.of(
                new NormalizedNameIdentity("textures\\example.dds"), PackOptions.Compression.ZLIB));
    PackRequest invalid =
        new PackRequest(
            target,
            base.family(),
            base.encoding(),
            base.compatibilityProfile(),
            List.of(
                new PackSource.GeneratedEntry(
                    "Textures/Example.dds",
                    source.length,
                    () -> {
                      opens.incrementAndGet();
                      return Channels.newChannel(new ByteArrayInputStream(source));
                    })),
            base.targetPolicy(),
            base.diagnosticPolicy(),
            base.resourceLimits(),
            base.workerSelection(),
            mixed,
            base.ddsTarget());
    assertEquals(
        FailureKind.UNSUPPORTED,
        assertThrows(
                ArchiveException.class,
                () -> BethesdaArchives.standard().pack(invalid, OperationControl.standard()))
            .kind());
    assertEquals(0, opens.get());
    assertFalse(Files.exists(target));
  }

  /** A raw-LZ4 mip chunk above the qualified dispatch limit fails before publication. */
  @Test
  void rejectsOversizeStarfieldRawChunkBeforePublication() throws Exception {
    int payloadSize = 2048 * 1025 * 8;
    byte[] source = bc1(8192, 4097, 1, payloadSize);
    Path target = temporary.resolve("oversize-raw.ba2");
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        starfieldRequest(target, source, PackOptions.Compression.FAMILY_DEFAULT),
                        OperationControl.standard()));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(
        "codec.dispatch-limit", failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    assertFalse(Files.exists(target));
  }

  /** An existing DDS archive is a valid ordered pack source with canonical entry bytes. */
  @Test
  void repacksDdsArchiveThroughDetectedSource() throws Exception {
    Path input = temporary.resolve("input.ba2"), output = temporary.resolve("output.ba2");
    BethesdaArchives.standard().pack(request(input, bc1(4, 4, 1, 8)), OperationControl.standard());
    var original = request(output, bc1(4, 4, 1, 8));
    var repack =
        PackRequest.standard(
            output,
            original.family(),
            original.encoding(),
            List.of(new PackSource.DetectedPath(input)),
            original.ddsTarget());
    BethesdaArchives.standard().pack(repack, OperationControl.standard());
    try (var source = BethesdaArchives.standard().open(input, OpenOptions.standard());
        var target = BethesdaArchives.standard().open(output, OpenOptions.standard());
        var left = source.entry(0).openContent();
        var right = target.entry(0).openContent()) {
      assertArrayEquals(
          Channels.newInputStream(left).readAllBytes(),
          Channels.newInputStream(right).readAllBytes());
    }
  }

  /** Even an expanding one-block payload is independently zlib compressed by default. */
  @Test
  void compressesSmallOddTextureAndReconstructsCanonicalHeader() throws Exception {
    byte[] source = bc1(5, 7, 1, 32);
    Path target = temporary.resolve("texture.ba2");
    BethesdaArchives.standard().pack(request(target, source), OperationControl.standard());
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(target)).order(ByteOrder.LITTLE_ENDIAN);
    assertEquals(0x30315844, wire.getInt(8));
    assertEquals(1, wire.get(37));
    assertEquals(24, wire.getShort(38));
    assertEquals(32, wire.getInt(60));
    assertTrue(wire.getInt(56) > 0);
    try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard());
        var content = archive.entry(0).openContent()) {
      byte[] reconstructed = Channels.newInputStream(content).readAllBytes();
      assertEquals(160, reconstructed.length);
      assertEquals(32, ByteBuffer.wrap(reconstructed).order(ByteOrder.LITTLE_ENDIAN).getInt(20));
      assertArrayEquals(
          Arrays.copyOfRange(source, 128, source.length),
          Arrays.copyOfRange(reconstructed, 128, reconstructed.length));
    }
  }

  /** Every selected entry must be named DDS, even if its bytes happen to contain a DDS header. */
  @Test
  void rejectsNonDdsNamesBeforeOpeningSourcesOrReplacingDestination() throws Exception {
    Path target = temporary.resolve("existing.ba2");
    Files.writeString(target, "keep");
    var opens = new java.util.concurrent.atomic.AtomicInteger();
    var original = request(target, bc1(1, 1, 1, 8));
    var invalid =
        new PackRequest(
            target,
            original.family(),
            original.encoding(),
            Optional.empty(),
            List.of(
                new PackSource.GeneratedEntry(
                    "Textures/NotDds.txt",
                    136,
                    () -> {
                      opens.incrementAndGet();
                      return Channels.newChannel(new ByteArrayInputStream(bc1(1, 1, 1, 8)));
                    })),
            TargetPolicy.REPLACE,
            original.diagnosticPolicy(),
            original.resourceLimits(),
            original.workerSelection(),
            original.options(),
            original.ddsTarget());
    assertEquals(
        FailureKind.UNSUPPORTED,
        assertThrows(
                ArchiveException.class,
                () -> BethesdaArchives.standard().pack(invalid, OperationControl.standard()))
            .kind());
    assertEquals(0, opens.get());
    assertEquals("keep", Files.readString(target));
  }

  /** Mip zero is isolated at the 512 threshold and the final chunk retains the remaining mips. */
  @Test
  void partitionsAndSharesChunksWithoutChangingMipRanges() throws Exception {
    byte[] source = bc1(512, 512, 3, 172032);
    Path target = temporary.resolve("shared.ba2");
    var original = request(target, source);
    var sources =
        List.<PackSource>of(
            original.sources().getFirst(),
            new PackSource.GeneratedEntry(
                "Textures/Copy.DDS",
                source.length,
                () -> Channels.newChannel(new ByteArrayInputStream(source))));
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            true,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    var selected =
        new PackRequest(
            target,
            original.family(),
            original.encoding(),
            Optional.empty(),
            sources,
            original.targetPolicy(),
            original.diagnosticPolicy(),
            original.resourceLimits(),
            original.workerSelection(),
            options,
            original.ddsTarget());
    BethesdaArchives.standard().pack(selected, OperationControl.standard());
    try (var archive = BethesdaArchives.standard().open(target, OpenOptions.standard())) {
      var first = (EntryMetadata.DdsBa2) archive.entry(0).metadata().facts();
      var second = (EntryMetadata.DdsBa2) archive.entry(1).metadata().facts();
      assertEquals(2, first.chunks().size());
      assertEquals(131072, first.chunks().getFirst().unpackedSize());
      assertEquals(0, first.chunks().getFirst().endMip());
      assertEquals(40960, first.chunks().getLast().unpackedSize());
      assertEquals(1, first.chunks().getLast().startMip());
      assertEquals(2, first.chunks().getLast().endMip());
      assertEquals(first.chunks(), second.chunks());
    }
  }

  /** Stored compression is never enabled by the immutable compatibility profile. */
  @Test
  void rejectsStoredInEveryProfileBeforeOpeningSource() throws Exception {
    for (var profile :
        List.of(
            Optional.<CompatibilityProfile>empty(),
            Optional.of(CompatibilityProfile.BSARCH_1_0_V1))) {
      Path target = temporary.resolve("stored-" + profile.isPresent() + ".ba2");
      var original = request(target, bc1(1, 1, 1, 8));
      var options =
          new PackOptions(
              List.of(),
              PackOptions.Compression.STORED,
              false,
              new PackOptions.Splitting.FamilyDefault(),
              FlagSelection.AUTOMATIC,
              FlagSelection.AUTOMATIC);
      var invalid =
          new PackRequest(
              target,
              original.family(),
              original.encoding(),
              profile,
              original.sources(),
              original.targetPolicy(),
              original.diagnosticPolicy(),
              original.resourceLimits(),
              original.workerSelection(),
              options,
              original.ddsTarget());
      assertEquals(
          FailureKind.UNSUPPORTED,
          assertThrows(
                  ArchiveException.class,
                  () -> BethesdaArchives.standard().pack(invalid, OperationControl.standard()))
              .kind());
      assertFalse(Files.exists(target));
    }
  }

  /** Each split sibling retains a complete texture and independently readable shared chunks. */
  @Test
  void splitsOnlyBetweenCompleteTextures() throws Exception {
    byte[] source = bc1(512, 512, 3, 172032);
    Path target = temporary.resolve("parts.ba2");
    var original = request(target, source);
    var options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            true,
            new PackOptions.Splitting.UpToBytes(1),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    var selected =
        new PackRequest(
            target,
            original.family(),
            original.encoding(),
            Optional.empty(),
            List.of(
                original.sources().getFirst(),
                new PackSource.GeneratedEntry(
                    "Textures/Copy.dds",
                    source.length,
                    () -> Channels.newChannel(new ByteArrayInputStream(source)))),
            original.targetPolicy(),
            original.diagnosticPolicy(),
            original.resourceLimits(),
            original.workerSelection(),
            options,
            original.ddsTarget());
    var report = BethesdaArchives.standard().pack(selected, OperationControl.standard());
    assertEquals(2, report.archiveParts().size());
    for (var part : report.archiveParts()) {
      try (var archive = BethesdaArchives.standard().open(part.path(), OpenOptions.standard());
          var content = archive.entry(0).openContent()) {
        assertEquals(1, archive.entryCount());
        assertEquals(
            2, ((EntryMetadata.DdsBa2) archive.entry(0).metadata().facts()).chunks().size());
        byte[] actual = Channels.newInputStream(content).readAllBytes();
        assertArrayEquals(
            Arrays.copyOfRange(source, 128, source.length),
            Arrays.copyOfRange(actual, 128, actual.length));
      }
    }
  }

  /** Normalization expansion counts once per logical entry, including shared identical entries. */
  @Test
  void countsNormalizedDdsBytesAcrossLogicalEntries() throws Exception {
    ByteBuffer bgr = ByteBuffer.allocate(131).order(ByteOrder.LITTLE_ENDIAN);
    bgr.putInt(0, 0x20534444)
        .putInt(4, 124)
        .putInt(12, 1)
        .putInt(16, 1)
        .putInt(76, 32)
        .putInt(80, 0x40)
        .putInt(88, 24)
        .putInt(92, 0xff0000)
        .putInt(96, 0xff00)
        .putInt(100, 0xff);
    Path target = temporary.resolve("limited.ba2");
    var original = request(target, bgr.array());
    var defaults = ResourceLimits.standard();
    var limits =
        new ResourceLimits(
            defaults.maxEntries(),
            defaults.maxMetadataBytes(),
            263,
            defaults.maxScratchBytes(),
            defaults.maxOutputs(),
            defaults.maxDiagnostics(),
            defaults.maxSecondaryFailures());
    var selected =
        new PackRequest(
            target,
            original.family(),
            original.encoding(),
            Optional.empty(),
            List.of(
                original.sources().getFirst(),
                new PackSource.GeneratedEntry(
                    "Textures/Copy.dds",
                    131,
                    () -> Channels.newChannel(new ByteArrayInputStream(bgr.array())))),
            original.targetPolicy(),
            original.diagnosticPolicy(),
            limits,
            original.workerSelection(),
            original.options(),
            original.ddsTarget());
    assertEquals(
        FailureKind.POLICY,
        assertThrows(
                ArchiveException.class,
                () -> BethesdaArchives.standard().pack(selected, OperationControl.standard()))
            .kind());
    assertFalse(Files.exists(target));
  }

  /** Supplies a deliberately noncanonical but valid input header to exercise reconstruction. */
  static byte[] bc1(int width, int height, int mips, int payloadSize) {
    ByteBuffer source = ByteBuffer.allocate(128 + payloadSize).order(ByteOrder.LITTLE_ENDIAN);
    source.putInt(0, 0x20534444).putInt(4, 124).putInt(8, 0x21007);
    source.putInt(12, height).putInt(16, width).putInt(28, mips);
    source.putInt(76, 32).putInt(80, 4).putInt(84, 0x31545844).putInt(108, 0x1000);
    for (int i = 128; i < source.capacity(); i++) source.put(i, (byte) (i * 31));
    return source.array();
  }

  /** Uses the mandatory explicit PC target and family-default compression. */
  static PackRequest request(Path target, byte[] source) {
    return PackRequest.standard(
        target,
        ArchiveFamily.FO4_DDS_BA2,
        new ArchiveEncoding(
            Optional.of(new WireVersion(1)), Optional.of(Ba2Subtype.DX10), OptionalLong.empty()),
        List.of(
            new PackSource.GeneratedEntry(
                "Textures/Example.dds",
                source.length,
                () -> Channels.newChannel(new ByteArrayInputStream(source)))),
        Optional.of(DdsTarget.PC));
  }

  /** Builds a public Starfield DDS request whose selector tuple follows its chunk codec. */
  private static PackRequest starfieldRequest(
      Path target, byte[] source, PackOptions.Compression compression) {
    boolean raw = compression != PackOptions.Compression.ZLIB;
    var standard = request(target, source);
    return new PackRequest(
        target,
        ArchiveFamily.STARFIELD_DDS_BA2,
        new ArchiveEncoding(
            Optional.of(new WireVersion(raw ? 3 : 2)),
            Optional.of(Ba2Subtype.DX10),
            raw ? OptionalLong.of(3) : OptionalLong.empty()),
        Optional.empty(),
        standard.sources(),
        standard.targetPolicy(),
        standard.diagnosticPolicy(),
        standard.resourceLimits(),
        standard.workerSelection(),
        new PackOptions(
            List.of(),
            compression,
            true,
            new PackOptions.Splitting.FamilyDefault(),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC),
        Optional.of(DdsTarget.PC));
  }
}
