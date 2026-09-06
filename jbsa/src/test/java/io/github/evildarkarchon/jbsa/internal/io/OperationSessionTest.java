package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.util.List;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

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
