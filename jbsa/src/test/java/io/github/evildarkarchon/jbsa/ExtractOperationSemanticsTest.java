package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * The extract counterpart of {@link OperationSemanticsTest}: pins the exact Progress Snapshot
 * sequence and outcome of the public extraction entry point around success, pre-publication
 * failure, cancellation, and observer failure. Every request selects one worker so the sequential
 * strategy's snapshot timing is the one under test. A session-originated failure (cancellation or
 * observer) is never re-admitted as its own Secondary Failure, so those cases pin an empty list.
 */
final class ExtractOperationSemanticsTest {
  /** Stored TES3 archive with entries {@code a} = 07 and {@code b} = 08. */
  private static final String TWO_ENTRIES =
      "00010000 1c000000 02000000 01000000 00000000 01000000 01000000 00000000 02000000"
          + " 61006200 0000000030000080 0000000018000080 0708";

  /** The same archive with entry 1 renamed to {@code .}, which has no extraction identity. */
  private static final String INELIGIBLE_SECOND =
      "00010000 1c000000 02000000 01000000 00000000 01000000 01000000 00000000 02000000"
          + " 61002e00 0000000030000080 0000000018000080 0708";

  @TempDir Path directory;

  /** A pre-cancelled request must not open the source, deliver progress, or create output. */
  @Test
  void cancellationBeforePreflightDeliversNothing() throws Exception {
    Path destination = directory.resolve("out");
    var snapshots = new ArrayList<ProgressSnapshot>();
    var failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        request(literal(TWO_ENTRIES), destination, EntrySelection.ALL),
                        new OperationControl(snapshots::add, () -> true)));
    assertEquals(FailureKind.CANCELLED, failure.kind());
    assertEquals(List.of(), failure.secondaryFailures());
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertEquals(OptionalLong.empty(), failure.primaryFailure().ordinal());
    assertTrue(snapshots.isEmpty());
    assertTrue(failure.artifacts().isEmpty());
    assertFalse(Files.exists(destination));
  }

  /**
   * A new root reports every phase in order. Its root artifact shares the first entry's ordinal
   * and, within that ordinal, artifacts are ordered by path.
   */
  @Test
  void newRootExtractionReportsEveryPhaseInOrder() throws Exception {
    Path destination = directory.resolve("out");
    var snapshots = new ArrayList<ProgressSnapshot>();
    OperationReport report =
        BethesdaArchives.standard()
            .extract(
                request(literal(TWO_ENTRIES), destination, EntrySelection.ALL),
                new OperationControl(snapshots::add, () -> false));
    assertEquals(
        List.of(
            open(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 0),
            open(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2),
            settled(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2),
            open(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 0),
            open(OperationPhase.PROCESSING, ProgressMetric.BYTES, 0),
            open(OperationPhase.PROCESSING, ProgressMetric.BYTES, 1),
            open(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 1),
            open(OperationPhase.PROCESSING, ProgressMetric.BYTES, 2),
            open(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 2),
            settled(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 2),
            settled(OperationPhase.PROCESSING, ProgressMetric.BYTES, 2),
            open(OperationPhase.PUBLISHING, ProgressMetric.ARTIFACTS, 0),
            open(OperationPhase.PUBLISHING, ProgressMetric.ARTIFACTS, 2),
            settled(OperationPhase.PUBLISHING, ProgressMetric.ARTIFACTS, 2),
            open(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0),
            settled(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 3)),
        snapshots);
    Path root = destination.toAbsolutePath().normalize();
    assertEquals(
        List.of(
            new Artifact(root, 0, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("a"), 0, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("b"), 1, ArtifactState.PUBLISHED)),
        report.artifacts());
    assertEquals(
        new ValidationExtent.Payloads(Set.of(0L, 1L)), report.assessment().orElseThrow().extent());
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(destination.resolve("a")));
    assertArrayEquals(new byte[] {8}, Files.readAllBytes(destination.resolve("b")));
  }

  /** An existing tree commits each entry in PROCESSING and never enters PUBLISHING. */
  @Test
  void existingTreeExtractionCommitsEachEntryDuringProcessing() throws Exception {
    Path destination = Files.createDirectory(directory.resolve("existing"));
    var snapshots = new ArrayList<ProgressSnapshot>();
    OperationReport report =
        BethesdaArchives.standard()
            .extract(
                request(literal(TWO_ENTRIES), destination, EntrySelection.ALL),
                new OperationControl(snapshots::add, () -> false));
    assertEquals(
        List.of(
            open(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 0),
            open(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2),
            settled(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2),
            open(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 0),
            open(OperationPhase.PROCESSING, ProgressMetric.BYTES, 0),
            open(OperationPhase.PROCESSING, ProgressMetric.ARTIFACTS, 0),
            open(OperationPhase.PROCESSING, ProgressMetric.BYTES, 1),
            open(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 1),
            open(OperationPhase.PROCESSING, ProgressMetric.ARTIFACTS, 1),
            open(OperationPhase.PROCESSING, ProgressMetric.BYTES, 2),
            open(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 2),
            open(OperationPhase.PROCESSING, ProgressMetric.ARTIFACTS, 2),
            settled(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 2),
            settled(OperationPhase.PROCESSING, ProgressMetric.BYTES, 2),
            settled(OperationPhase.PROCESSING, ProgressMetric.ARTIFACTS, 2),
            open(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0),
            settled(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 2)),
        snapshots);
    Path root = destination.toAbsolutePath().normalize();
    assertEquals(
        List.of(
            new Artifact(root.resolve("a"), 0, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("b"), 1, ArtifactState.PUBLISHED)),
        report.artifacts());
    assertEquals(
        new ValidationExtent.Payloads(Set.of(0L, 1L)), report.assessment().orElseThrow().extent());
    assertArrayEquals(new byte[] {7}, Files.readAllBytes(destination.resolve("a")));
    assertArrayEquals(new byte[] {8}, Files.readAllBytes(destination.resolve("b")));
  }

  /** An out-of-range selection fails in PREFLIGHT and still settles zero-artifact cleanup. */
  @Test
  void invalidEntryOrdinalSettlesZeroCleanup() throws Exception {
    Path destination = directory.resolve("out");
    var snapshots = new ArrayList<ProgressSnapshot>();
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        request(
                            literal(TWO_ENTRIES),
                            destination,
                            new EntrySelection.Ordinals(List.of(2L))),
                        new OperationControl(snapshots::add, () -> false)));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(
        Optional.of("extract.invalid-entry-ordinal"),
        failure.primaryFailure().diagnosticIdentifier());
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertEquals(OptionalLong.empty(), failure.primaryFailure().ordinal());
    assertEquals(zeroCleanup(), snapshots);
    assertTrue(failure.artifacts().isEmpty());
    assertFalse(Files.exists(destination));
  }

  /** An ineligible selected name fails at its archive ordinal before any destination effect. */
  @Test
  void ineligibleNameSettlesZeroCleanupAtItsOrdinal() throws Exception {
    Path destination = directory.resolve("out");
    var snapshots = new ArrayList<ProgressSnapshot>();
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        request(literal(INELIGIBLE_SECOND), destination, EntrySelection.ALL),
                        new OperationControl(snapshots::add, () -> false)));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(
        Optional.of("extract.ineligible-name"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertEquals(OptionalLong.of(1), failure.primaryFailure().ordinal());
    assertEquals(zeroCleanup(), snapshots);
    assertTrue(failure.artifacts().isEmpty());
    assertFalse(Files.exists(destination));
  }

  /** Cancellation accepted after PREFLIGHT begins stops there and settles zero cleanup. */
  @Test
  void cancellationDuringPreflightSettlesZeroCleanup() throws Exception {
    Path destination = directory.resolve("out");
    var snapshots = new ArrayList<ProgressSnapshot>();
    var cancelled = new AtomicBoolean();
    var failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        request(literal(TWO_ENTRIES), destination, EntrySelection.ALL),
                        new OperationControl(
                            snapshot -> {
                              snapshots.add(snapshot);
                              cancelled.set(true);
                            },
                            cancelled::get)));
    assertEquals(FailureKind.CANCELLED, failure.kind());
    assertEquals(List.of(), failure.secondaryFailures());
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertEquals(OptionalLong.empty(), failure.primaryFailure().ordinal());
    assertEquals(zeroCleanup(), snapshots);
    assertTrue(failure.artifacts().isEmpty());
    assertFalse(Files.exists(destination));
  }

  /**
   * Cancellation accepted on entering PROCESSING stops before the first entry stages and reports
   * every planned artifact, including the unpublished root, as missing.
   */
  @Test
  void cancellationDuringProcessingReportsMissingArtifacts() throws Exception {
    Path destination = directory.resolve("out");
    var snapshots = new ArrayList<ProgressSnapshot>();
    var cancelled = new AtomicBoolean();
    var failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        request(literal(TWO_ENTRIES), destination, EntrySelection.ALL),
                        new OperationControl(
                            snapshot -> {
                              snapshots.add(snapshot);
                              if (snapshot.phase() == OperationPhase.PROCESSING)
                                cancelled.set(true);
                            },
                            cancelled::get)));
    assertEquals(FailureKind.CANCELLED, failure.kind());
    assertEquals(List.of(), failure.secondaryFailures());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertEquals(OptionalLong.of(0), failure.primaryFailure().ordinal());
    assertEquals(
        List.of(
            open(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 0),
            open(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2),
            settled(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2),
            open(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 0),
            open(OperationPhase.PROCESSING, ProgressMetric.BYTES, 0),
            open(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0),
            settled(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 3)),
        snapshots);
    Path root = destination.toAbsolutePath().normalize();
    assertEquals(
        List.of(
            new Artifact(root, 0, ArtifactState.MISSING),
            new Artifact(root.resolve("a"), 0, ArtifactState.MISSING),
            new Artifact(root.resolve("b"), 1, ArtifactState.MISSING)),
        failure.artifacts());
    assertFalse(Files.exists(destination));
    assertNoPrivateStaging();
  }

  /** Once an observer throws it must never be invoked again, including during cleanup. */
  @Test
  void observerFailureStopsDeliveryAndRetainsItsCause() throws Exception {
    Path destination = directory.resolve("out");
    var thrown = new AssertionError("caller observer");
    var count = new AtomicInteger();
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        request(literal(TWO_ENTRIES), destination, EntrySelection.ALL),
                        new OperationControl(
                            snapshot -> {
                              count.incrementAndGet();
                              throw thrown;
                            },
                            () -> false)));
    assertEquals(FailureKind.OBSERVER, failure.kind());
    assertEquals(List.of(), failure.secondaryFailures());
    assertSame(thrown, failure.getCause());
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertEquals(1, count.get());
    assertTrue(failure.artifacts().isEmpty());
    assertFalse(Files.exists(destination));
  }

  /** An observer failure once publication owns the operation stops delivery for its cleanup too. */
  @Test
  void observerFailureDuringProcessingStopsDeliveryBeforeCleanup() throws Exception {
    Path destination = directory.resolve("out");
    var thrown = new IllegalStateException("processing observer");
    var snapshots = new ArrayList<ProgressSnapshot>();
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        request(literal(TWO_ENTRIES), destination, EntrySelection.ALL),
                        new OperationControl(
                            snapshot -> {
                              snapshots.add(snapshot);
                              if (snapshot.phase() == OperationPhase.PROCESSING) throw thrown;
                            },
                            () -> false)));
    assertEquals(FailureKind.OBSERVER, failure.kind());
    assertEquals(List.of(), failure.secondaryFailures());
    assertSame(thrown, failure.getCause());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertEquals(
        List.of(
            open(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 0),
            open(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2),
            settled(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 2),
            open(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 0)),
        snapshots);
    assertFalse(Files.exists(destination));
    assertNoPrivateStaging();
  }

  /** Cleanup observer failure is retained without masking an earlier pre-publication failure. */
  @Test
  void cleanupObserverFailureRemainsSecondary() throws Exception {
    Path destination = directory.resolve("out");
    var thrown = new IllegalStateException("cleanup observer");
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(
                        request(
                            literal(TWO_ENTRIES),
                            destination,
                            new EntrySelection.Ordinals(List.of(2L))),
                        new OperationControl(
                            snapshot -> {
                              if (snapshot.phase() == OperationPhase.CLEANUP) throw thrown;
                            },
                            () -> false)));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(
        Optional.of("extract.invalid-entry-ordinal"),
        failure.primaryFailure().diagnosticIdentifier());
    assertEquals(1, failure.secondaryFailures().size());
    assertEquals(FailureKind.OBSERVER, failure.secondaryFailures().getFirst().kind());
    assertEquals(OperationPhase.CLEANUP, failure.secondaryFailures().getFirst().phase());
    assertSame(thrown, failure.secondaryFailures().getFirst().cause().orElseThrow());
    assertEquals(0, failure.getSuppressed().length);
    assertFalse(Files.exists(destination));
  }

  /** The PREFLIGHT entry, then an empty CLEANUP that settles with zero artifacts. */
  private static List<ProgressSnapshot> zeroCleanup() {
    return List.of(
        open(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 0),
        open(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0),
        settled(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 0));
  }

  /** An in-progress extract snapshot whose total is not yet known. */
  private static ProgressSnapshot open(
      OperationPhase phase, ProgressMetric metric, long completed) {
    return new ProgressSnapshot(Operation.EXTRACT, phase, metric, completed, OptionalLong.empty());
  }

  /** A completed extract snapshot whose total equals its completed count. */
  private static ProgressSnapshot settled(
      OperationPhase phase, ProgressMetric metric, long completed) {
    return new ProgressSnapshot(
        Operation.EXTRACT, phase, metric, completed, OptionalLong.of(completed));
  }

  /** Asserts that no private {@code .jbsa-} staging directory survived the operation. */
  private void assertNoPrivateStaging() throws Exception {
    try (var files = Files.list(directory)) {
      assertTrue(
          files.noneMatch(path -> path.getFileName().toString().startsWith(".jbsa-")),
          "the operation must remove its private staging");
    }
  }

  /** Selects one worker so the sequential strategy's snapshot timing is pinned. */
  private static ExtractRequest request(Path source, Path destination, EntrySelection selection) {
    return new ExtractRequest(
        source,
        destination,
        selection,
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        new WorkerSelection.UpTo(1),
        OpenOptions.standard());
  }

  /** Writes fixture bytes without using a format encoder. */
  private Path literal(String hex) throws Exception {
    return Files.write(
        directory.resolve("input.bsa"), HexFormat.of().parseHex(hex.replace(" ", "")));
  }
}
