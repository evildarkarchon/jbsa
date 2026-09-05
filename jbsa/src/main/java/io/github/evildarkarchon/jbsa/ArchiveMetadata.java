package io.github.evildarkarchon.jbsa;

import java.util.Objects;
import java.util.OptionalLong;

/** Detached, family-typed archive header facts with no parser or backing-handle lifetime. */
public sealed interface ArchiveMetadata {
  /** Returns the archive family independently of its encoded selectors. */
  ArchiveFamily family();

  /** Returns the original encoded selectors. */
  ArchiveEncoding encoding();

  /** Returns the archive's decoded entry count. */
  long entryCount();

  /** TES3 header facts; hashOffset is relative to byte 12 and dataBaseOffset is absolute. */
  record Tes3(long entryCount, long hashOffset, long dataBaseOffset) implements ArchiveMetadata {
    /** Rejects negative counts or offsets in a detached, bounded header. */
    public Tes3 {
      nonnegative(entryCount, hashOffset, dataBaseOffset);
    }

    @Override
    public ArchiveFamily family() {
      return ArchiveFamily.TES3_BSA;
    }

    @Override
    public ArchiveEncoding encoding() {
      return ArchiveEncoding.tes3();
    }
  }

  /** Versioned-BSA header facts, preserving independent archive flags and file flags. */
  record VersionedBsa(
      ArchiveFamily family,
      ArchiveEncoding encoding,
      long entryCount,
      long folderRecordsOffset,
      long archiveFlags,
      long folderCount,
      long folderNamesLength,
      long fileNamesLength,
      long fileFlags)
      implements ArchiveMetadata {
    /** Rejects null selectors, incompatible family kinds, or negative bounded header fields. */
    public VersionedBsa {
      Objects.requireNonNull(encoding, "encoding");
      if (family != ArchiveFamily.TES4_BSA
          && family != ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA
          && family != ArchiveFamily.SSE_BSA) {
        throw new IllegalArgumentException(
            "Versioned BSA metadata requires a Versioned BSA family");
      }
      nonnegative(
          entryCount,
          folderRecordsOffset,
          archiveFlags,
          folderCount,
          folderNamesLength,
          fileNamesLength,
          fileFlags);
    }
  }

  /**
   * General-BA2 envelope facts; unknownValueAt24 preserves its unsigned 64-bit wire bit pattern.
   */
  record GeneralBa2(
      ArchiveFamily family,
      ArchiveEncoding encoding,
      long entryCount,
      long fileNameTableOffset,
      OptionalLong unknownValueAt24)
      implements ArchiveMetadata {
    /** Rejects null selectors, incompatible family kinds, or negative entry counts. */
    public GeneralBa2 {
      Objects.requireNonNull(encoding, "encoding");
      Objects.requireNonNull(unknownValueAt24, "unknownValueAt24");
      if (family != ArchiveFamily.FO4_GENERAL_BA2
          && family != ArchiveFamily.STARFIELD_GENERAL_BA2) {
        throw new IllegalArgumentException("General BA2 metadata requires a General BA2 family");
      }
      // A signed out-of-range name-table offset remains inspectable under JBSA-GNRL-008.
      nonnegative(entryCount);
    }
  }

  /** DDS-BA2 envelope facts, independent of a reconstruction DDS target. */
  record DdsBa2(
      ArchiveFamily family,
      ArchiveEncoding encoding,
      long entryCount,
      long fileNameTableOffset,
      OptionalLong unknownValueAt24)
      implements ArchiveMetadata {
    /** Rejects null selectors, incompatible family kinds, or negative entry counts. */
    public DdsBa2 {
      Objects.requireNonNull(encoding, "encoding");
      Objects.requireNonNull(unknownValueAt24, "unknownValueAt24");
      if (family != ArchiveFamily.FO4_DDS_BA2 && family != ArchiveFamily.STARFIELD_DDS_BA2) {
        throw new IllegalArgumentException("DDS BA2 metadata requires a DDS BA2 family");
      }
      // DDS BA2 inherits the tolerated name-table rule through JBSA-DX10-007.
      nonnegative(entryCount);
    }
  }

  /** Validates semantic long quantities without narrowing to implementation allocation sizes. */
  private static void nonnegative(long... values) {
    for (long value : values) {
      if (value < 0) {
        throw new IllegalArgumentException(
            "Metadata count, offset, size, or flag must be nonnegative");
      }
    }
  }
}
