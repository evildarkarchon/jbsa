package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.ResourceLimits;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import java.math.BigInteger;

/**
 * One pack's running charge against {@code maxDecodedBytes}, made in Logical Plan Order (Q14).
 * Admission charges every declared source size, unless the Emission declares a {@link
 * Admitted.Chunker}; then stabilization charges each entry instead, because only its envelope knows
 * the decoded extent. Only the coordinator charges, so it is not thread-safe.
 */
final class DecodedLimit {
  private final long ceiling;
  private long charged;

  /** Starts an empty charge against the request's {@code maxDecodedBytes}. */
  DecodedLimit(ResourceLimits limits) {
    this.ceiling = limits.maxDecodedBytes();
  }

  /**
   * Charges one entry's decoded bytes, leaving the total unchanged when the charge fails.
   *
   * @param bytes the entry's decoded size; never negative
   * @param location where a failure is reported: the pack's destination during admission, or the
   *     entry's PROCESSING location during stabilization
   * @throws ArchiveException POLICY {@code operation.resource-limit} with the exact decimal total
   *     when the charge would exceed the ceiling
   */
  void charge(long bytes, IoContext location) throws ArchiveException {
    if (bytes > ceiling - charged)
      throw location.limit(
          "maxDecodedBytes",
          ceiling,
          BigInteger.valueOf(charged).add(BigInteger.valueOf(bytes)).toString());
    charged += bytes;
  }
}
