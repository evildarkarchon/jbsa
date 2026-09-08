package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.math.BigInteger;
import java.util.EnumMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.TreeMap;

/**
 * Operation-owned outcome acceptance; explicit cancellation never interrupts a Java thread.
 *
 * <p>The synchronous coordinator owns phase transitions, counters, evidence admission, commit
 * fencing, callback delivery, and final settlement. Workers may concurrently call {@link #accept}
 * and {@link #checkpoint}; the outcome lock serializes their decisions with commit acceptance.
 * Callback code never runs under that lock. No method transfers resource or worker ownership.
 */
public final class OperationSession {
  private final Operation operation;
  private final OperationControl control;
  private final ResourceLimits limits;
  private final FailureRetention failures;
  private final Object outcome = new Object();
  private final EnumMap<ProgressMetric, Long> completed = new EnumMap<>(ProgressMetric.class);
  private OperationPhase phase;
  private boolean observerStopped;
  private boolean phaseCompleted;
  private ArchiveException deliveredFailure;
  private boolean commitInProgress;
  private boolean publicationSettled;
  private Error fatalFailure;

  /** Captures immutable request controls without invoking caller callbacks. */
  public OperationSession(Operation operation, ResourceLimits limits, OperationControl control) {
    this(operation, limits, DiagnosticPolicy.standard(), control);
  }

  /** Captures the request warning policy before any diagnostic can be retained or rejected. */
  public OperationSession(
      Operation operation,
      ResourceLimits limits,
      DiagnosticPolicy policy,
      OperationControl control) {
    this.operation = Objects.requireNonNull(operation, "operation");
    this.control = Objects.requireNonNull(control, "control");
    this.limits = Objects.requireNonNull(limits, "limits");
    failures = new FailureRetention(limits, operation, policy);
  }

  /**
   * Enters preflight for a fresh invocation, or verifies a collaborating adapter is still there.
   */
  public void ensurePreflight() throws ArchiveException {
    if (phase == null) begin();
    else if (phase != OperationPhase.PREFLIGHT)
      throw new IllegalStateException("The operation has already left preflight");
    else checkpoint(OperationPhase.PREFLIGHT, OptionalLong.empty());
  }

  /** Admits immutable evidence under the outcome lock and stops immediately on policy rejection. */
  public void diagnostic(Diagnostic diagnostic) throws ArchiveException {
    synchronized (outcome) {
      failures.diagnostic(diagnostic);
      throwIfFailed();
    }
  }

  /** Establishes new immutable assessment evidence, evaluating its warnings before retention. */
  public void assessment(ArchiveAssessment assessment) throws ArchiveException {
    synchronized (outcome) {
      failures.assessment(assessment);
      throwIfFailed();
    }
  }

  /** Advances validation extent without counting already admitted structural diagnostics again. */
  public void latestAssessment(ArchiveAssessment assessment) {
    synchronized (outcome) {
      failures.latestAssessment(assessment);
    }
  }

  /**
   * Accounts for one final logical entry and its decoded bytes without counting physical replay.
   */
  public void processedEntry(long bytes) throws ArchiveException {
    advance(ProgressMetric.BYTES, bytes);
    advance(ProgressMetric.ENTRIES, 1);
  }

  /**
   * Atomically fences a publication commit against cancellation and failure acceptance. Caller
   * cancellation code runs outside the lock; its result is accepted only if the fence still permits
   * it.
   */
  public void beginCommit(OperationPhase phase, OptionalLong ordinal) throws ArchiveException {
    boolean requested = control.cancellationRequested().getAsBoolean();
    synchronized (outcome) {
      if (commitInProgress || publicationSettled)
        throw new IllegalStateException("No further publication commit may begin");
      acceptCancellation(requested, phase, ordinal);
      throwIfFailed();
      commitInProgress = true;
    }
  }

  /**
   * Releases the fence only after commit or rollback settles. A final successful commit disables
   * later cancellation for the invocation; an existing-tree intermediate commit enables the next
   * stop.
   */
  public void endCommit(boolean finalCommit) {
    synchronized (outcome) {
      if (!commitInProgress) throw new IllegalStateException("No publication commit is active");
      commitInProgress = false;
      publicationSettled = finalCommit;
    }
  }

