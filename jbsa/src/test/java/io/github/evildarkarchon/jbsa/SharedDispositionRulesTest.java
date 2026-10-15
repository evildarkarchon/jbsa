package io.github.evildarkarchon.jbsa;

import static java.nio.charset.StandardCharsets.ISO_8859_1;
import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.stream.Stream;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * The Archive Disposition rules every reader shares, exercised through the public facade for each
 * reader shape: TES3, Versioned BSA, General BA2 and DDS BA2. Each family keeps its own diagnostic
 * identifiers; the rule that applies them is the same.
 */
final class SharedDispositionRulesTest {
  @TempDir Path directory;

  // Literal fixtures, worked by hand from the format specs and the family reader tests. A 0x81
  // byte has no windows-1252 mapping, so the name cannot be decoded under standard options.
  private static final String TES3_UNDECODABLE =
      "00010000 0e000000 01000000 01000000 00000000 00000000 8100 0000000030000080 07";
  // Folder name "\x81" with basename "b". Non-ASCII components are exempt from hash judgment.
  private static final String BSA_UNDECODABLE =
      "42534100 67000000 24000000 03000000 01000000 01000000 02000000 02000000 00000000 "
          + "6100016100000000 01000000 36000000 028100 "
          + "6200016200000000 01000000 49000000 6200 07";
  // Name "a/\x81.txt" in the filename table at 61.
  private static final String GENERAL_UNDECODABLE =
      "42544458 01000000 474e524c 01000000 3d00000000000000 "
          + "7400bca3 74787400 ce51b53a 00011000 3c00000000000000 00000000 01000000 0df0adba "
          + "07 0700 612f812e747874";
  // One stored 4x4 BC1 chunk, named "\x81.dds" by a filename table at 80.
  private static final String DDS_UNDECODABLE =
      "42544458 01000000 44583130 01000000 5000000000000000 "
          + "00000000 64647300 00000000 00 01 1800 0400 0400 01 47 00 00 "
          + "4800000000000000 00000000 08000000 0000 0000 0df0adba "
          + "0700000000000000 0500 812e646473";

  // Two entries whose payloads [56, 58) and [57, 59) share one byte without being identical.
  private static final String TES3_OVERLAP =
      "00010000 1c000000 02000000 02000000 00000000 02000000 01000000 00000000 02000000 "
          + "61006200 0000000030000080 0000000018000080 0708 09";
  // Two stored files a\b and a\c at [0x5b, 0x5d) and [0x5c, 0x5e).
  private static final String BSA_OVERLAP =
      "42534100 67000000 24000000 07000000 01000000 02000000 02000000 04000000 00000000 "
          + "6100016100000000 02000000 38000000 026100 "
          + "6200016200000000 02000040 5b000000 6300016300000000 02000040 5c000000 "
          + "62006300 0708 09";
  // Two stored entries at [96, 98) and [97, 98); the filename table follows at 98.
  private static final String GENERAL_OVERLAP =
      "42544458 01000000 474e524c 02000000 6200000000000000 "
          + "7400bca3 74787400 ce51b53a 00011000 6000000000000000 00000000 02000000 0df0adba "
          + "e230bbd4 74787400 ce51b53a 00011000 6100000000000000 00000000 01000000 0df0adba "
          + "0708 0700 612f622e747874 0700 612f632e747874";
  // One texture whose two 19-byte zlib chunks start at 96 and 97.
  private static final String DDS_OVERLAP =
      "42544458 01000000 44583130 01000000 0000000000000000 "
          + "00000000 64647300 00000000 00 02 1800 0400 0400 02 47 00 00 "
          + "6000000000000000 13000000 08000000 0000 0000 0df0adba "
          + "6100000000000000 13000000 08000000 0100 0100 0df0adba "
          + "7801010800f7ff070000000000000000400008 7801010800f7ff09000000000000000050000a";

  // Canonical single-entry archives followed by one unreferenced byte. Versioned BSA defines no
  // trailing-data rule, so it has no case here.
  private static final String TES3_TRAILING =
      "00010000 0e000000 01000000 01000000 00000000 00000000 6100 0000000030000080 07 ff";
  private static final String GENERAL_TRAILING =
      "42544458 01000000 474e524c 01000000 3d00000000000000 "
          + "7400bca3 74787400 ce51b53a 00011000 3c00000000000000 00000000 01000000 0df0adba "
          + "07 0700 612f622e747874 ff";
  private static final String DDS_TRAILING =
      "42544458 01000000 44583130 01000000 0000000000000000 "
          + "00000000 64647300 00000000 00 01 1800 0400 0400 01 47 00 00 "
          + "4800000000000000 00000000 08000000 0000 0000 0df0adba "
          + "0700000000000000 ff";

