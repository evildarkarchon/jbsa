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

/** Independently specified 0x69 records exercise the public reader and 24-byte folder layout. */
@EnabledOnOs(OS.WINDOWS)
final class Bsa69ReaderTest {
  @TempDir Path directory;

  private static final String STORED =
      "42534100 69000000 24000000 03000000 01000000 01000000 02000000 02000000 00000000 "
          + "6100016100000000 01000000 00000000 3e000000 00000000 026100 "
          + "6200016200000000 01000000 51000000 6200 07";

  /** A canonical 24-byte folder record resolves the SSE family and stored content. */
  @Test
  void opensStoredVersion69FolderRecords() throws Exception {
    try (var archive = BethesdaArchives.standard().open(literal(STORED), OpenOptions.standard())) {
      EntryMetadata metadata = archive.entry(0).metadata();
      assertEquals(ArchiveFamily.SSE_BSA, metadata.family());
      assertEquals(0x69, metadata.encoding().wireVersion().orElseThrow().value());
      assertEquals("a\\b", metadata.displayName());
      var facts = (EntryMetadata.VersionedBsa) metadata.facts();
      assertEquals(0, facts.folderPaddingBeforeOffset());
      assertEquals(0, facts.folderPaddingAfterOffset());
      try (var content = archive.entry(0).openContent()) {
        ByteBuffer bytes = ByteBuffer.allocate(2);
        assertEquals(1, content.read(bytes));
        assertEquals(7, bytes.get(0));
        assertEquals(-1, content.read(bytes));
      }
    }
  }

  /** Nonzero 0x69 padding remains decodable but cannot be classified as canonical. */
  @Test
  void treatsNonzeroFolderPaddingAsNoncanonical() throws Exception {
    var inspection =
        BethesdaArchives.standard()
            .inspect(literal(STORED.replaceFirst("00000000 3e000000", "01000000 3e000000")));
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    var facts = (EntryMetadata.VersionedBsa) inspection.entries().getFirst().facts();
    assertEquals(1, facts.folderPaddingBeforeOffset());
    assertEquals(0, facts.folderPaddingAfterOffset());
  }

  /** Stored SSE entries with explicit embedded names retain the required stable warning. */
  @Test
  void warnsForStoredEmbeddedNames() throws Exception {
    String embedded =
        STORED
            .replace("03000000 01000000", "03010000 01000000")
            .replace("01000000 51000000 6200 07", "05000000 51000000 6200 03615c62 07");
    var inspection = BethesdaArchives.standard().inspect(literal(embedded));
    var warning =
        inspection.assessment().diagnostics().stream()
            .filter(d -> d.identifier().equals("bsa.uncompressed-sse-embedded-name"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        new DiagnosticLocation.ByteSpan(82, 3), warning.location().byteSpan().orElseThrow());
  }

  /** Writes exact fixture bytes without calling the production serializer. */
  private Path literal(String hex) throws Exception {
    return Files.write(
        directory.resolve("archive.bsa"), HexFormat.of().parseHex(hex.replace(" ", "")));
  }
}
