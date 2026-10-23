package io.github.evildarkarchon.jbsa.internal.io;

/**
 * The operation session's stop signal: the operation has failed, and the session already holds
 * every record of that failure. It carries no evidence and is not a failure. Code unwinding it must
 * let it pass unchanged to the operation template, which settles the outcome the session holds.
 *
 * <p>It is a {@link StopSignal}, so a catch site that hands its throwable to {@link IoContext}'s
 * structuring entry gets it back unchanged; no site needs a rethrow clause of its own. Wrapping it
 * as a failure's cause is a JBSA bug, which {@link IoContext}'s raw constructor rejects. Only this
 * package raises it, and only after the session has failed.
 */
public final class OperationStopped extends StopSignal {
  private static final long serialVersionUID = 1L;

  /** Creates the signal; the session holds the failure it stops for. */
  OperationStopped() {
    super("The operation stopped; its operation session holds the failure");
  }
}
