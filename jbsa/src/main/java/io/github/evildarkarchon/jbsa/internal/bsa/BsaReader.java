package io.github.evildarkarchon.jbsa.internal.bsa;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;

/** Bounded common 0x67/0x68/0x69 BSA reader; the parent archive owns lazy payload access. */
public final class BsaReader {
  private final IndexSession session;
  private final IoContext context;
  private int version;
  private ArchiveFamily family;
  private ArchiveEncoding archiveEncoding;

  /** Binds the walk to the session that owns its reads, rules and assessment. */
  private BsaReader(IndexSession session) {
    this.session = session;
    context = session.context();
  }

  /**
   * Walks a 0x67, 0x68 or 0x69 index into {@code session} without reading payloads beyond the
   * bounded cubemap probe; the session owns the failure handling and the Archive Assessment.
   */
  public static ArchiveMetadata walk(IndexSession session) throws IOException {
    return new BsaReader(session).walk();
  }

  /** Checks section arithmetic before allocation and preserves serialized name association. */
  private ArchiveMetadata walk() throws IOException {
    ByteBuffer header = session.readMetadata(0, 36);
    if (header.getInt() != 0x00415342) throw malformed("bsa.invalid-selector");
    version = header.getInt();
    if (version != 0x67 && version != 0x68 && version != 0x69)
      throw malformed("bsa.invalid-selector");
    family =
        switch (version) {
          case 0x67 -> ArchiveFamily.TES4_BSA;
          case 0x68 -> ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA;
          default -> ArchiveFamily.SSE_BSA;
        };
    archiveEncoding =
        new ArchiveEncoding(
            Optional.of(new WireVersion(version)), Optional.empty(), OptionalLong.empty());
    long recordsOffset = u32(header),
        flags = u32(header),
        folderCount = u32(header),
        count = u32(header),
        folderNamesLength = u32(header),
        fileNamesLength = u32(header),
        fileFlags = u32(header);
    if (recordsOffset < 36
        || ((flags & 1) == 0 && folderNamesLength != 0)
        || ((flags & 2) == 0 && fileNamesLength != 0)) throw malformed("bsa.invalid-name-sections");
    int folderRecordSize = version == 0x69 ? 24 : 16;
    long blocksStart =
        ExactIo.end(
            recordsOffset,
            ExactIo.multiply(folderCount, folderRecordSize, context),
            session.size(),
            context);
    long namesStart =
        ExactIo.end(blocksStart, ExactIo.multiply(count, 16, context), session.size(), context);
    namesStart = ExactIo.end(namesStart, folderNamesLength, session.size(), context);
    if ((flags & 1) != 0)
      namesStart = ExactIo.end(namesStart, folderCount, session.size(), context);
    long dataStart = ExactIo.end(namesStart, fileNamesLength, session.size(), context);
    session.declareEntries(count);
    NameCursor names = new NameCursor(namesStart, dataStart);
    long block = blocksStart, ordinal = 0, folderBytes = 0;
    for (long folderOrdinal = 0; folderOrdinal < folderCount; folderOrdinal++) {
      long folderField = recordsOffset + folderOrdinal * folderRecordSize;
      ByteBuffer folder = session.readMetadata(folderField, folderRecordSize);
      long folderHash = folder.getLong(), files = u32(folder), paddingBefore = 0, paddingAfter = 0;
      if (version == 0x69) paddingBefore = u32(folder);
      long folderOffset = u32(folder);
      if (version == 0x69) {
        paddingAfter = u32(folder);
        // Padding is ignorable for bounded parsing but cannot be produced canonically, so each
        // nonzero field is a diagnosed noncanonical warning (JBSA-BSA-004, JBSA-BSA-014).
        checkPadding(paddingBefore, "paddingBefore", folderField + 12);
        checkPadding(paddingAfter, "paddingAfter", folderField + 20);
      }
      if (files > count - ordinal || folderOffset != block + fileNamesLength)
        throw malformed("bsa.invalid-folder-record");
      WireName folderName = null;
      long folderNameStart = block;
      if ((flags & 1) != 0) {
        int length = Byte.toUnsignedInt(session.readMetadata(block++, 1).get());
        if (length == 0) throw malformed("bsa.invalid-folder-name");
        ExactIo.end(block, length, namesStart, context);
        byte[] bytes = session.readName(block, length).bytes();
        if (bytes[length - 1] != 0) throw malformed("bsa.unterminated-folder-name");
        for (int i = 0; i < length - 1; i++)
          if (bytes[i] == 0) throw malformed("bsa.invalid-folder-name");
        folderNameStart = block;
        folderName = new WireName(Arrays.copyOf(bytes, length - 1));
        folderBytes += length;
        block += length;
      }
      ExactIo.end(block, ExactIo.multiply(files, 16, context), namesStart, context);
      // Empty folders have hash identity too, but no entry ordinal or complete display name.
      if (files == 0) checkHash(folderName, false, folderHash, -1, null, "folderHash", folderField);
      for (long local = 0; local < files; local++, ordinal++) {
        long fileField = block;
        ByteBuffer file = session.readMetadata(block, 16);
        block += 16;
        long hash = file.getLong(), sizeToggle = u32(file), offset = u32(file);
        long size = sizeToggle & ~0x40000000L;
        long nameStart = names.position;
        WireName basename = (flags & 2) != 0 ? names.next() : null;
        Map<String, WireName> wire = new HashMap<>();
        String synthetic =
            String.format(
                Locale.ROOT,
                "__jbsa_hash__\\f%08x-%016x\\e%08x-%016x",
                folderOrdinal,
                folderHash,
                ordinal,
                hash);
        String folderText =
            session.decodeComponent(folderName, ordinal, synthetic, "folder", folderNameStart);
        String basenameText =
            session.decodeComponent(basename, ordinal, synthetic, "basename", nameStart);
        if (folderName != null) wire.put("folder", folderName);
        if (basename != null) wire.put("basename", basename);
        String display =
            folderText != null && basenameText != null
                ? folderText + "\\" + basenameText
                : synthetic;
        Optional<NormalizedNameIdentity> identity =
            folderText != null && basenameText != null
                ? session.identify(display, "bsa.duplicate-name")
                : Optional.empty();
        if (local == 0)
          checkHash(folderName, false, folderHash, ordinal, display, "folderHash", folderField);
        checkHash(basename, true, hash, ordinal, display, "nameHash", fileField);
        if (offset < dataStart) throw malformed("bsa.payload-overlaps-index");
        long end = ExactIo.end(offset, size, session.size(), context);
        if (offset > Integer.MAX_VALUE)
          session.warning(
              "bsa.payload-offset-over-signed-2gib",
              session.location(ordinal, display, "dataOffset", offset, 0),
              Map.of(
                  "stored",
                  Long.toString(offset),
                  "expected",
                  Integer.toString(Integer.MAX_VALUE)));
        session.payload(offset, end);
        boolean compressed = ((flags & 4) != 0) ^ ((sizeToggle & 0x40000000L) != 0);
        // Oblivion's 0x0100 and XMem markers do not introduce embedded names or change zlib.
        boolean embedded = version != 0x67 && (flags & 0x100) != 0;
        long contentOffset = offset, contentSize = size;
        if (embedded) {
          if (contentSize == 0) throw malformed("bsa.invalid-embedded-name-framing");
          int length = Byte.toUnsignedInt(session.readMetadata(contentOffset++, 1).get());
          ExactIo.end(contentOffset, length, end, context);
          WireName embeddedName = session.readName(contentOffset, length);
          wire.put("embedded", embeddedName);
          if (folderName != null && basename != null) {
            // Compare the bounded prefix directly; a long index name must not allocate a joined
            // copy.
            byte[] embeddedBytes = embeddedName.bytes();
            byte[] folderBytesForName = folderName.bytes(), basenameBytes = basename.bytes();
            boolean matches = folderName.length() + 1 + basename.length() == length;
            if (matches) {
              int folderLength = folderBytesForName.length;
              matches =
                  Arrays.equals(folderBytesForName, 0, folderLength, embeddedBytes, 0, folderLength)
                      && embeddedBytes[folderLength] == '\\'
                      && Arrays.equals(
                          basenameBytes,
                          0,
                          basenameBytes.length,
                          embeddedBytes,
                          folderLength + 1,
                          length);
            }
            if (!matches)
              session.noncanonical(
                  "bsa.embedded-name-mismatch",
                  session.location(ordinal, display, "embedded", contentOffset, length),
                  Map.of());
          }
          if (basenameText != null && !basenameText.toLowerCase(Locale.ROOT).endsWith(".dds"))
            session.warning(
                "bsa.nontexture-with-embedded-name",
                session.location(ordinal, display, "embedded", contentOffset, length),
                Map.of());
          if (version == 0x69 && !compressed)
            session.warning(
                "bsa.uncompressed-sse-embedded-name",
                session.location(ordinal, display, "embedded", contentOffset, length),
                Map.of());
          contentOffset += length;
          contentSize -= 1L + length;
        }
        if (compressed && contentSize < 4) throw malformed("bsa.invalid-compressed-framing");
        long decodedSize = compressed ? u32(session.readMetadata(contentOffset, 4)) : contentSize;
        if (!embedded
            && basenameText != null
            && basenameText.toLowerCase(Locale.ROOT).endsWith(".dds")
            && decodedSize >= 128) {
          ByteBuffer dds =
              session.readPayloadPrefix(
                  contentOffset + (compressed ? 4 : 0),
                  contentSize - (compressed ? 4 : 0),
                  decodedSize,
                  compressed
                      ? version == 0x69
                          ? OwnedArchive.PayloadCodec.LZ4_FRAME
                          : OwnedArchive.PayloadCodec.ZLIB
                      : OwnedArchive.PayloadCodec.STORED,
                  128);
          if (dds.remaining() == 128
              && dds.getInt(0) == 0x20534444
              && dds.getInt(4) == 124
              && dds.getInt(76) == 32
              && (dds.getInt(112) & 0x200) != 0)
            session.warning(
                "bsa.cubemap-without-embedded-name",
                session.location(ordinal, display, "payload", offset, size),
                Map.of());
        }
        EntryMetadata metadata =
            new EntryMetadata(
                family,
                archiveEncoding,
                ordinal,
                display,
                identity,
                wire,
                decodedSize,
                size,
                new EntryMetadata.VersionedBsa(
                    folderHash,
                    hash,
                    folderOrdinal,
                    files,
                    folderOffset,
                    paddingBefore,
                    paddingAfter,
                    sizeToggle,
                    offset,
                    compressed));
        if (compressed)
          session.add(
              metadata,
              contentOffset + 4,
              contentSize - 4,
              version == 0x69
                  ? OwnedArchive.PayloadCodec.LZ4_FRAME
                  : OwnedArchive.PayloadCodec.ZLIB);
        else session.add(metadata, contentOffset, contentSize, OwnedArchive.PayloadCodec.STORED);
      }
    }
    if (ordinal != count
        || folderBytes != folderNamesLength
        || block != namesStart
        || names.position != dataStart) throw malformed("bsa.invalid-section-lengths");
    // Versioned BSA defines no trailing-data rule, so bytes after the last payload are not checked.
    session.checkOverlaps("bsa.overlapping-payloads");
    return new ArchiveMetadata.VersionedBsa(
        family,
        archiveEncoding,
        count,
        recordsOffset,
        flags,
        folderCount,
        folderNamesLength,
        fileNamesLength,
        fileFlags);
  }

