package io.github.evildarkarchon.jbsa;

import java.util.Objects;
import java.util.OptionalLong;

/**
 * Advisory progress evidence; method return or exception remains the authoritative terminal signal.
 *
 * @param operation mutating operation being observed
 * @param phase stable phase
 * @param metric exactly one logical metric
 * @param completed monotonic completed units for this phase and metric
 * @param total total units when known, fixed once present
 */
public record ProgressSnapshot(
    Operation operation,
    OperationPhase phase,
    ProgressMetric metric,
    long completed,
    OptionalLong total) {
  /** Rejects query operations, negative counters, and completion beyond a known total. */
  public ProgressSnapshot {
    Objects.requireNonNull(operation, "operation");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(metric, "metric");
    Objects.requireNonNull(total, "total");
    if (operation != Operation.EXTRACT && operation != Operation.PACK) {
      throw new IllegalArgumentException("Only extract and pack report progress");
    }
    if (completed < 0 || (total.isPresent() && total.getAsLong() < completed)) {
      throw new IllegalArgumentException("Progress requires 0 <= completed <= known total");
    }
  }
}
