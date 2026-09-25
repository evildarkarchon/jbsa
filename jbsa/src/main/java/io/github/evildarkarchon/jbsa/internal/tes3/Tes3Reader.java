package io.github.evildarkarchon.jbsa.internal.tes3;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.file.Path;
import java.util.*;

/** Bounded eager TES3 metadata decoding; stored payload bytes stay on the owned source handle. */
public final class Tes3Reader {
  private final Charset encoding;
  private static final Set<String> UNSUPPORTED_ROOTS =
      Set.of("sound", "music", "video", "fonts", "splash");
  private final OwnedArchive.IndexBuilder builder;
  private final IoContext context;
  private final FailureRetention retention;
  private boolean noncanonical;

  private Tes3Reader(
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

  /** Opens a TES3 input for a mutation while retaining the originating operation in diagnostics. */
  public static OpenArchive open(Path path, OpenOptions options, Operation operation)
      throws IOException {
    return open(path, options, operation, DiagnosticPolicy.standard());
  }

  /** Evaluates mutation warning policy before bounded diagnostic retention can omit a warning. */
  public static OpenArchive open(
      Path path, OpenOptions options, Operation operation, DiagnosticPolicy policy)
      throws IOException {
    return ArchiveReaders.open(path, options, operation, policy);
  }

  /**
   * Populates a parent-owned index and returns complete detached metadata without payload reads.
   */
  public static ArchiveInspection load(
      OwnedArchive.IndexBuilder builder, Path path, OpenOptions options, Operation operation)
      throws IOException {
    return load(builder, path, options, operation, DiagnosticPolicy.standard());
  }

  /**
   * Retains selected policy during parse, including warnings later omitted by retention ceilings.
   */
  public static ArchiveInspection load(
      OwnedArchive.IndexBuilder builder,
      Path path,
      OpenOptions options,
      Operation operation,
      DiagnosticPolicy policy)
      throws IOException {
    Tes3Reader reader = new Tes3Reader(builder, path, options, operation, policy);
    ArchiveInspection result;
    try {
      result = reader.parse();
    } catch (ArchiveException failure) {
      reader.retention.accept(failure);
      if (failure.kind() == FailureKind.FORMAT) {
        reader.retention.latestAssessment(
            new ArchiveAssessment(
                ArchiveDisposition.REJECTED,
                new ValidationExtent.Structure(),
                reader.retention.diagnostics()));
      }
      throw reader.retention.finish(List.of());
    }
    ArchiveException rejected = reader.retention.finish(List.of());
    if (rejected != null) throw rejected;
    return result;
  }

  /** Checks all section boundaries before index allocation, then admits every payload span. */
  private ArchiveInspection parse() throws IOException {
    ByteBuffer header = builder.readMetadata(0, 12);
    if (header.getInt() != 0x100) throw malformed("tes3.invalid-magic");
    long hashOffset = Integer.toUnsignedLong(header.getInt());
    long count = Integer.toUnsignedLong(header.getInt());
    long hashStart = ExactIo.end(12, hashOffset, builder.size(), context);
    long namesStart = ExactIo.end(12, ExactIo.multiply(count, 12, context), hashStart, context);
    long dataBase =
        ExactIo.end(hashStart, ExactIo.multiply(count, 8, context), builder.size(), context);
    builder.declareEntries(count);
    List<EntryMetadata> entries = new ArrayList<>();
    Set<NormalizedNameIdentity> identities = new HashSet<>();
    List<Span> spans = new ArrayList<>();
    long cursor = namesStart;
    NameCursor nameCursor = new NameCursor(namesStart, hashStart);
    long payloadEnd = dataBase;
    for (int ordinal = 0; ordinal < count; ordinal++) {
      ByteBuffer record = builder.readMetadata(12L + ordinal * 8L, 8);
      long size = Integer.toUnsignedLong(record.getInt());
      long relativeOffset = Integer.toUnsignedLong(record.getInt());
      long offsetField = 12L + count * 8L + ordinal * 4L;
      long nameOffset = Integer.toUnsignedLong(builder.readMetadata(offsetField, 4).getInt());
      if (nameOffset >= hashStart - namesStart) throw malformed("tes3.invalid-name-offset");
      long nameStart = cursor;
      // Sequential boundaries remain authoritative when a bounded offset field is inconsistent.
      WireName wire = nameCursor.next();
      cursor = nameCursor.position;
      byte[] bytes = wire.bytes();
      String name;
      Optional<NormalizedNameIdentity> identity;
      try {
        name = encoding.newDecoder().decode(ByteBuffer.wrap(bytes)).toString();
        identity = NormalizedNameIdentity.from(name, encoding);
      } catch (CharacterCodingException failure) {
        name = String.format(Locale.ROOT, "__jbsa_wire__\\e%08x", ordinal);
        identity = Optional.empty();
        warning(
            "archive-name.undecodable-wire-bytes",
            ordinal,
            name,
            "complete",
            nameStart,
            bytes.length,
            Map.of(),
            true);
      }
      if (identity.isPresent() && !identities.add(identity.orElseThrow()))
        throw malformed("tes3.duplicate-name");
      if (nameOffset != nameStart - namesStart) {
        warning(
            "tes3.name-offset-inconsistency",
            ordinal,
            name,
            "nameOffset",
            offsetField,
            4,
            Map.of(
                "stored",
                Long.toString(nameOffset),
                "expected",
                Long.toString(nameStart - namesStart)),
            true);
      }
      long hashField = hashStart + ordinal * 8L;
      long hash = builder.readMetadata(hashField, 8).getLong();
      boolean ascii = true;
      for (byte value : bytes) if (value < 0) ascii = false;
      if (ascii && identity.isPresent()) {
        long expected = Tes3Names.hash(Tes3Names.canonicalize(bytes));
        if (hash != expected)
          warning(
              "tes3.stored-hash-mismatch",
              ordinal,
              name,
              "nameHash",
              hashField,
              8,
              Map.of(
                  "stored",
                  String.format(Locale.ROOT, "%016X", hash),
                  "expected",
                  String.format(Locale.ROOT, "%016X", expected)),
              true);
      }
      long dataOffset = ExactIo.end(dataBase, relativeOffset, builder.size(), context);
      long end = ExactIo.end(dataOffset, size, builder.size(), context);
      if (size > 0) spans.add(new Span(dataOffset, end));
      payloadEnd = Math.max(payloadEnd, end);
      if (dataOffset > Integer.MAX_VALUE)
        warning(
            "tes3.payload-offset-over-signed-2gib",
            ordinal,
            name,
            "dataOffset",
            dataOffset,
            0,
            Map.of(
                "stored",
                Long.toString(dataOffset),
                "expected",
                Integer.toString(Integer.MAX_VALUE)),
            false);
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
        warning(
            "tes3.unsupported-asset-root",
            ordinal,
            name,
            "complete",
            nameStart,
            bytes.length,
            Map.of("stored", root),
            false);
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
      builder.addStored(metadata, dataOffset);
      entries.add(metadata);
    }
    if (cursor != hashStart) throw malformed("tes3.invalid-name-block");
    spans.sort(Comparator.comparingLong(Span::start).thenComparingLong(Span::end));
    Span previous = null;
    for (Span span : spans) {
      if (previous != null && span.start < previous.end && !span.equals(previous))
        throw malformed("tes3.overlapping-payloads");
      previous = span;
    }
    if (payloadEnd < builder.size())
      warning(
          "tes3.trailing-data",
          -1,
          null,
          "trailingData",
          payloadEnd,
          builder.size() - payloadEnd,
          Map.of("stored", Long.toString(builder.size() - payloadEnd), "expected", "0"),
          true);
    ArchiveDetection detection =
        new ArchiveDetection(
            DetectionStatus.SUPPORTED_FAMILY,
            new WireName(new byte[] {0, 1, 0, 0}),
            Optional.of(ArchiveFamily.TES3_BSA),
            Optional.empty(),
            Optional.empty(),
            OptionalLong.empty());
    ArchiveAssessment assessment =
        new ArchiveAssessment(
            noncanonical
                ? ArchiveDisposition.TOLERATED_NONCANONICAL
                : ArchiveDisposition.CONFORMING,
            new ValidationExtent.Structure(),
            retention.diagnostics());
    retention.latestAssessment(assessment);
    return new ArchiveInspection(
        detection, new ArchiveMetadata.Tes3(count, hashOffset, dataBase), assessment, entries);
  }

  /** Emits bounded, stable warning evidence; only format canonicality changes disposition. */
  private void warning(
      String identifier,
      long ordinal,
      String name,
      String field,
      long offset,
      long length,
      Map<String, String> values,
      boolean affectsCanonicality) {
    noncanonical |= affectsCanonicality;
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

  /** Creates a format failure whose rejected structural assessment is attached by the loader. */
  private ArchiveException malformed(String identifier) {
    return context.failure(FailureKind.FORMAT, identifier, null);
  }

  /** Nonempty payload range; exact duplicate ranges are permitted without reading their bytes. */
  private record Span(long start, long end) {}

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
          window = builder.readMetadata(position, (int) Math.min(65536, end - position));
        byte value = window.get();
        position++;
        if (value == 0) return new WireName(bytes.toByteArray());
        bytes.write(value);
      }
      throw malformed("tes3.unterminated-name");
    }
  }
}
