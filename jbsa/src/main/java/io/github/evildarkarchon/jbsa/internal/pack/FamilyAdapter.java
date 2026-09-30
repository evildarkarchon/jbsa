package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.PackRequest;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;

/**
 * Pure wire knowledge for one Archive Family, driven by {@link PackPipeline}. Implementations are
 * stateless singletons: every request-derived fact lives in the returned {@link Admitted} value,
 * and no adapter method performs I/O, owns resources, or observes cancellation.
 *
 * @param <K> the family's per-entry wire key, such as an encoded name and its hash
 */
public interface FamilyAdapter<K> {
  /**
   * Applies the family's request-level admission rules in the family's own order, before any source
   * is discovered.
   *
   * @param context the operation's PREFLIGHT location for structured failures
   * @return immutable request-derived state for the remaining pipeline steps
   * @throws ArchiveException the first family rule the request breaks
   */
  Admitted<K> admit(PackRequest request, IoContext context) throws ArchiveException;
}
