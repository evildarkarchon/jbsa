package io.github.evildarkarchon.jbsa;

/** Intrinsic diagnostic severity, unchanged by caller rejection policy. */
public enum DiagnosticSeverity {
  /** A retained warning, including a warning rejected by policy. */
  WARNING,
  /** An encoded-data or operational error. */
  ERROR
}
