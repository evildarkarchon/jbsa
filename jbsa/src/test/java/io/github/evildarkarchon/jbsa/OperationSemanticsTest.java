package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public mutation entry points apply operation semantics around every preflight rejection. */
class OperationSemanticsTest {
  @TempDir Path directory;

  /** A pre-cancelled request must not run preflight, deliver progress, or create output. */
  @Test
  void cancellationPrecedesCapabilityValidation() {
    var snapshots = new ArrayList<ProgressSnapshot>();
    var failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(request(), new OperationControl(snapshots::add, () -> true)));
    assertEquals(FailureKind.CANCELLED, failure.kind());
    assertTrue(snapshots.isEmpty());
    assertTrue(failure.artifacts().isEmpty());
    assertFalse(Files.exists(directory.resolve("archive.bsa")));
  }

  /** An unsupported selector tuple must still settle cleanup without completing preflight. */
  @Test
  void unsupportedEncodingEntersPreflightAndCompletesZeroCleanup() {
    var snapshots = new ArrayList<ProgressSnapshot>();
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(request(), new OperationControl(snapshots::add, () -> false)));
    assertEquals(FailureKind.UNSUPPORTED, failure.kind());
    assertEquals(
        List.of(
            new ProgressSnapshot(
                Operation.PACK,
                OperationPhase.PREFLIGHT,
                ProgressMetric.ENTRIES,
                0,
                java.util.OptionalLong.empty()),
            new ProgressSnapshot(
                Operation.PACK,
                OperationPhase.CLEANUP,
                ProgressMetric.ARTIFACTS,
                0,
                java.util.OptionalLong.empty()),
            new ProgressSnapshot(
                Operation.PACK,
                OperationPhase.CLEANUP,
                ProgressMetric.ARTIFACTS,
                0,
                java.util.OptionalLong.of(0))),
        snapshots);
  }

  /** Once an observer throws it must never be invoked again, including during cleanup. */
  @Test
  void observerFailureStopsDeliveryAndRetainsItsCause() {
    var thrown = new AssertionError("caller observer");
    var count = new java.util.concurrent.atomic.AtomicInteger();
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(),
                        new OperationControl(
                            snapshot -> {
                              count.incrementAndGet();
                              throw thrown;
                            },
                            () -> false)));
    assertEquals(FailureKind.OBSERVER, failure.kind());
    assertSame(thrown, failure.getCause());
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertEquals(1, count.get());
  }

  /** Cleanup observer failure is retained without masking an earlier unsupported encoding. */
  @Test
  void cleanupObserverFailureRemainsSecondary() {
    var thrown = new IllegalStateException("cleanup observer");
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(),
                        new OperationControl(
                            snapshot -> {
                              if (snapshot.phase() == OperationPhase.CLEANUP) throw thrown;
                            },
                            () -> false)));
    assertEquals(FailureKind.UNSUPPORTED, failure.kind());
    assertEquals(1, failure.secondaryFailures().size());
    assertEquals(FailureKind.OBSERVER, failure.secondaryFailures().getFirst().kind());
    assertSame(thrown, failure.secondaryFailures().getFirst().cause().orElseThrow());
    assertEquals(0, failure.getSuppressed().length);
  }

  /** Constructs a real request whose v2 selector conflicts with default v3 raw-LZ4 encoding. */
  private PackRequest request() {
    return PackRequest.standard(
        directory.resolve("archive.bsa"),
        ArchiveFamily.STARFIELD_DDS_BA2,
        new ArchiveEncoding(
            Optional.of(new WireVersion(2)),
            Optional.of(Ba2Subtype.DX10),
            java.util.OptionalLong.empty()),
        List.of(),
        Optional.of(DdsTarget.PC));
  }
}
