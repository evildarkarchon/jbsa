package io.github.evildarkarchon.jbsa.internal.ba2;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/** Bounded DDS BA2 index reader with parent-owned lazy payload access. */
public final class DdsBa2Reader {
  private final IndexSession session;
  private final IoContext context;
  private final DdsTarget target;
  private final Optional<CompatibilityProfile> compatibilityProfile;

  private DdsBa2Reader(IndexSession session, OpenOptions options) {
    this.session = session;
    context = session.context();
    compatibilityProfile = options.compatibilityProfile();
    // Filename inference is an explicit profile deviation; operation target data always wins.
    target =
        options
            .ddsTarget()
            .orElse(
                options.compatibilityProfile().isPresent()
                        && context
                            .path()
                            .getFileName()
                            .toString()
                            .toLowerCase(Locale.ROOT)
                            .contains("_xbox.")
                    ? DdsTarget.XBOX
                    : DdsTarget.PC);
  }

  /**
   * Walks a DDS BA2 index into {@code session} without reading payloads; the session owns the
   * failure handling and the Archive Assessment.
   *
   * @param options supplies the DDS target and the profile that gates the version 3 fallback
   */
  public static ArchiveMetadata walk(IndexSession session, OpenOptions options) throws IOException {
    return new DdsBa2Reader(session, options).walk();
  }

