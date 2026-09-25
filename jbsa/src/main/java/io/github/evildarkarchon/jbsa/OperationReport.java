package io.github.evildarkarchon.jbsa;

import java.nio.file.Path;
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
 * @param archiveParts published pack-part sizes and entry counts in logical order
 */
public record OperationReport(
    Operation operation,
    List<Artifact> artifacts,
    List<Diagnostic> diagnostics,
    Optional<ArchiveAssessment> assessment,
    List<ArchivePart> archiveParts) {
  /** Preserves construction for reports without detached pack-part summary facts. */
  public OperationReport(
      Operation operation,
      List<Artifact> artifacts,
      List<Diagnostic> diagnostics,
      Optional<ArchiveAssessment> assessment) {
    this(operation, artifacts, diagnostics, assessment, List.of());
  }

  /** Copies all records and rejects query operations, which return their own domain values. */
  public OperationReport {
    Objects.requireNonNull(operation, "operation");
    if (operation != Operation.EXTRACT && operation != Operation.PACK) {
      throw new IllegalArgumentException("Only extract and pack return operation reports");
    }
    artifacts = List.copyOf(artifacts);
    diagnostics = List.copyOf(diagnostics);
    Objects.requireNonNull(assessment, "assessment");
    archiveParts = List.copyOf(archiveParts);
    if (operation != Operation.PACK && !archiveParts.isEmpty()) {
      throw new IllegalArgumentException("Only pack reports contain archive parts");
    }
  }

  /** Detached published archive facts captured during packing, requiring no post-commit reopen. */
  public record ArchivePart(Path path, long byteSize, long entryCount) {
    /** Requires an exact artifact path and nonnegative completed quantities. */
    public ArchivePart {
      Objects.requireNonNull(path, "path");
      if (byteSize < 0 || entryCount < 0) {
        throw new IllegalArgumentException("Archive part quantities must be nonnegative");
      }
    }
  }
}
