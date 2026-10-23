package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.OperationPhase;
import io.github.evildarkarchon.jbsa.WorkerSelection;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.IntFunction;

/**
 * Operation-owned fixed platform workers beneath one synchronous, ordered coordinator.
 *
 * <p>The coordinator alone submits and consumes work. Workers receive only a noninterrupting stop
 * checkpoint and a preadmitted resource lease; they cannot deliver progress or publish output.
 * Every result owns its credits until the coordinator closes its {@link Outcome}.
 *
 * <p>Closing the runner settles the failures of unconsumed work and of discarded results straight
 * into the operation session, under the request's Diagnostic Policy, rather than folding them into
 * an exception of its own.
 *
 * <p>A stopped worker throws {@link WorkerStopped}, which is never a failure. Cleanup failures its
 * catch site attached to that stop are this runner's to settle (ADR-0006): they are accepted into
 * the session in plan order, whether the stopped outcome is drained by {@link #close} or consumed
 * as skipped and closed by the coordinator.
 */
public final class OrderedWorkerRunner<R extends AutoCloseable> implements AutoCloseable {
  private static final AtomicLong NEXT_OPERATION_ID = new AtomicLong();
  private static final int MAX_PLATFORM_WORKERS = 64;
  private static final long RESULT_SLOT_HEAP_BYTES = 1024;
  private static final ThreadLocal<ArrayDeque<InterruptionScope>> INTERRUPT_SCOPES =
      ThreadLocal.withInitial(ArrayDeque::new);

  private final Thread coordinator = Thread.currentThread();
  private final OperationSession session;
  private final ResourceBudget budget;
  private final IoContext context;
  private final IntFunction<IoContext> locate;
  private final ExecutorService workers;
  private final int capacity;
  private final ArrayDeque<Slot<R>> pending = new ArrayDeque<>();
  private final AtomicBoolean admissionStopped = new AtomicBoolean();
  private final AtomicBoolean forcedStop = new AtomicBoolean();
  private final AtomicInteger earliestFailure = new AtomicInteger(Integer.MAX_VALUE);
  private final AtomicReference<Error> fatalFailure = new AtomicReference<>();
  private int nextSubmitted;
  private int nextConsumed;
  private boolean interrupted;
  private boolean closed;

  /**
   * Snapshots the operation's worker limit and creates its private fixed platform pool. The plan
   * count has already been established in Logical Plan Order; no descriptors are created here.
   *
   * @param session the operation's session, which takes the failures {@link #close} settles
   * @param context the operation-level location of an admission failure
   * @param locate maps a submitted plan position to the PROCESSING location its unstructured worker
   *     and cleanup failures are structured at: its side, its path, and its ordinal, such as the
   *     archive entry ordinal of a sparse extraction selection
   */
  public OrderedWorkerRunner(
      OperationSession session,
      WorkerSelection selection,
      int plannedCount,
      ResourceBudget budget,
      IoContext context,
      IntFunction<IoContext> locate) {
    this.session = Objects.requireNonNull(session, "session");
    Objects.requireNonNull(selection, "selection");
    if (plannedCount < 0) throw new IllegalArgumentException("plannedCount must be nonnegative");
    this.budget = Objects.requireNonNull(budget, "budget");
    this.context = Objects.requireNonNull(context, "context");
    this.locate = Objects.requireNonNull(locate, "locate");
    long selected = WorkerLimits.snapshot(selection).workers();
    // The internal pool cap prevents pathological thread counts without changing the public
    // ceiling.
    int count =
        (int) Math.min(selected, Math.min((long) MAX_PLATFORM_WORKERS, Math.max(1L, plannedCount)));
    capacity = (int) Math.min((long) plannedCount, count == 1 ? 1L : 2L * count);
    long operationId = NEXT_OPERATION_ID.incrementAndGet();
    String prefix =
        "jbsa-"
            + context.operation().name().toLowerCase(Locale.ROOT)
            + "-"
            + operationId
            + "-worker-";
    AtomicInteger nextWorker = new AtomicInteger();
    workers =
        Executors.newFixedThreadPool(
            count,
            task ->
                Thread.ofPlatform()
                    .daemon(false)
                    .name(prefix + nextWorker.incrementAndGet())
                    .unstarted(task));
  }

