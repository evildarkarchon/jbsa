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

  /** The thread running the operation template, or null before {@link #run} starts. */
  private Thread coordinator;

  /**
   * Whether this session has thrown its own retained failure on the coordinator thread. From then
   * on the coordinator is unwinding that failure, so what it catches is already retained.
   */
  private boolean raisedToCoordinator;

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
     */
    void register(Resource resource);
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
     * @throws IOException any failure before publication; the template accepts and settles it
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
   *       #begin}), with no ordinal. An unstructured {@link IOException} becomes SOURCE {@code
   *       operation.source-io} at {@code context}. Any other {@link Error} propagates after the
   *       registered resources close.
   *   <li>An {@link ArchiveException} from the body is settled through {@link #unwound}: when it is
   *       the session's own raised failure, it is already retained and is not admitted again.
   *   <li>Registered resources close in reverse registration order, after the body and before the
   *       operation finishes, on success and on failure. A close failure is accepted as a CLEANUP
   *       failure, so it orders behind any earlier Primary Failure.
   *   <li>CLEANUP then reports the published artifact count, or zero when the body failed before
   *       publication returned.
   *   <li>The operation finishes exactly once, with the body's Archive Parts on success.
   * </ul>
   *
   * <p>A session runs at most one template, on its coordinator thread.
   *
   * @param context the operation's location: its path locates mapped body and close failures
   * @param body the operation, which registers its caller-owned resources as it acquires them
   * @return the settled report
   * @throws ArchiveException the Primary Failure, with Secondary Failures and artifact evidence
   */
  public OperationReport run(IoContext context, Body body) throws ArchiveException {
    Objects.requireNonNull(context, "context");
    Objects.requireNonNull(body, "body");
    synchronized (outcome) {
      if (coordinator != null) throw new IllegalStateException("The operation already ran");
      coordinator = Thread.currentThread();
    }
    List<Resource> registered = new ArrayList<>();
    Published published = null;
    try {
      published = body.run(resource -> registered.add(Objects.requireNonNull(resource)));
    } catch (ArchiveException failure) {
      unwound(failure);
    } catch (IOException failure) {
      accept(context.failure(FailureKind.SOURCE, "operation.source-io", failure));
    } catch (RuntimeException | AssertionError unexpected) {
      accept(
          located(context, phase().orElse(OperationPhase.PREFLIGHT))
              .failure(FailureKind.INTERNAL, "operation.internal-failure", unexpected));
    } finally {
      // ADR-0003 D10: caller-owned resources, the pack budget among them, close before the
      // epilogue settles, so their failures and residuals belong to the one outcome.
      close(registered, context);
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
   * Closes each registered resource, newest first, accepting every close failure at CLEANUP. A
   * later close still runs after an earlier one fails.
   */
  private void close(List<Resource> registered, IoContext context) {
    IoContext cleanup = located(context, OperationPhase.CLEANUP);
    for (Resource resource : registered.reversed()) {
      try {
        resource.close();
      } catch (ArchiveException failure) {
        accept(failure);
      } catch (IOException failure) {
        accept(cleanup.failure(FailureKind.SOURCE, "operation.source-io", failure));
      } catch (RuntimeException | AssertionError unexpected) {
        accept(cleanup.failure(FailureKind.INTERNAL, "operation.internal-failure", unexpected));
      }
    }
  }

  /** Locates an operation-level failure at a phase, on the context's path, with no ordinal. */
  private IoContext located(IoContext context, OperationPhase at) {
    return new IoContext(context.path(), operation, at, OptionalLong.empty());
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

  /**
   * Starts cleanup without allowing an observer failure to skip owned resource settlement. Only the
   * operation template enters CLEANUP; this stays package-private so no other module can.
   */
  void cleanup() {
    if (phase != null) enter(OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS);
  }

  /**
   * Emits exact settled cleanup evidence even after an earlier non-observer failure. Only the
   * operation template reports it.
   */
  void cleaned(long artifacts) {
    if (phase == OperationPhase.CLEANUP) {
      completed.put(ProgressMetric.ARTIFACTS, artifacts);
      deliver(ProgressMetric.ARTIFACTS, OptionalLong.of(artifacts));
    }
  }

  /**
   * Accepts a newly observed failure under the same lock used by cancellation; providers remain
   * causes. A failure the coordinator caught while unwinding goes through {@link #unwound} instead,
   * because it may be the session's own raised failure.
   */
  public void accept(ArchiveException failure) {
    synchronized (outcome) {
      failures.accept(failure);
    }
  }

  /**
   * Settles a failure the coordinator caught while unwinding its work: the template's body, or
   * publication's staging and commit. Once this session has raised its own retained failure on the
   * coordinator, the coordinator is unwinding that failure, so the caught one is that failure
   * itself and is already retained; it is not accepted again. Otherwise the caught failure is new
   * and is accepted.
   *
   * <p>A combiner that adds evidence while the coordinator unwinds (a cleanup failure behind the
   * raised failure) must accept that evidence with {@link #accept} and rethrow the raised failure,
   * rather than folding both into a new exception.
   *
   * <p>Coordinator thread only.
   */
  void unwound(ArchiveException failure) {
    synchronized (outcome) {
      if (!raisedToCoordinator) failures.accept(failure);
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

  /** Reports whether cleanup follows an accepted failure without invoking caller code. */
  public boolean failed() {
    synchronized (outcome) {
      return fatalFailure != null || failures.failed();
    }
  }

  /** Returns the settled immutable report or throws the selected checked outcome. */
  OperationReport finish(List<Artifact> artifacts) throws ArchiveException {
    return finish(artifacts, List.of());
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
   * Checks acceptance without resampling cancellation during cleanup or commit settlement. Raising
   * the retained failure on the coordinator marks the coordinator as unwinding it (see {@link
   * #unwound}). A worker's raise does not: the worker's exception reaches the coordinator rebuilt
   * by its runner's own retention, which still carries the raised records into the session.
   */
  private void throwIfFailed() throws ArchiveException {
    synchronized (outcome) {
      if (fatalFailure != null) throw fatalFailure;
      ArchiveException failure = failures.finish(List.of());
      if (failure != null) {
        if (Thread.currentThread() == coordinator) raisedToCoordinator = true;
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
