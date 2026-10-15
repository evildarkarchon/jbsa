package io.github.evildarkarchon.jbsa.internal.ba2;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/** Bounded General BA2 index reader with parent-owned lazy payload access. */
public final class Ba2Reader {
  private final IndexSession session;
  private final IoContext context;
  private final Optional<CompatibilityProfile> compatibilityProfile;

  private Ba2Reader(IndexSession session, Optional<CompatibilityProfile> compatibilityProfile) {
    this.session = session;
    context = session.context();
    this.compatibilityProfile = compatibilityProfile;
  }

  /**
   * Walks a General BA2 index into {@code session} without reading payloads; the session owns the
   * failure handling and the Archive Assessment.
   *
   * @param compatibilityProfile gates the Starfield version 3 zlib fallback
   */
  public static ArchiveMetadata walk(
      IndexSession session, Optional<CompatibilityProfile> compatibilityProfile)
      throws IOException {
    return new Ba2Reader(session, compatibilityProfile).walk();
  }

  /** Bounds every metadata and payload span before admitting the serialized entry index. */
  private ArchiveMetadata walk() throws IOException {
    ByteBuffer header = session.readMetadata(0, 24);
    if (header.getInt() != 0x58445442) throw malformed("ba2.invalid-selector");
    long version = u32(header);
    if (!Ba2Layout.supportsDecode(version) || header.getInt() != 0x4c524e47)
      throw malformed("ba2.invalid-selector");
    long count = u32(header), nameOffset = header.getLong();
    long headerSize = Ba2Layout.headerSize(version);
    OptionalLong extraHeader = OptionalLong.empty();
    OptionalLong compressionMethod = OptionalLong.empty();
    boolean rawLz4 = false;
    if (Ba2Layout.hasExtraHeader(version)) {
      long storedExtra = session.readMetadata(24, 8).getLong();
      extraHeader = OptionalLong.of(storedExtra);
      if (storedExtra != 1)
        mismatch(
            "ba2.extra-header-value",
            session.location(-1, null, "unknownValueAt24", 24, 8),
            Long.toUnsignedString(storedExtra),
            "1");
    }
    if (version == 3) {
      long method = u32(session.readMetadata(32, 4));
      compressionMethod = OptionalLong.of(method);
      rawLz4 = method == 3;
      if (!rawLz4) {
        if (compatibilityProfile.isEmpty()) throw unsupported("archive.unsupported-encoding");
        session.noncanonical(
            "ba2.sf3-zlib-fallback",
            session.location(-1, null, "compressionMethod", 32, 4),
            Map.of("stored", Long.toUnsignedString(method)));
      }
    }
    ArchiveFamily family =
        Ba2Layout.isFallout4(version)
            ? ArchiveFamily.FO4_GENERAL_BA2
            : ArchiveFamily.STARFIELD_GENERAL_BA2;
    ArchiveEncoding archiveEncoding =
        new ArchiveEncoding(
            Optional.of(new WireVersion(version)), Optional.of(Ba2Subtype.GNRL), compressionMethod);
    long recordsEnd =
        ExactIo.end(headerSize, ExactIo.multiply(count, 36, context), session.size(), context);
    session.declareEntries(count);
    boolean named = nameOffset > 0 && nameOffset < session.size();
    if (!named)
      session.noncanonical(
          "ba2.missing-name-table",
          session.location(-1, null, "fileNameTableOffset", 16, 8),
          Map.of("stored", Long.toString(nameOffset)));
    if (named && nameOffset < recordsEnd) throw malformed("ba2.names-overlap-index");
    long namePosition = nameOffset, usedEnd = recordsEnd;
    for (long ordinal = 0; ordinal < count; ordinal++) {
      long field = headerSize + ordinal * 36;
      ByteBuffer record = session.readMetadata(field, 36);
      long baseHash = u32(record);
      byte[] extension = new byte[4];
      record.get(extension);
      long directoryHash = u32(record),
          mod = Byte.toUnsignedLong(record.get()),
          chunks = Byte.toUnsignedLong(record.get()),
          chunkSize = Short.toUnsignedLong(record.getShort());
      long offset = record.getLong(),
          packed = u32(record),
          unpacked = u32(record),
          sentinel = u32(record);
      if (chunks != 1) throw malformed("gnrl.invalid-chunk-count");
      EntryMetadata.Ba2Identity prefix =
          new EntryMetadata.Ba2Identity(
              baseHash, new WireName(extension), directoryHash, mod, chunks, chunkSize);
      String display = Ba2Names.synthetic(prefix, ordinal);
      WireName wire = null;
      Optional<NormalizedNameIdentity> identity = Optional.empty();
      if (named) {
        int length = Short.toUnsignedInt(session.readMetadata(namePosition, 2).getShort());
        namePosition += 2;
        wire = session.readName(namePosition, length);
        for (byte value : wire.bytes()) if (value == 0) throw malformed("ba2.nul-name");
        // Present undecodable bytes use ordinal wire identity, distinct from an absent table.
        IndexSession.Name name =
            session.decodeName(wire, ordinal, namePosition, Ba2Reader::fold, "ba2.duplicate-name");
        display = name.display();
        identity = name.identity();
        if (name.decoded() && Ba2Names.ascii(wire.bytes()))
          checkIdentity(prefix, Ba2Names.identity(wire.bytes()), ordinal, display, field);
        namePosition += length;
      }
      if (mod != 0)
        mismatch(
            "gnrl.nonzero-mod-index",
            session.location(ordinal, display, "modIndex", field + 12, 1),
            Long.toString(mod),
            "0");
      if (chunkSize != 16)
        mismatch(
            "gnrl.chunk-header-size",
            session.location(ordinal, display, "chunkHeaderSize", field + 14, 2),
            Long.toString(chunkSize),
            "16");
      if (sentinel != 0xbaadf00dL)
        mismatch(
            "gnrl.sentinel-mismatch",
            session.location(ordinal, display, "sentinel", field + 32, 4),
            hex(sentinel),
            "BAADF00D");
      long stored = packed == 0 ? unpacked : packed;
      if (offset < recordsEnd) throw malformed("ba2.payload-overlaps-index");
      long end = ExactIo.end(offset, stored, session.size(), context);
      session.payload(offset, end);
      // Empty spans reference no bytes, so an EOF coordinate cannot hide trailing data.
      if (stored > 0) usedEnd = Math.max(usedEnd, end);
      EntryMetadata metadata =
          new EntryMetadata(
              family,
              archiveEncoding,
              ordinal,
              display,
              identity,
              wire == null ? Map.of() : Map.of("complete", wire),
              unpacked,
              stored,
              new EntryMetadata.GeneralBa2(prefix, offset, packed, unpacked, sentinel));
      if (packed == 0) session.add(metadata, offset, stored, OwnedArchive.PayloadCodec.STORED);
      else
        session.add(
            metadata,
            offset,
            packed,
            rawLz4 ? OwnedArchive.PayloadCodec.LZ4_RAW : OwnedArchive.PayloadCodec.ZLIB);
    }
    if (named)
      session.checkOverlaps(
          "ba2.overlapping-payloads", nameOffset, namePosition, "ba2.payload-overlaps-names");
    else session.checkOverlaps("ba2.overlapping-payloads");
    if (named) usedEnd = Math.max(usedEnd, namePosition);
    session.trailingData("ba2.trailing-data", usedEnd, Map.of());
    return new ArchiveMetadata.GeneralBa2(family, archiveEncoding, count, nameOffset, extraHeader);
  }

