package io.github.evildarkarchon.jbsa.internal.ba2;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import io.github.evildarkarchon.jbsa.internal.tes3.Tes3Names;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;

/** Bounded DDS BA2 index reader with parent-owned lazy payload access. */
public final class DdsBa2Reader {
  private final OwnedArchive.IndexBuilder builder;
  private final IoContext context;
  private final Charset encoding;
  private final DdsTarget target;
  private final Optional<CompatibilityProfile> compatibilityProfile;
  private final FailureRetention retention;
  private boolean noncanonical;

  /** Binds metadata accounting and warning retention to the owning operation. */
  private DdsBa2Reader(
      OwnedArchive.IndexBuilder builder,
      Path path,
      OpenOptions options,
      Operation operation,
      DiagnosticPolicy policy)
      throws ArchiveException {
    this.builder = builder;
    context = IoContext.of(path, operation);
    encoding = Tes3Names.encoding(options.compatibilityProfile(), context);
    compatibilityProfile = options.compatibilityProfile();
    // Filename inference is an explicit profile deviation; operation target data always wins.
    target =
        options
            .ddsTarget()
            .orElse(
                options.compatibilityProfile().isPresent()
                        && path.getFileName().toString().toLowerCase(Locale.ROOT).contains("_xbox.")
                    ? DdsTarget.XBOX
                    : DdsTarget.PC);
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
    DdsBa2Reader reader = new DdsBa2Reader(builder, path, options, operation, policy);
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

  /** Bounds every metadata and payload span before admitting the serialized entry index. */
  private ArchiveInspection parse() throws IOException {
    ByteBuffer header = builder.readMetadata(0, 24);
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
      long storedExtra = builder.readMetadata(24, 8).getLong();
      extraHeader = OptionalLong.of(storedExtra);
      if (storedExtra != 1)
        mismatch(
            "ba2.extra-header-value",
            -1,
            null,
            "unknownValueAt24",
            24,
            8,
            Long.toUnsignedString(storedExtra),
            "1");
    }
    if (version == 3) {
      long method = u32(builder.readMetadata(32, 4));
      compressionMethod = OptionalLong.of(method);
      rawLz4 = method == 3;
      if (!rawLz4) {
        if (compatibilityProfile.isEmpty()) throw unsupported("archive.unsupported-encoding");
        warning(
            "ba2.sf3-zlib-fallback",
            -1,
            null,
            "compressionMethod",
            32,
            4,
            Map.of("stored", Long.toUnsignedString(method)));
      }
    }
    ArchiveFamily family =
        Ba2Layout.isFallout4(version) ? ArchiveFamily.FO4_DDS_BA2 : ArchiveFamily.STARFIELD_DDS_BA2;
    ArchiveEncoding archiveEncoding =
        new ArchiveEncoding(
            Optional.of(new WireVersion(version)), Optional.of(Ba2Subtype.DX10), compressionMethod);
    ExactIo.end(headerSize, ExactIo.multiply(count, 48, context), builder.size(), context);
    long recordsEnd = headerSize;
    builder.declareEntries(count);
    boolean named = nameOffset > 0 && nameOffset < builder.size();
    if (!named)
      warning(
          "ba2.missing-name-table",
          -1,
          null,
          "fileNameTableOffset",
          16,
          8,
          Map.of("stored", Long.toString(nameOffset)));

    long namePosition = nameOffset, usedEnd = recordsEnd;
    List<EntryMetadata> entries = new ArrayList<>();
    List<Span> spans = new ArrayList<>();
    Set<NormalizedNameIdentity> identities = new HashSet<>();
    for (long ordinal = 0; ordinal < count; ordinal++) {
      long field = recordsEnd;
      ByteBuffer record = builder.readMetadata(field, 24);
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
      recordsEnd = ExactIo.end(field + 24, chunks * 24, builder.size(), context);
      List<EntryMetadata.DdsChunk> textureChunks = new ArrayList<>();
      long stored = 0, unpacked = 0;
      int nextMip = 0;
      for (int chunk = 0; chunk < chunks; chunk++) {
        long chunkField = field + 24 + chunk * 24;
        ByteBuffer chunkRecord = builder.readMetadata(chunkField, 24);
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
        long end = ExactIo.end(offset, size, builder.size(), context);
        stored = ExactIo.end(stored, size, Long.MAX_VALUE, context);
        unpacked = ExactIo.end(unpacked, decoded, Long.MAX_VALUE, context);
        spans.add(new Span(offset, end));
        if (size > 0) usedEnd = Math.max(usedEnd, end);
        if (packed == 0)
          warning(
              "dx10.stored-chunk",
              ordinal,
              null,
              "chunkSizes",
              chunkField + 8,
              8,
              Map.of("stored", "0", "unpacked", Long.toString(decoded)));
        if (sentinel != 0xbaadf00dL)
          mismatch(
              "dx10.sentinel-mismatch",
              ordinal,
              null,
              "sentinel",
              chunkField + 20,
              4,
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
        int length = Short.toUnsignedInt(builder.readMetadata(namePosition, 2).getShort());
        namePosition += 2;
        wire = builder.readName(namePosition, length);
        for (byte value : wire.bytes()) if (value == 0) throw malformed("ba2.nul-name");
        try {
          display =
              encoding
                  .newDecoder()
                  .decode(ByteBuffer.wrap(wire.bytes()))
                  .toString()
                  .replace('/', '\\');
          identity = NormalizedNameIdentity.from(display, encoding);
          if (identity.isPresent() && !identities.add(identity.orElseThrow()))
            throw malformed("ba2.duplicate-name");
          if (Ba2Names.ascii(wire.bytes()))
            checkIdentity(prefix, Ba2Names.identity(wire.bytes()), ordinal, display, field);
        } catch (CharacterCodingException failure) {
          // Present undecodable bytes use ordinal wire identity, distinct from an absent table.
          display = String.format(Locale.ROOT, "__jbsa_wire__\\e%08x", ordinal);
          warning(
              "archive-name.undecodable-wire-bytes",
              ordinal,
              display,
              "complete",
              namePosition,
              length,
              Map.of());
        }
        namePosition += length;
      }
      if (mod != 0)
        mismatch(
            "dx10.nonzero-mod-index",
            ordinal,
            display,
            "modIndex",
            field + 12,
            1,
            Long.toString(mod),
            "0");
      if (chunkSize != 24)
        mismatch(
            "dx10.chunk-header-size",
            ordinal,
            display,
            "chunkHeaderSize",
            field + 14,
            2,
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
      builder.addDds(
          metadata,
          ddsHeader,
          textureChunks,
          rawLz4 ? OwnedArchive.PayloadCodec.LZ4_RAW : OwnedArchive.PayloadCodec.ZLIB);
      entries.add(metadata);
    }
    if (named && nameOffset < recordsEnd) throw malformed("ba2.names-overlap-index");
    usedEnd = Math.max(usedEnd, recordsEnd);
    for (Span span : spans)
      if (span.start < recordsEnd) throw malformed("ba2.payload-overlaps-index");
    spans.sort(Comparator.comparingLong(Span::start).thenComparingLong(Span::end));
    Span previous = null;
    for (Span span : spans) {
      // Empty chunks name no bytes and cannot overlap another payload or the filename table.
      if (span.start == span.end) continue;
      if (named && span.start < namePosition && span.end > nameOffset)
        throw malformed("ba2.payload-overlaps-names");
      if (previous != null && span.start < previous.end && !span.equals(previous))
        throw malformed("ba2.overlapping-payloads");
      previous = span;
    }
    if (named) usedEnd = Math.max(usedEnd, namePosition);
    if (usedEnd < builder.size())
      warning(
          "ba2.trailing-data",
          -1,
          null,
          "trailingData",
          usedEnd,
          builder.size() - usedEnd,
          Map.of());
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
            version == 3 && !rawLz4
                ? DetectionStatus.UNSUPPORTED_VARIANT
                : DetectionStatus.SUPPORTED_FAMILY,
            new WireName(new byte[] {66, 84, 68, 88}),
            version == 3 && !rawLz4 ? Optional.empty() : Optional.of(family),
            archiveEncoding.wireVersion(),
            Optional.of(Ba2Subtype.DX10),
            archiveEncoding.compressionMethod());
    return new ArchiveInspection(
        detection,
        new ArchiveMetadata.DdsBa2(family, archiveEncoding, count, nameOffset, extraHeader),
        assessment,
        entries);
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
          ordinal,
          display,
          "baseNameHash",
          field,
          4,
          hex(stored.baseNameHash()),
          hex(expected.baseNameHash()));
    if (!stored.extension().equals(expected.extension()))
      mismatch(
          "ba2.extension-mismatch",
          ordinal,
          display,
          "extension",
          field + 4,
          4,
          HexFormat.of().withUpperCase().formatHex(stored.extension().bytes()),
          HexFormat.of().withUpperCase().formatHex(expected.extension().bytes()));
    if (stored.directoryHash() != expected.directoryHash())
      mismatch(
          "ba2.directory-hash-mismatch",
          ordinal,
          display,
          "directoryHash",
          field + 8,
          4,
          hex(stored.directoryHash()),
          hex(expected.directoryHash()));
  }

