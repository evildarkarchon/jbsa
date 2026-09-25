package io.github.evildarkarchon.jbsa;

/**
 * Semantic operation ceilings. Equality is allowed; admitting the next counted unit breaches a
 * limit. Entries include overlay candidates; metadata counts encoded bytes, not Java object size.
 * Decoded bytes count each final logical entry once (or each independent content channel); scratch
 * limits peak retained extent. Outputs count published leaf files or archive parts. Retention
 * counts returned records, with one diagnostic slot reserved for truncation.
 */
public record ResourceLimits(
    long maxEntries,
    long maxMetadataBytes,
    long maxDecodedBytes,
    long maxScratchBytes,
    long maxOutputs,
    long maxDiagnostics,
    long maxSecondaryFailures) {
  /** Creates ceilings; all must be nonnegative and diagnostics must reserve at least one slot. */
  public ResourceLimits {
    if (maxEntries < 0
        || maxMetadataBytes < 0
        || maxDecodedBytes < 0
        || maxScratchBytes < 0
        || maxOutputs < 0
        || maxDiagnostics < 1
        || maxSecondaryFailures < 0) {
      throw new IllegalArgumentException(
          "Limits must be nonnegative; diagnostics must be positive");
    }
  }

  /** Returns the fixed JBSA-LIB-009 defaults, independent of the host or selected profile. */
  public static ResourceLimits standard() {
    return new ResourceLimits(
        1_000_000L, 1_073_741_824L, 1_099_511_627_776L, 274_877_906_944L, 1_000_000L, 4_096L, 256L);
  }
}