  /** Bounds every metadata and payload span before admitting the serialized entry index. */
  private ArchiveMetadata walk() throws IOException {
    ByteBuffer header = session.readMetadata(0, 24);
    if (header.getInt() != 0x58445442) throw malformed("ba2.invalid-selector");
    long version = u32(header);
    if (!Ba2Layout.supportsDecode(version) || header.getInt() != 0x30315844)
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
        Ba2Layout.isFallout4(version) ? ArchiveFamily.FO4_DDS_BA2 : ArchiveFamily.STARFIELD_DDS_BA2;
    ArchiveEncoding archiveEncoding =
        new ArchiveEncoding(
            Optional.of(new WireVersion(version)), Optional.of(Ba2Subtype.DX10), compressionMethod);
    ExactIo.end(headerSize, ExactIo.multiply(count, 48, context), session.size(), context);
    long recordsEnd = headerSize;
    session.declareEntries(count);
    boolean named = nameOffset > 0 && nameOffset < session.size();
    if (!named)
      session.noncanonical(
          "ba2.missing-name-table",
          session.location(-1, null, "fileNameTableOffset", 16, 8),
          Map.of("stored", Long.toString(nameOffset)));

    long namePosition = nameOffset, usedEnd = recordsEnd;
    // Every chunk, empty or not, must start after the variable-length records; that check waits
    // for the last record, so only the lowest chunk offset needs to be kept.
    long firstChunk = Long.MAX_VALUE;
    for (long ordinal = 0; ordinal < count; ordinal++) {
      long field = recordsEnd;
      ByteBuffer record = session.readMetadata(field, 24);
      long baseHash = u32(record);
      byte[] extension = new byte[4];
      record.get(extension);
      long directoryHash = u32(record),
          mod = Byte.toUnsignedLong(record.get()),
          chunks = Byte.toUnsignedLong(record.get()),
          chunkSize = Short.toUnsignedLong(record.getShort());
      int height = Short.toUnsignedInt(record.getShort());
      int width = Short.toUnsignedInt(record.getShort());
      int mips = Byte.toUnsignedInt(record.get()), format = Byte.toUnsignedInt(record.get());
      int flags = Byte.toUnsignedInt(record.get()), tile = Byte.toUnsignedInt(record.get());
      if (chunks < 1 || chunks > 4 || ((flags & 1) != 0 && chunks != 1))
        throw malformed("dx10.invalid-chunk-count");
      if (width == 0
          || height == 0
          || mips == 0
          || mips > 1 + (31 - Integer.numberOfLeadingZeros(Math.max(width, height))))
        throw malformed("dx10.invalid-mip-count");
      recordsEnd = ExactIo.end(field + 24, chunks * 24, session.size(), context);
      List<EntryMetadata.DdsChunk> textureChunks = new ArrayList<>();
      long stored = 0, unpacked = 0;
      int nextMip = 0;
      for (int chunk = 0; chunk < chunks; chunk++) {
        long chunkField = field + 24 + chunk * 24;
        ByteBuffer chunkRecord = session.readMetadata(chunkField, 24);
        long offset = chunkRecord.getLong(), packed = u32(chunkRecord), decoded = u32(chunkRecord);
        int start = Short.toUnsignedInt(chunkRecord.getShort()),
            endMip = Short.toUnsignedInt(chunkRecord.getShort());
        long sentinel = u32(chunkRecord);
        if (start != nextMip
            || endMip < start
            || endMip >= mips
            || (chunk < chunks - 1 && endMip != start)) throw malformed("dx10.invalid-mip-range");
        nextMip = endMip + 1;
        long size = packed == 0 ? decoded : packed;
        long end = ExactIo.end(offset, size, session.size(), context);
        stored = ExactIo.end(stored, size, Long.MAX_VALUE, context);
        unpacked = ExactIo.end(unpacked, decoded, Long.MAX_VALUE, context);
        session.payload(offset, end);
        firstChunk = Math.min(firstChunk, offset);
        if (size > 0) usedEnd = Math.max(usedEnd, end);
        if (packed == 0)
          session.noncanonical(
              "dx10.stored-chunk",
              session.location(ordinal, null, "chunkSizes", chunkField + 8, 8),
              Map.of("stored", "0", "unpacked", Long.toString(decoded)));
        if (sentinel != 0xbaadf00dL)
          mismatch(
              "dx10.sentinel-mismatch",
              session.location(ordinal, null, "sentinel", chunkField + 20, 4),
              hex(sentinel),
              "BAADF00D");
        textureChunks.add(
            new EntryMetadata.DdsChunk(offset, packed, decoded, start, endMip, sentinel));
      }
      if (nextMip != mips) throw malformed("dx10.invalid-mip-range");
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
            session.decodeName(
                wire, ordinal, namePosition, DdsBa2Reader::fold, "ba2.duplicate-name");
        display = name.display();
        identity = name.identity();
        if (name.decoded() && Ba2Names.ascii(wire.bytes()))
          checkIdentity(prefix, Ba2Names.identity(wire.bytes()), ordinal, display, field);
        namePosition += length;
      }
      if (mod != 0)
        mismatch(
            "dx10.nonzero-mod-index",
            session.location(ordinal, display, "modIndex", field + 12, 1),
            Long.toString(mod),
            "0");
      if (chunkSize != 24)
        mismatch(
            "dx10.chunk-header-size",
            session.location(ordinal, display, "chunkHeaderSize", field + 14, 2),
            Long.toString(chunkSize),
            "24");

      byte[] ddsHeader =
          io.github.evildarkarchon.jbsa.internal.dds.DdsEnvelope.canonicalHeader(
              width, height, mips, format, (flags & 1) != 0, tile, target, context);
      EntryMetadata metadata =
          new EntryMetadata(
              family,
              archiveEncoding,
              ordinal,
              display,
              identity,
              wire == null ? Map.of() : Map.of("complete", wire),
              ExactIo.end(unpacked, ddsHeader.length, Long.MAX_VALUE, context),
              stored,
              new EntryMetadata.DdsBa2(
                  prefix, height, width, mips, format, flags, tile, textureChunks));
      session.addDds(
          metadata,
          ddsHeader,
          textureChunks,
          rawLz4 ? OwnedArchive.PayloadCodec.LZ4_RAW : OwnedArchive.PayloadCodec.ZLIB);
    }
    if (named && nameOffset < recordsEnd) throw malformed("ba2.names-overlap-index");
    usedEnd = Math.max(usedEnd, recordsEnd);
    if (firstChunk < recordsEnd) throw malformed("ba2.payload-overlaps-index");
    // Empty chunks name no bytes, so the session never registers them for these overlap checks.
    if (named)
      session.checkOverlaps(
          "ba2.overlapping-payloads", nameOffset, namePosition, "ba2.payload-overlaps-names");
    else session.checkOverlaps("ba2.overlapping-payloads");
    if (named) usedEnd = Math.max(usedEnd, namePosition);
    session.trailingData("ba2.trailing-data", usedEnd, Map.of());
    return new ArchiveMetadata.DdsBa2(family, archiveEncoding, count, nameOffset, extraHeader);
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

  private ArchiveException unsupported(String id) {
    return context.failure(FailureKind.UNSUPPORTED, id, null);
  }
}
