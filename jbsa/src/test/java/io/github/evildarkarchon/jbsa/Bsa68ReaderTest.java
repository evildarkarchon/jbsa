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

/** Independently worked 0x68 records exercise the public reader and framing contract. */
@EnabledOnOs(OS.WINDOWS)
final class Bsa68ReaderTest {
  @TempDir Path directory;
  private static final String ONE =
      "42534100 68000000 24000000 03000000 01000000 01000000 02000000 02000000 00000000 "
          + "6100016100000000 01000000 36000000 026100 "
          + "6200016200000000 01000000 49000000 6200 07";

  /** Stored and compressed framing exclude embedded bytes from returned content. */
  @Test
  void opensStoredAndZlibWithAndWithoutEmbeddedNames() throws Exception {
    for (boolean compressed : new boolean[] {false, true}) {
      for (boolean embedded : new boolean[] {false, true}) {
        String payload = compressed ? "01000000 78da010100feff0700080008" : "07";
        int size = (compressed ? 16 : 1) + (embedded ? 4 : 0);
        String fixture =
            ONE.replace(
                "03000000",
                compressed
                    ? (embedded ? "07010000" : "07000000")
                    : (embedded ? "03010000" : "03000000"));
        fixture =
            fixture
                .replace("01000000 49000000", String.format("%02x000000 49000000", size))
                .replace("6200 07", "6200 " + (embedded ? "03615c62 " : "") + payload);
        try (OpenArchive archive =
            BethesdaArchives.standard().open(literal(fixture), OpenOptions.standard())) {
          EntryMetadata metadata = archive.entry(0).metadata();
          assertEquals(ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA, metadata.family());
          assertEquals(0x68, metadata.encoding().wireVersion().orElseThrow().value());
          assertEquals(1, metadata.decodedSize());
          assertEquals(size, metadata.storedSize());
          assertEquals(embedded, metadata.wireNames().containsKey("embedded"));
          if (embedded)
            assertArrayEquals(
                new byte[] {97, 92, 98}, metadata.wireNames().get("embedded").bytes());
          try (EntryContent content = archive.entry(0).openContent()) {
            ByteBuffer bytes = ByteBuffer.allocate(2);
            assertEquals(1, content.read(bytes));
            assertEquals(7, bytes.get(0));
            assertEquals(-1, content.read(bytes));
          }
        }
      }
    }
  }

