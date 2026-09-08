package io.github.evildarkarchon.jbsa;

import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Immutable canonical-pack choices. Inclusion masks match the complete mapped basename with ASCII
 * case folding: '*' matches zero or more Unicode scalars and '?' exactly one. Masks form a union;
 * an empty list applies no filter. Entry compression overrides address exact normalized identities
 * in the final post-overlay entry set. Unmatched identities and unsupported family codecs are
 * rejected before payload factories open. Overrides require a concrete codec choice; omit a key to
 * inherit global compression. TES3 does not support per-entry overrides. Packing and family/codec
 * policy checks occur when invoked.
 */
public record PackOptions(
    List<String> inclusionMasks,
    Compression compression,
    boolean sharing,
    Splitting splitting,
    FlagSelection archiveFlags,
    FlagSelection fileFlags,
    Map<NormalizedNameIdentity, Compression> entryCompression) {
  /** Copies masks and requires explicit choices; an empty individual mask is a programmer error. */
  public PackOptions {
    inclusionMasks = List.copyOf(inclusionMasks);
    if (inclusionMasks.stream().anyMatch(String::isEmpty)) {
      throw new IllegalArgumentException("inclusion masks must not be empty");
    }
    Objects.requireNonNull(compression, "compression");
    Objects.requireNonNull(splitting, "splitting");
    Objects.requireNonNull(archiveFlags, "archiveFlags");
    Objects.requireNonNull(fileFlags, "fileFlags");
    entryCompression = Map.copyOf(entryCompression);
  }

  /** Preserves the original constructor and global compression behavior without entry overrides. */
  public PackOptions(
      List<String> inclusionMasks,
      Compression compression,
      boolean sharing,
      Splitting splitting,
      FlagSelection archiveFlags,
      FlagSelection fileFlags) {
    this(inclusionMasks, compression, sharing, splitting, archiveFlags, fileFlags, Map.of());
  }

  /**
   * Returns family defaults with sharing enabled, no filter, and independent automatic flag groups.
   */
  public static PackOptions standard() {
    return new PackOptions(
        List.of(),
        Compression.FAMILY_DEFAULT,
        true,
        new Splitting.FamilyDefault(),
        FlagSelection.AUTOMATIC,
        FlagSelection.AUTOMATIC);
  }

  /** Requested payload encoding; supported combinations are owned by each archive family. */
  public enum Compression {
    /** Stored for non-DDS families, zlib for FO4 DDS, and raw LZ4 for Starfield DDS. */
    FAMILY_DEFAULT,
    /**
     * Store canonical payload bytes without compression. Forbidden for DDS BA2 output because
     * Fallout 4 and Starfield crash when DDS archive files use stored payloads.
     */
    STORED,
    /** Use the family's zlib framing. */
    ZLIB,
    /** Use raw LZ4 blocks. */
    LZ4_RAW,
    /** Use LZ4 frames. */
    LZ4_FRAME
  }

  /** Whole-entry archive splitting policy; targets are advisory, subject to family wire limits. */
  public sealed interface Splitting
      permits Splitting.FamilyDefault, Splitting.UpToBytes, Splitting.LegacyPerEntry {
    /** Uses 2,147,483,647 bytes for BSA and disabled splitting for BA2. */
    record FamilyDefault() implements Splitting {}

    /** Explicit target bytes; zero disables splitting. */
    record UpToBytes(long targetBytes) implements Splitting {
      /** Creates a nonnegative split target; payloads are never divided within a logical entry. */
      public UpToBytes {
        if (targetBytes < 0) {
          throw new IllegalArgumentException("targetBytes must be nonnegative");
        }
      }
    }

    /**
     * Legacy negative-split interpretation: one nonempty logical entry per part, requiring a
     * profile.
     */
    record LegacyPerEntry() implements Splitting {}
  }
}
