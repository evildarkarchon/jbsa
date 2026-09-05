package io.github.evildarkarchon.jbsa;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;

/**
 * Immutable extraction request. Selected entries and open options are its extraction choices;
 * publication safety is determined by destination preflight and the target policy. Construction
 * performs no archive, source, or destination I/O.
 */
public record ExtractRequest(
    Path source,
    Path destination,
    EntrySelection entries,
    TargetPolicy targetPolicy,
    DiagnosticPolicy diagnosticPolicy,
    WorkerSelection workerSelection,
    OpenOptions openOptions) {
  /** Captures explicit request values; null values are unchecked programmer errors. */
  public ExtractRequest {
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(destination, "destination");
    Objects.requireNonNull(entries, "entries");
    Objects.requireNonNull(targetPolicy, "targetPolicy");
    Objects.requireNonNull(diagnosticPolicy, "diagnosticPolicy");
    Objects.requireNonNull(workerSelection, "workerSelection");
    Objects.requireNonNull(openOptions, "openOptions");
  }

  /** Returns an all-entry request with safe defaults and the fixed standard resource limits. */
  public static ExtractRequest standard(Path source, Path destination) {
    return new ExtractRequest(
        source,
        destination,
        EntrySelection.ALL,
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        WorkerSelection.AUTOMATIC,
        OpenOptions.standard());
  }

  /** Returns the same profile selection used for both input interpretation and extraction. */
  public Optional<CompatibilityProfile> compatibilityProfile() {
    return openOptions.compatibilityProfile();
  }

  /** Returns the common ceilings for opening the input and performing this extraction. */
  public ResourceLimits resourceLimits() {
    return openOptions.resourceLimits();
  }
}
