package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Exercises layered evidence through the accepted public archive and content interfaces. */
@EnabledOnOs(OS.WINDOWS)
final class OwnedArchiveValidationTest {
  @TempDir Path directory;

  /** Unsafe host names retain conforming structure and report each condition's first segment. */
  @Test
  void unsafeNamesRemainInspectableWithAllApplicableWarnings() throws Exception {
    try (OpenArchive archive = open("/../CON.txt/../NUL", ResourceLimits.standard())) {
      ArchiveAssessment structure = archive.inspection().assessment();
      assertEquals(ArchiveDisposition.CONFORMING, structure.disposition());
      assertEquals(new ValidationExtent.Structure(), structure.extent());
      assertEquals(3, structure.diagnostics().size());
      Map<String, Diagnostic> warnings = new HashMap<>();
      for (Diagnostic diagnostic : structure.diagnostics()) {
        warnings.put(diagnostic.identifier(), diagnostic);
        assertEquals(DiagnosticSeverity.WARNING, diagnostic.severity());
        assertEquals(Operation.OPEN, diagnostic.operation());
        assertEquals(0, diagnostic.location().entryOrdinal().orElseThrow());
        assertEquals("/../CON.txt/../NUL", diagnostic.location().entryName().orElseThrow());
      }
      assertTrue(warnings.containsKey("archive-name.absolute-path"));
      Diagnostic traversal = warnings.get("archive-name.traversal-segment");
      assertEquals("..", traversal.location().field().orElseThrow());
      assertEquals("1", traversal.values().get("segmentOrdinal"));
      Diagnostic windows = warnings.get("archive-name.windows-invalid-segment");
      assertEquals("CON.txt", windows.location().field().orElseThrow());
      assertEquals("2", windows.values().get("segmentOrdinal"));
      try (EntryContent content = archive.entry(0).openContent()) {
        assertEquals(1, content.read(ByteBuffer.allocate(1)));
        assertEquals(-1, content.read(ByteBuffer.allocate(1)));
        ArchiveAssessment payload = content.assessment().orElseThrow();
        assertEquals(new ValidationExtent.Payloads(Set.of(0L)), payload.extent());
        assertEquals(structure.diagnostics(), payload.diagnostics());
        assertSame(structure, archive.inspection().assessment());
      }
    }
  }

  /** Qualified Windows device and character rules are locale-independent and segment-scoped. */
  @org.junit.jupiter.params.ParameterizedTest
  @org.junit.jupiter.params.provider.ValueSource(
      strings = {
        "folder/CoM\u00b9.txt/NUL",
        "folder/LpT\u00b3/NUL",
        "folder/a?b/NUL",
        "folder/a\u0001b/NUL"
      })
  void qualifiedWindowsRulesIdentifyTheFirstInvalidSegment(String name) throws Exception {
    try (OpenArchive archive = open(name, ResourceLimits.standard())) {
      ArchiveAssessment assessment = archive.inspection().assessment();
      assertEquals(ArchiveDisposition.CONFORMING, assessment.disposition());
      Diagnostic warning = assessment.diagnostics().getFirst();
      assertEquals(1, assessment.diagnostics().size());
      assertEquals("archive-name.windows-invalid-segment", warning.identifier());
      assertEquals("1", warning.values().get("segmentOrdinal"));
      assertEquals(
          name.substring(7, name.lastIndexOf('/')), warning.location().field().orElseThrow());
    }
  }

  /** Success retains the reserved summary even when every actual warning is omitted. */
  @Test
  void unsafeNameWarningsRespectTheRetentionCeiling() throws Exception {
    ResourceLimits limits = new ResourceLimits(1, 0, 1, 0, 0, 1, 0);
    try (OpenArchive archive = open("/../CON.txt/../NUL", limits)) {
      ArchiveAssessment structure = archive.inspection().assessment();
      assertEquals(ArchiveDisposition.CONFORMING, structure.disposition());
      assertEquals(1, structure.diagnostics().size());
      Diagnostic summary = structure.diagnostics().getFirst();
      assertEquals("operation.records-truncated", summary.identifier());
      assertEquals("3", summary.values().get("omittedDiagnostics"));
      assertEquals("false", summary.values().get("additionalDiagnosticsMayExist"));
    }
  }

  /**
   * Internal credit exhaustion must preserve the same public evidence as semantic-limit rejection.
   */
  @Test
  void childCapacityFailureRetainsStructuralEvidenceAndReadContext() throws Exception {
    try (OpenArchive archive = open("/unsafe", ResourceLimits.standard(), true)) {
      var structure = archive.inspection().assessment();
      var failure = assertThrows(ArchiveException.class, () -> archive.entry(0).openContent());
      assertEquals(FailureKind.POLICY, failure.kind());
      assertEquals(Optional.of(structure), failure.assessment());
      assertTrue(failure.diagnostics().containsAll(structure.diagnostics()));
      var capacity =
          failure.diagnostics().stream()
              .filter(d -> d.identifier().equals("io.resource-capacity"))
              .findFirst()
              .orElseThrow();
      assertEquals(Operation.READ_CONTENT, capacity.operation());
      assertEquals(OperationPhase.PROCESSING, capacity.phase());
      assertEquals(0, capacity.location().entryOrdinal().orElseThrow());
    }
  }

  /** Supplies only a bounded stored index; no fixture claims archive-family decoding support. */
  private OpenArchive open(String name, ResourceLimits limits) throws Exception {
    return open(name, limits, false);
  }

  /** Can saturate metadata credits with discarded bounded reads to exercise child admission. */
  private OpenArchive open(String name, ResourceLimits limits, boolean exhaustCredits)
      throws Exception {
    byte[] bytes = new byte[exhaustCredits ? 65536 : 1];
    bytes[0] = 41;
    Path path = Files.write(directory.resolve("synthetic.bin"), bytes);
    return OwnedArchive.load(
        path,
        limits,
        Operation.OPEN,
        builder -> {
          builder.declareEntries(1);
          if (exhaustCredits) {
            // First fill in bounded windows, then consume the remainder without retaining buffers.
            // Failed reservations are atomic, allowing the fixture's validated index to finish.
            for (int window : new int[] {65536, 64}) {
              while (true) {
                try {
                  builder.readMetadata(0, window);
                } catch (ArchiveException full) {
                  assertEquals(
                      "io.resource-capacity",
                      full.primaryFailure().diagnosticIdentifier().orElseThrow());
                  break;
                }
              }
            }
          }
          builder.addStored(
              new EntryMetadata(
                  ArchiveFamily.TES3_BSA,
                  ArchiveEncoding.tes3(),
                  0,
                  name,
                  Optional.empty(),
                  Map.of(),
                  1,
                  1,
                  new EntryMetadata.Tes3(0, 0, 0, 0)),
              0);
          return new ArchiveInspection(
              new ArchiveDetection(
                  DetectionStatus.SUPPORTED_FAMILY,
                  new WireName(new byte[] {0, 1, 0, 0}),
                  Optional.of(ArchiveFamily.TES3_BSA),
                  Optional.empty(),
                  Optional.empty(),
                  OptionalLong.empty()),
              new ArchiveMetadata.Tes3(1, 0, 0),
              new ArchiveAssessment(
                  ArchiveDisposition.CONFORMING, new ValidationExtent.Structure(), List.of()));
        });
  }
}
