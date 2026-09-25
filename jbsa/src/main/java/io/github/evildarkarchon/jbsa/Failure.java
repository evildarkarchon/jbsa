package io.github.evildarkarchon.jbsa;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Immutable semantic failure facts; provider-specific detail remains only in the cause.
 *
 * @param kind recovery-oriented failure category
 * @param phase phase in which the failure occurred
 * @param ordinal optional logical input or artifact ordinal
 * @param diagnosticIdentifier optional diagnostic explaining this failure
 * @param location optional structured evidence location
 * @param cause optional underlying throwable; its identity is preserved
 */
public record Failure(
    FailureKind kind,
    OperationPhase phase,
    OptionalLong ordinal,
    Optional<String> diagnosticIdentifier,
    Optional<DiagnosticLocation> location,
    Optional<Throwable> cause) {
  /** Checks required members and rejects negative ordinals or blank diagnostic identifiers. */
  public Failure {
    Objects.requireNonNull(kind, "kind");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(ordinal, "ordinal");
    Objects.requireNonNull(diagnosticIdentifier, "diagnosticIdentifier");
    Objects.requireNonNull(location, "location");
    Objects.requireNonNull(cause, "cause");
    if (ordinal.isPresent() && ordinal.getAsLong() < 0) {
      throw new IllegalArgumentException("Failure ordinal must be nonnegative");
    }
    if (diagnosticIdentifier.isPresent() && diagnosticIdentifier.orElseThrow().isBlank()) {
      throw new IllegalArgumentException("Diagnostic identifier must not be blank");
    }
  }
}
