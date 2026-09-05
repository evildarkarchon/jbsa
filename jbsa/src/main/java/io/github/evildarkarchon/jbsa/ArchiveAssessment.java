package io.github.evildarkarchon.jbsa;

import java.util.List;
import java.util.Objects;

/**
 * Immutable evidence established within an explicit validation boundary.
 *
 * @param disposition format-intrinsic content classification
 * @param extent boundary of established evidence
 * @param diagnostics diagnostics in the operation's deterministic logical order
 */
public record ArchiveAssessment(
    ArchiveDisposition disposition, ValidationExtent extent, List<Diagnostic> diagnostics) {
  /** Copies ordered diagnostics so later validation cannot rewrite this assessment. */
  public ArchiveAssessment {
    Objects.requireNonNull(disposition, "disposition");
    Objects.requireNonNull(extent, "extent");
    diagnostics = List.copyOf(diagnostics);
  }
}
