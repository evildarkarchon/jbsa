package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.math.BigInteger;
import java.util.ArrayList;
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
 *
 * <p>Pack and extract run through one operation template, {@link #run}. It alone maps unexpected
 * throwables, settles pre-publication failures with CLEANUP progress, closes the caller-owned
 * resources an operation body registers, and finishes the operation exactly once.
 *
 * <p>Once the operation has failed, a session method that observes it throws {@link
 * OperationStopped}, a signal that carries no evidence, never a copy of its retained failure. The
 * only failure the session ever throws is the settled outcome {@link #run} finishes with, so no
 * {@link ArchiveException} a caller catches during the operation is a copy the session raised.
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
  private boolean commitInProgress;
  private boolean publicationSettled;
  private boolean finished;

  /** Whether {@link #run} has started; a session runs at most one template. */
  private boolean started;

  private Error fatalFailure;

  /**
   * The operation's settled result: the artifacts its publication reported and, for pack, the
   * staged Archive Parts. Publication cleaned exactly these artifacts, so their count is the
   * CLEANUP total.
   *
   * @param artifacts the settled artifacts publication reports, in any order
   * @param archiveParts pack's staged Archive Parts in logical order; empty for extraction
   */
  public record Published(
      List<Artifact> artifacts, List<OperationReport.ArchivePart> archiveParts) {
    /** Copies both lists, so a body cannot change the result after returning it. */
    public Published {
      artifacts = List.copyOf(artifacts);
      archiveParts = List.copyOf(archiveParts);
    }

    /** A result without Archive Parts, as every extraction publishes. */
    public Published(List<Artifact> artifacts) {
      this(artifacts, List.of());
    }
  }

  /** A caller-owned resource the operation template closes after the operation body. */
  @FunctionalInterface
  public interface Resource {
    /**
     * Releases the resource. The template calls this at most once per registration.
     *
     * @throws IOException if the release fails; the template retains it as a CLEANUP failure
     */
    void close() throws IOException;
  }

  /** Where an operation body registers the caller-owned resources the template must close. */
  @FunctionalInterface
  public interface Resources {
    /**
     * Registers a resource the body has just acquired. Resources close in reverse registration
     * order, after the body and before the operation finishes, whether the body succeeds or not.
     *
     * @param location where the resource lives and on which side: the open source archive is a
     *     source at the archive; a stabilization spool or mutation budget is a destination at its
     *     path. A raw close failure is structured there, at CLEANUP with no ordinal.
     */
    void register(Resource resource, IoContext location);
  }

  /** One mutating operation's work, from {@code begin} through publication. */
  @FunctionalInterface
  public interface Body {
    /**
     * Runs the operation on the coordinator thread. Failures accepted into the session during
     * publication do not need to be thrown: the template finishes with them either way.
     *
     * @param resources the scope where the body registers each caller-owned resource it acquires
     * @return the published result; publication returns it even when it accepted a failure
     * @throws IOException any failure before publication, structured at its own site; the template
     *     accepts and settles it, except {@link OperationStopped}, whose failure the session
     *     already holds
     */
    Published run(Resources resources) throws IOException;
  }

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
   * The operation template: runs a pack or extract body and owns its whole epilogue.
   *
   * <ul>
   *   <li>A {@link RuntimeException} or {@link AssertionError} from the body becomes INTERNAL
   *       {@code operation.internal-failure} at the phase it interrupted (PREFLIGHT before {@link
   *       #begin}), with no ordinal. So does an unstructured {@link IOException}: every catch site
   *       structures its own faults, so a raw one reaching the template is a JBSA bug (ADR-0006).
   *       Any other {@link Error} propagates after the registered resources close.
   *   <li>An {@link ArchiveException} from the body is accepted. {@link OperationStopped} is not a
   *       failure and admits nothing: the session already holds the failure it stopped for.
   *   <li>Registered resources close in reverse registration order, after the body and before the
   *       operation finishes, on success and on failure. A close failure is structured at the
   *       resource's declared location and accepted as a CLEANUP failure, so it orders behind any
   *       earlier Primary Failure.
   *   <li>CLEANUP then reports the published artifact count, or zero when the body failed before
   *       publication returned.
   *   <li>The operation finishes exactly once, with the body's Archive Parts on success.
   * </ul>
   *
   * <p>A session runs at most one template, on its coordinator thread.
   *
   * @param context the operation's location: its path locates an unexpected body failure
   * @param body the operation, which registers its caller-owned resources as it acquires them
   * @return the settled report
   * @throws ArchiveException the Primary Failure, with Secondary Failures and artifact evidence
   */
  public OperationReport run(IoContext context, Body body) throws ArchiveException {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(body, "body");
    synchronized (outcome) {
      if (started) throw new IllegalStateException("The operation already ran");
      started = true;
    }
    List<Registered> registered = new ArrayList<>();
    Published published = null;
    try {
      published =
          body.run(
              (resource, location) ->
                  registered.add(
                      new Registered(
                          Objects.requireNonNull(resource, "resource"),
                          Objects.requireNonNull(location, "location"))));
    } catch (OperationStopped stopped) {
      // A signal, not a failure: the session already holds every record of what stopped the body.
    } catch (WorkerStopped leaked) {
      // A worker's stop belongs to its runner; reaching the template is a JBSA bug. A stop can
      // never
      // be a failure's cause, so the INTERNAL failure records the leak without it.
      accept(
          context
              .at(phase().orElse(OperationPhase.PREFLIGHT), OptionalLong.empty())
              .failure(FailureKind.INTERNAL, "operation.internal-failure", null));
    } catch (ArchiveException failure) {
      accept(failure);
    } catch (IOException | RuntimeException | AssertionError unexpected) {
      // ADR-0006: every catch site structures its own I/O faults, so an unstructured one that
      // reaches the template is a JBSA bug, never a SOURCE or DESTINATION fault.
      accept(
          context
              .at(phase().orElse(OperationPhase.PREFLIGHT), OptionalLong.empty())
              .failure(FailureKind.INTERNAL, "operation.internal-failure", unexpected));
    } finally {
      // ADR-0003 D10: caller-owned resources, the pack budget among them, close before the
      // epilogue settles, so their failures and residuals belong to the one outcome.
      close(registered);
    }
    cleanup();
    if (published == null) {
      cleaned(0);
      return finish(List.of(), List.of());
    }
    cleaned(published.artifacts().size());
    return finish(published.artifacts(), published.archiveParts());
  }

  /**
   * Closes each registered resource, newest first, accepting every close failure at CLEANUP and the
   * resource's declared location. A later close still runs after an earlier one fails.
   */
  private void close(List<Registered> registered) {
    for (Registered resource : registered.reversed()) {
      try {
        resource.resource().close();
      } catch (IOException | RuntimeException | AssertionError failure) {
        try {
          accept(
              resource
                  .location()
                  .at(OperationPhase.CLEANUP, OptionalLong.empty())
                  .structure(failure));
        } catch (StopSignal stopped) {
          // A signal, not a failure: whatever stopped the close already holds its evidence.
        }
      }
    }
  }

  /** One registered caller-owned resource and the location its raw close failure reports. */
  private record Registered(Resource resource, IoContext location) {}

  /**
   * Enters preflight for a fresh invocation, or verifies a collaborating adapter is still there.
   */
  public void ensurePreflight() throws OperationStopped {
    if (phase == null) begin();
    else if (phase != OperationPhase.PREFLIGHT)
      throw new IllegalStateException("The operation has already left preflight");
    else checkpoint(OperationPhase.PREFLIGHT, OptionalLong.empty());
  }

  /** Admits immutable evidence under the outcome lock and stops immediately on policy rejection. */
  public void diagnostic(Diagnostic diagnostic) throws OperationStopped {
    synchronized (outcome) {
      failures.diagnostic(diagnostic);
      throwIfFailed();
    }
  }

  /** Establishes new immutable assessment evidence, evaluating its warnings before retention. */
  public void assessment(ArchiveAssessment assessment) throws OperationStopped {
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
  public void processedEntry(long bytes) throws OperationStopped {
    advance(ProgressMetric.BYTES, bytes);
    advance(ProgressMetric.ENTRIES, 1);
  }

  /**
   * Atomically fences a publication commit against cancellation and failure acceptance. Caller
   * cancellation code runs outside the lock; its result is accepted only if the fence still permits
   * it.
   */
  public void beginCommit(OperationPhase phase, OptionalLong ordinal) throws OperationStopped {
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
  public void begin() throws OperationStopped {
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
  public void completePhase() throws OperationStopped {
    if (phase == null) throw new IllegalStateException("No progress phase has started");
    if (!phaseCompleted) {
      phaseCompleted = true;
      for (ProgressMetric metric : completed.keySet())
        deliver(metric, OptionalLong.of(completed.get(metric)));
    }
    throwIfFailed();
  }

  /** Adds semantic units once at their logical completion, never from physical replay or writes. */
  public void advance(ProgressMetric metric, long units) throws OperationStopped {
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
      throws OperationStopped {
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
  public void processing(boolean existingTree) throws OperationStopped {
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
  public void publishing() throws OperationStopped {
    enter(OperationPhase.PUBLISHING, ProgressMetric.ARTIFACTS);
    throwIfFailed();
  }

  /**
   * Starts cleanup without allowing an observer failure to skip owned resource settlement. Only the
   * operation template enters CLEANUP; this stays private so nothing else can.
   */
  private void cleanup() {
    if (phase != null) enter(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS);
  }

  /**
   * Emits exact settled cleanup evidence even after an earlier non-observer failure. Only the
   * operation template reports it.
   */
  private void cleaned(long artifacts) {
    if (phase == OperationPhase.CLEANUP) {
      completed.put(ProgressMetric.ARTIFACTS, artifacts);
      deliver(ProgressMetric.ARTIFACTS, OptionalLong.of(artifacts));
    }
  }

  /**
   * Accepts a newly observed failure under the same lock used by cancellation; providers remain
   * causes. Any thread may accept; the retention orders failures independently of arrival order.
   */
  public void accept(ArchiveException failure) {
    synchronized (outcome) {
      failures.accept(failure);
    }
  }

  /**
   * Returns the progress phase the operation has entered, for locating a failure the coordinator
   * raises itself. Only the coordinator thread changes the phase, so only it may rely on the
   * answer.
   *
   * @return the current phase, or empty before {@link #begin} has entered PREFLIGHT
   */
  public Optional<OperationPhase> phase() {
    return Optional.ofNullable(phase);
  }

  /**
   * Stops the caller if the operation has failed, without sampling cancellation. Code that settled
   * failures straight into the session, such as a closing worker runner, uses it to stop its caller
   * the same way every other session method does.
   *
   * @throws OperationStopped if the operation has failed
   */
  void stopIfFailed() throws OperationStopped {
    throwIfFailed();
  }

  /** Reports whether cleanup follows an accepted failure without invoking caller code. */
  public boolean failed() {
    synchronized (outcome) {
      return fatalFailure != null || failures.failed();
    }
  }

  /**
   * Reports whether the operation template has finished this operation. Finishing happens exactly
   * once, at the end of {@link #run}, including when it then throws a deferred fatal error.
   */
  boolean finished() {
    synchronized (outcome) {
      return finished;
    }
  }

  /**
   * Returns the settled immutable report, with pack's Archive Parts, or throws the selected checked
   * outcome. Only the operation template finishes, and only once.
   *
   * @throws IllegalStateException if the operation has already finished
   */
  private OperationReport finish(
      List<Artifact> artifacts, List<OperationReport.ArchivePart> archiveParts)
      throws ArchiveException {
    synchronized (outcome) {
      if (finished) throw new IllegalStateException("The operation has already finished");
      finished = true;
      if (fatalFailure != null) throw fatalFailure;
      ArchiveException failure = failures.finish(artifacts);
      if (failure != null) throw failure;
      return failures.report(artifacts, archiveParts);
    }
  }

  /**
   * Checks acceptance without resampling cancellation during cleanup or commit settlement. A failed
   * operation stops its caller with {@link OperationStopped}, never with a copy of the retained
   * failure, so no caller can admit that failure a second time.
   */
  private void throwIfFailed() throws OperationStopped {
    synchronized (outcome) {
      if (fatalFailure != null) throw fatalFailure;
      if (failures.failed()) throw new OperationStopped();
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
  public void checkpoint(OperationPhase phase, OptionalLong ordinal) throws OperationStopped {
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
