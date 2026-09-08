package io.github.evildarkarchon.jbsa.internal.bsa;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import io.github.evildarkarchon.jbsa.internal.tes3.Tes3Names;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;

/** Bounded Oblivion metadata reader; the parent archive owns lazy payload access. */
public final class BsaReader {
  private final OwnedArchive.IndexBuilder builder;
  private final IoContext context;
  private final Charset encoding;
  private final FailureRetention retention;
  private boolean noncanonical;
  private static final ArchiveEncoding ENCODING =
      new ArchiveEncoding(
          Optional.of(new WireVersion(0x67)), Optional.empty(), OptionalLong.empty());

  private BsaReader(
      OwnedArchive.IndexBuilder builder,
      Path path,
      OpenOptions options,
      Operation operation,
      DiagnosticPolicy policy)
      throws ArchiveException {
    this.builder = builder;
    context = IoContext.of(path, operation);
    encoding = Tes3Names.encoding(options.compatibilityProfile(), context);
    retention = new FailureRetention(options.resourceLimits(), operation, policy);
  }

  /** Loads structural metadata with standard warning policy. */
  public static ArchiveInspection load(
      OwnedArchive.IndexBuilder builder, Path path, OpenOptions options, Operation operation)
      throws IOException {
    return load(builder, path, options, operation, DiagnosticPolicy.standard());
  }

  /** Applies warning policy before retention limits and attaches rejected structural evidence. */
  public static ArchiveInspection load(
      OwnedArchive.IndexBuilder builder,
      Path path,
      OpenOptions options,
      Operation operation,
      DiagnosticPolicy policy)
      throws IOException {
    BsaReader reader = new BsaReader(builder, path, options, operation, policy);
    ArchiveInspection result;
    try {
      result = reader.parse();
    } catch (ArchiveException failure) {
      reader.retention.accept(failure);
      if (failure.kind() == FailureKind.FORMAT)
        reader.retention.latestAssessment(
            new ArchiveAssessment(
                ArchiveDisposition.REJECTED,
                new ValidationExtent.Structure(),
                reader.retention.diagnostics()));
      throw reader.retention.finish(List.of());
    }
    ArchiveException rejected = reader.retention.finish(List.of());
    if (rejected != null) throw rejected;
    return result;
  }