  /** Enters preflight only after the initial cancellation observation. */
  public void begin() throws ArchiveException {
    checkpoint(OperationPhase.PREFLIGHT, OptionalLong.empty());
    enter(OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES);
    throwIfFailed();
    checkpoint(OperationPhase.PREFLIGHT, OptionalLong.empty());
  }

  /** Starts each applicable pair at zero; only the synchronous coordinator delivers callbacks. */
  private void enter(OperationPhase next, ProgressMetric... metrics) {
    if (phase != null && next.compareTo(phase) <= 0)
      throw new IllegalStateException("Progress phases must advance");
    phase = next;
    phaseCompleted = false;
    completed.clear();
    for (ProgressMetric metric : metrics) {
      completed.put(metric, 0L);
      deliver(metric, OptionalLong.empty());
    }
  }

  /** Completes the entered pairs with exact totals; interrupted phases are never completed. */
  public void completePhase() throws ArchiveException {
    if (phase == null) throw new IllegalStateException("No progress phase has started");
    if (!phaseCompleted) {
      phaseCompleted = true;
      for (ProgressMetric metric : completed.keySet())
        deliver(metric, OptionalLong.of(completed.get(metric)));
    }
    throwIfFailed();
  }

  /** Adds semantic units once at their logical completion, never from physical replay or writes. */
  public void advance(ProgressMetric metric, long units) throws ArchiveException {
    if (phaseCompleted) throw new IllegalStateException("The progress phase has already completed");
    if (units < 0 || !completed.containsKey(metric))
      throw new IllegalArgumentException("Metric must belong to the current phase");
    if (phase == OperationPhase.PREFLIGHT && metric == ProgressMetric.ENTRIES)
      checkLimit("maxEntries", limits.maxEntries(), completed.get(metric), units);
    if (phase == OperationPhase.PROCESSING && metric == ProgressMetric.BYTES)
      checkLimit("maxDecodedBytes", limits.maxDecodedBytes(), completed.get(metric), units);
    completed.put(metric, Math.addExact(completed.get(metric), units));
    deliver(metric, OptionalLong.empty());
    throwIfFailed();
  }

  /**
   * Rejects semantic admission before progress changes, preserving unsigned counts after overflow.
   */
  private void checkLimit(String field, long ceiling, long previous, long units)
      throws ArchiveException {
    BigInteger observed = BigInteger.valueOf(previous).add(BigInteger.valueOf(units));
    if (observed.compareTo(BigInteger.valueOf(ceiling)) > 0) {
      String identifier = "operation.resource-limit";
      var location = DiagnosticLocation.operation();
      var values = new TreeMap<String, String>();
      values.put("field", field);
      values.put("ceiling", Long.toString(ceiling));
      values.put("observed", observed.toString());
      var diagnostic =
          new Diagnostic(
              identifier,
              DiagnosticSeverity.ERROR,
              operation,
              phase,
              location,
              values,
              Optional.empty());
      var failure =
          new Failure(
              FailureKind.POLICY,
              phase,
              OptionalLong.empty(),
              Optional.of(identifier),
              Optional.of(location),
              Optional.empty());
      accept(
          new ArchiveException(
              identifier, failure, List.of(diagnostic), List.of(), Optional.empty(), List.of()));
      throwIfFailed();
    }
  }

  /** Enters processing after complete preflight, even for a zero-entry plan. */
  public void processing(boolean existingTree) throws ArchiveException {
    if (existingTree)
      enter(
          OperationPhase.PROCESSING,
          ProgressMetric.ENTRIES,
          ProgressMetric.BYTES,
          ProgressMetric.ARTIFACTS);
    else enter(OperationPhase.PROCESSING, ProgressMetric.ENTRIES, ProgressMetric.BYTES);
    throwIfFailed();
  }

  /** Enters atomic-set publication immediately before its commit decision. */
  public void publishing() throws ArchiveException {
    enter(OperationPhase.PUBLISHING, ProgressMetric.ARTIFACTS);
    throwIfFailed();
  }

  /** Starts cleanup without allowing an observer failure to skip owned resource settlement. */
  public void cleanup() {
    if (phase != null) enter(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS);
  }

