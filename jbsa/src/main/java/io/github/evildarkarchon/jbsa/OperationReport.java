package io.github.evildarkarchon.jbsa;

import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * A successful mutating operation's detached completion record; non-success uses ArchiveException.
 *
 * @param operation completed extract or pack operation
 * @param artifacts settled affected artifacts in deterministic logical order
 * @param diagnostics retained diagnostics in deterministic logical order
 * @param assessment latest established archive assessment, if applicable
 */
public record OperationReport(
    Operation operation,
    List<Artifact> artifacts,
    List<Diagnostic> diagnostics,
    Optional<ArchiveAssessment> assessment) {
  /** Copies all records and rejects query operations, which return their own domain values. */
  public OperationReport {
    Objects.requireNonNull(operation, "operation");
    if (operation != Operation.EXTRACT && operation != Operation.PACK) {
      throw new IllegalArgumentException("Only extract and pack return operation reports");
    }
    artifacts = List.copyOf(artifacts);
    diagnostics = List.copyOf(diagnostics);
    Objects.requireNonNull(assessment, "assessment");
  }
}
