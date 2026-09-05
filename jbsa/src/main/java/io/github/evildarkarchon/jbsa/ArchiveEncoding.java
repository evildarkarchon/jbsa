package io.github.evildarkarchon.jbsa;

import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/** Independent wire selectors; neither recognition nor an encode-support claim. */
public record ArchiveEncoding(
    Optional<WireVersion> wireVersion,
    Optional<Ba2Subtype> ba2Subtype,
    OptionalLong compressionMethod) {
  /** Retains each selector independently; rejects null values or an out-of-range method. */
  public ArchiveEncoding {
    Objects.requireNonNull(wireVersion, "wireVersion");
    Objects.requireNonNull(ba2Subtype, "ba2Subtype");
    Objects.requireNonNull(compressionMethod, "compressionMethod");
    if (compressionMethod.isPresent()
        && (compressionMethod.getAsLong() < 0 || compressionMethod.getAsLong() > 0xffff_ffffL)) {
      throw new IllegalArgumentException("Compression method must fit unsigned 32 bits");
    }
  }

  /** Returns the selector-free encoding of the unversioned TES3 family. */
  public static ArchiveEncoding tes3() {
    return new ArchiveEncoding(Optional.empty(), Optional.empty(), OptionalLong.empty());
  }
}
