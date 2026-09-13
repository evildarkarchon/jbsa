package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Public BSA reader contracts against independently worked literal wire records. */
@EnabledOnOs(OS.WINDOWS)
final class BsaReaderTest {
  @TempDir Path directory;

  // Folder "a", basename "b": one-character stem hash is first << 24 | length << 16 | last.
  private static final String ONE =
      "42534100 67000000 24000000 03000000 01000000 01000000 02000000 02000000 00000000 "
          + "6100016100000000 01000000 36000000 026100 "
          + "6200016200000000 01000000 49000000 6200 07";
  private static final String ZLIB =
      ONE.replace("03000000", "07030000")
          .replace("01000000 49000000", "10000000 49000000")
          .replace("6200 07", "6200 01000000 78da010100feff0700080008");

  /** Structure and original names remain detached while content gains evidence only at EOF. */
  @Test
  void opensStoredOblivionArchive() throws Exception {
    Path path = literal(ONE);
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
      EntryMetadata entry = archive.entry(0).metadata();
      assertEquals("a\\b", entry.displayName());
      assertEquals(ArchiveDisposition.CONFORMING, archive.inspection().assessment().disposition());
      assertArrayEquals(new byte[] {97}, entry.wireNames().get("folder").bytes());
      assertArrayEquals(new byte[] {98}, entry.wireNames().get("basename").bytes());
      assertEquals(
          new EntryMetadata.VersionedBsa(0x61010061L, 0x62010062L, 0, 1, 54, 0, 0, 1, 73, false),
          entry.facts());
      try (EntryContent content = archive.entry(0).openContent()) {
        ByteBuffer bytes = ByteBuffer.allocate(2);
        assertEquals(1, content.read(bytes));
        assertEquals(7, bytes.get(0));
        assertEquals(-1, content.read(bytes));
      }
    }
  }

  /** Zlib framing is decoded despite the Oblivion XMem and embedded-name flag markers. */
  @Test
  void decodesLiteralZlibWithOblivionMarkers() throws Exception {
    // RFC 1950 stream: level-9 header, final stored DEFLATE block containing 07, Adler32 00080008.
    Path path = literal(ZLIB);
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
      assertEquals(1, archive.entry(0).metadata().decodedSize());
      assertEquals(16, archive.entry(0).metadata().storedSize());
      assertTrue(((EntryMetadata.VersionedBsa) archive.entry(0).metadata().facts()).compressed());
      try (EntryContent content = archive.entry(0).openContent()) {
        ByteBuffer bytes = ByteBuffer.allocate(2);
        assertEquals(1, content.read(bytes));
        assertEquals(7, bytes.get(0));
        assertEquals(-1, content.read(bytes));
        assertTrue(content.assessment().isPresent());
      }
    }
  }

  /** Invalid streams remain inspectable structurally and fail at payload validation. */
  @Test
  void rejectsChecksumTrailingTruncationAndDecodedSizeMismatch() throws Exception {
    for (String bad :
        new String[] {
          ZLIB.replace("0700080008", "0700080009"),
          ZLIB.replace("10000000 49000000", "11000000 49000000") + " ff",
          ZLIB.replace("10000000 49000000", "0f000000 49000000").substring(0, ZLIB.length() - 2),
          ZLIB.replace("6200 01000000", "6200 02000000"),
          ZLIB.replace("6200 01000000", "6200 00000000"),
          ZLIB.replace("78da", "0000")
        }) {
      Path path = literal(bad);
      try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard());
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

  /** Each child owns decoding state; closing an unread child cannot establish EOF evidence. */
  @Test
  void keepsCompressedChildrenIndependent() throws Exception {
    Path path = literal(ZLIB);
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
      EntryContent first = archive.entry(0).openContent();
      EntryContent second = archive.entry(0).openContent();
      first.close();
      assertTrue(first.assessment().isEmpty());
      ByteBuffer bytes = ByteBuffer.allocate(2);
      assertEquals(1, second.read(bytes));
      assertEquals(7, bytes.get(0));
      assertEquals(-1, second.read(bytes));
      second.close();
    }
  }

  /** Missing index sections preserve hashes and ordinals without fabricating original names. */
  @Test
  void preservesAbsentComponentsAcrossAllPresenceFlags() throws Exception {
    for (int flags = 0; flags < 4; flags++) {
      String hex =
          switch (flags) {
            case 0 ->
                "42534100 67000000 24000000 00000000 01000000 01000000 00000000 00000000 00000000 6100016100000000 01000000 34000000 6200016200000000 01000000 44000000 07";
            case 1 ->
                "42534100 67000000 24000000 01000000 01000000 01000000 02000000 00000000 00000000 6100016100000000 01000000 34000000 026100 6200016200000000 01000000 47000000 07";
            case 2 ->
                "42534100 67000000 24000000 02000000 01000000 01000000 00000000 02000000 00000000 6100016100000000 01000000 36000000 6200016200000000 01000000 46000000 6200 07";
            default -> ONE;
          };
      EntryMetadata entry = BethesdaArchives.standard().inspect(literal(hex)).entries().getFirst();
      assertEquals((flags & 1) != 0, entry.wireNames().containsKey("folder"));
      assertEquals((flags & 2) != 0, entry.wireNames().containsKey("basename"));
      assertEquals(flags == 3, entry.normalizedNameIdentity().isPresent());
      if (flags != 3)
        assertEquals(
            "__jbsa_hash__\\f00000000-0000000061010061\\e00000000-0000000062010062",
            entry.displayName());
    }
  }

  /** Hash mismatches preserve original records and exact normative diagnostic locations. */
  @Test
  void diagnosesHashesAndUndecodableComponents() throws Exception {
    ArchiveInspection hashes =
        BethesdaArchives.standard()
            .inspect(
                literal(
                    ONE.replace("6100016100000000", "0000000000000000")
                        .replace("6200016200000000", "0000000000000000")));
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, hashes.assessment().disposition());
    assertEquals("bsa.folder-hash-mismatch", hashes.assessment().diagnostics().get(0).identifier());
    assertEquals(
        new DiagnosticLocation.ByteSpan(36, 8),
        hashes.assessment().diagnostics().get(0).location().byteSpan().orElseThrow());
    assertEquals("bsa.file-hash-mismatch", hashes.assessment().diagnostics().get(1).identifier());
    ArchiveInspection invalid =
        BethesdaArchives.standard().inspect(literal(ONE.replace("026100", "028100")));
    assertTrue(invalid.entries().getFirst().normalizedNameIdentity().isEmpty());
    assertArrayEquals(
        new byte[] {(byte) 0x81}, invalid.entries().getFirst().wireNames().get("folder").bytes());
    assertEquals(
        "archive-name.undecodable-wire-bytes",
        invalid.assessment().diagnostics().getFirst().identifier());
  }

  /**
   * Index bounds, conditioned lengths, and unsigned bit 31 cannot silently change payload spans.
   */
  @Test
  void rejectsMalformedIndexAndUnsignedSizeSpans() throws Exception {
    for (String bad :
        new String[] {
          ONE.replace("36000000", "35000000"), ONE.replace("026100", "026101"),
          ONE.replace("49000000", "48000000"),
              ONE.replace("01000000 49000000", "01000080 49000000"),
          ONE.replace("03000000", "00000000"), ONE.replace("02000000 02000000", "03000000 02000000")
        }) {
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class, () -> BethesdaArchives.standard().inspect(literal(bad)))
              .kind());
    }
  }

  /**
   * Unsigned payload offsets remain readable while reporting the signed implementation boundary.
   */
  @Test
  void warnsAtPayloadOffsetBeyondSignedTwoGiB() throws Exception {
    Path path = literal(ONE.replace("49000000", "00000080"));
    try (var channel =
        java.nio.channels.FileChannel.open(path, java.nio.file.StandardOpenOption.WRITE)) {
      channel.write(ByteBuffer.wrap(new byte[] {7}), 0x80000000L);
    }
    ArchiveInspection result = BethesdaArchives.standard().inspect(path);
    Diagnostic diagnostic = result.assessment().diagnostics().getFirst();
    assertEquals("bsa.payload-offset-over-signed-2gib", diagnostic.identifier());
    assertEquals(
        new DiagnosticLocation.ByteSpan(0x80000000L, 0),
        diagnostic.location().byteSpan().orElseThrow());
    assertEquals(ArchiveDisposition.CONFORMING, result.assessment().disposition());
  }

  /** Compression XOR is per record, and exact shared ranges are valid while partial ranges fail. */
  @Test
  void opensMixedRecordsAndDistinguishesExactSharedSpans() throws Exception {
    String prefix =
        "42534100 67000000 24000000 07000000 01000000 02000000 02000000 04000000 00000000 6100016100000000 02000000 38000000 026100 ";
    String mixed =
        prefix
            + "6200016200000000 01000040 5b000000 6300016300000000 10000000 5c000000 62006300 07 01000000 78da010100feff0700080008";
    try (OpenArchive archive =
        BethesdaArchives.standard().open(literal(mixed), OpenOptions.standard())) {
      assertFalse(((EntryMetadata.VersionedBsa) archive.entry(0).metadata().facts()).compressed());
      assertTrue(((EntryMetadata.VersionedBsa) archive.entry(1).metadata().facts()).compressed());
      for (int ordinal = 0; ordinal < 2; ordinal++)
        try (EntryContent content = archive.entry(ordinal).openContent()) {
          ByteBuffer bytes = ByteBuffer.allocate(2);
          assertEquals(1, content.read(bytes));
          assertEquals(7, bytes.get(0));
          assertEquals(-1, content.read(bytes));
        }
    }
    String shared =
        prefix
            + "6200016200000000 02000040 5b000000 6300016300000000 02000040 5b000000 62006300 0708";
    assertEquals(2, BethesdaArchives.standard().inspect(literal(shared)).entries().size());
    String overlap =
        shared.replace("6300016300000000 02000040 5b000000", "6300016300000000 02000040 5c000000")
            + "09";
    assertEquals(
        FailureKind.FORMAT,
        assertThrows(
                ArchiveException.class, () -> BethesdaArchives.standard().inspect(literal(overlap)))
            .kind());
  }

  /**
   * Inspection identifies cubemap DDS headers in stored and compressed payloads with bounded work.
   */
  @Test
  void warnsAboutCubemapsWithoutEmbeddedNames() throws Exception {
    byte[] dds = new byte[128];
    ByteBuffer header = ByteBuffer.wrap(dds).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    header.putInt(0, 0x20534444).putInt(4, 124).putInt(76, 32).putInt(112, 0x200);
    for (boolean compressed : new boolean[] {false, true}) {
      byte[] payload = dds;
      if (compressed) {
        var output = new java.io.ByteArrayOutputStream();
        output.write(new byte[] {(byte) 128, 0, 0, 0});
        try (var zlib = new java.util.zip.DeflaterOutputStream(output)) {
          zlib.write(dds);
        }
        payload = output.toByteArray();
      }
      byte[] prefix = HexFormat.of().parseHex(ONE.replace(" ", ""));
      ByteBuffer index =
          ByteBuffer.allocate(77 + payload.length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      index.put(prefix, 0, 71);
      index.put(new byte[] {98, 46, 100, 100, 115, 0}).put(payload);
      index
          .putInt(12, compressed ? 7 : 3)
          .putInt(28, 6)
          .putInt(48, 58)
          .putInt(63, payload.length)
          .putInt(67, 77);
      Path path = Files.write(directory.resolve("cube.bsa"), index.array());
      ArchiveInspection result = BethesdaArchives.standard().inspect(path);
      Diagnostic warning =
          result.assessment().diagnostics().stream()
              .filter(d -> d.identifier().equals("bsa.cubemap-without-embedded-name"))
              .findFirst()
              .orElseThrow();
      assertEquals(0, warning.location().entryOrdinal().orElseThrow());
      assertEquals(new ValidationExtent.Structure(), result.assessment().extent());
    }
  }

  /** Unqualified non-ASCII and empty-stem components retain stored hashes without comparisons. */
  @Test
  void preservesAuthoritativeNonAsciiAndEmptyStemHashes() throws Exception {
    ArchiveInspection nonAscii =
        BethesdaArchives.standard()
            .inspect(literal(ONE.replace("026100", "02e900").replace("6200 07", "e900 07")));
    assertTrue(nonAscii.assessment().diagnostics().isEmpty());
    assertEquals("é\\é", nonAscii.entries().getFirst().displayName());
    ArchiveInspection emptyStem =
        BethesdaArchives.standard().inspect(literal(ONE.replace("6200 07", "2e00 07")));
    assertTrue(
        emptyStem.assessment().diagnostics().stream()
            .noneMatch(d -> d.identifier().contains("hash-mismatch")));
    assertEquals("a\\.", emptyStem.entries().getFirst().displayName());
  }

  /** An empty folder still retains its independent name hash and once-per-folder warning. */
  @Test
  void diagnosesHashMismatchInEmptyFolder() throws Exception {
    Path path =
        literal(
            "42534100 67000000 24000000 03000000 01000000 00000000 02000000 00000000 00000000 0000000000000000 00000000 34000000 026100");
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(path);
    assertEquals(0, inspection.entries().size());
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    Diagnostic warning = inspection.assessment().diagnostics().getFirst();
    assertEquals("bsa.folder-hash-mismatch", warning.identifier());
    assertEquals(
        new DiagnosticLocation.ByteSpan(36, 8), warning.location().byteSpan().orElseThrow());
    assertTrue(warning.location().entryOrdinal().isEmpty());
  }

  /** Writes exact fixture bytes without deriving expected hashes from production helpers. */
  private Path literal(String hex) throws Exception {
    return Files.write(
        directory.resolve("archive.bsa"), HexFormat.of().parseHex(hex.replace(" ", "")));
  }
}
