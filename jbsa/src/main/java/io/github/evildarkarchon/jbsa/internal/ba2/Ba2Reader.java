package io.github.evildarkarchon.jbsa.internal.ba2;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.dds.DdsEnvelope;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/**
 * Bounded General and DDS BA2 index reader with parent-owned lazy payload access. One walk covers
 * the shared header, the version 3 compression method, the filename table and the final span
 * checks; a {@link RecordShape} walks each subtype's records.
 */
public final class Ba2Reader {
  private final IndexSession session;
  private final IoContext context;
  private final Optional<CompatibilityProfile> compatibilityProfile;
  private final RecordShape shape;

  // Walk state shared with the record shape. A reader walks exactly one index.
  private ArchiveFamily family;
  private ArchiveEncoding archiveEncoding;
  private boolean rawLz4;
  private boolean named;
  private long namePosition;
  private long recordsEnd;
  private long usedEnd;
  // Every DX10 chunk, empty or not, must start after the variable-length records; that check
  // waits for the last record, so only the lowest chunk offset needs to be kept.
  private long firstPayload = Long.MAX_VALUE;

  /** Binds the walk to its session and picks the record shape {@code detection} names. */
  private Ba2Reader(IndexSession session, ArchiveDetection detection, OpenOptions options) {
    this.session = session;
    context = session.context();
    compatibilityProfile = options.compatibilityProfile();
    shape = dx10(detection) ? new Dx10(options) : new General();
  }

  /**
   * Walks a General or DDS BA2 index into {@code session} without reading payloads; the session
   * owns the failure handling and the Archive Assessment.
   *
   * @param detection selects the record shape: its Archive Family, or for the profile-gated
   *     Starfield version 3 fallback (which detects no family) its BA2 subtype
   * @param options supplies the DDS target and the profile that gates the version 3 fallback
   * @throws IllegalArgumentException if {@code detection} names neither a BA2 family nor a General
   *     or DX10 subtype
   */
  public static ArchiveMetadata walk(
      IndexSession session, ArchiveDetection detection, OpenOptions options) throws IOException {
    return new Ba2Reader(session, detection, options).walk();
  }

  /** Chooses the DX10 record shape for a DDS family or, without a family, a DX10 subtype. */
  private static boolean dx10(ArchiveDetection detection) {
    return switch (detection.family().orElse(null)) {
      case FO4_GENERAL_BA2, STARFIELD_GENERAL_BA2 -> false;
      case FO4_DDS_BA2, STARFIELD_DDS_BA2 -> true;
      case null -> {
        Ba2Subtype subtype =
            detection
                .ba2Subtype()
                .orElseThrow(() -> new IllegalArgumentException("Detection has no BA2 subtype"));
        if (subtype.equals(Ba2Subtype.DX10)) yield true;
        if (subtype.equals(Ba2Subtype.GNRL)) yield false;
        throw new IllegalArgumentException("Unsupported BA2 subtype: " + subtype.value());
      }
      default -> throw new IllegalArgumentException("Not a BA2 family: " + detection.family());
    };
  }

