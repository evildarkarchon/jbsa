package io.github.evildarkarchon.jbsa.internal.codec;

import java.io.IOException;

/**
 * Gives the operation coordinator a cancellation boundary for each bounded codec or window step. It
 * is distinct from {@code OrderedWorkerRunner.Checkpoint}, which a transform-stage worker receives
 * and adapts to this type.
 */
@FunctionalInterface
public interface Checkpoint {
  /**
   * Checks operation cancellation without retaining codec state; throws when the operation must
   * stop.
   */
  void check() throws IOException;
}
