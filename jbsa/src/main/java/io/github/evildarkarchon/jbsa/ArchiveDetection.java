package io.github.evildarkarchon.jbsa;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Detached bounded recognition evidence, preserving selectors without assigning a disposition. */
public record ArchiveDetection(
    DetectionStatus status,
    WireName observedPrefix,
    Optional<ArchiveFamily> family,
    Optional<WireVersion> wireVersion,
    Optional<Ba2Subtype> ba2Subtype,
    OptionalLong compressionMethod) {
  /**
   * Retains an immutable prefix, including incomplete selectors, with at most 36 identifying bytes.
   */
  public ArchiveDetection {
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(observedPrefix, "observedPrefix");
    Objects.requireNonNull(family, "family");
    new ArchiveEncoding(wireVersion, ba2Subtype, compressionMethod);
    if (observedPrefix.length() > 36) {
      throw new IllegalArgumentException("Detection prefix exceeds the identifying header");
    }
    if ((status == DetectionStatus.SUPPORTED_FAMILY) != family.isPresent()) {
      throw new IllegalArgumentException("Only supported-family recognition carries a family");
    }
  }
}
