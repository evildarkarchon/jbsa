package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Independently specified 0x69 records exercise the public reader and 24-byte folder layout. */
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
    // JBSA-BSA-014: the fixture's only folder record starts at 36, so paddingBefore is 48..52.
    var warnings = paddingWarnings(inspection);
    assertEquals(1, warnings.size());
    var warning = warnings.getFirst();
    assertEquals(DiagnosticSeverity.WARNING, warning.severity());
    assertEquals(Optional.of("paddingBefore"), warning.location().field());
    assertEquals(
        Optional.of(new DiagnosticLocation.ByteSpan(48, 4)), warning.location().byteSpan());
    assertEquals(Map.of("stored", "1", "expected", "0"), warning.values());
  }

  /** Each nonzero padding field of one folder record carries its own located warning. */
  @Test
  void diagnosesEachNonzeroFolderPaddingField() throws Exception {
    var inspection =
        BethesdaArchives.standard()
            .inspect(
                literal(
                    STORED
                        .replaceFirst("00000000 3e000000", "01000000 3e000000")
                        .replaceFirst("3e000000 00000000", "3e000000 02000000")));
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    var located =
        paddingWarnings(inspection).stream()
            .collect(
                Collectors.toMap(
                    warning -> warning.location().field().orElseThrow(), warning -> warning));
    assertEquals(Set.of("paddingBefore", "paddingAfter"), located.keySet());
    var after = located.get("paddingAfter");
    assertEquals(DiagnosticSeverity.WARNING, after.severity());
    assertEquals(Optional.of(new DiagnosticLocation.ByteSpan(56, 4)), after.location().byteSpan());
    assertEquals(Map.of("stored", "2", "expected", "0"), after.values());
    assertEquals(Map.of("stored", "1", "expected", "0"), located.get("paddingBefore").values());
  }

  /** Canonical zero padding stays conforming and carries no padding warning. */
  @Test
  void canonicalFolderPaddingProducesNoDiagnostic() throws Exception {
    var inspection = BethesdaArchives.standard().inspect(literal(STORED));
    assertEquals(ArchiveDisposition.CONFORMING, inspection.assessment().disposition());
    assertEquals(List.of(), paddingWarnings(inspection));
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

  /** Selects the folder-padding warnings, leaving every other diagnostic to its own tests. */
  private static List<Diagnostic> paddingWarnings(ArchiveInspection inspection) {
    return inspection.assessment().diagnostics().stream()
        .filter(d -> d.identifier().equals("bsa.nonzero-folder-padding"))
        .toList();
  }

  /** Writes exact fixture bytes without calling the production serializer. */
  private Path literal(String hex) throws Exception {
    return Files.write(
        directory.resolve("archive.bsa"), HexFormat.of().parseHex(hex.replace(" ", "")));
  }
}
