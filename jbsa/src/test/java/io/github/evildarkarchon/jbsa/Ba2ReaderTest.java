package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Public General BA2 contracts using independently specified wire records. */
@EnabledOnOs(OS.WINDOWS)
final class Ba2ReaderTest {
  @TempDir Path directory;

  // CRC table values for single ASCII bytes b and a, initial zero and no final XOR.
  private static final String ONE =
      "42544458 01000000 474e524c 01000000 3d00000000000000 "
          + "7400bca3 74787400 ce51b53a 00011000 3c00000000000000 00000000 01000000 0df0adba "
          + "07 0700 612f622e747874";

  /** Preserves complete wire names and exposes bounded stored content through the public API. */
  @Test
  void opensStoredGeneralBa2() throws Exception {
    try (OpenArchive archive =
        BethesdaArchives.standard().open(literal(ONE), OpenOptions.standard())) {
      EntryMetadata entry = archive.entry(0).metadata();
      assertEquals("a\\b.txt", entry.displayName());
      assertEquals(ArchiveFamily.FO4_GENERAL_BA2, entry.family());
      assertEquals(ArchiveDisposition.CONFORMING, archive.inspection().assessment().disposition());
      assertArrayEquals(
          "a/b.txt".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          entry.wireNames().get("complete").bytes());
      try (EntryContent content = archive.entry(0).openContent()) {
        ByteBuffer bytes = ByteBuffer.allocate(2);
        assertEquals(1, content.read(bytes));
        assertEquals(7, bytes.get(0));
        assertEquals(-1, content.read(bytes));
      }
    }
  }

