package io.github.evildarkarchon.jbsa;

import java.util.Objects;
import java.util.Optional;

/** Immutable query and extraction input policy; DDS target applies only to reconstruction. */
public record OpenOptions(
    Optional<CompatibilityProfile> compatibilityProfile,
    ResourceLimits resourceLimits,
    Optional<DdsTarget> ddsTarget) {
  /** Creates explicit input policy, rejecting null option containers and limits. */
  public OpenOptions {
    Objects.requireNonNull(compatibilityProfile, "compatibilityProfile");
    Objects.requireNonNull(resourceLimits, "resourceLimits");
    Objects.requireNonNull(ddsTarget, "ddsTarget");
  }

  /** Returns safe normative options without environmental or archive-name inference. */
  public static OpenOptions standard() {
    return new OpenOptions(Optional.empty(), ResourceLimits.standard(), Optional.empty());
  }
}