  /** Worst-case per-work credits retained through ordered result consumption. */
  public record Cost(long heap, long nativeBytes, long handles, long scratch) {
    /** Rejects invalid internal estimates before any operation credit changes. */
    public Cost {
      if (heap < 0 || nativeBytes < 0 || handles < 0 || scratch < 0)
        throw new IllegalArgumentException("Work costs must be nonnegative");
    }
  }

  /** Private work that may read, hash, or transform but may not publish or report progress. */
  @FunctionalInterface
  public interface Worker<R extends AutoCloseable> {
    /** Produces one private result while borrowing the result's preadmitted lease. */
    R run(Checkpoint checkpoint, ResourceBudget.Lease lease) throws IOException;
  }

  /** Noninterrupting stop observation used at bounded I/O and codec boundaries. */
  @FunctionalInterface
  public interface Checkpoint {
    /** Stops later work after a failure, or all work after an explicit coordinator stop. */
    void check() throws IOException;
  }

  /** Calling-thread observation while an earlier worker result is still pending. */
  @FunctionalInterface
  public interface CoordinatorCheckpoint {
    /** Samples cancellation without holding a runner or resource-budget lock. */
    void check() throws IOException;
  }

  /** Returns the maximum number of queued, executing, and unconsumed results for this operation. */
  public int capacity() {
    return capacity;
  }

  /** Reports whether admission has stopped after worker failure or coordinator request. */
  public boolean stopped() {
    return admissionStopped.get();
  }

  /** Reports whether ordered outcomes still need to be consumed. */
  public boolean hasPending() {
    requireCoordinator();
    return !pending.isEmpty();
  }

  /**
   * Opens an operation-local restoration boundary so nested unrelated calls cannot consume it. The
   * synchronous caller closes the returned scope after this invocation's publication and cleanup.
   */
  public static InterruptionScope enterOperation() {
    InterruptionScope scope = new InterruptionScope(Thread.currentThread());
    INTERRUPT_SCOPES.get().push(scope);
    return scope;
  }

  /** Restores only this invocation's remembered interrupt after its publication and cleanup. */
  public static final class InterruptionScope implements AutoCloseable {
    private final Thread owner;
    private boolean interrupted;
    private boolean closed;

    /** Binds one restoration token to the synchronous caller thread. */
    private InterruptionScope(Thread owner) {
      this.owner = owner;
    }

    /**
     * Pops this operation's token on its opening thread after all inner scopes close, then restores
     * its interrupt. Throws {@link IllegalStateException} for cross-thread or out-of-order close.
     */
    @Override
    public void close() {
      if (Thread.currentThread() != owner)
        throw new IllegalStateException("Interruption scope belongs to its caller");
      if (closed) return;
      ArrayDeque<InterruptionScope> scopes = INTERRUPT_SCOPES.get();
      if (scopes.peek() != this)
        throw new IllegalStateException("Interruption scopes must close in invocation order");
      closed = true;
      scopes.pop();
      if (scopes.isEmpty()) INTERRUPT_SCOPES.remove();
      if (interrupted) owner.interrupt();
    }
  }

