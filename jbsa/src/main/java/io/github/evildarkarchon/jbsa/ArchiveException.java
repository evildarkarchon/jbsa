package io.github.evildarkarchon.jbsa;

import java.io.IOException;
import java.io.Serial;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Checked operational non-success with immutable semantic evidence. Secondary causes remain on
 * their structured failures and are not copied into Java's suppressed-exception list.
 *
 * <p>The semantic records are detached, but retained throwables preserve provider identity and are
 * not promised to be immutable or Java-serializable.
 */
@SuppressWarnings("serial")
public sealed class ArchiveException extends IOException permits ArchiveCancelledException {
  @Serial private static final long serialVersionUID = 1L;
  private final Failure primaryFailure;
  private final List<Diagnostic> diagnostics;
  private final List<Artifact> artifacts;
  private final Optional<ArchiveAssessment> assessment;
  private final List<Failure> secondaryFailures;

  /**
   * Creates an operational failure and preserves the primary cause.
   *
   * @param message optional human explanation, outside conformance comparison
   * @param primaryFailure selected primary failure
   * @param diagnostics retained diagnostics in deterministic logical order
   * @param artifacts affected artifacts in deterministic logical order
   * @param assessment latest assessment established before failure, if any
   * @param secondaryFailures bounded additional failures in deterministic selection order
   * @throws IllegalArgumentException if cancellation is represented without its dedicated subtype
   */
  public ArchiveException(
      String message,
      Failure primaryFailure,
      List<Diagnostic> diagnostics,
      List<Artifact> artifacts,
      Optional<ArchiveAssessment> assessment,
      List<Failure> secondaryFailures) {
    super(message, Objects.requireNonNull(primaryFailure, "primaryFailure").cause().orElse(null));
    if (primaryFailure.kind() == FailureKind.CANCELLED
        && !(this instanceof ArchiveCancelledException)) {
      throw new IllegalArgumentException("Cancellation requires ArchiveCancelledException");
    }
    this.primaryFailure = primaryFailure;
    this.diagnostics = List.copyOf(diagnostics);
    this.artifacts = List.copyOf(artifacts);
    this.assessment = Objects.requireNonNull(assessment, "assessment");
    this.secondaryFailures = List.copyOf(secondaryFailures);
  }

  /** Returns the primary failure's stable recovery category. */
  public final FailureKind kind() {
    return primaryFailure.kind();
  }

  /** Returns the selected primary failure. */
  public final Failure primaryFailure() {
    return primaryFailure;
  }

  /** Returns retained warnings and errors in deterministic logical order. */
  public final List<Diagnostic> diagnostics() {
    return diagnostics;
  }

  /** Returns affected artifacts in deterministic logical order. */
  public final List<Artifact> artifacts() {
    return artifacts;
  }

  /** Returns the latest established assessment without implying validation beyond its extent. */
  public final Optional<ArchiveAssessment> assessment() {
    return assessment;
  }

  /**
   * Returns bounded secondary failures; their causes are not duplicated as suppressed exceptions.
   */
  public final List<Failure> secondaryFailures() {
    return secondaryFailures;
  }
}
