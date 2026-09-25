package io.github.evildarkarchon.jbsa;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * Detached entry facts; names, identity, original wire components, and format facts stay distinct.
 */
public record EntryMetadata(
    ArchiveFamily family,
    ArchiveEncoding encoding,
    long ordinal,
    String displayName,
    Optional<NormalizedNameIdentity> normalizedNameIdentity,
    Map<String, WireName> wireNames,
    long decodedSize,
    long storedSize,
    Facts facts) {
  /**
   * Copies wire-component mappings and retains immutable facts without a parent archive reference.
   * Component keys identify complete, folder, basename, or embedded names as present on the wire;
   * missing or synthetic names must not be inserted as wire components.
   */
  public EntryMetadata {
    Objects.requireNonNull(family, "family");
    Objects.requireNonNull(encoding, "encoding");
    nonnegative(ordinal, decodedSize, storedSize);
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(normalizedNameIdentity, "normalizedNameIdentity");
    wireNames = Map.copyOf(wireNames);
    Objects.requireNonNull(facts, "facts");
  }

  /** Format-specific entry metadata without parser or codec implementation types. */
  public sealed interface Facts permits Tes3, VersionedBsa, GeneralBa2, DdsBa2 {}

  /** TES3 record facts; nameHash retains its unsigned 64-bit wire bit pattern. */
  public record Tes3(long nameHash, long nameOffset, long relativeDataOffset, long dataOffset)
      implements Facts {
    /** Rejects negative offsets while preserving every hash bit. */
    public Tes3 {
      nonnegative(nameOffset, relativeDataOffset, dataOffset);
    }
  }

  /** Versioned-BSA folder and entry facts, including retained noncanonical folder padding. */
  public record VersionedBsa(
      long folderHash,
      long nameHash,
      long folderOrdinal,
      long folderFileCount,
      long folderOffset,
      long folderPaddingBeforeOffset,
      long folderPaddingAfterOffset,
      long sizeAndCompressionToggle,
      long dataOffset,
      boolean compressed)
      implements Facts {
    /** Rejects negative bounded fields while retaining unsigned hash bit patterns. */
    public VersionedBsa {
      nonnegative(
          folderOrdinal,
          folderFileCount,
          folderOffset,
          folderPaddingBeforeOffset,
          folderPaddingAfterOffset,
          sizeAndCompressionToggle,
          dataOffset);
    }
  }

  /** Shared BA2 identity prefix with four exact extension octets. */
  public record Ba2Identity(
      long baseNameHash,
      WireName extension,
      long directoryHash,
      long modIndex,
      long chunkCount,
      long chunkHeaderSize) {
    /** Rejects negative identity fields or an extension other than four wire octets. */
    public Ba2Identity {
      nonnegative(baseNameHash, directoryHash, modIndex, chunkCount, chunkHeaderSize);
      Objects.requireNonNull(extension, "extension");
      if (extension.length() != 4) {
        throw new IllegalArgumentException("BA2 extension must contain four wire octets");
      }
    }
  }

  /** General BA2 payload record; packedSize zero distinguishes stored payloads. */
  public record GeneralBa2(
      Ba2Identity identity, long payloadOffset, long packedSize, long unpackedSize, long sentinel)
      implements Facts {
    /** Rejects negative record quantities and retains the immutable identity prefix. */
    public GeneralBa2 {
      Objects.requireNonNull(identity, "identity");
      nonnegative(payloadOffset, packedSize, unpackedSize, sentinel);
    }
  }

  /** DDS BA2 texture record and chunks in serialized reconstruction order. */
  public record DdsBa2(
      Ba2Identity identity,
      long height,
      long width,
      long mipCount,
      long dxgiFormat,
      long flags,
      long tileMode,
      List<DdsChunk> chunks)
      implements Facts {
    /** Copies the serialized chunk sequence; no mutable payload data is retained. */
    public DdsBa2 {
      Objects.requireNonNull(identity, "identity");
      nonnegative(height, width, mipCount, dxgiFormat, flags, tileMode);
      chunks = List.copyOf(chunks);
    }
  }

  /** DDS chunk facts; mip ordinals are inclusive and stored sizes retain their wire meaning. */
  public record DdsChunk(
      long payloadOffset,
      long packedSize,
      long unpackedSize,
      long startMip,
      long endMip,
      long sentinel) {
    /** Rejects negative quantities without reordering or canonicalizing retained chunk metadata. */
    public DdsChunk {
      nonnegative(payloadOffset, packedSize, unpackedSize, startMip, endMip, sentinel);
    }
  }

  /**
   * Validates semantic quantities while permitting full-width signed bit patterns for u64 hashes.
   */
  private static void nonnegative(long... values) {
    for (long value : values) {
      if (value < 0) {
        throw new IllegalArgumentException("Entry metadata quantities must be nonnegative");
      }
    }
  }
}
