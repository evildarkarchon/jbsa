package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.WorkerSelection;
import java.util.Objects;

/** Resolves host-derived worker selection once at the start of a synchronous operation. */
public final class WorkerLimits {
  private WorkerLimits() {}

  /** Returns an explicit upper bound so later preflight cannot resample processor availability. */
  public static WorkerSelection.UpTo snapshot(WorkerSelection selection) {
    Objects.requireNonNull(selection, "selection");
    return selection instanceof WorkerSelection.UpTo explicit
        ? explicit
        : new WorkerSelection.UpTo(Math.max(1, Runtime.getRuntime().availableProcessors()));
  }

  /**
   * Keeps the direct streaming path when a tight scratch ceiling cannot conservatively retain the
   * stable raw and transformed bytes, a private result, and the staged archive at the same time.
   */
  public static boolean hasParallelScratchHeadroom(long plannedSourceBytes, long scratchLimit) {
    if (plannedSourceBytes < 0 || scratchLimit < 0)
      throw new IllegalArgumentException("Negative scratch estimate");
    // Six source extents cover raw/transformed stabilization, the private worker result, and
    // publication staging even when an encoded payload is larger than its original bytes.
    return plannedSourceBytes <= scratchLimit / 6;
  }
}
