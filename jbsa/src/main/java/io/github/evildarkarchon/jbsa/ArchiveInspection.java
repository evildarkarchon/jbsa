package io.github.evildarkarchon.jbsa;

import java.util.Objects;

/** Immutable archive inspection that remains usable after its source archive closes. */
public record ArchiveInspection(
    ArchiveDetection detection, ArchiveMetadata metadata, ArchiveAssessment assessment) {
  /** Requires all detached evidence; detection alone never substitutes for the assessment. */
  public ArchiveInspection {
    Objects.requireNonNull(detection, "detection");
    Objects.requireNonNull(metadata, "metadata");
    Objects.requireNonNull(assessment, "assessment");
  }
}