  /** Bounds every metadata and payload span before admitting the serialized entry index. */
  private ArchiveMetadata walk() throws IOException {
    ByteBuffer header = session.readMetadata(0, 24);
    if (header.getInt() != 0x58445442) throw malformed("ba2.invalid-selector");
    long version = u32(header);
    if (!Ba2Layout.supportsDecode(version) || header.getInt() != shape.selector)
      throw malformed("ba2.invalid-selector");
    long count = u32(header), nameOffset = header.getLong();
    long headerSize = Ba2Layout.headerSize(version);
    OptionalLong extraHeader = OptionalLong.empty();
    OptionalLong compressionMethod = OptionalLong.empty();
    if (Ba2Layout.hasExtraHeader(version)) {
      long storedExtra = session.readMetadata(24, 8).getLong();
      extraHeader = OptionalLong.of(storedExtra);
      if (storedExtra != 1)
        mismatch(
            "ba2.extra-header-value",
            session.archiveLocation("unknownValueAt24", 24, 8),
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
            session.archiveLocation("compressionMethod", 32, 4),
            Map.of("stored", Long.toUnsignedString(method)));
      }
    }
    family = Ba2Layout.isFallout4(version) ? shape.fallout4 : shape.starfield;
    archiveEncoding =
        new ArchiveEncoding(
            Optional.of(new WireVersion(version)), Optional.of(shape.subtype), compressionMethod);
    long minimumEnd =
        ExactIo.end(
            headerSize,
            ExactIo.multiply(count, shape.minimumRecordSize, context),
            session.size(),
            context);
    recordsEnd = shape.recordsEndKnownBeforeWalk ? minimumEnd : headerSize;
    session.declareEntries(count);
    named = nameOffset > 0 && nameOffset < session.size();
    if (!named)
      session.noncanonical(
          "ba2.missing-name-table",
          session.archiveLocation("fileNameTableOffset", 16, 8),
          Map.of("stored", Long.toString(nameOffset)));
    if (shape.recordsEndKnownBeforeWalk) checkNamesAfterRecords(nameOffset);
    namePosition = nameOffset;
    usedEnd = recordsEnd;
    long field = headerSize;
    for (long ordinal = 0; ordinal < count; ordinal++) field = shape.walk(ordinal, field);
    if (!shape.recordsEndKnownBeforeWalk) {
      recordsEnd = field;
      checkNamesAfterRecords(nameOffset);
      usedEnd = Math.max(usedEnd, recordsEnd);
      if (firstPayload < recordsEnd) throw malformed("ba2.payload-overlaps-index");
    }
    // Empty chunks name no bytes, so the session never registers them for these overlap checks.
    if (named)
      session.checkOverlaps(
          "ba2.overlapping-payloads",
          new IndexSession.ReservedRegion(nameOffset, namePosition, "ba2.payload-overlaps-names"));
    else session.checkOverlaps("ba2.overlapping-payloads");
    if (named) usedEnd = Math.max(usedEnd, namePosition);
    session.trailingData("ba2.trailing-data", usedEnd, Map.of());
    return shape.metadata(count, nameOffset, extraHeader);
  }

  /**
   * The per-subtype record walk: record layout, chunk rules, the subtype's diagnostic identifiers
   * and how an entry's payload is admitted.
   */
  private abstract sealed class RecordShape permits General, Dx10 {
    /** The subtype selector octets at offset 8, read as a little-endian integer. */
    final int selector;

    final Ba2Subtype subtype;
    final ArchiveFamily fallout4;
    final ArchiveFamily starfield;

    /** The fewest bytes one record can occupy, which bounds the index before the walk. */
    final long minimumRecordSize;

    /**
     * Whether the end of the records is known before the first record is walked. The layout forces
     * the answer: a General record is a fixed 36 bytes, so the minimum is exact, but a DX10 record
     * carries its own chunk count, so its end is known only after the last record. Both
     * index-overlap checks therefore run before the walk (filename table) and at each entry
     * (payload) for General, and after the walk for DX10. Moving General's checks after the walk
     * would change which Primary Failure a multi-fault General BA2 reports.
     */
    final boolean recordsEndKnownBeforeWalk;

    /**
     * Declares one subtype's wire constants.
     *
     * @param selector the subtype selector octets at offset 8, as a little-endian integer
     * @param subtype the subtype reported in the Archive Encoding
     * @param fallout4 the family reported for a Fallout 4 wire version
     * @param starfield the family reported for a Starfield wire version
     * @param minimumRecordSize the fewest bytes one record can occupy
     * @param recordsEndKnownBeforeWalk whether the records' end is known before the walk, which
     *     decides when the index-overlap checks run
     */
    RecordShape(
        int selector,
        Ba2Subtype subtype,
        ArchiveFamily fallout4,
        ArchiveFamily starfield,
        long minimumRecordSize,
        boolean recordsEndKnownBeforeWalk) {
      this.selector = selector;
      this.subtype = subtype;
      this.fallout4 = fallout4;
      this.starfield = starfield;
      this.minimumRecordSize = minimumRecordSize;
      this.recordsEndKnownBeforeWalk = recordsEndKnownBeforeWalk;
    }

    /**
     * Walks one entry's record at {@code field}, admitting its payload into the session.
     *
     * @return the offset just past the record, where the next record starts
     */
    abstract long walk(long ordinal, long field) throws IOException;

    /**
     * Builds the subtype's archive metadata once every record has been walked.
     *
     * @param count the declared entry count
     * @param nameOffset the stored filename table offset, reported even when no table exists
     * @param extraHeader the value at offset 24, present only for versions with an extra header
     */
    abstract ArchiveMetadata metadata(long count, long nameOffset, OptionalLong extraHeader);
  }

  /** Fixed 36-byte records, each naming one payload. */
  private final class General extends RecordShape {
    /** Declares the {@code GNRL} selector and its fixed 36-byte record. */
    General() {
      super(
          0x4c524e47,
          Ba2Subtype.GNRL,
          ArchiveFamily.FO4_GENERAL_BA2,
          ArchiveFamily.STARFIELD_GENERAL_BA2,
          36,
          true);
    }

    @Override
    long walk(long ordinal, long field) throws IOException {
      ByteBuffer record = session.readMetadata(field, 36);
      EntryMetadata.Ba2Identity prefix = identity(record);
      long offset = record.getLong(), packed = u32(record), unpacked = u32(record);
      long sentinel = u32(record);
      if (prefix.chunkCount() != 1) throw malformed("gnrl.invalid-chunk-count");
      EntryName name = name(prefix, ordinal, field);
      checkModIndexAndChunkHeaderSize(
          "gnrl.nonzero-mod-index", "gnrl.chunk-header-size", 16, ordinal, name, field, prefix);
      if (sentinel != 0xbaadf00dL)
        mismatch(
            "gnrl.sentinel-mismatch",
            session.location(ordinal, name.display(), "sentinel", field + 32, 4),
            hex(sentinel),
            "BAADF00D");
      long stored = packed == 0 ? unpacked : packed;
      span(offset, stored);
      EntryMetadata metadata =
          new EntryMetadata(
              family,
              archiveEncoding,
              ordinal,
              name.display(),
              name.identity(),
              name.wireNames(),
              unpacked,
              stored,
              new EntryMetadata.GeneralBa2(prefix, offset, packed, unpacked, sentinel));
      if (packed == 0) session.add(metadata, offset, stored, PayloadCodec.STORED);
      else session.add(metadata, offset, packed, codec());
      return field + 36;
    }

    @Override
    ArchiveMetadata metadata(long count, long nameOffset, OptionalLong extraHeader) {
      return new ArchiveMetadata.GeneralBa2(
          family, archiveEncoding, count, nameOffset, extraHeader);
    }
  }

  /** A 24-byte texture record followed by one to four 24-byte chunk records. */
  private final class Dx10 extends RecordShape {
    private final DdsTarget target;

    /**
     * Declares the {@code DX10} selector and its 48-byte minimum record, and resolves the DDS
     * target every generated header uses.
     *
     * @param options supplies the explicit DDS target, or the profile that permits inferring it
     */
    Dx10(OpenOptions options) {
      super(
          0x30315844,
          Ba2Subtype.DX10,
          ArchiveFamily.FO4_DDS_BA2,
          ArchiveFamily.STARFIELD_DDS_BA2,
          48,
          false);
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

    @Override
    long walk(long ordinal, long field) throws IOException {
      ByteBuffer record = session.readMetadata(field, 24);
      EntryMetadata.Ba2Identity prefix = identity(record);
      long chunks = prefix.chunkCount();
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
      long next = ExactIo.end(field + 24, chunks * 24, session.size(), context);
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
        span(offset, size);
        stored = ExactIo.end(stored, size, Long.MAX_VALUE, context);
        unpacked = ExactIo.end(unpacked, decoded, Long.MAX_VALUE, context);
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
      EntryName name = name(prefix, ordinal, field);
      checkModIndexAndChunkHeaderSize(
          "dx10.nonzero-mod-index", "dx10.chunk-header-size", 24, ordinal, name, field, prefix);
      byte[] ddsHeader =
          DdsEnvelope.canonicalHeader(
              width, height, mips, format, (flags & 1) != 0, tile, target, context);
      EntryMetadata metadata =
          new EntryMetadata(
              family,
              archiveEncoding,
              ordinal,
              name.display(),
              name.identity(),
              name.wireNames(),
              ExactIo.end(unpacked, ddsHeader.length, Long.MAX_VALUE, context),
              stored,
              new EntryMetadata.DdsBa2(
                  prefix, height, width, mips, format, flags, tile, textureChunks));
      session.addDds(metadata, ddsHeader, textureChunks, codec());
      return next;
    }

    @Override
    ArchiveMetadata metadata(long count, long nameOffset, OptionalLong extraHeader) {
      return new ArchiveMetadata.DdsBa2(family, archiveEncoding, count, nameOffset, extraHeader);
    }
  }

  /**
   * An entry's name: the decoded display name, or the synthetic one without a filename table.
   *
   * @param wire the complete wire name; null when the archive has no filename table
   */
  private record EntryName(
      String display, WireName wire, Optional<NormalizedNameIdentity> identity) {
    /**
     * Returns the entry's wire names keyed by component: the complete name, or none without a
     * filename table.
     */
    Map<String, WireName> wireNames() {
      return wire == null ? Map.of() : Map.of("complete", wire);
    }
  }

  /**
   * Reads the entry's next name from the filename table, if there is one, and diagnoses identity
   * fields that disagree with an ASCII name.
   */
  private EntryName name(EntryMetadata.Ba2Identity prefix, long ordinal, long field)
      throws IOException {
    if (!named) return new EntryName(Ba2Names.synthetic(prefix, ordinal), null, Optional.empty());
    int length = Short.toUnsignedInt(session.readMetadata(namePosition, 2).getShort());
    namePosition += 2;
    WireName wire = session.readName(namePosition, length);
    for (byte value : wire.bytes()) if (value == 0) throw malformed("ba2.nul-name");
    // Present undecodable bytes use ordinal wire identity, distinct from an absent table.
    IndexSession.Name name =
        session.decodeName(wire, ordinal, namePosition, Ba2Reader::fold, "ba2.duplicate-name");
    if (name.decoded() && Ba2Names.ascii(wire.bytes()))
      checkIdentity(prefix, Ba2Names.identity(wire.bytes()), ordinal, name.display(), field);
    namePosition += length;
    return new EntryName(name.display(), wire, name.identity());
  }

  /**
   * Parses the 16-byte identity prefix every BA2 record starts with: base name hash, extension,
   * directory hash, mod index, chunk count and chunk header size. Building the identity cannot
   * fail, because every field is read unsigned and the extension is always four octets, so parsing
   * it before a record's own checks moves no read, span check or diagnostic.
   *
   * @param record the record window, positioned at its first byte and advanced past the prefix
   */
  private static EntryMetadata.Ba2Identity identity(ByteBuffer record) {
    long baseHash = u32(record);
    byte[] extension = new byte[4];
    record.get(extension);
    long directoryHash = u32(record),
        mod = Byte.toUnsignedLong(record.get()),
        chunks = Byte.toUnsignedLong(record.get()),
        chunkSize = Short.toUnsignedLong(record.getShort());
    return new EntryMetadata.Ba2Identity(
        baseHash, new WireName(extension), directoryHash, mod, chunks, chunkSize);
  }

  /** Diagnoses a nonzero mod index and a chunk header size other than the subtype's. */
  private void checkModIndexAndChunkHeaderSize(
      String modId,
      String chunkSizeId,
      long expectedChunkSize,
      long ordinal,
      EntryName name,
      long field,
      EntryMetadata.Ba2Identity prefix) {
    if (prefix.modIndex() != 0)
      mismatch(
          modId,
          session.location(ordinal, name.display(), "modIndex", field + 12, 1),
          Long.toString(prefix.modIndex()),
          "0");
    if (prefix.chunkHeaderSize() != expectedChunkSize)
      mismatch(
          chunkSizeId,
          session.location(ordinal, name.display(), "chunkHeaderSize", field + 14, 2),
          Long.toString(prefix.chunkHeaderSize()),
          Long.toString(expectedChunkSize));
  }

  /** Rejects a filename table that starts inside the records. */
  private void checkNamesAfterRecords(long nameOffset) throws ArchiveException {
    if (named && nameOffset < recordsEnd) throw malformed("ba2.names-overlap-index");
  }

  /**
   * Bounds one payload span and registers it for the overlap and trailing-data checks. Whether it
   * starts inside the records is judged here when the record shape knows where they end, and after
   * the walk otherwise.
   */
  private void span(long offset, long size) throws ArchiveException {
    if (shape.recordsEndKnownBeforeWalk) {
      if (offset < recordsEnd) throw malformed("ba2.payload-overlaps-index");
    } else firstPayload = Math.min(firstPayload, offset);
    long end = ExactIo.end(offset, size, session.size(), context);
    session.payload(offset, end);
    // Empty spans reference no bytes, so an EOF coordinate cannot hide trailing data.
    if (size > 0) usedEnd = Math.max(usedEnd, end);
  }

  /** Compressed payloads use raw LZ4 only under a version 3 method 3 header, and zlib otherwise. */
  private PayloadCodec codec() {
    return rawLz4 ? PayloadCodec.LZ4_RAW : PayloadCodec.ZLIB;
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