  /**
   * Attempts the next ordinal without waiting for credits or a free slot. A full window or a
   * temporarily exhausted budget returns false so the coordinator can consume an earlier result. A
   * cost that cannot fit even with no live work fails as POLICY.
   *
   * @throws ArchiveException if the cost cannot fit without any earlier work releasing credits
   */
  public boolean trySubmit(int ordinal, Cost cost, Worker<R> work) throws ArchiveException {
    requireCoordinator();
    ensureOpen();
    Objects.requireNonNull(cost, "cost");
    Objects.requireNonNull(work, "work");
    if (ordinal != nextSubmitted)
      throw new IllegalArgumentException("Work must be submitted in Logical Plan Order");
    if (admissionStopped.get() || pending.size() == capacity) return false;
    long heap;
    try {
      heap = Math.addExact(cost.heap(), RESULT_SLOT_HEAP_BYTES);
    } catch (ArithmeticException failure) {
      throw context.failure(FailureKind.POLICY, "io.resource-capacity", failure);
    }
    ResourceBudget.Lease lease;
    try {
      lease = budget.reserve(heap, cost.nativeBytes(), cost.handles(), cost.scratch());
    } catch (ArchiveException failure) {
      if (!pending.isEmpty()) return false;
      throw failure;
    }
    try {
      Future<Completion<R>> future = workers.submit(() -> execute(ordinal, lease, work));
      pending.addLast(new Slot<>(ordinal, lease, future));
      nextSubmitted++;
      return true;
    } catch (RuntimeException | Error failure) {
      lease.close();
      throw failure;
    }
  }

  /**
   * Waits for only the next admitted ordinal and transfers its result and credits to the
   * coordinator. If the caller thread is interrupted during the wait, settlement continues and the
   * public operation restores its interrupt status after publication and cleanup.
   */
  public Outcome<R> takeNext() {
    try {
      return takeNext(() -> {});
    } catch (IOException impossible) {
      throw new AssertionError("Empty coordinator checkpoint failed", impossible);
    }
  }

  /**
   * Samples caller-owned cancellation during a timed wait for the next plan ordinal. A failed
   * checkpoint stops all running work and leaves the pending slot for ordered close/drain.
   */
  public Outcome<R> takeNext(CoordinatorCheckpoint checkpoint) throws IOException {
    requireCoordinator();
    ensureOpen();
    Objects.requireNonNull(checkpoint, "checkpoint");
    Slot<R> slot = pending.peekFirst();
    if (slot == null) throw new IllegalStateException("No admitted work remains");
    if (slot.ordinal != nextConsumed)
      throw new IllegalStateException("Work outcomes lost Logical Plan Order");
    Completion<R> completion;
    try {
      completion = await(slot.ordinal, slot.future, checkpoint);
    } catch (IOException | RuntimeException | Error failure) {
      // A native worker can finish while caller cancellation is being accepted; forced stop
      // discards that uncommitted completion when close drains the pending ordered slot.
      stop();
      throw failure;
    }
    pending.removeFirst();
    nextConsumed++;
    boolean skip = forcedStop.get() || slot.ordinal > earliestFailure.get();
    return new Outcome<>(slot.ordinal, completion, slot.lease, skip, session);
  }

  /** Explicitly stops admission and asks every running item to stop at its next checkpoint. */
  public void stop() {
    requireCoordinator();
    admissionStopped.set(true);
    forcedStop.set(true);
  }

  /**
   * Stops admission, drains unconsumed results, and joins all owned workers without interruption.
   * Consumed outcomes remain coordinator-owned and must be closed after their staged resources.
   *
   * <p>Each drained failure, of unconsumed work or of closing a discarded result, is accepted
   * straight into the operation session, so none is lost when the caller is already unwinding
   * another outcome and suppresses whatever closing throws.
   *
   * @throws OperationStopped once every worker has joined, if a drained failure was accepted
   * @throws Error a fatal worker or cleanup error, which never reaches the session
   */
  @Override
  public void close() throws IOException {
    requireCoordinator();
    if (closed) return;
    closed = true;
    admissionStopped.set(true);
    workers.shutdown();
    boolean accepted = false;
    while (!pending.isEmpty()) {
      Slot<R> slot = pending.removeFirst();
      Completion<R> completion = await(slot.ordinal, slot.future);
      if (!forcedStop.get()
          && slot.ordinal <= earliestFailure.get()
          && completion.failure != null) {
        accepted |= settle(locate.apply(slot.ordinal), completion.failure);
      }
      // A stopped worker's cleanup evidence goes in first, then the discarded result's own.
      accepted |= !completion.stopCleanup.isEmpty();
      try {
        new Outcome<>(slot.ordinal, completion, slot.lease, true, session).close();
      } catch (Throwable cleanup) {
        accepted |= settle(locate.apply(slot.ordinal).at(OperationPhase.CLEANUP), cleanup);
      }
    }
    try {
      while (true) {
        try {
          if (workers.awaitTermination(1, TimeUnit.DAYS)) break;
        } catch (InterruptedException ignored) {
          // Joining is noninterrupting; restore the coordinator's status after all workers settle.
          interrupted = true;
        }
      }
    } finally {
      if (interrupted) {
        ArrayDeque<InterruptionScope> scopes = INTERRUPT_SCOPES.get();
        if (scopes.isEmpty()) {
          INTERRUPT_SCOPES.remove();
          coordinator.interrupt();
        } else scopes.peek().interrupted = true;
      }
    }
    Error fatal = fatalFailure.get();
    if (fatal != null) throw fatal;
    // The session holds what was drained; stop the caller as any other session failure does.
    if (accepted) session.stopIfFailed();
  }

