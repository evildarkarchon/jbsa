package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies atomic outcome decisions using controlled overlap and detached public results. */
final class OperationSessionTest {
  /**
   * A checkpoint already sampling caller state cannot accept cancellation across a commit fence.
   */
  @Test
  void commitFenceWinsAgainstAnOverlappingCancellationObservation() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var cancelled = new AtomicBoolean();
    var worker = new AtomicReference<Thread>();
    var thrown = new AtomicReference<Throwable>();
    var session =
        new OperationSession(
            Operation.PACK,
            ResourceLimits.standard(),
            new OperationControl(
                snapshot -> {},
                () -> {
                  if (Thread.currentThread() == worker.get()) {
                    entered.countDown();
                    await(release);
                  }
                  return cancelled.get();
                }));
    session.begin();
    worker.set(
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  try {
                    session.checkpoint(OperationPhase.PROCESSING, OptionalLong.of(0));
                  } catch (Throwable failure) {
                    thrown.set(failure);
                  }
                }));
    worker.get().start();
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      session.beginCommit(OperationPhase.PUBLISHING, OptionalLong.of(0));
      cancelled.set(true);
    } finally {
      release.countDown();
      worker.get().join();
    }
    assertNull(thrown.get());
    session.endCommit(false);
    assertThrows(
        ArchiveCancelledException.class,
        () -> session.checkpoint(OperationPhase.PROCESSING, OptionalLong.of(1)));
  }

  /** Cancellation arriving after the final commit cannot change the successful cleanup outcome. */
  @Test
  void finalCommitDisablesLaterCancellationObservations() throws Exception {
    var cancelled = new AtomicBoolean();
    var session =
        new OperationSession(
            Operation.PACK,
            ResourceLimits.standard(),
            new OperationControl(snapshot -> {}, cancelled::get));
    session.begin();
    session.beginCommit(OperationPhase.PUBLISHING, OptionalLong.of(0));
    session.endCommit(true);
    cancelled.set(true);
    session.checkpoint(OperationPhase.CLEANUP, OptionalLong.empty());
    session.cleanup();
    session.cleaned(0);
    assertEquals(Operation.PACK, session.finish(List.of()).operation());
  }

  /** A fatal cleanup observer must not unwind before the caller settles its owned resources. */
  @Test
  void fatalCleanupObserverIsRethrownOnlyAfterSettlement() throws Exception {
    var fatal = new InternalError("controlled observer failure");
    var callbacks = new java.util.concurrent.atomic.AtomicInteger();
    var session =
        new OperationSession(
            Operation.PACK,
            ResourceLimits.standard(),
            new OperationControl(
                snapshot -> {
                  callbacks.incrementAndGet();
                  if (snapshot.phase() == OperationPhase.CLEANUP) throw fatal;
                },
                () -> false));
    session.begin();
    int beforeCleanup = callbacks.get();
    assertDoesNotThrow(session::cleanup);
    session.cleaned(0);
    assertEquals(beforeCleanup + 1, callbacks.get());
    assertSame(fatal, assertThrows(InternalError.class, () -> session.finish(List.of())));
  }

  /**
   * The same semantic limits apply to discovery candidates and final decoded bytes before progress.
   */
  @Test
  void semanticLimitsRejectTheNextUnitBeforeAdvancingCounters() throws Exception {
    var limits = new ResourceLimits(1, 10, 3, 10, 10, 10, 10);
    var snapshots = new java.util.ArrayList<ProgressSnapshot>();
    var discovery =
        new OperationSession(
            Operation.PACK, limits, new OperationControl(snapshots::add, () -> false));
    discovery.begin();
    discovery.advance(ProgressMetric.ENTRIES, 1);
    ArchiveException rejected =
        assertThrows(ArchiveException.class, () -> discovery.advance(ProgressMetric.ENTRIES, 1));
    assertEquals(FailureKind.POLICY, rejected.kind());
    assertEquals("maxEntries", rejected.diagnostics().getFirst().values().get("field"));
    assertEquals("2", rejected.diagnostics().getFirst().values().get("observed"));
    assertEquals(1, snapshots.getLast().completed());

    var processing = new OperationSession(Operation.PACK, limits, OperationControl.standard());
    processing.begin();
    processing.processing(false);
    processing.processedEntry(3);
    rejected = assertThrows(ArchiveException.class, () -> processing.processedEntry(1));
    assertEquals("maxDecodedBytes", rejected.diagnostics().getFirst().values().get("field"));
    assertEquals("3", rejected.diagnostics().getFirst().values().get("ceiling"));
    assertEquals("4", rejected.diagnostics().getFirst().values().get("observed"));
    assertEquals(OperationPhase.PROCESSING, rejected.primaryFailure().phase());
  }

  /** Failure acceptance while caller cancellation code blocks wins the atomic outcome race. */
  @Test
  void operationalFailureWinsBeforePendingCancellationCanBeAccepted() throws Exception {
    var entered = new CountDownLatch(1);
    var release = new CountDownLatch(1);
    var worker = new AtomicReference<Thread>();
    var thrown = new AtomicReference<Throwable>();
    var session =
        new OperationSession(
            Operation.PACK,
            ResourceLimits.standard(),
            new OperationControl(
                snapshot -> {},
                () -> {
                  if (Thread.currentThread() == worker.get()) {
                    entered.countDown();
                    await(release);
                    return true;
                  }
                  return false;
                }));
    session.begin();
    worker.set(
        Thread.ofPlatform()
            .unstarted(
                () -> {
                  try {
                    session.checkpoint(OperationPhase.PROCESSING, OptionalLong.of(0));
                  } catch (Throwable failure) {
                    thrown.set(failure);
                  }
                }));
    worker.get().start();
    var cause = new java.io.IOException("controlled source failure");
    try {
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      session.accept(
          new IoContext(
                  java.nio.file.Path.of("source"),
                  Operation.PACK,
                  OperationPhase.PROCESSING,
                  OptionalLong.of(0))
              .failure(FailureKind.SOURCE, "source.failed", cause));
    } finally {
      release.countDown();
      worker.get().join();
    }
    ArchiveException result = assertInstanceOf(ArchiveException.class, thrown.get());
    assertEquals(FailureKind.SOURCE, result.kind());
    assertSame(cause, result.getCause());
  }

  /** Large logical counts reject overflow with unsigned evidence instead of ArithmeticException. */
  @Test
  void decodedLimitOverflowRetainsTheExactObservedCount() throws Exception {
    var limits = new ResourceLimits(2, 1, Long.MAX_VALUE, 1, 1, 10, 10);
    var session = new OperationSession(Operation.PACK, limits, OperationControl.standard());
    session.begin();
    session.processing(false);
    session.processedEntry(Long.MAX_VALUE);
    ArchiveException failure =
        assertThrows(ArchiveException.class, () -> session.processedEntry(1));
    assertEquals("9223372036854775808", failure.diagnostics().getFirst().values().get("observed"));
    assertEquals(FailureKind.POLICY, failure.kind());
  }

  /** A terminal total remains fixed even if collaborating adapters ask to complete it twice. */
  @Test
  void terminalPhaseIsIdempotentAndRejectsLaterUnits() throws Exception {
    var snapshots = new java.util.ArrayList<ProgressSnapshot>();
    var session =
        new OperationSession(
            Operation.PACK,
            ResourceLimits.standard(),
            new OperationControl(snapshots::add, () -> false));
    session.ensurePreflight();
    session.ensurePreflight();
    session.advance(ProgressMetric.ENTRIES, 1);
    session.completePhase();
    List<ProgressSnapshot> terminal = List.copyOf(snapshots);
    session.completePhase();
    assertEquals(terminal, snapshots);
    assertThrows(IllegalStateException.class, () -> session.advance(ProgressMetric.ENTRIES, 1));
    session.processing(false);
    assertThrows(IllegalStateException.class, session::ensurePreflight);
  }

  /**
   * A published body settles once: registered resources close newest first, CLEANUP reports the
   * published artifact count, and the report carries the body's Archive Parts.
   */
  @Test
  void templateClosesResourcesInReverseOrderAndFinishesOnceOnSuccess() throws Exception {
    var snapshots = new ArrayList<ProgressSnapshot>();
    var session =
        new OperationSession(
            Operation.PACK,
            ResourceLimits.standard(),
            new OperationControl(snapshots::add, () -> false));
    var closed = new ArrayList<String>();
    Path target = Path.of("archive.bsa").toAbsolutePath();
    var artifact = new Artifact(target, 0, ArtifactState.PUBLISHED);
    var part = new OperationReport.ArchivePart(target, 12, 1);

    OperationReport report =
        session.run(
            IoContext.of(target, Operation.PACK),
            resources -> {
              resources.register(() -> closed.add("first"));
              session.begin();
              resources.register(() -> closed.add("second"));
              return new OperationSession.Published(List.of(artifact), List.of(part));
            });

    assertEquals(List.of("second", "first"), closed);
    assertEquals(List.of(artifact), report.artifacts());
    assertEquals(List.of(part), report.archiveParts());
    assertEquals(
        List.of(
            snapshot(Operation.PACK, OperationPhase.PREFLIGHT, 0, OptionalLong.empty()),
            snapshot(Operation.PACK, OperationPhase.CLEANUP, 0, OptionalLong.empty()),
            snapshot(Operation.PACK, OperationPhase.CLEANUP, 1, OptionalLong.of(1))),
        snapshots);
    assertSettled(session);
  }

  /**
   * A failure before publication settles zero CLEANUP progress, and a resource that then fails to
   * close is a CLEANUP Secondary Failure behind the body's Primary Failure.
   */
  @Test
  void closeFailureAfterBodyFailureIsCleanupSecondary() {
    var snapshots = new ArrayList<ProgressSnapshot>();
    var session =
        new OperationSession(
            Operation.PACK,
            ResourceLimits.standard(),
            new OperationControl(snapshots::add, () -> false));
    Path target = Path.of("archive.bsa").toAbsolutePath();
    var context = IoContext.of(target, Operation.PACK);
    var closing = new IOException("close");

    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                session.run(
                    context,
                    resources -> {
                      resources.register(
                          () -> {
                            throw closing;
                          });
                      session.begin();
                      throw context.failure(FailureKind.POLICY, "fixture.rejected", null);
                    }));

    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(Optional.of("fixture.rejected"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(1, failure.secondaryFailures().size());
    var secondary = failure.secondaryFailures().getFirst();
    assertEquals(FailureKind.SOURCE, secondary.kind());
    assertEquals(OperationPhase.CLEANUP, secondary.phase());
    assertEquals(Optional.of("operation.source-io"), secondary.diagnosticIdentifier());
    assertSame(closing, secondary.cause().orElseThrow());
    assertEquals(zeroCleanup(Operation.PACK), snapshots);
    assertSettled(session);
  }

  /** A resource that fails to close after a successful body is the one outcome's failure. */
  @Test
  void closeFailureAfterSuccessfulBodySettlesOnce() {
    var snapshots = new ArrayList<ProgressSnapshot>();
    var session =
        new OperationSession(
            Operation.PACK,
            ResourceLimits.standard(),
            new OperationControl(snapshots::add, () -> false));
    Path target = Path.of("archive.bsa").toAbsolutePath();
    var artifact = new Artifact(target, 0, ArtifactState.PUBLISHED);

    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                session.run(
                    IoContext.of(target, Operation.PACK),
                    resources -> {
                      resources.register(
                          () -> {
                            throw new IOException("close");
                          });
                      session.begin();
                      return new OperationSession.Published(List.of(artifact), List.of());
                    }));

    assertEquals(FailureKind.SOURCE, failure.kind());
    assertEquals(OperationPhase.CLEANUP, failure.primaryFailure().phase());
    assertEquals(List.of(), failure.secondaryFailures());
    assertEquals(List.of(artifact), failure.artifacts());
    assertEquals(
        List.of(
            snapshot(Operation.PACK, OperationPhase.PREFLIGHT, 0, OptionalLong.empty()),
            snapshot(Operation.PACK, OperationPhase.CLEANUP, 0, OptionalLong.empty()),
            snapshot(Operation.PACK, OperationPhase.CLEANUP, 1, OptionalLong.of(1))),
        snapshots);
    assertSettled(session);
  }

  /**
   * An unexpected runtime failure in either operation's body ends as INTERNAL {@code
   * operation.internal-failure} at the phase it interrupted, with zero CLEANUP progress.
   */
  @Test
  void unexpectedBodyFailureEndsInternalWithCleanupProgress() throws Exception {
    for (Operation operation : List.of(Operation.PACK, Operation.EXTRACT)) {
      var snapshots = new ArrayList<ProgressSnapshot>();
      var session =
          new OperationSession(
              operation,
              ResourceLimits.standard(),
              new OperationControl(snapshots::add, () -> false));
      var bug = new IllegalStateException("body bug");

      var failure =
          assertThrows(
              ArchiveException.class,
              () ->
                  session.run(
                      IoContext.of(Path.of("source").toAbsolutePath(), operation),
                      resources -> {
                        session.begin();
                        session.completePhase();
                        session.processing(false);
                        throw bug;
                      }));

      assertEquals(FailureKind.INTERNAL, failure.kind());
      assertEquals(
          Optional.of("operation.internal-failure"),
          failure.primaryFailure().diagnosticIdentifier());
      assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
      assertEquals(OptionalLong.empty(), failure.primaryFailure().ordinal());
      assertSame(bug, failure.primaryFailure().cause().orElseThrow());
      assertEquals(List.of(), failure.secondaryFailures());
      assertEquals(
          List.of(
              new ProgressSnapshot(
                  operation,
                  OperationPhase.CLEANUP,
                  ProgressMetric.ARTIFACTS,
                  0,
                  OptionalLong.empty()),
              new ProgressSnapshot(
                  operation,
                  OperationPhase.CLEANUP,
                  ProgressMetric.ARTIFACTS,
                  0,
                  OptionalLong.of(0))),
          snapshots.subList(snapshots.size() - 2, snapshots.size()));
      assertSettled(session);
    }
  }

  /**
   * A failure the session raised itself is not admitted again when the body rethrows it, so the
   * outcome holds one Primary Failure and no duplicate.
   */
  @Test
  void sessionFailureRethrownByBodyIsRetainedOnce() {
    var session =
        new OperationSession(
            Operation.EXTRACT,
            ResourceLimits.standard(),
            new OperationControl(s -> {}, () -> true));
    Path source = Path.of("source").toAbsolutePath();

    var failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                session.run(
                    IoContext.of(source, Operation.EXTRACT),
                    resources -> {
                      session.begin();
                      throw new AssertionError("begin must observe cancellation");
                    }));

    assertEquals(FailureKind.CANCELLED, failure.kind());
    assertEquals(List.of(), failure.secondaryFailures());
    assertEquals(List.of(), failure.diagnostics());
    assertSettled(session);
  }

  /**
   * Extraction's source archive closes after publication has settled. When that close fails behind
   * a publication failure, it is a CLEANUP Secondary Failure of the one settled outcome.
   */
  @Test
  void lateSourceCloseFailureIsSecondaryOfTheSettledExtraction(@TempDir Path directory) {
    var snapshots = new ArrayList<ProgressSnapshot>();
    var session =
        new OperationSession(
            Operation.EXTRACT,
            ResourceLimits.standard(),
            DiagnosticPolicy.standard(),
            new OperationControl(snapshots::add, () -> false));
    Path source = directory.resolve("input.bsa");
    Path root = directory.resolve("out");
    var closing = new IOException("source close");

    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                session.run(
                    IoContext.of(source, Operation.EXTRACT),
                    resources -> {
                      resources.register(
                          () -> {
                            throw closing;
                          });
                      session.begin();
                      List<Artifact> artifacts =
                          PublicationTransaction.extractSequential(
                              root,
                              List.of(
                                  new PublicationTransaction.Entry(
                                      "a",
                                      output -> {
                                        throw new IOException("write");
                                      },
                                      1,
                                      0)),
                              TargetPolicy.FAIL,
                              ResourceLimits.standard(),
                              session);
                      return new OperationSession.Published(artifacts, List.of());
                    }));

    assertEquals(FailureKind.DESTINATION, failure.kind());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertEquals(OptionalLong.of(0), failure.primaryFailure().ordinal());
    assertEquals(1, failure.secondaryFailures().size());
    var secondary = failure.secondaryFailures().getFirst();
    assertEquals(FailureKind.SOURCE, secondary.kind());
    assertEquals(OperationPhase.CLEANUP, secondary.phase());
    assertEquals(OptionalLong.empty(), secondary.ordinal());
    assertEquals(Optional.of("operation.source-io"), secondary.diagnosticIdentifier());
    assertSame(closing, secondary.cause().orElseThrow());
    var cleanup = snapshots.stream().filter(s -> s.phase() == OperationPhase.CLEANUP).toList();
    assertEquals(
        List.of(
            new ProgressSnapshot(
                Operation.EXTRACT,
                OperationPhase.CLEANUP,
                ProgressMetric.ARTIFACTS,
                0,
                OptionalLong.empty()),
            new ProgressSnapshot(
                Operation.EXTRACT,
                OperationPhase.CLEANUP,
                ProgressMetric.ARTIFACTS,
                failure.artifacts().size(),
                OptionalLong.of(failure.artifacts().size()))),
        cleanup);
    assertFalse(Files.exists(root.resolve("a")));
    assertSettled(session);
  }

  /** Asserts the template already finished the session, which accepts exactly one finish. */
  private static void assertSettled(OperationSession session) {
    assertThrows(IllegalStateException.class, () -> session.finish(List.of()));
  }

  /** The snapshots of a failure in PREFLIGHT that settles zero CLEANUP progress. */
  private static List<ProgressSnapshot> zeroCleanup(Operation operation) {
    return List.of(
        snapshot(operation, OperationPhase.PREFLIGHT, 0, OptionalLong.empty()),
        snapshot(operation, OperationPhase.CLEANUP, 0, OptionalLong.empty()),
        snapshot(operation, OperationPhase.CLEANUP, 0, OptionalLong.of(0)));
  }

  /** One snapshot of the single metric PREFLIGHT and CLEANUP each report. */
  private static ProgressSnapshot snapshot(
      Operation operation, OperationPhase phase, long completed, OptionalLong total) {
    return new ProgressSnapshot(
        operation,
        phase,
        phase == OperationPhase.PREFLIGHT ? ProgressMetric.ENTRIES : ProgressMetric.ARTIFACTS,
        completed,
        total);
  }

  /**
   * Controlled waits fail clearly without using interruption as simulated operation cancellation.
   */
  private static void await(CountDownLatch latch) {
    try {
      assertTrue(latch.await(10, TimeUnit.SECONDS), "Timed out waiting for controlled overlap");
    } catch (InterruptedException interrupted) {
      Thread.currentThread().interrupt();
      throw new AssertionError("The test coordinator was interrupted", interrupted);
    }
  }
}
