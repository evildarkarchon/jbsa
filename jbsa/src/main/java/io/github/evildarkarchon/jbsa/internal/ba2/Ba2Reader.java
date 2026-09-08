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

/** Bounded FO4 General BA2 v1 index reader with parent-owned lazy payload access. */
public final class Ba2Reader {
  private static final ArchiveEncoding ENCODING =
      new ArchiveEncoding(
          Optional.of(new WireVersion(1)), Optional.of(Ba2Subtype.GNRL), OptionalLong.empty());
  private final OwnedArchive.IndexBuilder builder;
  private final IoContext context;
  private final Charset encoding;
  private final FailureRetention retention;
  private boolean noncanonical;

  /** Binds metadata accounting and warning retention to the owning operation. */
  private Ba2Reader(
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
    Ba2Reader reader = new Ba2Reader(builder, path, options, operation, policy);
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
    if (header.getInt() != 0x58445442 || header.getInt() != 1 || header.getInt() != 0x4c524e47)
      throw malformed("ba2.invalid-selector");
    long count = u32(header), nameOffset = header.getLong();
    long recordsEnd =
        ExactIo.end(24, ExactIo.multiply(count, 36, context), builder.size(), context);
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
    if (named && nameOffset < recordsEnd) throw malformed("ba2.names-overlap-index");
    long namePosition = nameOffset, usedEnd = recordsEnd;
    List<EntryMetadata> entries = new ArrayList<>();
    List<Span> spans = new ArrayList<>();
    Set<NormalizedNameIdentity> identities = new HashSet<>();
    for (long ordinal = 0; ordinal < count; ordinal++) {
      long field = 24 + ordinal * 36;
      ByteBuffer record = builder.readMetadata(field, 36);
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
            "gnrl.nonzero-mod-index",
            ordinal,
            display,
            "modIndex",
            field + 12,
            1,
            Long.toString(mod),
            "0");
      if (chunkSize != 16)
        mismatch(
            "gnrl.chunk-header-size",
            ordinal,
            display,
            "chunkHeaderSize",
            field + 14,
            2,
            Long.toString(chunkSize),
            "16");
      if (sentinel != 0xbaadf00dL)
        mismatch(
            "gnrl.sentinel-mismatch",
            ordinal,
            display,
            "sentinel",
            field + 32,
            4,
            hex(sentinel),
            "BAADF00D");
      long stored = packed == 0 ? unpacked : packed;
      if (offset < recordsEnd) throw malformed("ba2.payload-overlaps-index");
      long end = ExactIo.end(offset, stored, builder.size(), context);
      if (stored > 0) {
        spans.add(new Span(offset, end));
        // Empty spans reference no bytes, so an EOF coordinate cannot hide trailing data.
        usedEnd = Math.max(usedEnd, end);
      }
      EntryMetadata metadata =
          new EntryMetadata(
              ArchiveFamily.FO4_GENERAL_BA2,
              ENCODING,
              ordinal,
              display,
              identity,
              wire == null ? Map.of() : Map.of("complete", wire),
              unpacked,
              stored,
              new EntryMetadata.GeneralBa2(prefix, offset, packed, unpacked, sentinel));
      if (packed == 0) builder.addStored(metadata, offset);
      else builder.addZlib(metadata, offset, packed);
      entries.add(metadata);
    }
    spans.sort(Comparator.comparingLong(Span::start).thenComparingLong(Span::end));
    Span previous = null;
    for (Span span : spans) {
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
            DetectionStatus.SUPPORTED_FAMILY,
            new WireName(new byte[] {66, 84, 68, 88}),
            Optional.of(ArchiveFamily.FO4_GENERAL_BA2),
            ENCODING.wireVersion(),
            Optional.of(Ba2Subtype.GNRL),
            OptionalLong.empty());
    return new ArchiveInspection(
        detection,
        new ArchiveMetadata.GeneralBa2(
            ArchiveFamily.FO4_GENERAL_BA2, ENCODING, count, nameOffset, OptionalLong.empty()),
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

  private record Span(long start, long end) {}
}
