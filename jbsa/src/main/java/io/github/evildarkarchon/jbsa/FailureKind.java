package io.github.evildarkarchon.jbsa;

/** Stable recovery-oriented categories for operational non-success. */
public enum FailureKind {
  /** Encoded data is corrupt, contradictory, truncated, or invalid. */
  FORMAT,
  /** Recognized semantics are outside the supported archive contract. */
  UNSUPPORTED,
  /** Supported semantics are unavailable with the current platform or providers. */
  CAPABILITY,
  /** Caller limits, safety rules, or warning policy reject the operation. */
  POLICY,
  /** Input shape, I/O, mutation, or regenerated content failed. */
  SOURCE,
  /** Destination writing, publication, rollback, or cleanup failed. */
  DESTINATION,
  /** A progress observer threw. */
  OBSERVER,
  /** An invariant or unexpected provider failure occurred. */
  INTERNAL,
  /** Cooperative cancellation was accepted before the applicable commit. */
  CANCELLED
}