  /**
   * Structures one drained failure at its location and accepts it into the session. A fatal or
   * other propagating error is kept for {@link #close} to throw once every worker has joined.
   *
   * @return whether a failure was accepted
   */
  private boolean settle(IoContext location, Throwable failure) {
    try {
      session.accept(location.structure(failure));
      return true;
    } catch (StopSignal stopped) {
      // A signal, not a failure: a stop carries no evidence of its own.
      return false;
    } catch (Error propagating) {
      fatalFailure.compareAndSet(null, propagating);
      return false;
    }
  }

  /** Runs a worker without observer callbacks and records the earliest failing plan ordinal. */
  private Completion<R> execute(int ordinal, ResourceBudget.Lease lease, Worker<R> work) {
    Checkpoint checkpoint = () -> checkStop(ordinal);
    try {
      checkpoint.check();
      R result = Objects.requireNonNull(work.run(checkpoint, lease), "worker result");
      return new Completion<>(result, null, false, List.of());
    } catch (WorkerStopped stopped) {
      // Later results are discarded after failure or explicit stop, with no new public failure.
      // The cleanup evidence the worker attached to its stop is still this runner's to settle.
      List<ArchiveException> cleanup = new ArrayList<>();
      for (Throwable suppressed : stopped.getSuppressed())
        if (suppressed instanceof ArchiveException failure) cleanup.add(failure);
      return new Completion<>(null, null, true, List.copyOf(cleanup));
    } catch (VirtualMachineError | ThreadDeath fatal) {
      fatalFailure.compareAndSet(null, fatal);
      markFailure(ordinal);
      return new Completion<>(null, fatal, false, List.of());
    } catch (Throwable failure) {
      markFailure(ordinal);
      return new Completion<>(null, failure, false, List.of());
    }
  }

  /**
   * Keeps earlier work alive so a later failure cannot hide an earlier Primary Failure. Each stop
   * throws a fresh signal, so the cleanup a worker attaches to it belongs to that work alone.
   */
  private void checkStop(int ordinal) throws WorkerStopped {
    if (forcedStop.get() || ordinal > earliestFailure.get()) throw new WorkerStopped();
  }

  /** Atomically lowers the failure candidate before any later task can be admitted. */
  private void markFailure(int ordinal) {
    earliestFailure.accumulateAndGet(ordinal, Math::min);
    admissionStopped.set(true);
  }

  /** Waits for a settled future while remembering interruption for final restoration. */
  private Completion<R> await(int ordinal, Future<Completion<R>> future) {
    while (true) {
      try {
        return future.get();
      } catch (InterruptedException ignored) {
        // Interruption is not Cooperative Cancellation and cannot abandon owned worker resources.
        interrupted = true;
      } catch (java.util.concurrent.ExecutionException failure) {
        return escaped(ordinal, failure.getCause());
      } catch (java.util.concurrent.CancellationException failure) {
        return escaped(ordinal, failure);
      }
    }
  }

