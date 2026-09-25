package io.github.evildarkarchon.jbsa;

/** Destination collision policy for the complete intended publication set. */
public enum TargetPolicy {
  /** Reject every pre-existing target during output-set preflight; the safe library default. */
  FAIL,
  /**
   * Back up each predecessor privately before installing its replacement, with defined rollback.
   */
  REPLACE
}
