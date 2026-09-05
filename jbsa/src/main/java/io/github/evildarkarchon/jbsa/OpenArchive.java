package io.github.evildarkarchon.jbsa;

import java.io.IOException;

/**
 * Caller-owned archive capability, owning its input, validated index, and outstanding child
 * channels.
 *
 * <p>The parent must outlive every child. Closing is idempotent and linearizable: it prevents new
 * content channels, invalidates and closes existing children, and closes the shared backing handle.
 * Detached metadata remains usable afterward. This is a returned capability, not a storage adapter;
 * no public operation accepts caller implementations. The backing implementation follows in #34.
 */
public interface OpenArchive extends AutoCloseable {
  /** Returns the immutable structural inspection, also usable after this archive closes. */
  ArchiveInspection inspection();

  /** Returns the validated number of entries, without narrowing to a Java collection's size. */
  long entryCount();

  /**
   * Returns the entry capability at its zero-based decoded archive-order ordinal, repeatedly.
   *
   * @throws IndexOutOfBoundsException if the ordinal is outside the validated index
   * @throws IllegalStateException if this archive is closed
   */
  ArchiveEntry entry(long ordinal);

  /**
   * Closes the input and all outstanding children exactly once, including under concurrent close.
   *
   * @throws IOException if owned resource cleanup fails
   */
  @Override
  void close() throws IOException;
}