  // Further noncanonical causes, each one field changed from a canonical fixture in
  // InspectionDetectionParityTest. TES3 stores a zero name hash instead of 0x8000003000000000.
  private static final String TES3_HASH_MISMATCH =
      "00010000 0e000000 01000000 01000000 00000000 00000000 6100 0000000000000000 07";
  // Folder "a" stores a zero folder hash; its file record keeps the correct hash.
  private static final String BSA_FOLDER_HASH_MISMATCH =
      "42534100 67000000 24000000 03000000 01000000 01000000 02000000 02000000 00000000 "
          + "0000000000000000 01000000 36000000 026100 "
          + "6200016200000000 01000000 49000000 6200 07";
  // The 0x69 folder record's paddingBefore field at 48 stores 1.
  private static final String SSE_NONZERO_FOLDER_PADDING =
      "42534100 69000000 24000000 03000000 01000000 01000000 02000000 02000000 00000000 "
          + "6100016100000000 01000000 01000000 3e000000 00000000 026100 "
          + "6200016200000000 01000000 51000000 6200 07";
  // The record for a/b.txt stores a zero basename hash instead of 0xA3BC0074.
  private static final String GENERAL_BASENAME_HASH_MISMATCH =
      "42544458 01000000 474e524c 01000000 3d00000000000000 "
          + "00000000 74787400 ce51b53a 00011000 3c00000000000000 00000000 01000000 0df0adba "
          + "07 0700 612f622e747874";
  // The record for a/b.txt stores a zero sentinel instead of 0xBAADF00D.
  private static final String GENERAL_SENTINEL_MISMATCH =
      "42544458 01000000 474e524c 01000000 3d00000000000000 "
          + "7400bca3 74787400 ce51b53a 00011000 3c00000000000000 00000000 01000000 00000000 "
          + "07 0700 612f622e747874";
  // One stored 4x4 BC1 chunk without a filename table: both are noncanonical on their own.
  private static final String DDS_STORED_CHUNK =
      "42544458 01000000 44583130 01000000 0000000000000000 "
          + "00000000 64647300 00000000 00 01 1800 0400 0400 01 47 00 00 "
          + "4800000000000000 00000000 08000000 0000 0000 0df0adba "
          + "0700000000000000";
  // The same texture with mod index 1 and a zero chunk sentinel.
  private static final String DDS_MOD_INDEX_AND_SENTINEL =
      "42544458 01000000 44583130 01000000 0000000000000000 "
          + "00000000 64647300 00000000 01 01 1800 0400 0400 01 47 00 00 "
          + "4800000000000000 00000000 08000000 0000 0000 00000000 "
          + "0700000000000000";
  // Version 2 with one zlib chunk, whose extra header at 24 stores 2 instead of 1.
  private static final String STARFIELD_DDS_EXTRA_HEADER =
      "42544458 02000000 44583130 01000000 0000000000000000 0200000000000000 "
          + "00000000 64647300 00000000 00 01 1800 0400 0400 01 47 00 00 "
          + "5000000000000000 13000000 08000000 0000 0000 0df0adba "
          + "7801010800f7ff070000000000000000400008";

  /** Supplies one archive per reader shape whose first entry's name cannot be decoded. */
  static Stream<Arguments> undecodableNames() {
    return Stream.of(
        Arguments.of(ArchiveFamily.TES3_BSA, TES3_UNDECODABLE),
        Arguments.of(ArchiveFamily.TES4_BSA, BSA_UNDECODABLE),
        Arguments.of(ArchiveFamily.FO4_GENERAL_BA2, GENERAL_UNDECODABLE),
        Arguments.of(ArchiveFamily.FO4_DDS_BA2, DDS_UNDECODABLE));
  }

