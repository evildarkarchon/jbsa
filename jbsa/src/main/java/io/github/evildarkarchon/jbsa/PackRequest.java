package io.github.evildarkarchon.jbsa;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable ordered-source pack request. Family and wire encoding remain independent, and the
 * encode DDS target is independent of later reconstruction options. Discovery, overlay resolution,
 * supported encoding checks, packing, and publication occur at operation time, not during
 * construction.
 */
public record PackRequest(
    Path destination,
    ArchiveFamily family,
    ArchiveEncoding encoding,
    Optional<CompatibilityProfile> compatibilityProfile,
    List<PackSource> sources,
    TargetPolicy targetPolicy,
    DiagnosticPolicy diagnosticPolicy,
    ResourceLimits resourceLimits,
    WorkerSelection workerSelection,
    PackOptions options,
    Optional<DdsTarget> ddsTarget) {
  /**
   * Copies ordered sources and validates programmer contracts. DDS families require exactly one
   * encode target; all other families prohibit it. Legacy splitting requires the complete
   * registered compatibility bundle. This does not inspect source paths or invoke generated payload
   * factories.
   */
  public PackRequest {
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(family, "family");
    Objects.requireNonNull(encoding, "encoding");
    Objects.requireNonNull(compatibilityProfile, "compatibilityProfile");
    sources = List.copyOf(sources);
    Objects.requireNonNull(targetPolicy, "targetPolicy");
    Objects.requireNonNull(diagnosticPolicy, "diagnosticPolicy");
    Objects.requireNonNull(resourceLimits, "resourceLimits");
    Objects.requireNonNull(workerSelection, "workerSelection");
    Objects.requireNonNull(options, "options");
    Objects.requireNonNull(ddsTarget, "ddsTarget");
    boolean dds = family == ArchiveFamily.FO4_DDS_BA2 || family == ArchiveFamily.STARFIELD_DDS_BA2;
    if (dds != ddsTarget.isPresent()) {
      throw new IllegalArgumentException("Exactly DDS BA2 families require an encode DDS target");
    }
    if (options.splitting() instanceof PackOptions.Splitting.LegacyPerEntry
        && !compatibilityProfile.equals(Optional.of(CompatibilityProfile.BSARCH_1_0_V1))) {
      throw new IllegalArgumentException("Legacy splitting requires the bsarch-1.0/v1 profile");
    }
  }

  /**
   * Returns safe defaults while requiring explicit target family, wire encoding, and DDS selection.
   */
  public static PackRequest standard(
      Path destination,
      ArchiveFamily family,
      ArchiveEncoding encoding,
      List<PackSource> sources,
      Optional<DdsTarget> ddsTarget) {
    return new PackRequest(
        destination,
        family,
        encoding,
        Optional.empty(),
        sources,
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        ResourceLimits.standard(),
        WorkerSelection.AUTOMATIC,
        PackOptions.standard(),
        ddsTarget);
  }
}
