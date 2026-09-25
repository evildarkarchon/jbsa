package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Public extraction behavior from independent literal stored TES3 archives. */
@EnabledOnOs(OS.WINDOWS)
final class Tes3ExtractTest {
  @TempDir Path directory;

  /**
   * The public operation publishes canonical stored bytes and reports validated payload ordinals.
   */
  @Test
  void extractsLiteralArchiveIntoNewRoot() throws Exception {
    Path source =
        literal("00010000 0e000000 01000000 01000000 00000000 00000000 6100 0000000030000080 07");
    Path destination = directory.resolve("out");
    OperationReport report =
        BethesdaArchives.standard()
            .extract(ExtractRequest.standard(source, destination), OperationControl.standard());
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(destination.resolve("a")));
    assertEquals(Operation.EXTRACT, report.operation());
    assertEquals(
        new ValidationExtent.Payloads(Set.of(0L)), report.assessment().orElseThrow().extent());
    assertTrue(report.diagnostics().stream().allMatch(d -> d.operation() == Operation.EXTRACT));
    assertTrue(report.artifacts().stream().anyMatch(a -> a.state() == ArtifactState.PUBLISHED));
    Files.delete(source);
  }

  /** Explicit selection uses archive ordinals and does not extract unselected unsafe names. */
  @Test
  void selectsArchiveOrdinalsAndRejectsUnknownMembership() throws Exception {
    Path source =
        literal(
            "00010000 1c000000 02000000 01000000 00000000 01000000 01000000 00000000 02000000 61006200 0000000030000080 0000000018000080 0708");
    Path destination = directory.resolve("selected");
    ExtractRequest request =
        request(
            source,
            destination,
            new EntrySelection.Ordinals(List.of(1L)),
            OpenOptions.standard(),
            DiagnosticPolicy.standard());
    OperationReport report =
        BethesdaArchives.standard().extract(request, OperationControl.standard());
    assertFalse(Files.exists(destination.resolve("a")));
    assertArrayEquals(new byte[] {8}, Files.readAllBytes(destination.resolve("b")));
    assertEquals(
        new ValidationExtent.Payloads(Set.of(1L)), report.assessment().orElseThrow().extent());
    Path invalid = directory.resolve("invalid");
    assertEquals(
        FailureKind.POLICY,
        assertThrows(
                ArchiveException.class,
                () ->
                    BethesdaArchives.standard()
                        .extract(
                            request(
                                source,
                                invalid,
                                new EntrySelection.Ordinals(List.of(2L)),
                                OpenOptions.standard(),
                                DiagnosticPolicy.standard()),
                            OperationControl.standard()))
            .kind());
    assertFalse(Files.exists(invalid));
  }

  /** Unsafe decoded names stay inspectable but cannot create any destination artifact. */
  @Test
  void rejectsIneligibleNameBeforeDestinationEffects() throws Exception {
    Path source =
        literal("00010000 0e000000 01000000 01000000 00000000 00000000 2e00 0000000000000000 07");
    assertTrue(
        BethesdaArchives.standard()
            .inspect(source)
            .entries()
            .getFirst()
            .normalizedNameIdentity()
            .isEmpty());
    Path destination = directory.resolve("unsafe");
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        ExtractRequest.standard(source, destination), OperationControl.standard()));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertFalse(Files.exists(destination));
    assertTrue(failure.artifacts().isEmpty());
    assertTrue(failure.assessment().isPresent());
  }

  /** Warning policy is applied before retention, even when only a truncation summary fits. */
  @Test
  void rejectsOmittedWarningsBeforeDestinationEffects() throws Exception {
    Path source =
        literal("00010000 0e000000 01000000 01000000 00000000 00000000 6100 0000000000000000 07");
    Path destination = directory.resolve("policy");
    ResourceLimits limits = new ResourceLimits(1, 34, 1, 10, 1, 1, 1);
    OpenOptions options = new OpenOptions(Optional.empty(), limits, Optional.empty());
    DiagnosticPolicy policy = new DiagnosticPolicy(Set.of("tes3.stored-hash-mismatch"));
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        request(source, destination, EntrySelection.ALL, options, policy),
                        OperationControl.standard()));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertFalse(Files.exists(destination));
    assertEquals(
        ArchiveDisposition.TOLERATED_NONCANONICAL,
        failure.assessment().orElseThrow().disposition());
    assertEquals("operation.records-truncated", failure.diagnostics().getFirst().identifier());
  }

  /** Known cumulative decoded demand is rejected in preflight without output creation. */
  @Test
  void rejectsDecodedBudgetBeforeStaging() throws Exception {
    Path source =
        literal("00010000 0e000000 01000000 01000000 00000000 00000000 6100 0000000030000080 07");
    Path destination = directory.resolve("limited");
    OpenOptions options =
        new OpenOptions(
            Optional.empty(), new ResourceLimits(1, 34, 0, 10, 1, 16, 1), Optional.empty());
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        request(
                            source,
                            destination,
                            EntrySelection.ALL,
                            options,
                            DiagnosticPolicy.standard()),
                        OperationControl.standard()));
    assertEquals(
        "maxDecodedBytes",
        failure.diagnostics().stream()
            .filter(d -> d.identifier().equals("operation.resource-limit"))
            .findFirst()
            .orElseThrow()
            .values()
            .get("field"));
    assertFalse(Files.exists(destination));
  }

  /** Cancellation after staging begins removes private output and releases the source handle. */
  @Test
  void cancellationDuringProcessingLeavesNoDestination() throws Exception {
    Path source =
        literal("00010000 0e000000 01000000 01000000 00000000 00000000 6100 0000000030000080 07");
    Path destination = directory.resolve("cancelled");
    var cancelled = new java.util.concurrent.atomic.AtomicBoolean();
    OperationControl control =
        new OperationControl(
            progress -> {
              if (progress.phase() == OperationPhase.PROCESSING) cancelled.set(true);
            },
            cancelled::get);
    assertThrows(
        ArchiveCancelledException.class,
        () ->
            BethesdaArchives.standard()
                .extract(ExtractRequest.standard(source, destination), control));
    assertFalse(Files.exists(destination));
    try (var children = Files.list(directory)) {
      assertEquals(List.of(source), children.toList());
    }
    Files.delete(source);
  }

  /** Existing-tree fail and replace policies use the shared transaction's per-file behavior. */
  @Test
  void honorsExistingTreeTargetPolicies() throws Exception {
    Path source =
        literal("00010000 0e000000 01000000 01000000 00000000 00000000 6100 0000000030000080 07");
    Path destination = Files.createDirectory(directory.resolve("existing"));
    Path target = Files.write(destination.resolve("a"), new byte[] {9});
    assertThrows(
        ArchiveException.class,
        () ->
            BethesdaArchives.standard()
                .extract(
                    ExtractRequest.standard(source, destination), OperationControl.standard()));
    assertArrayEquals(new byte[] {9}, Files.readAllBytes(target));
    ExtractRequest replace =
        new ExtractRequest(
            source,
            destination,
            EntrySelection.ALL,
            TargetPolicy.REPLACE,
            DiagnosticPolicy.standard(),
            WorkerSelection.AUTOMATIC,
            OpenOptions.standard());
    BethesdaArchives.standard().extract(replace, OperationControl.standard());
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(target));
  }

  /**
   * Supplies explicit extraction policies without changing the selected source or target surface.
   */
  private ExtractRequest request(
      Path source,
      Path destination,
      EntrySelection selection,
      OpenOptions options,
      DiagnosticPolicy policy) {
    return new ExtractRequest(
        source,
        destination,
        selection,
        TargetPolicy.FAIL,
        policy,
        WorkerSelection.AUTOMATIC,
        options);
  }

  /** Writes fixture bytes without using a format encoder. */
  private Path literal(String hex) throws Exception {
    return Files.write(
        directory.resolve("input.bsa"), HexFormat.of().parseHex(hex.replace(" ", "")));
  }
}
