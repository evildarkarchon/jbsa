package io.github.evildarkarchon.jbsa;

import java.io.IOException;

/** An entry capability tied to its parent archive, distinct from detached entry metadata. */
public interface ArchiveEntry {
  /** Returns immutable metadata that remains usable after parent and child channels close. */
  EntryMetadata metadata();

  /**
   * Opens a fresh, sequential, caller-closed channel of canonical uncompressed entry bytes.
   *
   * <p>The parent must remain open. Each channel has its own position; closing it affects only that
   * child. Seeking, persistent decoded caching, and revocation of already returned bytes are not
   * promised.
   *
   * @throws ArchiveException on payload, policy, or capability non-success
   * @throws java.nio.channels.ClosedChannelException if the parent has closed
   * @throws IOException on a channel lifetime failure
   */
  EntryContent openContent() throws IOException;
}
