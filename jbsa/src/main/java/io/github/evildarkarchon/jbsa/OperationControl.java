package io.github.evildarkarchon.jbsa;

import java.util.Objects;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/**
 * Immutable callback references for mutation progress and cooperative cancellation.
 *
 * <p>Progress delivery is serial and outside library locks; thread identity and cadence are not
 * guarantees. Observers must not re-enter the same invocation, and a thrown observer terminates
 * delivery. Cancellation is explicit cooperative state, not thread interruption. Callback objects
 * may carry caller-owned state and must be safe for the threads on which the operation uses them.
 *
 * @param observer consumer of advisory progress snapshots
 * @param cancellationRequested source reporting whether cancellation has been requested
 */
public record OperationControl(
    Consumer<ProgressSnapshot> observer, BooleanSupplier cancellationRequested) {
  /** Requires both callback references; request validation never invokes them. */
  public OperationControl {
    Objects.requireNonNull(observer, "observer");
    Objects.requireNonNull(cancellationRequested, "cancellationRequested");
  }

  /** Returns no-op progress observation and a never-cancelled source. */
  public static OperationControl standard() {
    return new OperationControl(
        snapshot -> {
          // Default observation intentionally has no external side effects.
        },
        () -> false);
  }
}
