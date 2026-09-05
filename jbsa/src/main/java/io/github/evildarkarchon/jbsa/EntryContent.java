package io.github.evildarkarchon.jbsa;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.util.Optional;

/**
 * Sequential entry bytes with a detached, payload-scoped assessment only after normal terminal EOF.
 *
 * <p>A late decoding or exact-size failure throws a FORMAT {@link ArchiveException}, closes this
 * child, and cannot revoke bytes already delivered. Its parent stays usable unless shared state was
 * invalidated. A read racing parent close throws {@code AsynchronousCloseException}; subsequent
 * reads throw {@code ClosedChannelException}. Direct caller interruption propagates {@code
 * ClosedByInterruptException}, closes shared input, and invalidates siblings. These lifetime
 * failures are not diagnostics or Cooperative Cancellation.
 */
public interface EntryContent extends ReadableByteChannel {
  /**
   * Returns empty until normal terminal EOF; early caller close never produces an assessment. A
   * completed assessment is immutable and remains available after close.
   */
  Optional<ArchiveAssessment> assessment();

  /**
   * Reads canonical bytes into caller-owned storage, with the standard channel count and EOF rules.
   * The inherited {@code int} transfer count is bounded by the supplied buffer, not the entry size.
   *
   * @throws IOException on structured operational or channel lifetime failure
   */
  @Override
  int read(ByteBuffer destination) throws IOException;

  /**
   * Closes only this child, idempotently, without establishing an assessment for incomplete
   * content.
   *
   * @throws IOException if child resource cleanup fails
   */
  @Override
  void close() throws IOException;
}
