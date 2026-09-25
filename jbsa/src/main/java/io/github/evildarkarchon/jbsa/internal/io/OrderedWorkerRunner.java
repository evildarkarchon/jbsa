package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.WorkerSelection;
import java.io.IOException;
import java.util.ArrayDeque;
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

/**
 * Operation-owned fixed platform workers beneath one synchronous, ordered coordinator.
 *
 * <p>The coordinator alone submits and consumes work. Workers receive only a noninterrupting stop
 * checkpoint and a preadmitted resource lease; they cannot deliver progress or publish output.
 * Every result owns its credits until the coordinator closes its {@link Outcome}.
 */
public final class OrderedWorkerRunner<R extends AutoCloseable> implements AutoCloseable {
  private static final AtomicLong NEXT_OPERATION_ID = new AtomicLong();
  private static final int MAX_PLATFORM_WORKERS = 64;
  private static final long RESULT_SLOT_HEAP_BYTES = 1024;
  private static final ThreadLocal<ArrayDeque<InterruptionScope>> INTERRUPT_SCOPES =
      ThreadLocal.withInitial(ArrayDeque::new);

  private final Thread coordinator = Thread.currentThread();
  private final ResourceBudget budget;
  private final IoContext context;
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
   */
  public OrderedWorkerRunner(
      WorkerSelection selection, int plannedCount, ResourceBudget budget, IoContext context) {
    Objects.requireNonNull(selection, "selection");
    if (plannedCount < 0) throw new IllegalArgumentException("plannedCount must be nonnegative");
    this.budget = Objects.requireNonNull(budget, "budget");
    this.context = Objects.requireNonNull(context, "context");
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
    return new Outcome<>(slot.ordinal, completion, slot.lease, skip);
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
   */
  @Override
  public void close() throws IOException {
    requireCoordinator();
    if (closed) return;
    closed = true;
    admissionStopped.set(true);
    workers.shutdown();
    FailureRetention failures = new FailureRetention(budget.limits(), context.operation());
    while (!pending.isEmpty()) {
      Slot<R> slot = pending.removeFirst();
      Completion<R> completion = await(slot.ordinal, slot.future);
      if (!forcedStop.get() && slot.ordinal <= earliestFailure.get() && completion.failure != null)
        failures.accept(structured(slot.ordinal, completion.failure));
      try {
        new Outcome<>(slot.ordinal, completion, slot.lease, true).close();
      } catch (VirtualMachineError | ThreadDeath fatal) {
        fatalFailure.compareAndSet(null, fatal);
      } catch (Throwable cleanup) {
        failures.accept(cleanupFailure(slot.ordinal, cleanup));
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
    ArchiveException settled = failures.finish(List.of());
    if (fatal != null) {
      if (settled != null) fatal.addSuppressed(settled);
      throw fatal;
    }
    if (settled != null) throw settled;
  }

  /** Runs a worker without observer callbacks and records the earliest failing plan ordinal. */
  private Completion<R> execute(int ordinal, ResourceBudget.Lease lease, Worker<R> work) {
    Checkpoint checkpoint = () -> checkStop(ordinal);
    try {
      checkpoint.check();
      R result = Objects.requireNonNull(work.run(checkpoint, lease), "worker result");
      return new Completion<>(result, null, false);
    } catch (StoppedWork ignored) {
      // Later results are discarded after failure or explicit stop, with no new public failure.
      return new Completion<>(null, null, true);
    } catch (VirtualMachineError | ThreadDeath fatal) {
      fatalFailure.compareAndSet(null, fatal);
      markFailure(ordinal);
      return new Completion<>(null, fatal, false);
    } catch (Throwable failure) {
      markFailure(ordinal);
      return new Completion<>(null, failure, false);
    }
  }

  /** Keeps earlier work alive so a later failure cannot hide an earlier Primary Failure. */
  private void checkStop(int ordinal) throws StoppedWork {
    if (forcedStop.get() || ordinal > earliestFailure.get()) throw new StoppedWork();
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
    return new Completion<>(null, failure, false);
  }

  /** Gives cleanup and unexpected worker exceptions a stable operation-owned failure location. */
  private ArchiveException structured(int ordinal, Throwable failure) {
    if (failure instanceof ArchiveException archive) return archive;
    IoContext location =
        new IoContext(
            context.path(),
            context.operation(),
            io.github.evildarkarchon.jbsa.OperationPhase.PROCESSING,
            java.util.OptionalLong.of(ordinal));
    return failure instanceof IOException
        ? location.failure(FailureKind.SOURCE, "operation.source-io", failure)
        : location.failure(FailureKind.INTERNAL, "operation.internal-failure", failure);
  }

  /** Locates a discarded result's cleanup fault after all processing outcomes. */
  private ArchiveException cleanupFailure(int ordinal, Throwable failure) {
    if (failure instanceof ArchiveException archive) return archive;
    IoContext location =
        new IoContext(
            context.path(),
            context.operation(),
            io.github.evildarkarchon.jbsa.OperationPhase.CLEANUP,
            java.util.OptionalLong.of(ordinal));
    return location.failure(FailureKind.DESTINATION, "operation.destination-io", failure);
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

  /** Worker-published private state; no observer or destination effect crosses this boundary. */
  private record Completion<R extends AutoCloseable>(
      R result, Throwable failure, boolean stopped) {}

  /**
   * Internal checked checkpoint signal that codecs propagate without classifying a provider fault.
   */
  private static final class StoppedWork extends IOException {
    private static final long serialVersionUID = 1L;
  }

  /** One ordered result; closing it releases the result before its admission credits. */
  public static final class Outcome<R extends AutoCloseable> implements AutoCloseable {
    private final int ordinal;
    private final Completion<R> completion;
    private final ResourceBudget.Lease lease;
    private final boolean skipped;
    private boolean closed;

    /** Adopts a completed worker result and its resource lease on the coordinator thread. */
    private Outcome(
        int ordinal, Completion<R> completion, ResourceBudget.Lease lease, boolean skipped) {
      this.ordinal = ordinal;
      this.completion = completion;
      this.lease = lease;
      this.skipped = skipped || completion.stopped;
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

    /** Closes any produced private result before releasing its preadmitted credits. */
    @Override
    public void close() throws IOException {
      if (closed) return;
      closed = true;
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
