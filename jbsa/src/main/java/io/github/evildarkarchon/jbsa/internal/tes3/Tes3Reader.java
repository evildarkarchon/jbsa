package io.github.evildarkarchon.jbsa.internal.tes3;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.*;
import java.util.function.UnaryOperator;

/** Bounded eager TES3 metadata decoding; stored payload bytes stay on the owned source handle. */
public final class Tes3Reader {
  private static final Set<String> UNSUPPORTED_ROOTS =
      Set.of("sound", "music", "video", "fonts", "splash");
  private final IndexSession session;
  private final IoContext context;

  /** Binds the walk to the session that owns its reads, rules and assessment. */
  private Tes3Reader(IndexSession session) {
    this.session = session;
    context = session.context();
  }

  /**
   * Walks a TES3 index into {@code session} without reading payloads; the session owns the failure
   * handling and the Archive Assessment.
   */
  public static ArchiveMetadata walk(IndexSession session) throws IOException {
    return new Tes3Reader(session).walk();
  }

  /** Checks all section boundaries before index allocation, then admits every payload span. */
  private ArchiveMetadata walk() throws IOException {
    ByteBuffer header = session.readMetadata(0, 12);
    if (header.getInt() != 0x100) throw malformed("tes3.invalid-magic");
    long hashOffset = Integer.toUnsignedLong(header.getInt());
    long count = Integer.toUnsignedLong(header.getInt());
    long hashStart = ExactIo.end(12, hashOffset, session.size(), context);
    long namesStart = ExactIo.end(12, ExactIo.multiply(count, 12, context), hashStart, context);
    long dataBase =
        ExactIo.end(hashStart, ExactIo.multiply(count, 8, context), session.size(), context);
    session.declareEntries(count);
    long cursor = namesStart;
    NameCursor nameCursor = new NameCursor(namesStart, hashStart);
    long payloadEnd = dataBase;
    for (int ordinal = 0; ordinal < count; ordinal++) {
      ByteBuffer record = session.readMetadata(12L + ordinal * 8L, 8);
      long size = Integer.toUnsignedLong(record.getInt());
      long relativeOffset = Integer.toUnsignedLong(record.getInt());
      long offsetField = 12L + count * 8L + ordinal * 4L;
      long nameOffset = Integer.toUnsignedLong(session.readMetadata(offsetField, 4).getInt());
      if (nameOffset >= hashStart - namesStart) throw malformed("tes3.invalid-name-offset");
      long nameStart = cursor;
      // Sequential boundaries remain authoritative when a bounded offset field is inconsistent.
      WireName wire = nameCursor.next();
      cursor = nameCursor.position;
      byte[] bytes = wire.bytes();
      IndexSession.Name decoded =
          session.decodeName(
              wire, ordinal, nameStart, UnaryOperator.identity(), "tes3.duplicate-name");
      String name = decoded.display();
      Optional<NormalizedNameIdentity> identity = decoded.identity();
      if (nameOffset != nameStart - namesStart) {
        session.noncanonical(
            "tes3.name-offset-inconsistency",
            session.location(ordinal, name, "nameOffset", offsetField, 4),
            Map.of(
                "stored",
                Long.toString(nameOffset),
                "expected",
                Long.toString(nameStart - namesStart)));
      }
      long hashField = hashStart + ordinal * 8L;
      long hash = session.readMetadata(hashField, 8).getLong();
      boolean ascii = true;
      for (byte value : bytes) if (value < 0) ascii = false;
      if (ascii && identity.isPresent()) {
        long expected = Tes3Names.hash(Tes3Names.canonicalize(bytes));
        if (hash != expected)
          session.noncanonical(
              "tes3.stored-hash-mismatch",
              session.location(ordinal, name, "nameHash", hashField, 8),
              Map.of(
                  "stored",
                  String.format(Locale.ROOT, "%016X", hash),
                  "expected",
                  String.format(Locale.ROOT, "%016X", expected)));
      }
      long dataOffset = ExactIo.end(dataBase, relativeOffset, session.size(), context);
      long end = ExactIo.end(dataOffset, size, session.size(), context);
      session.payload(dataOffset, end);
      payloadEnd = Math.max(payloadEnd, end);
      if (dataOffset > Integer.MAX_VALUE)
        session.warning(
            "tes3.payload-offset-over-signed-2gib",
            session.location(ordinal, name, "dataOffset", dataOffset, 0),
            Map.of(
                "stored",
                Long.toString(dataOffset),
                "expected",
                Integer.toString(Integer.MAX_VALUE)));
      String normalized = name.replace('/', '\\');
      int separator = normalized.indexOf('\\');
      String root = separator < 0 ? normalized : normalized.substring(0, separator);
      StringBuilder foldedRoot = new StringBuilder(root.length());
      for (int index = 0; index < root.length(); index++) {
        char value = root.charAt(index);
        foldedRoot.append(value >= 'A' && value <= 'Z' ? (char) (value + 32) : value);
      }
      root = foldedRoot.toString();
      if (separator >= 0 && UNSUPPORTED_ROOTS.contains(root))
        session.warning(
            "tes3.unsupported-asset-root",
            session.location(ordinal, name, "complete", nameStart, bytes.length),
            Map.of("stored", root));
      EntryMetadata metadata =
          new EntryMetadata(
              ArchiveFamily.TES3_BSA,
              ArchiveEncoding.tes3(),
              ordinal,
              name,
              identity,
              Map.of("complete", wire),
              size,
              size,
              new EntryMetadata.Tes3(hash, nameOffset, relativeOffset, dataOffset));
      session.add(metadata, dataOffset, size, OwnedArchive.PayloadCodec.STORED);
    }
    if (cursor != hashStart) throw malformed("tes3.invalid-name-block");
    session.checkOverlaps("tes3.overlapping-payloads");
    // Empty entries count toward the used extent, so an empty entry at EOF claims no trailing data.
    session.trailingData(
        "tes3.trailing-data",
        payloadEnd,
        Map.of("stored", Long.toString(session.size() - payloadEnd), "expected", "0"));
    return new ArchiveMetadata.Tes3(count, hashOffset, dataBase);
  }

  /** Creates a format failure whose rejected structural assessment is attached by the session. */
  private ArchiveException malformed(String identifier) {
    return session.malformed(identifier);
  }

  /** Streams each name-block byte once, billing metadata before allocating retained name bytes. */
  private final class NameCursor {
    private final long end;
    private long position;
    private ByteBuffer window = ByteBuffer.allocate(0);

    private NameCursor(long start, long end) {
      position = start;
      this.end = end;
    }

    /** Returns one exact name without its terminator, or rejects an unterminated bounded block. */
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
      throw malformed("tes3.unterminated-name");
    }
  }
}