  /** Compares qualified ASCII components, excluding empty-stem basenames. */
  private void checkHash(
      WireName name,
      boolean basename,
      long hash,
      long ordinal,
      String display,
      String field,
      long offset) {
    if (name == null
        || !BsaNames.ascii(name.bytes())
        || (basename && !BsaNames.hasStem(name.bytes()))) return;
    long expected = BsaNames.hash(BsaNames.canonicalize(name.bytes()), basename, version);
    if (hash != expected)
      session.noncanonical(
          basename ? "bsa.file-hash-mismatch" : "bsa.folder-hash-mismatch",
          session.location(ordinal, display, field, offset, 8),
          Map.of(
              "stored",
              String.format(Locale.ROOT, "%016X", hash),
              "expected",
              String.format(Locale.ROOT, "%016X", expected)));
  }

  /**
   * Warns once for a nonzero 0x69 folder padding field and marks the archive noncanonical.
   *
   * <p>Padding belongs to a folder record rather than an entry, so the location carries no entry
   * ordinal or name; the field and its 4-byte span identify it.
   *
   * @param stored unsigned value read from the padding field
   * @param field {@code paddingBefore} or {@code paddingAfter}
   * @param offset absolute offset of the padding field
   */
  private void checkPadding(long stored, String field, long offset) {
    if (stored != 0)
      session.noncanonical(
          "bsa.nonzero-folder-padding",
          session.archiveLocation(field, offset, 4),
          Map.of("stored", Long.toString(stored), "expected", "0"));
  }

  private static long u32(ByteBuffer bytes) {
    return Integer.toUnsignedLong(bytes.getInt());
  }

  private ArchiveException malformed(String identifier) {
    return session.malformed(identifier);
  }

  /** Reads each basename byte once within its declared metadata extent. */
  private final class NameCursor {
    private long position;
    private final long end;
    private ByteBuffer window = ByteBuffer.allocate(0);

    private NameCursor(long start, long end) {
      position = start;
      this.end = end;
    }

    /** Returns one terminated basename or rejects an incomplete name table. */
    private WireName next() throws IOException {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      while (position < end) {
        if (!window.hasRemaining())
          window = session.readMetadata(position, (int) Math.min(65536, end - position));
        byte value = window.get();
        position++;
        if (value == 0) return new WireName(bytes.toByteArray());
        bytes.write(value);
      }
      throw malformed("bsa.unterminated-basename");
    }
  }
}
