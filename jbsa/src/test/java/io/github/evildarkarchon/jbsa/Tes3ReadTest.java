package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Public query and content contracts against literal independently specified TES3 bytes. */
@EnabledOnOs(OS.WINDOWS)
final class Tes3ReadTest {
  @TempDir Path directory;

  // Single name "a": empty low half, rotateRight(0x61, 1) = 0x80000030 high half.
  private static final String ONE =
      "00010000 0e000000 01000000 01000000 00000000 00000000 6100 0000000030000080 07";

  /** Recognized TES3 bytes become detached structural evidence and bounded stored content. */
  @Test
  void opensLiteralStoredArchiveAndRetainsEofEvidence() throws Exception {
    Path path = literal(ONE);
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(path);
    assertEquals(new ArchiveMetadata.Tes3(1, 14, 34), inspection.metadata());
    assertEquals(ArchiveDisposition.CONFORMING, inspection.assessment().disposition());
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
      EntryMetadata metadata = archive.entry(0).metadata();
      assertEquals("a", metadata.displayName());
      assertEquals("a", metadata.normalizedNameIdentity().orElseThrow().value());
      assertArrayEquals(new byte[] {97}, metadata.wireNames().get("complete").bytes());
      assertEquals(new EntryMetadata.Tes3(0x8000003000000000L, 0, 0, 34), metadata.facts());
      try (EntryContent content = archive.entry(0).openContent()) {
        ByteBuffer destination = ByteBuffer.allocate(2);
        assertEquals(1, content.read(destination));
        assertEquals(7, destination.get(0));
        assertEquals(-1, content.read(destination));
        assertEquals(
            new ValidationExtent.Payloads(Set.of(0L)), content.assessment().orElseThrow().extent());
      }
    }
  }

  /** Metadata equality counts each encoded byte once, excluding stored payload bytes. */
  @Test
  void permitsExactMetadataBudgetAndRejectsTheNextByte() throws Exception {
    Path path = literal(ONE);
    var limits = new ResourceLimits(1, 34, 1, 0, 0, 16, 1);
    var options = new OpenOptions(Optional.empty(), limits, Optional.empty());
    assertEquals(1, BethesdaArchives.standard().inspect(path, options).entries().size());
    var smaller =
        new OpenOptions(
            Optional.empty(), new ResourceLimits(1, 33, 1, 0, 0, 16, 1), Optional.empty());
    assertEquals(
        FailureKind.POLICY,
        assertThrows(
                ArchiveException.class, () -> BethesdaArchives.standard().inspect(path, smaller))
            .kind());
  }

  /** Tolerated fields preserve original values and carry exact structured warning locations. */
  @Test
  void retainsOffsetHashAndTrailingWarnings() throws Exception {
    Path path =
        literal(
            "00010000 0e000000 01000000 01000000 00000000 01000000 6100 0000000000000000 07 ff");
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(path);
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    var warnings = inspection.assessment().diagnostics();
    Diagnostic offset =
        warnings.stream()
            .filter(d -> d.identifier().equals("tes3.name-offset-inconsistency"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        new DiagnosticLocation.ByteSpan(20, 4), offset.location().byteSpan().orElseThrow());
    assertEquals("1", offset.values().get("stored"));
    assertEquals("0", offset.values().get("expected"));
    Diagnostic hash =
        warnings.stream()
            .filter(d -> d.identifier().equals("tes3.stored-hash-mismatch"))
            .findFirst()
            .orElseThrow();
    assertEquals("8000003000000000", hash.values().get("expected"));
    Diagnostic trailing =
        warnings.stream()
            .filter(d -> d.identifier().equals("tes3.trailing-data"))
            .findFirst()
            .orElseThrow();
    assertEquals(
        new DiagnosticLocation.ByteSpan(35, 1), trailing.location().byteSpan().orElseThrow());
    assertTrue(warnings.stream().allMatch(d -> d.severity() == DiagnosticSeverity.WARNING));
  }

  /** Reference-qualified unsupported roots warn per entry after ASCII-only case mapping. */
  @Test
  void warnsForSoundRootAtTheOriginalNameBytes() throws Exception {
    Path path =
        literal(
            "00010000 14000000 01000000 01000000 00000000 00000000 536f556e445c6100 0000000000000000 07");
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(path);
    Diagnostic warning =
        inspection.assessment().diagnostics().stream()
            .filter(d -> d.identifier().equals("tes3.unsupported-asset-root"))
            .findFirst()
            .orElseThrow();
    assertEquals(DiagnosticSeverity.WARNING, warning.severity());
    assertEquals("sound", warning.values().get("stored"));
    assertEquals("SoUnD\\a", warning.location().entryName().orElseThrow());
    assertEquals(
        new DiagnosticLocation.ByteSpan(24, 7), warning.location().byteSpan().orElseThrow());
    assertTrue(
        BethesdaArchives.standard().inspect(literal(ONE)).assessment().diagnostics().isEmpty());
  }

  /** Invalid encoding remains visible with original wire bytes and no invented name identity. */
  @Test
  void retainsUndecodableAndNonAsciiNamesWithoutHashJudgment() throws Exception {
    Path path = literal(ONE.replace("6100", "8100"));
    ArchiveInspection inspection = BethesdaArchives.standard().inspect(path);
    assertEquals("__jbsa_wire__\\e00000000", inspection.entries().getFirst().displayName());
    assertTrue(inspection.entries().getFirst().normalizedNameIdentity().isEmpty());
    assertArrayEquals(
        new byte[] {(byte) 0x81},
        inspection.entries().getFirst().wireNames().get("complete").bytes());
    assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
    assertTrue(
        inspection.assessment().diagnostics().stream()
            .noneMatch(d -> d.identifier().equals("tes3.stored-hash-mismatch")));
    inspection = BethesdaArchives.standard().inspect(literal(ONE.replace("6100", "e900")));
    assertEquals("é", inspection.entries().getFirst().displayName());
    assertEquals(ArchiveDisposition.CONFORMING, inspection.assessment().disposition());
  }

  /** Structural ambiguity and out-of-file ranges fail with a rejected structural assessment. */
  @Test
  void rejectsTruncationUnterminatedNamesAndImpossibleCounts() throws Exception {
    for (String malformed :
        new String[] {
          ONE.substring(0, ONE.length() - 2),
          ONE.replace("6100", "6161"),
          ONE.replace("01000000 01000000", "ffffffff 01000000")
        }) {
      Path path = literal(malformed);
      ArchiveException failure =
          assertThrows(ArchiveException.class, () -> BethesdaArchives.standard().inspect(path));
      assertEquals(FailureKind.FORMAT, failure.kind());
      assertEquals(ArchiveDisposition.REJECTED, failure.assessment().orElseThrow().disposition());
    }
  }

  /**
   * Two logical entries may share exactly one stored byte range, but partial overlap is corrupt.
   */
  @Test
  void permitsExactSharingAndRejectsPartialOverlap() throws Exception {
    String two =
        "00010000 1c000000 02000000 02000000 00000000 02000000 00000000 00000000 02000000 61006200 0000000030000080 0000000018000080 0708";
    Path path = literal(two);
    try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
      assertEquals(2, archive.entryCount());
      ByteBuffer first = ByteBuffer.allocate(2);
      ByteBuffer second = ByteBuffer.allocate(2);
      try (EntryContent a = archive.entry(0).openContent();
          EntryContent b = archive.entry(1).openContent()) {
        assertEquals(2, a.read(first));
        assertEquals(2, b.read(second));
        assertArrayEquals(first.array(), second.array());
      }
    }
    path =
        literal(
            two.replace(
                    "02000000 00000000 00000000 02000000", "02000000 01000000 00000000 02000000")
                + "09");
    Path overlapping = path;
    assertEquals(
        "tes3.overlapping-payloads",
        assertThrows(ArchiveException.class, () -> BethesdaArchives.standard().inspect(overlapping))
            .primaryFailure()
            .diagnosticIdentifier()
            .orElseThrow());
    Path duplicate = literal(two.replace("61006200", "61004100"));
    assertEquals(
        "tes3.duplicate-name",
        assertThrows(ArchiveException.class, () -> BethesdaArchives.standard().inspect(duplicate))
            .primaryFailure()
            .diagnosticIdentifier()
            .orElseThrow());
  }

  /** Writes a literal byte sequence without invoking the implementation's encoder or hash. */
  private Path literal(String hex) throws Exception {
    return Files.write(
        directory.resolve("fixture.bsa"), HexFormat.of().parseHex(hex.replace(" ", "")));
  }
}
