package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Public texture-reader behavior from independently authored archive records. */
@EnabledOnOs(OS.WINDOWS)
final class DdsBa2ReaderTest {
  @TempDir Path directory;

  /** Fallout 4 versions 7 and 8 retain the v1 texture and zlib-chunk records. */
  @Test
  void decodesFallout4VersionsSevenAndEight() throws Exception {
    for (int version : new int[] {7, 8}) {
      byte[] bytes = twoChunks();
      ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putInt(4, version);
      Path path = Files.write(directory.resolve("fallout4-" + version + ".ba2"), bytes);
      try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard());
          EntryContent content = archive.entry(0).openContent()) {
        assertEquals(ArchiveFamily.FO4_DDS_BA2, archive.inspection().metadata().family());
        assertEquals(
            new WireVersion(version),
            archive.inspection().metadata().encoding().wireVersion().orElseThrow());
        assertTrue(
            ((ArchiveMetadata.DdsBa2) archive.inspection().metadata())
                .unknownValueAt24()
                .isEmpty());
        byte[] reconstructed = java.nio.channels.Channels.newInputStream(content).readAllBytes();
        assertEquals(144, reconstructed.length);
        assertEquals(7, reconstructed[128]);
        assertEquals(9, reconstructed[136]);
      }
    }
  }

  /** Independent Starfield wire vectors select zlib or raw LZ4 and preserve exact chunk bytes. */
  @Test
  void decodesIndependentStarfieldChunkProfiles() throws Exception {
    byte[] zlib = java.util.HexFormat.of().parseHex("7801010800f7ff070000000000000000400008");
    byte[] rawLz4 = java.util.HexFormat.of().parseHex("800700000000000000");
    for (byte[] bytes :
        new byte[][] {starfieldFixture(2, 0, zlib), starfieldFixture(3, 3, rawLz4)}) {
      Path path = Files.write(directory.resolve("starfield-" + bytes[4] + ".ba2"), bytes);
      try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard());
          EntryContent content = archive.entry(0).openContent()) {
        assertEquals(ArchiveFamily.STARFIELD_DDS_BA2, archive.inspection().metadata().family());
        assertEquals(
            Byte.toUnsignedInt(bytes[4]),
            archive.inspection().metadata().encoding().wireVersion().orElseThrow().value());
        byte[] reconstructed = java.nio.channels.Channels.newInputStream(content).readAllBytes();
        assertEquals(136, reconstructed.length);
        assertEquals(7, reconstructed[128]);
      }
    }

    rawLz4[0] = 0;
    Path corrupt =
        Files.write(directory.resolve("corrupt-starfield.ba2"), starfieldFixture(3, 3, rawLz4));
    try (OpenArchive archive = BethesdaArchives.standard().open(corrupt, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class,
                  () -> java.nio.channels.Channels.newInputStream(content).readAllBytes())
              .kind());
    }
  }

  /** A Starfield v3 non-method-3 zlib stream is available only through the qualified profile. */
  @Test
  void confinesStarfieldV3ZlibFallbackToCompatibilityProfile() throws Exception {
    byte[] zlib = java.util.HexFormat.of().parseHex("7801010800f7ff070000000000000000400008");
    Path path = Files.write(directory.resolve("fallback.ba2"), starfieldFixture(3, 0, zlib));
    assertEquals(
        FailureKind.UNSUPPORTED,
        assertThrows(ArchiveException.class, () -> BethesdaArchives.standard().inspect(path))
            .kind());
    OpenOptions profile =
        new OpenOptions(
            java.util.Optional.of(CompatibilityProfile.BSARCH_1_0_V1),
            ResourceLimits.standard(),
            java.util.Optional.empty());
    try (OpenArchive archive = BethesdaArchives.standard().open(path, profile);
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(DetectionStatus.UNSUPPORTED_VARIANT, archive.inspection().detection().status());
      assertEquals(
          ArchiveDisposition.TOLERATED_NONCANONICAL,
          archive.inspection().assessment().disposition());
      assertTrue(
          archive.inspection().assessment().diagnostics().stream()
              .anyMatch(diagnostic -> diagnostic.identifier().equals("ba2.sf3-zlib-fallback")));
      assertEquals(136, java.nio.channels.Channels.newInputStream(content).readAllBytes().length);
    }
  }

  /** A bounded stored BC1 texture reconstructs a canonical DDS header and opaque image bytes. */
  @Test
  void reconstructsStoredTexture() throws Exception {
    Path path = Files.write(directory.resolve("texture.ba2"), fixture());
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(136, archive.entry(0).metadata().decodedSize());
      assertEquals(8, archive.entry(0).metadata().storedSize());
      assertTrue(
          archive.inspection().assessment().diagnostics().stream()
              .anyMatch(d -> d.identifier().equals("dx10.stored-chunk")));
      ByteBuffer output = ByteBuffer.allocate(137).order(ByteOrder.LITTLE_ENDIAN);
      while (content.read(output) >= 0) {}
      assertEquals(136, output.position());
      assertEquals(0x20534444, output.getInt(0));
      assertEquals(0x31545844, output.getInt(84));
      assertEquals(7, output.get(128));
      assertTrue(content.assessment().isPresent());
    }
  }

  /** Each zlib chunk is independently framed, and the second chunk retains serialized order. */
  @Test
  void decodesIndependentChunksAndRejectsCorruptTail() throws Exception {
    byte[] bytes = twoChunks();
    Path path = Files.write(directory.resolve("chunks.ba2"), bytes);
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      ByteBuffer output = ByteBuffer.allocate(145);
      while (content.read(output) >= 0) {}
      assertEquals(144, output.position());
      assertEquals(7, output.get(128));
      assertEquals(9, output.get(136));
    }
    bytes[bytes.length - 1] ^= 1;
    Files.write(path, bytes);
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      ByteBuffer output = ByteBuffer.allocate(145);
      assertTrue(content.assessment().isEmpty());
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class,
                  () -> {
                    while (content.read(output) >= 0) {}
                  })
              .kind());
    }
  }

  /** Explicit Xbox reconstruction owns its header length and retained tile mode. */
  @Test
  void honorsExplicitXboxTarget() throws Exception {
    byte[] bytes = fixture();
    bytes[47] = 13;
    Path path = Files.write(directory.resolve("pc.ba2"), bytes);
    OpenOptions options =
        new OpenOptions(
            java.util.Optional.empty(),
            ResourceLimits.standard(),
            java.util.Optional.of(DdsTarget.XBOX));
    try (OpenArchive archive = BethesdaArchives.standard().open(path, options);
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(172, archive.entry(0).metadata().decodedSize());
      ByteBuffer output = ByteBuffer.allocate(173).order(ByteOrder.LITTLE_ENDIAN);
      while (content.read(output) >= 0) {}
      assertEquals(0x584f4258, output.getInt(84));
      assertEquals(13, output.getInt(148));
      assertEquals(10705, output.getInt(160));
    }
  }

  /** Qualified filename inference applies only when the caller has not selected a target. */
  @Test
  void profileInfersXboxWithoutOverridingExplicitPc() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeTrue(
        System.getProperty("os.name").startsWith("Windows"));
    Path path = Files.write(directory.resolve("textures_xbox.ba2"), fixture());
    OpenOptions inferred =
        new OpenOptions(
            java.util.Optional.of(CompatibilityProfile.BSARCH_1_0_V1),
            ResourceLimits.standard(),
            java.util.Optional.empty());
    try (OpenArchive archive = BethesdaArchives.standard().open(path, inferred)) {
      assertEquals(172, archive.entry(0).metadata().decodedSize());
    }
    OpenOptions explicit =
        new OpenOptions(
            inferred.compatibilityProfile(),
            inferred.resourceLimits(),
            java.util.Optional.of(DdsTarget.PC));
    try (OpenArchive archive = BethesdaArchives.standard().open(path, explicit)) {
      assertEquals(136, archive.entry(0).metadata().decodedSize());
    }
  }

  /** Invalid variable records and chunk spans fail before any content capability is exposed. */
  @Test
  void rejectsMalformedTextureIndex() throws Exception {
    for (int mode = 0; mode < 7; mode++) {
      byte[] bytes = fixture();
      ByteBuffer b = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      switch (mode) {
        case 0 -> b.put(37, (byte) 0);
        case 1 -> b.put(37, (byte) 5);
        case 2 -> b.putLong(48, -1);
        case 3 -> b.putLong(48, 24);
        case 4 -> b.putInt(60, 100);
        case 5 -> b.putShort(64, (short) 1);
        default -> b.putShort(66, (short) 1);
      }
      Path path = Files.write(directory.resolve("invalid.ba2"), bytes);
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(ArchiveException.class, () -> BethesdaArchives.standard().inspect(path))
              .kind());
    }
  }

  /** Stored and constant-field warnings retain exact locations and can veto extraction. */
  @Test
  void warningPolicyRejectsBeforeDestinationEffects() throws Exception {
    byte[] bytes = namedFixture("textures/a.dds");
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putShort(38, (short) 12).putInt(68, 0);
    Path path = Files.write(directory.resolve("warnings.ba2"), bytes);
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(path);
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    var expected =
        java.util.Map.of(
            "dx10.stored-chunk", new DiagnosticLocation.ByteSpan(56, 8),
            "dx10.chunk-header-size", new DiagnosticLocation.ByteSpan(38, 2),
            "dx10.sentinel-mismatch", new DiagnosticLocation.ByteSpan(68, 4));
    for (var selected : expected.entrySet()) {
      Diagnostic warning =
          inspection.assessment().diagnostics().stream()
              .filter(d -> d.identifier().equals(selected.getKey()))
              .findFirst()
              .orElseThrow();
      assertEquals(DiagnosticSeverity.WARNING, warning.severity());
      assertEquals(0, warning.location().entryOrdinal().orElseThrow());
      assertEquals(selected.getValue(), warning.location().byteSpan().orElseThrow());
      Path destination = directory.resolve(selected.getKey());
      ExtractRequest request =
          new ExtractRequest(
              path,
              destination,
              EntrySelection.ALL,
              TargetPolicy.FAIL,
              new DiagnosticPolicy(java.util.Set.of(selected.getKey())),
              WorkerSelection.AUTOMATIC,
              OpenOptions.standard());
      ArchiveException failure =
          assertThrows(
              ArchiveException.class,
              () -> BethesdaArchives.standard().extract(request, OperationControl.standard()));
      assertEquals(FailureKind.POLICY, failure.kind());
      assertEquals(
          ArchiveDisposition.TOLERATED_NONCANONICAL,
          failure.assessment().orElseThrow().disposition());
      assertFalse(Files.exists(destination));
    }
  }

  /** Index admission counts encoded metadata exactly and content limits include the DDS header. */
  @Test
  void enforcesEntryMetadataAndReconstructedContentLimits() throws Exception {
    Path path = Files.write(directory.resolve("limits.ba2"), fixture());
    for (ResourceLimits limits :
        new ResourceLimits[] {
          new ResourceLimits(0, 72, 1000, 1000, 10, 20, 10),
          new ResourceLimits(1, 71, 1000, 1000, 10, 20, 10)
        }) {
      OpenOptions options =
          new OpenOptions(java.util.Optional.empty(), limits, java.util.Optional.empty());
      assertEquals(
          FailureKind.POLICY,
          assertThrows(
                  ArchiveException.class, () -> BethesdaArchives.standard().open(path, options))
              .kind());
    }
    OpenOptions exact =
        new OpenOptions(
            java.util.Optional.empty(),
            new ResourceLimits(1, 72, 135, 1000, 10, 20, 10),
            java.util.Optional.empty());
    try (OpenArchive archive = BethesdaArchives.standard().open(path, exact)) {
      assertEquals(1, archive.entryCount());
      assertEquals(136, archive.entry(0).metadata().decodedSize());
      assertEquals(
          FailureKind.POLICY,
          assertThrows(ArchiveException.class, () -> archive.entry(0).openContent()).kind());
    }
  }

  /** Exact compressed spans can be reused while partially overlapping spans remain ambiguous. */
  @Test
  void acceptsExactSharingAndRejectsPartialOverlap() throws Exception {
    byte[] shared = twoChunks();
    ByteBuffer.wrap(shared).order(ByteOrder.LITTLE_ENDIAN).putLong(72, 96);
    Path path = Files.write(directory.resolve("shared.ba2"), shared);
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      byte[] reconstructed = java.nio.channels.Channels.newInputStream(content).readAllBytes();
      assertEquals(144, reconstructed.length);
      assertArrayEquals(
          java.util.Arrays.copyOfRange(reconstructed, 128, 136),
          java.util.Arrays.copyOfRange(reconstructed, 136, 144));
    }
    ByteBuffer.wrap(shared).order(ByteOrder.LITTLE_ENDIAN).putLong(72, 97);
    Files.write(path, shared);
    assertEquals(
        FailureKind.FORMAT,
        assertThrows(ArchiveException.class, () -> BethesdaArchives.standard().inspect(path))
            .kind());
  }

  /** Unsafe complete names remain inspectable but cannot create an extraction destination. */
  @Test
  void refusesUnsafeNamesBeforeExtractionEffects() throws Exception {
    for (String name : new String[] {"../escape.dds", "/textures/a.dds", "textures/NUL.dds"}) {
      Path path = Files.write(directory.resolve("unsafe.ba2"), namedFixture(name));
      ArchiveInspection inspection = BethesdaArchives.standard().inspect(path);
      assertEquals(name.replace('/', '\\'), inspection.entries().getFirst().displayName());
      assertTrue(
          inspection.assessment().diagnostics().stream()
              .anyMatch(d -> d.identifier().startsWith("archive-name.")));
      Path destination = directory.resolve("unsafe-output");
      assertEquals(
          FailureKind.POLICY,
          assertThrows(
                  ArchiveException.class,
                  () ->
                      BethesdaArchives.standard()
                          .extract(
                              ExtractRequest.standard(path, destination),
                              OperationControl.standard()))
              .kind());
      assertFalse(Files.exists(destination));
    }
  }

  /** Appends an independently chosen ASCII name without changing the stored texture payload. */
  private byte[] namedFixture(String name) {
    byte[] encoded = name.getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    ByteBuffer b = ByteBuffer.allocate(82 + encoded.length).order(ByteOrder.LITTLE_ENDIAN);
    b.put(fixture()).putShort((short) encoded.length).put(encoded).putLong(16, 80);
    return b.array();
  }

  /** Builds two literal RFC 1950 stored blocks with separate Adler checksums. */
  private byte[] twoChunks() {
    byte[] first = java.util.HexFormat.of().parseHex("7801010800f7ff070000000000000000400008");
    byte[] second = java.util.HexFormat.of().parseHex("7801010800f7ff09000000000000000050000a");
    ByteBuffer b =
        ByteBuffer.allocate(96 + first.length + second.length).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x58445442).putInt(1).putInt(0x30315844).putInt(1).putLong(0);
    b.putInt(0).putInt(0x00736464).putInt(0).put((byte) 0).put((byte) 2).putShort((short) 24);
    b.putShort((short) 4)
        .putShort((short) 4)
        .put((byte) 2)
        .put((byte) 71)
        .put((byte) 0)
        .put((byte) 0);
    b.putLong(96)
        .putInt(first.length)
        .putInt(8)
        .putShort((short) 0)
        .putShort((short) 0)
        .putInt(0xbaadf00d);
    b.putLong(96 + first.length)
        .putInt(second.length)
        .putInt(8)
        .putShort((short) 1)
        .putShort((short) 1)
        .putInt(0xbaadf00d);
    b.put(first).put(second);
    return b.array();
  }

  /** Builds one 4x4 BC1 stored block with an absent filename table. */
  private byte[] fixture() {
    ByteBuffer b = ByteBuffer.allocate(80).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x58445442).putInt(1).putInt(0x30315844).putInt(1).putLong(0);
    b.putInt(0).putInt(0x00736464).putInt(0).put((byte) 0).put((byte) 1).putShort((short) 24);
    b.putShort((short) 4)
        .putShort((short) 4)
        .put((byte) 1)
        .put((byte) 71)
        .put((byte) 0)
        .put((byte) 0);
    b.putLong(72).putInt(0).putInt(8).putShort((short) 0).putShort((short) 0).putInt(0xbaadf00d);
    b.putLong(7);
    return b.array();
  }

  /** Builds one independently framed Starfield DDS chunk without using the production packer. */
  private static byte[] starfieldFixture(int version, int method, byte[] encoded) {
    int headerSize = version == 2 ? 32 : 36;
    int payloadOffset = headerSize + 48;
    ByteBuffer b =
        ByteBuffer.allocate(payloadOffset + encoded.length).order(ByteOrder.LITTLE_ENDIAN);
    b.putInt(0x58445442).putInt(version).putInt(0x30315844).putInt(1).putLong(0).putLong(1);
    if (version == 3) b.putInt(method);
    b.putInt(0)
        .putInt(0x00736464)
        .putInt(0)
        .put((byte) 0)
        .put((byte) 1)
        .putShort((short) 24)
        .putShort((short) 4)
        .putShort((short) 4)
        .put((byte) 1)
        .put((byte) 71)
        .put((byte) 0)
        .put((byte) 0)
        .putLong(payloadOffset)
        .putInt(encoded.length)
        .putInt(8)
        .putShort((short) 0)
        .putShort((short) 0)
        .putInt(0xbaadf00d)
        .put(encoded);
    return b.array();
  }
}