  /** Periodically returns control to the coordinator so cancellation does not wait for a worker. */
  private Completion<R> await(
      int ordinal, Future<Completion<R>> future, CoordinatorCheckpoint checkpoint)
      throws IOException {
    while (true) {
      checkpoint.check();
      try {
        return future.get(50, TimeUnit.MILLISECONDS);
      } catch (InterruptedException ignored) {
        // The final close restores the caller's interrupt status after noninterrupting settlement.
        interrupted = true;
      } catch (TimeoutException ignored) {
        // A pending earlier ordinal still requires caller-thread cancellation observation.
      } catch (java.util.concurrent.ExecutionException failure) {
        return escaped(ordinal, failure.getCause());
      } catch (java.util.concurrent.CancellationException failure) {
        return escaped(ordinal, failure);
      }
    }
  }

  /** Preserves even a failure that escaped the worker wrapper as an ordered outcome. */
  private Completion<R> escaped(int ordinal, Throwable failure) {
    if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath)
      fatalFailure.compareAndSet(null, (Error) failure);
    markFailure(ordinal);
    return new Completion<>(null, failure, false, List.of());
  }

  /** Enforces the synchronous caller-thread coordinator contract without a library lock. */
  private void requireCoordinator() {
    if (Thread.currentThread() != coordinator)
      throw new IllegalStateException("Runner methods belong to the operation coordinator");
  }

  /** Rejects use after the operation's worker lifetime has settled. */
  private void ensureOpen() {
    if (closed) throw new IllegalStateException("Runner is closed");
  }

  /** The coordinator-owned container for one admitted task and its preadmitted credits. */
  private record Slot<R extends AutoCloseable>(
      int ordinal, ResourceBudget.Lease lease, Future<Completion<R>> future) {}

  /**
   * Worker-published private state; no observer or destination effect crosses this boundary.
   *
   * @param stopCleanup the cleanup failures a stopped worker attached to its stop, in close order
   */
  private record Completion<R extends AutoCloseable>(
      R result, Throwable failure, boolean stopped, List<ArchiveException> stopCleanup) {}

  /** One ordered result; closing it releases the result before its admission credits. */
  public static final class Outcome<R extends AutoCloseable> implements AutoCloseable {
    private final int ordinal;
    private final Completion<R> completion;
    private final ResourceBudget.Lease lease;
    private final boolean skipped;
    private final OperationSession session;
    private boolean closed;

    /**
     * Adopts a completed worker result and its resource lease on the coordinator thread.
     *
     * @param session takes a stopped worker's cleanup evidence when this outcome closes
     */
    private Outcome(
        int ordinal,
        Completion<R> completion,
        ResourceBudget.Lease lease,
        boolean skipped,
        OperationSession session) {
      this.ordinal = ordinal;
      this.completion = completion;
      this.lease = lease;
      this.skipped = skipped || completion.stopped;
      this.session = session;
    }

    /** Returns the assigned Logical Plan Order ordinal. */
    public int ordinal() {
      return ordinal;
    }

    /** Returns a successful private result, or null for a failed or discarded item. */
    public R result() {
      return skipped ? null : completion.result;
    }

    /** Returns a worker failure, or null for successful or discarded work. */
    public Throwable failure() {
      return skipped ? null : completion.failure;
    }

    /** Reports work discarded by a noninterrupting stop checkpoint. */
    public boolean skipped() {
      return skipped;
    }

    /**
     * Settles a stopped worker's cleanup evidence into the session, then closes any produced
     * private result before releasing its preadmitted credits.
     */
    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
      // The session's normal selection decides Primary vs Secondary; workers never call it.
      completion.stopCleanup.forEach(session::accept);
      try {
        if (completion.result != null) completion.result.close();
      } catch (IOException failure) {
        throw failure;
      } catch (RuntimeException | Error failure) {
        throw failure;
      } catch (Exception failure) {
        throw new IOException(failure);
      } finally {
        lease.close();
      }
    }
  }
}
