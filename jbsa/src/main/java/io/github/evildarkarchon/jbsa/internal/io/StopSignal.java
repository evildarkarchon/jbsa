package io.github.evildarkarchon.jbsa.internal.io;

import java.io.IOException;

/**
 * A stop: work ended because something else already failed, so it carries no evidence and is never
 * a failure. {@link OperationStopped} stops a coordinator once the operation session has failed;
 * {@link WorkerStopped} stops a private worker once the ordered worker runner discards its work.
 *
 * <p>Both are checked {@link IOException}s so they cross the {@code throws IOException} writer,
 * reader and codec interfaces without changing them. {@link IoContext}'s structuring entry rethrows
 * either unchanged, so a catch site that hands its throwable over never has to judge whether a stop
 * can reach it (ADR-0006). Each signal is created fresh on every throw, so cleanup evidence
 * attached to one with {@link #addSuppressed} belongs to that stop alone.
 */
abstract sealed class StopSignal extends IOException permits OperationStopped, WorkerStopped {
  private static final long serialVersionUID = 1L;

  /** Creates a signal with a fixed explanation and no cause. */
  StopSignal(String message) {
    super(message);
  }
}