  /** Supplies one partially overlapping payload pair per reader shape, with its family's id. */
  static Stream<Arguments> partialOverlaps() {
    return Stream.of(
        Arguments.of(ArchiveFamily.TES3_BSA, TES3_OVERLAP, "tes3.overlapping-payloads"),
        Arguments.of(ArchiveFamily.TES4_BSA, BSA_OVERLAP, "bsa.overlapping-payloads"),
        Arguments.of(ArchiveFamily.FO4_GENERAL_BA2, GENERAL_OVERLAP, "ba2.overlapping-payloads"),
        Arguments.of(ArchiveFamily.FO4_DDS_BA2, DDS_OVERLAP, "ba2.overlapping-payloads"));
  }

  /** Supplies one trailing byte per family that defines trailing data, with its exact span. */
  static Stream<Arguments> trailingData() {
    return Stream.of(
        Arguments.of(ArchiveFamily.TES3_BSA, TES3_TRAILING, "tes3.trailing-data", 35L),
        Arguments.of(ArchiveFamily.FO4_GENERAL_BA2, GENERAL_TRAILING, "ba2.trailing-data", 70L),
        Arguments.of(ArchiveFamily.FO4_DDS_BA2, DDS_TRAILING, "ba2.trailing-data", 80L));
  }

  /** Supplies every fixture that inspects successfully, regardless of its disposition. */
  static Stream<Arguments> inspectable() {
    return Stream.concat(
        undecodableNames(), trailingData().map(a -> Arguments.of(a.get()[0], a.get()[1])));
  }

  /**
   * Supplies every noncanonical fixture with one diagnostic identifier its outcome must carry: the
   * undecodable-name and trailing-data fixtures, plus further family causes such as hash
   * mismatches, nonzero folder padding, BA2 constant fields and a stored DX10 chunk.
   */
  static Stream<Arguments> noncanonicalOutcomes() {
    return Stream.of(
            undecodableNames()
                .map(
                    a ->
                        Arguments.of(
                            a.get()[0], a.get()[1], "archive-name.undecodable-wire-bytes")),
            trailingData().map(a -> Arguments.of(a.get()[0], a.get()[1], a.get()[2])),
            Stream.of(
                Arguments.of(
                    ArchiveFamily.TES3_BSA, TES3_HASH_MISMATCH, "tes3.stored-hash-mismatch"),
                Arguments.of(
                    ArchiveFamily.TES4_BSA, BSA_FOLDER_HASH_MISMATCH, "bsa.folder-hash-mismatch"),
                Arguments.of(
                    ArchiveFamily.SSE_BSA,
                    SSE_NONZERO_FOLDER_PADDING,
                    "bsa.nonzero-folder-padding"),
                Arguments.of(
                    ArchiveFamily.FO4_GENERAL_BA2,
                    GENERAL_BASENAME_HASH_MISMATCH,
                    "ba2.basename-hash-mismatch"),
                Arguments.of(
                    ArchiveFamily.FO4_GENERAL_BA2,
                    GENERAL_SENTINEL_MISMATCH,
                    "gnrl.sentinel-mismatch"),
                Arguments.of(ArchiveFamily.FO4_DDS_BA2, DDS_STORED_CHUNK, "dx10.stored-chunk"),
                Arguments.of(ArchiveFamily.FO4_DDS_BA2, DDS_STORED_CHUNK, "ba2.missing-name-table"),
                Arguments.of(
                    ArchiveFamily.FO4_DDS_BA2,
                    DDS_MOD_INDEX_AND_SENTINEL,
                    "dx10.nonzero-mod-index"),
                Arguments.of(
                    ArchiveFamily.FO4_DDS_BA2,
                    DDS_MOD_INDEX_AND_SENTINEL,
                    "dx10.sentinel-mismatch"),
                Arguments.of(
                    ArchiveFamily.STARFIELD_DDS_BA2,
                    STARFIELD_DDS_EXTRA_HEADER,
                    "ba2.extra-header-value")))
        .flatMap(cases -> cases);
  }