  /** Retains stored and expected values at a field's exact wire location. */
  private void mismatch(
      String id,
      long ordinal,
      String display,
      String field,
      long offset,
      long length,
      String stored,
      String expected) {
    warning(
        id,
        ordinal,
        display,
        field,
        offset,
        length,
        Map.of("stored", stored, "expected", expected));
  }

  /** Records intrinsic noncanonical evidence independently of diagnostic retention capacity. */
  private void warning(
      String id,
      long ordinal,
      String display,
      String field,
      long offset,
      long length,
      Map<String, String> values) {
    noncanonical = true;
    retention.diagnostic(
        new Diagnostic(
            id,
            DiagnosticSeverity.WARNING,
            context.operation(),
            context.phase(),
            new DiagnosticLocation(
                Optional.of(context.path()),
                ordinal < 0 ? OptionalLong.empty() : OptionalLong.of(ordinal),
                Optional.ofNullable(display),
                Optional.of(field),
                Optional.of(new DiagnosticLocation.ByteSpan(offset, length)),
                Optional.empty()),
            new TreeMap<>(values),
            Optional.empty()));
  }

  private static long u32(ByteBuffer bytes) {
    return Integer.toUnsignedLong(bytes.getInt());
  }

  private static String hex(long value) {
    return String.format(Locale.ROOT, "%08X", value);
  }

  private ArchiveException malformed(String id) {
    return context.failure(FailureKind.FORMAT, id, null);
  }

  private ArchiveException unsupported(String id) {
    return context.failure(FailureKind.UNSUPPORTED, id, null);
  }

  private record Span(long start, long end) {}
}
