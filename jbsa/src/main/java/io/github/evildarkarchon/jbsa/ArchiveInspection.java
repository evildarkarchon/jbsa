package io.github.evildarkarchon.jbsa;

import java.util.List;
import java.util.Objects;

/** Immutable archive inspection that remains usable after its source archive closes. */
public record ArchiveInspection(
    ArchiveDetection detection,
    ArchiveMetadata metadata,
    ArchiveAssessment assessment,
    List<EntryMetadata> entries) {
  /** Preserves the initial constructor for callers constructing header-only evidence. */
  public ArchiveInspection(
      ArchiveDetection detection, ArchiveMetadata metadata, ArchiveAssessment assessment) {
    this(detection, metadata, assessment, List.of());
  }

  /** Requires all detached evidence; detection alone never substitutes for the assessment. */
  public ArchiveInspection {
    Objects.requireNonNull(detection, "detection");
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(assessment, "assessment");
    entries = List.copyOf(entries);
  }
}