  /**
   * An undecodable name keeps its wire bytes, gains no Normalized Name Identity, and makes the
   * archive noncanonical with a diagnostic at that entry.
   */
  @ParameterizedTest(name = "{0}")
  @MethodSource("undecodableNames")
  void undecodableNameIsToleratedWithoutIdentity(ArchiveFamily family, String hex)
      throws Exception {
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(literal(hex));
    assertEquals(family, inspection.metadata().family());
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    EntryMetadata entry = inspection.entries().getFirst();
    assertTrue(entry.normalizedNameIdentity().isEmpty());
    assertTrue(
        entry.wireNames().values().stream()
            .anyMatch(wire -> new String(wire.bytes(), ISO_8859_1).indexOf('\u0081') >= 0));
    List<Diagnostic> undecodable =
        inspection.assessment().diagnostics().stream()
            .filter(d -> d.identifier().equals("archive-name.undecodable-wire-bytes"))
            .toList();
    assertEquals(1, undecodable.size());
    assertEquals(DiagnosticSeverity.WARNING, undecodable.getFirst().severity());
    assertEquals(OptionalLong.of(0), undecodable.getFirst().location().entryOrdinal());
    assertEquals(Optional.of(entry.displayName()), undecodable.getFirst().location().entryName());
  }

  /** Payloads that share some bytes without being identical are ambiguous and rejected. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("partialOverlaps")
  void partialPayloadOverlapIsRejected(ArchiveFamily family, String hex, String identifier)
      throws Exception {
    Path path = literal(hex);
    assertEquals(Optional.of(family), BethesdaArchives.standard().detect(path).family());
    ArchiveException failure =
        assertThrows(ArchiveException.class, () -> BethesdaArchives.standard().inspect(path));
    assertEquals(FailureKind.FORMAT, failure.kind());
    assertEquals(identifier, failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    assertEquals(ArchiveDisposition.REJECTED, failure.assessment().orElseThrow().disposition());
  }

  /** Unreferenced trailing bytes are diagnosed once, at the complete trailing span. */
  @ParameterizedTest(name = "{0}")
  @MethodSource("trailingData")
  void trailingDataIsDiagnosedOnceAtItsSpan(
      ArchiveFamily family, String hex, String identifier, long offset) throws Exception {
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(literal(hex));
    assertEquals(family, inspection.metadata().family());
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    List<Diagnostic> trailing =
        inspection.assessment().diagnostics().stream()
            .filter(d -> d.identifier().equals(identifier))
            .toList();
    assertEquals(1, trailing.size());
    assertEquals(OptionalLong.empty(), trailing.getFirst().location().entryOrdinal());
    assertEquals(
        Optional.of(new DiagnosticLocation.ByteSpan(offset, 1)),
        trailing.getFirst().location().byteSpan());
  }

  /**
   * A Tolerated Noncanonical Archive always produces at least one diagnostic explaining why,
   * including the diagnostic for the fixture's own noncanonical cause.
   */
  @ParameterizedTest(name = "{0} {2}")
  @MethodSource("noncanonicalOutcomes")
  void noncanonicalOutcomeCarriesDiagnostics(ArchiveFamily family, String hex, String identifier)
      throws Exception {
    Path path = literal(hex);
    ArchiveAssessment inspected = BethesdaArchives.standard().inspect(path).assessment();
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspected.disposition());
    assertFalse(inspected.diagnostics().isEmpty());
    assertTrue(
        inspected.diagnostics().stream().anyMatch(d -> d.identifier().equals(identifier)),
        () -> identifier + " missing from " + inspected.diagnostics());
    // Opening reports the same disposition; its diagnostics differ only by their operation.
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
      ArchiveAssessment opened = archive.inspection().assessment();
      assertEquals(family, archive.inspection().metadata().family());
      assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, opened.disposition());
      assertEquals(
          inspected.diagnostics().stream().map(Diagnostic::identifier).toList(),
          opened.diagnostics().stream().map(Diagnostic::identifier).toList());
    }
  }

  /** Inspection reports exactly the recognition evidence detection reports (JBSA-DET-001). */
  @ParameterizedTest(name = "{0}")
  @MethodSource("inspectable")
  void inspectionReportsDetectedEvidence(ArchiveFamily family, String hex) throws Exception {
    Path path = literal(hex);
    ArchiveDetection detected = BethesdaArchives.standard().detect(path);
    assertEquals(Optional.of(family), detected.family());
    assertEquals(detected, BethesdaArchives.standard().inspect(path).detection());
  }

  /** Writes a literal byte sequence without invoking the implementation's encoder or hash. */
  private Path literal(String hex) throws Exception {
    return Files.write(
        directory.resolve("fixture.bin"), HexFormat.of().parseHex(hex.replace(" ", "")));
  }
}