  /** Emits exact settled cleanup evidence even after an earlier non-observer failure. */
  public void cleaned(long artifacts) {
    if (phase == OperationPhase.CLEANUP) {
      completed.put(ProgressMetric.ARTIFACTS, artifacts);
      deliver(ProgressMetric.ARTIFACTS, OptionalLong.of(artifacts));
    }
  }

  /** Accepts a candidate under the same lock used by cancellation; providers remain causes. */
  public void accept(ArchiveException failure) {
    synchronized (outcome) {
      // A thrown snapshot of our own accepted outcome must not be admitted a second time.
      if (failure == deliveredFailure) return;
      failures.accept(failure);
    }
  }

  /** Reports whether cleanup follows an accepted failure without invoking caller code. */
  public boolean failed() {
    synchronized (outcome) {
      return fatalFailure != null || failures.failed();
    }
  }

  /** Returns the settled immutable report or throws the selected checked outcome. */
  public OperationReport finish(List<Artifact> artifacts) throws ArchiveException {
    synchronized (outcome) {
      if (fatalFailure != null) throw fatalFailure;
      ArchiveException failure = failures.finish(artifacts);
      if (failure != null) {
        deliveredFailure = failure;
        throw failure;
      }
      return failures.report(artifacts);
    }
  }

  /** Checks acceptance without resampling cancellation during cleanup or commit settlement. */
  private void throwIfFailed() throws ArchiveException {
    synchronized (outcome) {
      if (fatalFailure != null) throw fatalFailure;
      ArchiveException failure = failures.finish(List.of());
      if (failure != null) {
        deliveredFailure = failure;
        throw failure;
      }
    }
  }

  /** Calls user code outside the outcome lock and permanently disables a throwing observer. */
  private void deliver(ProgressMetric metric, OptionalLong total) {
    if (observerStopped) return;
    var snapshot = new ProgressSnapshot(operation, phase, metric, completed.get(metric), total);
    try {
      control.observer().accept(snapshot);
    } catch (VirtualMachineError | ThreadDeath fatal) {
      observerStopped = true;
      synchronized (outcome) {
        fatalFailure = fatal;
      }
      // Cleanup owns live resources when this callback runs. Defer fatal unwinding until finish
      // so the coordinator can settle them; earlier phases unwind through its ordinary finally.
      if (phase != OperationPhase.CLEANUP) throw fatal;
    } catch (Throwable cause) {
      observerStopped = true;
      String identifier = "operation.observer-failed";
      var location = DiagnosticLocation.operation();
      var diagnostic =
          new Diagnostic(
              identifier,
              DiagnosticSeverity.ERROR,
              operation,
              phase,
              location,
              new TreeMap<>(),
              Optional.empty());
      var failure =
          new Failure(
              FailureKind.OBSERVER,
              phase,
              OptionalLong.empty(),
              Optional.of(identifier),
              Optional.of(location),
              Optional.of(cause));
      accept(
          new ArchiveException(
              identifier, failure, List.of(diagnostic), List.of(), Optional.empty(), List.of()));
    }
  }

  /** Observes cancellation before any operational validation or externally visible work. */
  public void checkpoint(OperationPhase phase, OptionalLong ordinal) throws ArchiveException {
    synchronized (outcome) {
      throwIfFailed();
      if (commitInProgress || publicationSettled) return;
    }
    boolean requested = control.cancellationRequested().getAsBoolean();
    synchronized (outcome) {
      // A commit can begin while caller code is running, so the second check is the acceptance
      // point. The observation alone never wins a race against an already established fence.
      acceptCancellation(requested, phase, ordinal);
      throwIfFailed();
    }
  }

  /** Accepts cancellation only under the shared outcome lock and outside a publication fence. */
  private void acceptCancellation(boolean requested, OperationPhase phase, OptionalLong ordinal) {
    if (requested
        && !commitInProgress
        && !publicationSettled
        && !failures.failed()
        && fatalFailure == null) {
      var primary =
          new Failure(
              FailureKind.CANCELLED,
              phase,
              ordinal,
              Optional.empty(),
              Optional.empty(),
              Optional.empty());
      failures.accept(
          new ArchiveCancelledException(
              "Operation cancelled", primary, List.of(), List.of(), Optional.empty(), List.of()));
    }
  }
}
