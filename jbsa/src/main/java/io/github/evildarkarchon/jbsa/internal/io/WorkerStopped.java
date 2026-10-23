package io.github.evildarkarchon.jbsa.internal.io;

/**
 * The ordered worker runner's stop signal: a private worker's work is discarded after an earlier
 * failure or an explicit coordinator stop. It carries no evidence of its own. A worker catch site
 * that closed resources while it unwound attaches each cleanup failure with {@link #addSuppressed};
 * the runner settles that evidence into the operation session (ADR-0006).
 */
final class WorkerStopped extends StopSignal {
  private static final long serialVersionUID = 1L;

  /** Creates the signal; a fresh one is thrown by every stopped checkpoint. */
  WorkerStopped() {
    super("The worker stopped; its runner discards this work");
  }
}