  /** Bounded mismatches preserve content and expose the exact diagnostic span. */
  @Test
  void diagnosesEmbeddedMismatchAndNontexture() throws Exception {
    var inspection =
        BethesdaArchives.standard()
            .inspect(
                literal(
                    ONE.replace("03000000", "03010000")
                        .replace("01000000 49000000", "05000000 49000000")
                        .replace("6200 07", "6200 03615c63 07")));
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    var mismatch =
        inspection.assessment().diagnostics().stream()
            .filter(d -> d.identifier().equals("bsa.embedded-name-mismatch"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        new DiagnosticLocation.ByteSpan(74, 3), mismatch.location().byteSpan().orElseThrow());
    assertTrue(
        inspection.assessment().diagnostics().stream()
            .anyMatch(d -> d.identifier().equals("bsa.nontexture-with-embedded-name")));
  }

  /** Prefix and compressed-size fields must fit the record even when adjacent bytes exist. */
  @Test
  void rejectsEmbeddedFramingOutsideItsRecord() throws Exception {
    for (String payload : new String[] {"ff", "03615c62", "04615c62"}) {
      String fixture =
          ONE.replace("03000000", "07010000")
              .replace("01000000 49000000", "04000000 49000000")
              .replace("6200 07", "6200 " + payload + "00000000");
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class,
                  () -> BethesdaArchives.standard().inspect(literal(fixture)))
              .kind());
    }
  }

  /** A framing name cannot fabricate index components or a normalized identity. */
  @Test
  void keepsEmbeddedNamesSeparateFromAbsentIndexComponents() throws Exception {
    String unnamed =
        "42534100 68000000 24000000 00010000 01000000 01000000 00000000 00000000 00000000 "
            + "6100016100000000 01000000 34000000 6200016200000000 05000000 44000000 03615c62 07";
    var entry = BethesdaArchives.standard().inspect(literal(unnamed)).entries().getFirst();
    assertTrue(entry.normalizedNameIdentity().isEmpty());
    assertEquals(java.util.Set.of("embedded"), entry.wireNames().keySet());
    assertTrue(entry.displayName().startsWith("__jbsa_hash__"));
  }

  /** Compression XOR remains per-entry with each embedded prefix before its decoded-size field. */
  @Test
  void opensMixedEmbeddedRecords() throws Exception {
    String mixed =
        "42534100 68000000 24000000 07010000 01000000 02000000 02000000 04000000 00000000 "
            + "6100016100000000 02000000 38000000 026100 "
            + "6200016200000000 05000040 5b000000 6300016300000000 14000000 60000000 62006300 "
            + "03615c62 07 03615c63 01000000 78da010100feff0700080008";
    try (var archive = BethesdaArchives.standard().open(literal(mixed), OpenOptions.standard())) {
      for (int ordinal = 0; ordinal < 2; ordinal++) {
        assertEquals(
            ordinal == 1,
            ((EntryMetadata.VersionedBsa) archive.entry(ordinal).metadata().facts()).compressed());
        try (var content = archive.entry(ordinal).openContent()) {
          var bytes = ByteBuffer.allocate(2);
          assertEquals(1, content.read(bytes));
          assertEquals(7, bytes.get(0));
          assertEquals(-1, content.read(bytes));
        }
      }
    }
  }

  /** Embedded bytes consume metadata credit and decoded limits apply to content after framing. */
  @Test
  void chargesEmbeddedMetadataAndDecodedBytes() throws Exception {
    String embedded =
        ONE.replace("03000000", "03010000")
            .replace("01000000 49000000", "05000000 49000000")
            .replace("6200 07", "6200 03615c62 07");
    Path path = literal(embedded);
    var defaults = ResourceLimits.standard();
    var noDecoded =
        new ResourceLimits(
            defaults.maxEntries(),
            defaults.maxMetadataBytes(),
            0,
            defaults.maxScratchBytes(),
            defaults.maxOutputs(),
            defaults.maxDiagnostics(),
            defaults.maxSecondaryFailures());
    var options =
        new OpenOptions(java.util.Optional.empty(), noDecoded, java.util.Optional.empty());
    try (var archive = BethesdaArchives.standard().open(path, options)) {
      assertEquals(
          FailureKind.POLICY,
          assertThrows(ArchiveException.class, () -> archive.entry(0).openContent()).kind());
    }
    var lowMetadata =
        new ResourceLimits(
            defaults.maxEntries(),
            73,
            defaults.maxDecodedBytes(),
            defaults.maxScratchBytes(),
            defaults.maxOutputs(),
            defaults.maxDiagnostics(),
            defaults.maxSecondaryFailures());
    var metadataOptions =
        new OpenOptions(java.util.Optional.empty(), lowMetadata, java.util.Optional.empty());
    assertEquals(
        FailureKind.POLICY,
        assertThrows(
                ArchiveException.class,
                () -> BethesdaArchives.standard().open(path, metadataOptions))
            .kind());
    // The same index fits exactly without the four framing bytes.
    try (var archive = BethesdaArchives.standard().open(literal(ONE), metadataOptions)) {
      assertEquals(1, archive.inspection().entries().size());
    }
  }

  /** Writes exact wire bytes without production serialization helpers. */
  private Path literal(String hex) throws Exception {
    return Files.write(
        directory.resolve("archive.bsa"), HexFormat.of().parseHex(hex.replace(" ", "")));
  }
}
