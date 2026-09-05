package io.github.evildarkarchon.jbsa;

import java.io.Serial;
import java.util.List;
import java.util.Optional;

/** The sole specialized operational exception: cooperative cancellation was accepted. */
public final class ArchiveCancelledException extends ArchiveException {
  @Serial private static final long serialVersionUID = 1L;

  /**
   * Creates a cancellation outcome carrying the same evidence as every operational failure.
   *
   * @param message optional human explanation
   * @param primaryFailure primary failure whose kind must be CANCELLED
   * @param diagnostics retained diagnostics in deterministic logical order
   * @param artifacts affected artifacts in deterministic logical order
   * @param assessment latest established assessment, if any
   * @param secondaryFailures bounded secondary failures in deterministic selection order
   * @throws IllegalArgumentException if the primary kind is not CANCELLED
   */
  public ArchiveCancelledException(
      String message,
      Failure primaryFailure,
      List<Diagnostic> diagnostics,
      List<Artifact> artifacts,
      Optional<ArchiveAssessment> assessment,
      List<Failure> secondaryFailures) {
    super(message, primaryFailure, diagnostics, artifacts, assessment, secondaryFailures);
    if (primaryFailure.kind() != FailureKind.CANCELLED) {
      throw new IllegalArgumentException("Cancellation must have CANCELLED failure kind");
    }
  }
}