  /** Checks section arithmetic before allocation and preserves serialized name association. */
  private ArchiveInspection parse() throws IOException {
    ByteBuffer header = builder.readMetadata(0, 36);
    if (header.getInt() != 0x00415342 || header.getInt() != 0x67)
      throw malformed("bsa.invalid-selector");
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
    long blocksStart =
        ExactIo.end(
            recordsOffset, ExactIo.multiply(folderCount, 16, context), builder.size(), context);
    long namesStart =
        ExactIo.end(blocksStart, ExactIo.multiply(count, 16, context), builder.size(), context);
    namesStart = ExactIo.end(namesStart, folderNamesLength, builder.size(), context);
    if ((flags & 1) != 0)
      namesStart = ExactIo.end(namesStart, folderCount, builder.size(), context);
    long dataStart = ExactIo.end(namesStart, fileNamesLength, builder.size(), context);
    builder.declareEntries(count);
    List<EntryMetadata> entries = new ArrayList<>();
    List<Span> spans = new ArrayList<>();
    Set<NormalizedNameIdentity> identities = new HashSet<>();
    NameCursor names = new NameCursor(namesStart, dataStart);
    long block = blocksStart, ordinal = 0, folderBytes = 0;
    for (long folderOrdinal = 0; folderOrdinal < folderCount; folderOrdinal++) {
      long folderField = recordsOffset + folderOrdinal * 16;
      ByteBuffer folder = builder.readMetadata(folderField, 16);
      long folderHash = folder.getLong(), files = u32(folder), folderOffset = u32(folder);
      if (files > count - ordinal || folderOffset != block + fileNamesLength)
        throw malformed("bsa.invalid-folder-record");
      WireName folderName = null;
      long folderNameStart = block;
      if ((flags & 1) != 0) {
        int length = Byte.toUnsignedInt(builder.readMetadata(block++, 1).get());
        if (length == 0) throw malformed("bsa.invalid-folder-name");
        ExactIo.end(block, length, namesStart, context);
        byte[] bytes = builder.readName(block, length).bytes();
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
        ByteBuffer file = builder.readMetadata(block, 16);
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
        String folderText = decode(folderName, ordinal, synthetic, "folder", folderNameStart);
        String basenameText = decode(basename, ordinal, synthetic, "basename", nameStart);
        if (folderName != null) wire.put("folder", folderName);
        if (basename != null) wire.put("basename", basename);
        String display =
            folderText != null && basenameText != null
                ? folderText + "\\" + basenameText
                : synthetic;
        Optional<NormalizedNameIdentity> identity =
            folderText != null && basenameText != null
                ? NormalizedNameIdentity.from(display, encoding)
                : Optional.empty();
        if (identity.isPresent() && !identities.add(identity.orElseThrow()))
          throw malformed("bsa.duplicate-name");
        if (local == 0)
          checkHash(folderName, false, folderHash, ordinal, display, "folderHash", folderField);
        checkHash(basename, true, hash, ordinal, display, "nameHash", fileField);
        if (offset < dataStart) throw malformed("bsa.payload-overlaps-index");
        long end = ExactIo.end(offset, size, builder.size(), context);
        if (offset > Integer.MAX_VALUE)
          warning(
              "bsa.payload-offset-over-signed-2gib",
              ordinal,
              display,
              "dataOffset",
              offset,
              0,
              Map.of(
                  "stored", Long.toString(offset), "expected", Integer.toString(Integer.MAX_VALUE)),
              false);
        if (size > 0) spans.add(new Span(offset, end));
        boolean compressed = ((flags & 4) != 0) ^ ((sizeToggle & 0x40000000L) != 0);
        // Oblivion's 0x0100 and XMem markers do not introduce embedded names or change zlib.
        if (compressed && size < 4) throw malformed("bsa.invalid-compressed-framing");
        long decodedSize = compressed ? u32(builder.readMetadata(offset, 4)) : size;
        if (basenameText != null
            && basenameText.toLowerCase(Locale.ROOT).endsWith(".dds")
            && decodedSize >= 128) {
          ByteBuffer dds =
              builder.readPayloadPrefix(
                  offset + (compressed ? 4 : 0),
                  size - (compressed ? 4 : 0),
                  decodedSize,
                  compressed,
                  128);
          if (dds.remaining() == 128
              && dds.getInt(0) == 0x20534444
              && dds.getInt(4) == 124
              && dds.getInt(76) == 32
              && (dds.getInt(112) & 0x200) != 0)
            warning(
                "bsa.cubemap-without-embedded-name",
                ordinal,
                display,
                "payload",
                offset,
                size,
                Map.of(),
                false);
        }
        EntryMetadata metadata =
            new EntryMetadata(
                ArchiveFamily.TES4_BSA,
                ENCODING,
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
                    0,
                    0,
                    sizeToggle,
                    offset,
                    compressed));
        if (compressed) builder.addZlib(metadata, offset + 4, size - 4);
        else builder.addStored(metadata, offset);
        entries.add(metadata);
      }
    }
    if (ordinal != count
        || folderBytes != folderNamesLength
        || block != namesStart
        || names.position != dataStart) throw malformed("bsa.invalid-section-lengths");
    spans.sort(Comparator.comparingLong(Span::start).thenComparingLong(Span::end));
    Span previous = null;
    for (Span span : spans) {
      if (previous != null && span.start < previous.end && !span.equals(previous))
        throw malformed("bsa.overlapping-payloads");
      previous = span;
    }
    ArchiveAssessment assessment =
        new ArchiveAssessment(
            noncanonical
                ? ArchiveDisposition.TOLERATED_NONCANONICAL
                : ArchiveDisposition.CONFORMING,
            new ValidationExtent.Structure(),
            retention.diagnostics());
    retention.latestAssessment(assessment);
    ArchiveDetection detection =
        new ArchiveDetection(
            DetectionStatus.SUPPORTED_FAMILY,
            new WireName(new byte[] {66, 83, 65, 0}),
            Optional.of(ArchiveFamily.TES4_BSA),
            ENCODING.wireVersion(),
            Optional.empty(),
            OptionalLong.empty());
    return new ArchiveInspection(
        detection,
        new ArchiveMetadata.VersionedBsa(
            ArchiveFamily.TES4_BSA,
            ENCODING,
            count,
            recordsOffset,
            flags,
            folderCount,
            folderNamesLength,
            fileNamesLength,
            fileFlags),
        assessment,
        entries);
  }

  /** Decodes strictly, keeping absent and undecodable components unresolved. */
  private String decode(
      WireName name, long ordinal, String display, String component, long offset) {
    if (name == null) return null;
    try {
      return encoding.newDecoder().decode(ByteBuffer.wrap(name.bytes())).toString();
    } catch (CharacterCodingException failure) {
      warning(
          "archive-name.undecodable-wire-bytes",
          ordinal,
          display,
          component,
          offset,
          name.length(),
          Map.of(),
          true);
      return null;
    }
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
    long expected = BsaNames.hash(BsaNames.canonicalize(name.bytes()), basename);
    if (hash != expected)
      warning(
          basename ? "bsa.file-hash-mismatch" : "bsa.folder-hash-mismatch",
          ordinal,
          display,
          field,
          offset,
          8,
          Map.of(
              "stored",
              String.format(Locale.ROOT, "%016X", hash),
              "expected",
              String.format(Locale.ROOT, "%016X", expected)),
          true);
  }

  /** Retains bounded warning evidence independently of intrinsic canonicality. */
  private void warning(
      String identifier,
      long ordinal,
      String name,
      String field,
      long offset,
      long length,
      Map<String, String> values,
      boolean canonicality) {
    noncanonical |= canonicality;
    retention.diagnostic(
        new Diagnostic(
            identifier,
            DiagnosticSeverity.WARNING,
            context.operation(),
            context.phase(),
            new DiagnosticLocation(
                Optional.of(context.path()),
                ordinal < 0 ? OptionalLong.empty() : OptionalLong.of(ordinal),
                Optional.ofNullable(name),
                Optional.of(field),
                Optional.of(new DiagnosticLocation.ByteSpan(offset, length)),
                Optional.empty()),
            new TreeMap<>(values),
            Optional.empty()));
  }

  private static long u32(ByteBuffer bytes) {
    return Integer.toUnsignedLong(bytes.getInt());
  }

  private ArchiveException malformed(String identifier) {
    return context.failure(FailureKind.FORMAT, identifier, null);
  }

  private record Span(long start, long end) {}

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
          window = builder.readMetadata(position, (int) Math.min(65536, end - position));
        byte value = window.get();
        position++;
        if (value == 0) return new WireName(bytes.toByteArray());
        bytes.write(value);
      }
      throw malformed("bsa.unterminated-basename");
    }
  }
}
