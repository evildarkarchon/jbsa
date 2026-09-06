package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/** Tests settled publication evidence through real files and deliberate mutation failures. */
final class PublicationFailuresTest {
  @TempDir Path directory;

  /** Unsupported atomic publication is rejected before any payload writer is called. */
  @Test
  void atomicCapabilityFailurePrecedesPayloadStaging() throws Exception {
    Path target = Files.writeString(directory.resolve("archive.ba2"), "predecessor");
    AtomicBoolean invoked = new AtomicBoolean();
    var files =
        new PublicationTransaction.FileActions() {
          /**
           * Simulates a filesystem that cannot guarantee the required atomic publication surface.
           */
          @Override
          void probeAtomic(Path staging, boolean directoryMove) throws IOException {
            throw new AtomicMoveNotSupportedException(
                staging.toString(), null, "injected capability gap");
          }
        };
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    target,
                    List.of(
                        output -> {
                          invoked.set(true);
                          bytes(1).write(output);
                        }),
                    TargetPolicy.REPLACE,
                    ResourceLimits.standard(),
                    OperationControl.standard(),
                    files));
    assertEquals(FailureKind.CAPABILITY, failure.kind());
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertFalse(invoked.get());
    assertEquals("predecessor", Files.readString(target));
    assertEquals(List.of(new Artifact(target, 0, ArtifactState.UNCHANGED)), failure.artifacts());
    try (var paths = Files.list(directory)) {
      assertEquals(List.of(target), paths.toList());
    }
  }

  /**
   * Failed backup cleanup retains the successful installation and transfers exact residual paths.
   */
  @Test
  void cleanupFailureRetainsPublishedStateAndExactResiduals() throws Exception {
    Path target = Files.writeString(directory.resolve("archive.ba2"), "predecessor");
    AtomicReference<Path> backup = new AtomicReference<>();
    var files =
        new PublicationTransaction.FileActions() {
          /** Records the old file's actual backup path without depending on its private name. */
          @Override
          void move(Path source, Path destination) throws IOException {
            super.move(source, destination);
            if (source.equals(target)) backup.set(destination);
          }

          /** Leaves only the predecessor backup undeleted; its nonempty parent also remains. */
          @Override
          void delete(Path path) throws IOException {
            if (path.equals(backup.get())) throw new IOException("injected backup cleanup failure");
            super.delete(path);
          }
        };
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    target,
                    List.of(bytes(7)),
                    TargetPolicy.REPLACE,
                    ResourceLimits.standard(),
                    OperationControl.standard(),
                    files));
    assertEquals(FailureKind.DESTINATION, failure.kind());
    assertEquals(OperationPhase.CLEANUP, failure.primaryFailure().phase());
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(target));
    assertEquals("predecessor", Files.readString(backup.get()));
    assertTrue(failure.artifacts().contains(new Artifact(target, 0, ArtifactState.PUBLISHED)));
    assertEquals(
        Set.of(backup.get(), backup.get().getParent()),
        failure.artifacts().stream()
            .filter(artifact -> artifact.state() == ArtifactState.RESIDUAL_STAGING)
            .map(Artifact::path)
            .collect(java.util.stream.Collectors.toSet()));
    assertEquals(1, failure.secondaryFailures().size());
    for (Artifact artifact : failure.artifacts()) {
      assertTrue(artifact.path().isAbsolute());
      assertEquals(artifact.path().normalize(), artifact.path());
      assertTrue(Files.exists(artifact.path()));
    }
  }

  /** Secondary retention is bounded without suppressing cleanup attempts or truncation evidence. */
  @ParameterizedTest
  @CsvSource({"0,2", "1,1"})
  void secondaryLimitRetainsExactOmittedCount(int retained, String omitted) throws Exception {
    Path first = Files.writeString(directory.resolve("archive.ba2"), "first predecessor");
    Path second = Files.writeString(directory.resolve("archive2.ba2"), "second predecessor");
    Set<Path> backups = new HashSet<>();
    var files =
        new PublicationTransaction.FileActions() {
          /** Records each predecessor's actual backup path at the mutation boundary. */
          @Override
          void move(Path source, Path destination) throws IOException {
            super.move(source, destination);
            if (source.equals(first) || source.equals(second)) backups.add(destination);
          }

          /** Two backups and their nonempty parent produce three cleanup failures in total. */
          @Override
          void delete(Path path) throws IOException {
            if (backups.contains(path)) throw new IOException("injected backup cleanup failure");
            super.delete(path);
          }
        };
    ResourceLimits limits = new ResourceLimits(10, 4096, 4096, 4096, 10, 4, retained);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    first,
                    List.of(bytes(1), bytes(2)),
                    TargetPolicy.REPLACE,
                    limits,
                    OperationControl.standard(),
                    files));
    assertEquals(OperationPhase.CLEANUP, failure.primaryFailure().phase());
    assertEquals(retained, failure.secondaryFailures().size());
    assertEquals(0, failure.getSuppressed().length);
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(first));
    assertArrayEquals(new byte[] {2}, Files.readAllBytes(second));
    assertEquals(
        3,
        failure.artifacts().stream()
            .filter(artifact -> artifact.state() == ArtifactState.RESIDUAL_STAGING)
            .count());
    var summaries =
        failure.diagnostics().stream()
            .filter(diagnostic -> diagnostic.identifier().equals("operation.records-truncated"))
            .toList();
    assertEquals(1, summaries.size());
    Diagnostic truncation = summaries.getFirst();
    assertEquals(DiagnosticSeverity.WARNING, truncation.severity());
    assertEquals("0", truncation.values().get("omittedDiagnostics"));
    assertEquals(omitted, truncation.values().get("omittedSecondaryFailures"));
    assertEquals("false", truncation.values().get("additionalDiagnosticsMayExist"));
    assertEquals("false", truncation.values().get("additionalSecondaryFailuresMayExist"));
  }

  /** Failure after the second backup restores only that file and retains the committed sibling. */
  @Test
  void secondExistingTreeInstallFailureRetainsFirstAndRestoresSecond() throws Exception {
    Path root = Files.createDirectory(directory.resolve("extract"));
    Path first = root.resolve("first.txt");
    Path second = Files.writeString(root.resolve("second.txt"), "second predecessor");
    AtomicReference<Path> backup = new AtomicReference<>();
    var files =
        new PublicationTransaction.FileActions() {
          /**
           * Allows the predecessor restoration while rejecting the second file's staged
           * installation.
           */
          @Override
          void move(Path source, Path destination) throws IOException {
            if (destination.equals(second) && !source.equals(backup.get())) {
              throw new IOException("injected second-file installation failure");
            }
            super.move(source, destination);
            if (source.equals(second)) backup.set(destination);
          }
        };
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.extract(
                    root,
                    List.of(
                        new PublicationTransaction.Entry("first.txt", bytes(1)),
                        new PublicationTransaction.Entry("second.txt", bytes(2))),
                    TargetPolicy.REPLACE,
                    ResourceLimits.standard(),
                    OperationControl.standard(),
                    files));
    assertEquals(FailureKind.DESTINATION, failure.kind());
    assertEquals(OperationPhase.PUBLISHING, failure.primaryFailure().phase());
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(first));
    assertEquals("second predecessor", Files.readString(second));
    assertEquals(
        List.of(
            new Artifact(first, 0, ArtifactState.PUBLISHED),
            new Artifact(second, 1, ArtifactState.RESTORED)),
        failure.artifacts());
    try (var paths = Files.list(directory)) {
      assertEquals(List.of(root), paths.toList());
    }
  }

  /**
   * Cancellation between per-file commits preserves the first file and the untouched predecessor.
   */
  @Test
  void cancellationBeforeNextExistingTreeCommitRetainsSibling() throws Exception {
    Path root = Files.createDirectory(directory.resolve("extract"));
    Path first = root.resolve("first.txt");
    Path second = Files.writeString(root.resolve("second.txt"), "second predecessor");
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicBoolean secondWriter = new AtomicBoolean();
    var files =
        new PublicationTransaction.FileActions() {
          /**
           * Requests cooperative cancellation immediately after the first visible file
           * installation.
           */
          @Override
          void move(Path source, Path destination) throws IOException {
            super.move(source, destination);
            if (destination.equals(first)) cancelled.set(true);
          }
        };
    ArchiveCancelledException failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                PublicationTransaction.extract(
                    root,
                    List.of(
                        new PublicationTransaction.Entry("first.txt", bytes(1)),
                        new PublicationTransaction.Entry(
                            "second.txt",
                            output -> {
                              secondWriter.set(true);
                              bytes(2).write(output);
                            })),
                    TargetPolicy.REPLACE,
                    ResourceLimits.standard(),
                    new OperationControl(snapshot -> {}, cancelled::get),
                    files));
    assertFalse(secondWriter.get());
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(first));
    assertEquals("second predecessor", Files.readString(second));
    assertEquals(
        List.of(
            new Artifact(first, 0, ArtifactState.PUBLISHED),
            new Artifact(second, 1, ArtifactState.UNCHANGED)),
        failure.artifacts());
  }

  /** The specification's literal examples define split spelling independently of file creation. */
  @Test
  void splitNamesPreserveLeadingTrailingAndMultipleFullStops() {
    String[][] examples = {
      {"archive", "archive2"},
      {"archive.ba2", "archive2.ba2"},
      {".archive", "2.archive"},
      {".archive.ba2", ".archive2.ba2"},
      {"archive.", "archive2."}
    };
    for (String[] example : examples) {
      Path requested = directory.resolve(example[0]);
      assertEquals(requested, PublicationTransaction.splitPath(requested, 1));
      assertEquals(directory.resolve(example[1]), PublicationTransaction.splitPath(requested, 2));
    }
    assertEquals(
        directory.resolve("archive12.ba2"),
        PublicationTransaction.splitPath(directory.resolve("archive.ba2"), 12));
  }

  /** The complete split count is rejected before writers, atomic probes, or destination effects. */
  @Test
  void outputLimitFailsDuringPreflightWithoutDestinationEffects() throws Exception {
    Path target = directory.resolve("archive.ba2");
    AtomicBoolean writerInvoked = new AtomicBoolean();
    AtomicBoolean probeInvoked = new AtomicBoolean();
    var files =
        new PublicationTransaction.FileActions() {
          /**
           * Records whether filesystem capability work started after the output limit was known.
           */
          @Override
          void probeAtomic(Path staging, boolean directoryMove) throws IOException {
            probeInvoked.set(true);
            super.probeAtomic(staging, directoryMove);
          }
        };
    PublicationTransaction.Writer writer = output -> writerInvoked.set(true);
    ResourceLimits limits = new ResourceLimits(10, 4096, 4096, 4096, 1, 4, 4);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    target,
                    List.of(writer, writer),
                    TargetPolicy.FAIL,
                    limits,
                    OperationControl.standard(),
                    files));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertEquals("maxOutputs", failure.diagnostics().getFirst().values().get("field"));
    assertEquals("1", failure.diagnostics().getFirst().values().get("ceiling"));
    assertEquals("2", failure.diagnostics().getFirst().values().get("observed"));
    assertFalse(writerInvoked.get());
    assertFalse(probeInvoked.get());
    try (var paths = Files.list(directory)) {
      assertEquals(0, paths.count());
    }
  }

  /** Staged-file growth consumes scratch before writing and reports the actual processing phase. */
  @Test
  void scratchLimitDuringStagingReportsProcessingPhase() throws Exception {
    Path target = directory.resolve("archive.ba2");
    ResourceLimits limits = new ResourceLimits(10, 4096, 4096, 0, 1, 4, 4);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    target,
                    List.of(bytes(1)),
                    TargetPolicy.FAIL,
                    limits,
                    OperationControl.standard()));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertEquals("maxScratchBytes", failure.diagnostics().getFirst().values().get("field"));
    assertEquals("0", failure.diagnostics().getFirst().values().get("ceiling"));
    assertEquals("1", failure.diagnostics().getFirst().values().get("observed"));
    assertFalse(Files.exists(target));
    try (var paths = Files.list(directory)) {
      assertEquals(0, paths.count());
    }
  }

  /**
   * Long reservations survive bounded header backpatches without materializing the reserved gap.
   */
  @Test
  void reservesBeyondFourGiBAndBackpatchesWithoutProportionalStorage() throws Exception {
    Path target = directory.resolve("archive.ba2");
    var files =
        new PublicationTransaction.FileActions() {
          /**
           * The writer aborts before finalization, so no owned file may contain the reserved gap.
           */
          @Override
          void delete(Path path) throws IOException {
            if (Files.isRegularFile(path)) assertTrue(Files.size(path) <= 4);
            super.delete(path);
          }
        };
    ResourceLimits limits = new ResourceLimits(10, 4096, 4096, 4_294_967_297L, 1, 4, 4);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    target,
                    List.of(
                        output -> {
                          output.reserve(4_294_967_297L);
                          assertEquals(4_294_967_297L, output.size());
                          output.write(0, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
                          assertEquals(4_294_967_297L, output.size());
                          // Abort before finalization so this test never extends a real file to the
                          // reserved size.
                          throw new IOException("deliberate abort after bounded backpatch");
                        }),
                    TargetPolicy.FAIL,
                    limits,
                    OperationControl.standard(),
                    files));
    assertEquals(FailureKind.DESTINATION, failure.kind());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertFalse(Files.exists(target));
    try (var paths = Files.list(directory)) {
      assertEquals(0, paths.count());
    }
  }

  /** Writes a fixed one-byte payload without depending on an archive codec or binary fixture. */
  private static PublicationTransaction.Writer bytes(int value) {
    return output -> output.write(0, ByteBuffer.wrap(new byte[] {(byte) value}));
  }
}
