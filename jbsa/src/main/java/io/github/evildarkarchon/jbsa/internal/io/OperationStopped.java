package io.github.evildarkarchon.jbsa.internal.io;

import java.io.IOException;

/**
 * The operation session's stop signal: the operation has failed, and the session already holds
 * every record of that failure. It carries no evidence and is not a failure. Code unwinding it must
 * let it pass unchanged to the operation template, which settles the outcome the session holds.
 * Wrapping it as a failure's cause is a JBSA bug, which {@link IoContext} rejects.
 *
 * <p>It is a checked {@link IOException} so it crosses the {@code throws IOException} writer,
 * reader and codec interfaces between a session call and the template without changing them. Every
 * {@code catch (IOException)} on such a path must rethrow it before structuring other failures.
 * Only this package raises it, and only after the session has failed.
 */
public final class OperationStopped extends IOException {
  private static final long serialVersionUID = 1L;

  /** Creates the signal; the session holds the failure it stops for. */
  OperationStopped() {
    super("The operation stopped; its operation session holds the failure");
  }
}