  /** General zlib starts directly at the payload offset and establishes evidence only at EOF. */
  @Test
  void decodesZlibWithoutBsaSizePrefix() throws Exception {
    try (OpenArchive archive =
            BethesdaArchives.standard().open(literal(zlib()), OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(12, archive.entry(0).metadata().storedSize());
      assertEquals(1, archive.entry(0).metadata().decodedSize());
      ByteBuffer bytes = ByteBuffer.allocate(4);
      assertEquals(1, content.read(bytes));
      assertEquals(7, bytes.get(0));
      assertEquals(-1, content.read(bytes));
      assertTrue(content.assessment().isPresent());
    }
  }

  /** Starfield version 2 preserves its extra header and otherwise uses General zlib framing. */
  @Test
  void decodesStarfieldVersionTwoZlib() throws Exception {
    String versionTwo =
        "42544458 02000000 474e524c 01000000 5000000000000000 0100000000000000 "
            + "7400bca3 74787400 ce51b53a 00011000 4400000000000000 0c000000 01000000 0df0adba "
            + "78da010100feff0700080008 0700 612f622e747874";
    try (OpenArchive archive =
            BethesdaArchives.standard().open(literal(versionTwo), OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(ArchiveFamily.STARFIELD_GENERAL_BA2, archive.inspection().metadata().family());
      assertEquals(
          new WireVersion(2),
          archive.inspection().metadata().encoding().wireVersion().orElseThrow());
      assertTrue(archive.inspection().metadata().encoding().compressionMethod().isEmpty());
      assertEquals(
          1,
          ((ArchiveMetadata.GeneralBa2) archive.inspection().metadata())
              .unknownValueAt24()
              .orElseThrow());
      ByteBuffer decoded = ByteBuffer.allocate(2);
      assertEquals(1, content.read(decoded));
      assertEquals(7, decoded.get(0));
      assertEquals(-1, content.read(decoded));
    }
  }

  /** A safely ignored Starfield extra-header value remains inspectable with exact evidence. */
  @Test
  void diagnosesNoncanonicalStarfieldExtraHeader() throws Exception {
    byte[] versionTwo =
        bytes(
            "42544458 02000000 474e524c 01000000 5000000000000000 0100000000000000 "
                + "7400bca3 74787400 ce51b53a 00011000 4400000000000000 0c000000 01000000 0df0adba "
                + "78da010100feff0700080008 0700 612f622e747874");
    ByteBuffer.wrap(versionTwo).order(ByteOrder.LITTLE_ENDIAN).putLong(24, 2);
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(write(versionTwo));
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    Diagnostic diagnostic = inspection.assessment().diagnostics().getFirst();
    assertEquals("ba2.extra-header-value", diagnostic.identifier());
    assertEquals(
        new DiagnosticLocation.ByteSpan(24, 8), diagnostic.location().byteSpan().orElseThrow());
    assertEquals(Map.of("stored", "2", "expected", "1"), diagnostic.values());
  }

  /** Starfield version 3 method 3 decodes one complete raw-LZ4 block without frame bytes. */
  @Test
  void decodesStarfieldVersionThreeRawLz4() throws Exception {
    String versionThree =
        "42544458 03000000 474e524c 01000000 5900000000000000 0100000000000000 03000000 "
            + "4702f2e5 74787400 7f2cb78c 00011000 4800000000000000 11000000 0f000000 0df0adba "
            + "f0006a6273612d737461726669656c640a 0c00 646174612f7261772e747874";
    try (OpenArchive archive =
            BethesdaArchives.standard().open(literal(versionThree), OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      ArchiveMetadata metadata = archive.inspection().metadata();
      assertEquals(ArchiveFamily.STARFIELD_GENERAL_BA2, metadata.family());
      assertEquals(new WireVersion(3), metadata.encoding().wireVersion().orElseThrow());
      assertEquals(3, metadata.encoding().compressionMethod().orElseThrow());
      assertArrayEquals(
          "jbsa-starfield\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          java.nio.channels.Channels.newInputStream(content).readAllBytes());
      assertTrue(content.assessment().isPresent());
    }
  }

  /** A profile may decode a non-method-3 v3 archive without rewriting its detection status. */
  @Test
  void appliesQualifiedVersionThreeZlibFallbackWithoutChangingRecognition() throws Exception {
    String fallback =
        "42544458 03000000 474e524c 01000000 5400000000000000 0100000000000000 02000000 "
            + "7400bca3 74787400 ce51b53a 00011000 4800000000000000 0c000000 01000000 0df0adba "
            + "78da010100feff0700080008 0700 612f622e747874";
    Path archivePath = literal(fallback);
    ArchiveException unsupported =
        assertThrows(
            ArchiveException.class,
            () -> BethesdaArchives.standard().open(archivePath, OpenOptions.standard()));
    assertEquals(FailureKind.UNSUPPORTED, unsupported.kind());
    OpenOptions qualified =
        new OpenOptions(
            java.util.Optional.of(CompatibilityProfile.BSARCH_1_0_V1),
            ResourceLimits.standard(),
            java.util.Optional.empty());
    try (OpenArchive archive = BethesdaArchives.standard().open(archivePath, qualified);
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(DetectionStatus.UNSUPPORTED_VARIANT, archive.inspection().detection().status());
      assertTrue(archive.inspection().detection().family().isEmpty());
      assertEquals(ArchiveFamily.STARFIELD_GENERAL_BA2, archive.inspection().metadata().family());
      Diagnostic diagnostic = archive.inspection().assessment().diagnostics().getFirst();
      assertEquals("ba2.sf3-zlib-fallback", diagnostic.identifier());
      assertEquals(
          new DiagnosticLocation.ByteSpan(32, 4), diagnostic.location().byteSpan().orElseThrow());
      assertEquals("2", diagnostic.values().get("stored"));
      ByteBuffer decoded = ByteBuffer.allocate(2);
      assertEquals(1, content.read(decoded));
      assertEquals(7, decoded.get(0));
      assertEquals(-1, content.read(decoded));
    }
  }

  /** Raw-LZ4 rejects trailing block bytes and oversize output before changing caller buffers. */
  @Test
  void rejectsInvalidAndOversizeStarfieldRawBlocksWithoutOutputEffects() throws Exception {
    String trailing =
        "42544458 03000000 474e524c 01000000 5a00000000000000 0100000000000000 03000000 "
            + "4702f2e5 74787400 7f2cb78c 00011000 4800000000000000 12000000 0f000000 0df0adba "
            + "f0006a6273612d737461726669656c640a00 0c00 646174612f7261772e747874";
    assertRawReadFailsWithoutOutput(trailing, FailureKind.FORMAT);

    byte[] oversize =
        bytes(
            "42544458 03000000 474e524c 01000000 5900000000000000 0100000000000000 03000000 "
                + "4702f2e5 74787400 7f2cb78c 00011000 4800000000000000 11000000 0f000000 0df0adba "
                + "f0006a6273612d737461726669656c640a 0c00 646174612f7261772e747874");
    ByteBuffer.wrap(oversize).order(ByteOrder.LITTLE_ENDIAN).putInt(64, 16 * 1024 * 1024 + 1);
    assertRawReadFailsWithoutOutput(HexFormat.of().formatHex(oversize), FailureKind.POLICY);
  }

  /** Unusable table offsets remain bounded and preserve hashes without synthetic wire identity. */
  @Test
  void preservesMissingNameTableIdentity() throws Exception {
    for (long offset : new long[] {0, -1, Long.MAX_VALUE, 70}) {
      byte[] bytes = bytes(ONE);
      ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(16, offset);
      ArchiveInspection inspection = BethesdaArchives.standard().inspect(write(bytes));
      EntryMetadata entry = inspection.entries().getFirst();
      assertEquals("__jbsa_hash__\\d3ab551ce\\e00000000-a3bc0074-x74787400", entry.displayName());
      assertTrue(entry.normalizedNameIdentity().isEmpty());
      assertTrue(entry.wireNames().isEmpty());
      assertEquals(
          1,
          inspection.assessment().diagnostics().stream()
              .filter(d -> d.identifier().equals("ba2.missing-name-table"))
              .count());
      assertEquals(
          ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    }
  }

  /** Ignored constants and mismatching identity fields retain exact per-field warning evidence. */
  @Test
  void diagnosesNoncanonicalRecordFieldsAndTrailingBytes() throws Exception {
    byte[] bytes = java.util.Arrays.copyOf(bytes(ONE), 71);
    ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
    data.putInt(24, 0)
        .putInt(28, 0)
        .putInt(32, 0)
        .put(36, (byte) 2)
        .putShort(38, (short) 8)
        .putInt(56, 0);
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(write(bytes));
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    assertEquals(
        java.util.Set.of(
            "ba2.basename-hash-mismatch",
            "ba2.extension-mismatch",
            "ba2.directory-hash-mismatch",
            "gnrl.nonzero-mod-index",
            "gnrl.chunk-header-size",
            "gnrl.sentinel-mismatch",
            "ba2.trailing-data"),
        inspection.assessment().diagnostics().stream()
            .map(Diagnostic::identifier)
            .collect(java.util.stream.Collectors.toSet()));
    Diagnostic hash =
        inspection.assessment().diagnostics().stream()
            .filter(d -> d.identifier().equals("ba2.basename-hash-mismatch"))
            .findFirst()
            .orElseThrow();
    assertEquals(new DiagnosticLocation.ByteSpan(24, 4), hash.location().byteSpan().orElseThrow());
    assertEquals("A3BC0074", hash.values().get("expected"));
  }

  /**
   * Metadata validation rejects truncation, impossible records, bad chunks and metadata overlap.
   */
  @Test
  void rejectsInvalidStructuralSpans() throws Exception {
    for (int mode = 0; mode < 8; mode++) {
      byte[] bytes = bytes(ONE);
      ByteBuffer data = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN);
      switch (mode) {
        case 0 -> data.putInt(12, -1);
        case 1 -> data.put(37, (byte) 2);
        case 2 -> data.putLong(40, -1);
        case 3 -> data.putLong(40, Long.MAX_VALUE);
        case 4 -> data.putLong(40, 24);
        case 5 -> data.putLong(16, 24);
        case 6 -> data.putLong(40, 63);
        default -> data.putShort(61, (short) 100);
      }
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class, () -> BethesdaArchives.standard().inspect(write(bytes)))
              .kind());
    }
    assertEquals(
        FailureKind.FORMAT,
        assertThrows(
                ArchiveException.class,
                () ->
                    BethesdaArchives.standard()
                        .inspect(write(java.util.Arrays.copyOf(bytes(ONE), 59))))
            .kind());
  }

  /** Wire bytes survive decode failure and non-ASCII names do not trigger speculative hashing. */
  @Test
  void retainsUndecodableAndNonAsciiNames() throws Exception {
    byte[] bytes = bytes(ONE);
    bytes[65] = (byte) 0x81;
    ArchiveInspection undecodable = BethesdaArchives.standard().inspect(write(bytes));
    assertEquals("__jbsa_wire__\\e00000000", undecodable.entries().getFirst().displayName());
    assertTrue(undecodable.entries().getFirst().normalizedNameIdentity().isEmpty());
    assertArrayEquals(
        java.util.Arrays.copyOfRange(bytes, 63, 70),
        undecodable.entries().getFirst().wireNames().get("complete").bytes());
    assertTrue(
        undecodable.assessment().diagnostics().stream()
            .anyMatch(d -> d.identifier().equals("archive-name.undecodable-wire-bytes")));
    bytes[65] = (byte) 0xe9;
    ArchiveInspection nonAscii = BethesdaArchives.standard().inspect(write(bytes));
    assertEquals("a\\é.txt", nonAscii.entries().getFirst().displayName());
    assertEquals(ArchiveDisposition.CONFORMING, nonAscii.assessment().disposition());
  }

  /**
   * Shared spans remain valid while duplicate normalized names and partial sharing are rejected.
   */
  @Test
  void validatesSharedSpansAndDuplicateNames() throws Exception {
    byte[] shared = twoEntries();
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(write(shared));
    assertEquals(
        java.util.List.of("a\\b.txt", "a\\c.txt"),
        inspection.entries().stream().map(EntryMetadata::displayName).toList());
    assertEquals(ArchiveDisposition.CONFORMING, inspection.assessment().disposition());
    shared[110] = 'b';
    assertEquals(
        FailureKind.FORMAT,
        assertThrows(
                ArchiveException.class, () -> BethesdaArchives.standard().inspect(write(shared)))
            .kind());
    byte[] partial = twoEntries();
    ByteBuffer.wrap(partial).order(ByteOrder.LITTLE_ENDIAN).putInt(52, 2);
    assertEquals(
        FailureKind.FORMAT,
        assertThrows(
                ArchiveException.class, () -> BethesdaArchives.standard().inspect(write(partial)))
            .kind());
  }

  /** Invalid codec framing and size/checksum errors remain structural-only until content read. */
  @Test
  void rejectsInvalidCompressedContent() throws Exception {
    for (String invalid :
        new String[] {
          zlib().replace("0700080008", "0700080009"),
          zlib().replace("78da", "0422"),
          zlib().replace("78da", "0101"),
          zlib()
              .replace("4800000000000000", "4900000000000000")
              .replace("0c000000 01000000", "0d000000 01000000")
              .replace("00080008 0700", "00080008 ff 0700"),
          zlib().replace("0c000000 01000000", "0c000000 02000000"),
          zlib().replace("0c000000 01000000", "0c000000 00000000")
        }) {
      try (OpenArchive archive =
              BethesdaArchives.standard().open(literal(invalid), OpenOptions.standard());
          EntryContent content = archive.entry(0).openContent()) {
        assertEquals(
            FailureKind.FORMAT,
            assertThrows(
                    ArchiveException.class,
                    () -> {
                      ByteBuffer bytes = ByteBuffer.allocate(8);
                      while (content.read(bytes) >= 0) bytes.clear();
                    })
                .kind());
      }
    }
  }

  /** Unsafe names remain inspectable and extraction rejects them before creating destinations. */
  @Test
  void refusesUnsafeNamesBeforeDestinationEffects() throws Exception {
    for (String name : new String[] {"/a/b.tx", "../b.tx", "a/NUL.t"}) {
      byte[] bytes = bytes(ONE);
      System.arraycopy(name.getBytes(java.nio.charset.StandardCharsets.US_ASCII), 0, bytes, 63, 7);
      Path path = write(bytes);
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

  /** A zero-byte stored entry has an empty content stream and consumes no payload byte. */
  @Test
  void opensZeroLengthStoredEntry() throws Exception {
    String empty =
        ONE.replace("3d00000000000000", "3c00000000000000")
            .replace("00000000 01000000 0df0adba", "00000000 00000000 0df0adba")
            .replace("07 0700", "0700");
    try (OpenArchive archive =
            BethesdaArchives.standard().open(literal(empty), OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(0, archive.entry(0).metadata().decodedSize());
      assertEquals(-1, content.read(ByteBuffer.allocate(1)));
    }
  }

  /** Empty payload coordinates cannot claim otherwise unreferenced trailing bytes. */
  @Test
  void diagnosesTrailingBytesBeyondNamesWithEmptyPayloadAtEof() throws Exception {
    byte[] bytes =
        bytes(
            ONE.replace("3d00000000000000", "3c00000000000000")
                    .replace("00000000 01000000 0df0adba", "00000000 00000000 0df0adba")
                    .replace("07 0700", "0700")
                + " ff");
    ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).putLong(40, bytes.length);
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(write(bytes));
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    Diagnostic trailing =
        inspection.assessment().diagnostics().stream()
            .filter(d -> d.identifier().equals("ba2.trailing-data"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        new DiagnosticLocation.ByteSpan(69, 1), trailing.location().byteSpan().orElseThrow());
  }

  /** Entry and encoded-metadata limits fail as policy before constructing an oversized index. */
  @Test
  void enforcesMetadataAndEntryLimits() throws Exception {
    for (ResourceLimits limits :
        new ResourceLimits[] {
          new ResourceLimits(0, 1000, 1000, 1000, 10, 10, 10),
          new ResourceLimits(10, 68, 1000, 1000, 10, 10, 10)
        }) {
      OpenOptions options =
          new OpenOptions(java.util.Optional.empty(), limits, java.util.Optional.empty());
      assertEquals(
          FailureKind.POLICY,
          assertThrows(
                  ArchiveException.class,
                  () -> BethesdaArchives.standard().open(literal(ONE), options))
              .kind());
    }
  }

  /** Warning rejection applies even when a tiny retention budget omits the rejected warning. */
  @Test
  void rejectsWarningsBeforeRetentionAndPublication() throws Exception {
    byte[] bytes = bytes(ONE);
    ByteBuffer.wrap(bytes)
        .order(ByteOrder.LITTLE_ENDIAN)
        .putInt(24, 0)
        .putInt(28, 0)
        .putInt(32, 0)
        .putInt(56, 0);
    for (int retained : new int[] {1, 20}) {
      Path destination = directory.resolve("policy-output-" + retained);
      OpenOptions options =
          new OpenOptions(
              java.util.Optional.empty(),
              new ResourceLimits(10, 1000, 1000, 1000, 10, retained, 10),
              java.util.Optional.empty());
      ExtractRequest request =
          new ExtractRequest(
              write(bytes),
              destination,
              EntrySelection.ALL,
              TargetPolicy.FAIL,
              new DiagnosticPolicy(java.util.Set.of("gnrl.sentinel-mismatch")),
              WorkerSelection.AUTOMATIC,
              options);
      ArchiveException failure =
          assertThrows(
              ArchiveException.class,
              () -> BethesdaArchives.standard().extract(request, OperationControl.standard()));
      assertEquals(FailureKind.POLICY, failure.kind());
      assertFalse(Files.exists(destination));
      assertEquals(
          ArchiveDisposition.TOLERATED_NONCANONICAL,
          failure.assessment().orElseThrow().disposition());
      if (retained == 1)
        assertTrue(
            failure.diagnostics().stream()
                .anyMatch(d -> d.identifier().equals("operation.records-truncated")));
      else
        assertTrue(
            failure.diagnostics().stream()
                .anyMatch(
                    d ->
                        d.identifier().equals("gnrl.sentinel-mismatch")
                            && d.severity() == DiagnosticSeverity.WARNING));
    }
  }

  /**
   * Returns a literal RFC 1950 stored block containing one byte with the correct Adler checksum.
   */
  private static String zlib() {
    return ONE.replace("3d00000000000000", "4800000000000000")
        .replace("00000000 01000000 0df0adba", "0c000000 01000000 0df0adba")
        .replace("07 0700", "78da010100feff0700080008 0700");
  }

  /** Builds two independently specified identity records sharing a single byte payload. */
  private static byte[] twoEntries() {
    return bytes(
        "42544458 01000000 474e524c 02000000 6100000000000000 "
            + "7400bca3 74787400 ce51b53a 00011000 6000000000000000 00000000 01000000 0df0adba "
            + "e230bbd4 74787400 ce51b53a 00011000 6000000000000000 00000000 01000000 0df0adba "
            + "07 0700 612f622e747874 0700 612f632e747874");
  }

  private static byte[] bytes(String hex) {
    return HexFormat.of().parseHex(hex.replace(" ", ""));
  }

  /** Writes test-owned fixture bytes without using an encoder as the expected-value oracle. */
  private Path write(byte[] bytes) throws Exception {
    return Files.write(directory.resolve("fixture.ba2"), bytes);
  }

  /** Writes an independently authored literal fixture into the test-owned directory. */
  private Path literal(String hex) throws Exception {
    return Files.write(
        directory.resolve("fixture.ba2"), HexFormat.of().parseHex(hex.replace(" ", "")));
  }

  /** Reads one raw entry and proves a rejected block never publishes bytes to the caller. */
  private void assertRawReadFailsWithoutOutput(String hex, FailureKind kind) throws Exception {
    try (OpenArchive archive =
            BethesdaArchives.standard().open(literal(hex), OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      ByteBuffer destination = ByteBuffer.allocate(8);
      java.util.Arrays.fill(destination.array(), (byte) 0x55);
      ArchiveException failure =
          assertThrows(ArchiveException.class, () -> content.read(destination));
      assertEquals(kind, failure.kind());
      assertEquals(0, destination.position());
      assertArrayEquals(
          new byte[] {0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55, 0x55}, destination.array());
    }
  }
}
