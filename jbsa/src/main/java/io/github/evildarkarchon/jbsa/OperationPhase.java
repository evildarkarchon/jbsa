package io.github.evildarkarchon.jbsa;

/** Stable operation phases in their semantic order. */
public enum OperationPhase {
  /** Validate configuration and plan work before ordinary payload processing. */
  PREFLIGHT,
  /** Process logical payloads. */
  PROCESSING,
  /** Commit staged outputs. */
  PUBLISHING,
  /** Settle resource and artifact ownership. */
  CLEANUP
}