  /** BA2 names use either separator on the wire; the display name always uses a backslash. */
  private static String fold(String decoded) {
    return decoded.replace('/', '\\');
  }

  /** Diagnoses each independently mismatching identity field without changing wire facts. */
  private void checkIdentity(
      EntryMetadata.Ba2Identity stored,
      EntryMetadata.Ba2Identity expected,
      long ordinal,
      String display,
      long field) {
    if (stored.baseNameHash() != expected.baseNameHash())
      mismatch(
          "ba2.basename-hash-mismatch",
          session.location(ordinal, display, "baseNameHash", field, 4),
          hex(stored.baseNameHash()),
          hex(expected.baseNameHash()));
    if (!stored.extension().equals(expected.extension()))
      mismatch(
          "ba2.extension-mismatch",
          session.location(ordinal, display, "extension", field + 4, 4),
          HexFormat.of().withUpperCase().formatHex(stored.extension().bytes()),
          HexFormat.of().withUpperCase().formatHex(expected.extension().bytes()));
    if (stored.directoryHash() != expected.directoryHash())
      mismatch(
          "ba2.directory-hash-mismatch",
          session.location(ordinal, display, "directoryHash", field + 8, 4),
          hex(stored.directoryHash()),
          hex(expected.directoryHash()));
  }

  /**
   * Retains stored and expected values at a field's exact wire location. Every BA2 warning makes
   * the archive noncanonical.
   */
  private void mismatch(String id, DiagnosticLocation location, String stored, String expected) {
    session.noncanonical(id, location, Map.of("stored", stored, "expected", expected));
  }

  private static long u32(ByteBuffer bytes) {
    return Integer.toUnsignedLong(bytes.getInt());
  }

  private static String hex(long value) {
    return String.format(Locale.ROOT, "%08X", value);
  }

  private ArchiveException malformed(String id) {
    return session.malformed(id);
  }

  /** Rejects a recognized selector combination without reclassifying it as corrupt bytes. */
  private ArchiveException unsupported(String id) {
    return context.failure(FailureKind.UNSUPPORTED, id, null);
  }
}
