package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.codec.ByteSource;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.io.OwnedArchive.StoredEntry;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * One archive's eager index admission and the Archive Disposition rules every reader shares.
 *
 * <p>A reader contributes only a wire walk ({@link IndexWalk}) and its family's rules: diagnostic
 * identifiers and values, hash formatting and eligibility, display folding, and which diagnostics
 * make the archive noncanonical. The session owns everything else: bounded metadata reads, the
 * payload table, the failure retention and its REJECTED assessment, name decoding with the
 * Normalized Name Identity registry, payload span overlap and trailing data, and the final Archive
 * Assessment including the name-safety validation pass. The session never names a family's
 * diagnostic identifier; readers pass theirs in.
 *
 * <p>A session is confined to the opening thread and lives only while {@link OwnedArchive} loads
 * the index. Only {@code OwnedArchive} creates one, so a reader cannot transfer or close the
 * parent-owned input.
 */
public final class IndexSession {
  /** A reader's walk over one archive's index, populating the session it is given. */
  @FunctionalInterface
  public interface IndexWalk {
    /**
     * Reads the complete index through the session and returns the archive's structural metadata.
     *
     * @throws ArchiveException for a structural failure; FORMAT failures are reported REJECTED
     * @throws IOException if reading the input fails
     */
    ArchiveMetadata walk(IndexSession session) throws IOException;
  }

  /**
   * A complete name after decoding.
   *
   * @param display the decoded and folded display name, or the synthetic wire name if undecodable
   * @param identity the registered Normalized Name Identity; empty when the name is undecodable
   * @param decoded whether the wire bytes decoded under the Archive Name Encoding
   */
  public record Name(String display, Optional<NormalizedNameIdentity> identity, boolean decoded) {}

  private final ArchiveInput input;
  private final ResourceBudget budget;
  private final IoContext context;
  private final FailureRetention retention;
  private final List<StoredEntry> entries = new ArrayList<>();
  private final Set<NormalizedNameIdentity> identities = new HashSet<>();
  private final List<Span> spans = new ArrayList<>();
  private long expected = -1;
  private Charset encoding;
  private boolean noncanonical;
  private ArchiveInspection completed;

  /** Keeps the handle private so a loader cannot transfer or close parent ownership. */
  IndexSession(
      ArchiveInput input,
      ResourceBudget budget,
      IoContext context,
      ResourceLimits limits,
      DiagnosticPolicy policy) {
    this.input = input;
    this.budget = budget;
    this.context = context;
    // Warning policy is applied as each diagnostic arrives, before retention ceilings can omit it.
    this.retention = new FailureRetention(limits, context.operation(), policy);
  }

  /**
   * Walks the index under the reader's rules and returns the complete, validated inspection.
   *
   * <p>A FORMAT failure from the walk gains a REJECTED structural assessment carrying every
   * diagnostic retained so far; other failures pass through without one. After a successful walk, a
   * policy-rejected warning fails the read before validation runs. The returned inspection carries
   * {@code detection} unchanged, so inspection and detection report the same evidence.
   *
   * @param detection the evidence dispatch recognized, reported unchanged
   * @param profile selects the Archive Name Encoding; resolved before the walk begins
   * @throws ArchiveException for a structural, policy, capability or validation failure
   * @throws IllegalStateException if the session has already been read
   */
  public ArchiveInspection read(
      ArchiveDetection detection, Optional<CompatibilityProfile> profile, IndexWalk walk)
      throws IOException {
    if (encoding != null) throw new IllegalStateException("Index already read");
    encoding = ArchiveNames.encoding(profile, context);
    ArchiveMetadata metadata;
    try {
      metadata = walk.walk(this);
    } catch (ArchiveException failure) {
      retention.accept(failure);
      if (failure.kind() == FailureKind.FORMAT) {
        retention.latestAssessment(
            new ArchiveAssessment(
                ArchiveDisposition.REJECTED,
                new ValidationExtent.Structure(),
                retention.diagnostics()));
      }
      throw retention.finish(List.of());
    }
    return finish(detection, metadata);
  }

  /**
   * Builds the Archive Assessment from the walk's diagnostics, then runs the name-safety pass in
   * the same retention, so reader diagnostics and validation diagnostics share one ordering and one
   * set of ceilings.
   */
  private ArchiveInspection finish(ArchiveDetection detection, ArchiveMetadata metadata)
      throws ArchiveException {
    ArchiveAssessment structure =
        new ArchiveAssessment(
            noncanonical
                ? ArchiveDisposition.TOLERATED_NONCANONICAL
                : ArchiveDisposition.CONFORMING,
            new ValidationExtent.Structure(),
            retention.diagnostics());
    retention.latestAssessment(structure);
    // A rejected reader warning ends the read here; validation never adds to that failure.
    ArchiveException rejected = retention.finish(List.of());
    if (rejected != null) throw rejected;
    // Thrown outside the walk, so this internal-consistency failure carries no REJECTED assessment.
    checkCount(metadata);
    completed =
        ArchiveValidation.structure(
            new ArchiveInspection(detection, metadata, structure, entryMetadata()),
            context,
            retention);
    return completed;
  }

  /**
   * Completes whatever the loader returned. An inspection from {@link #read} is already complete.
   * Any other loader (an internal test seam) supplies its own evidence; it is admitted into this
   * session's retention and validated the same way.
   *
   * <p>The seam exists because {@code OwnedArchiveTest}, {@code OwnedArchiveValidationTest} and
   * {@code OwnedArchiveRaceTest} drive the load lifecycle with fake loaders that populate the
   * session directly instead of walking a real index through {@link #read}. Their evidence must
   * still pass the same count check and name-safety validation before {@code OwnedArchive} adopts
   * it, so those tests cover the adoption checks production relies on.
   */
  ArchiveInspection complete(ArchiveInspection inspection) throws ArchiveException {
    if (completed != null) {
      if (inspection != completed)
        throw new IllegalStateException("A read session must return the inspection it completed");
      return inspection;
    }
    checkCount(inspection.metadata());
    retention.assessment(inspection.assessment());
    return ArchiveValidation.structure(
        new ArchiveInspection(
            inspection.detection(),
            inspection.metadata(),
            inspection.assessment(),
            entryMetadata()),
        context,
        retention);
  }

  /** Returns the admitted payload table in archive order. */
  List<StoredEntry> entries() {
    return entries;
  }

  /** Rejects a loader whose declared, admitted and reported entry counts disagree. */
  private void checkCount(ArchiveMetadata metadata) throws ArchiveException {
    if (expected != entries.size() || metadata.entryCount() != expected)
      throw context.failure(FailureKind.FORMAT, "io.index-count-mismatch", null);
  }

  /** Returns the admitted entries' metadata in archive order, for the reported inspection. */
  private List<EntryMetadata> entryMetadata() {
    return entries.stream().map(StoredEntry::metadata).toList();
  }

  /** Returns the operation context for exact arithmetic and family failures. */
  public IoContext context() {
    return context;
  }

  /** Creates a FORMAT failure for a family identifier; the session attaches the assessment. */
  public ArchiveException malformed(String identifier) {
    return context.failure(FailureKind.FORMAT, identifier, null);
  }

  /** Returns the stable source extent without transferring handle ownership to the loader. */
  public long size() {
    return input.size();
  }

  /** Reads the fixed recognition prefix without charging it again as parsed format metadata. */
  public ByteBuffer readSelectors(int length) throws IOException {
    if (length < 0 || length > 36) throw new IllegalArgumentException("Selector extent");
    ByteBuffer result = ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    input.readExact(0, result);
    return result.flip();
  }

  /** Checks the encoded count before a format loader allocates or constructs entry metadata. */
  public void declareEntries(long count) throws ArchiveException {
    if (expected != -1) throw new IllegalStateException("Entry count already declared");
    ExactIo.multiply(count, 1, context);
    budget.entryIndex(count);
    if (count > Integer.MAX_VALUE)
      throw context.failure(FailureKind.POLICY, "io.index-capacity", null);
    expected = count;
  }

  /** Reads one encoded header/table window, charging metadata and heap before allocation. */
  public ByteBuffer readMetadata(long offset, int length) throws IOException {
    ExactIo.end(offset, length, input.size(), context);
    if (length > ExactIo.WINDOW_BYTES)
      throw context.failure(FailureKind.POLICY, "io.resource-capacity", null);
    budget.metadata(length);
    ByteBuffer result = ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    input.readExact(offset, result);
    return result.flip();
  }

  /** Retains an exact encoded name only after validating its full span and allocation credit. */
  public WireName readName(long offset, long length) throws IOException {
    ExactIo.end(offset, length, input.size(), context);
    budget.metadata(length);
    if (length > Integer.MAX_VALUE)
      throw context.failure(FailureKind.POLICY, "io.resource-capacity", null);
    byte[] bytes = new byte[Math.toIntExact(length)];
    input.readExact(offset, ByteBuffer.wrap(bytes));
    return new WireName(bytes);
  }

  /**
   * Borrows at most 128 decoded bytes for format warnings without claiming complete payload
   * validation. Codec and buffer credits are reserved before allocation and released on return.
   *
   * @param offset absolute stored stream offset after archive-specific framing
   * @param streamSize exact stored stream size
   * @param decodedSize declared decoded payload size
   * @param codec codec required to expose the decoded prefix
   * @param length maximum requested decoded prefix, from zero through 128 bytes
   * @throws IOException if input, capability, codec, or resource admission fails
   */
  public ByteBuffer readPayloadPrefix(
      long offset, long streamSize, long decodedSize, PayloadCodec codec, int length)
      throws IOException {
    if (length < 0 || length > 128) throw new IllegalArgumentException("Payload prefix extent");
    ExactIo.end(offset, streamSize, input.size(), context);
    // A raw block's wire and dispatch sizes are refused before its cost is charged, as the
    // self-reserving decoder did, so an oversized block reports its codec limit, not capacity.
    AdmittedCodec.checkDecodeSize(codec, streamSize, decodedSize, context);
    // The codec is admitted when the prefix first needs it, so an LZ4 credit charges the provider
    // admission pins. Every codec's decode cost, raw LZ4's whole block included, joins the prefix
    // buffer in one reservation: the codecs never reserve, and one combined lease reaches the
    // same total the old separate raw charge did, so the same ceiling is exhausted.
    AdmittedCodec admitted = AdmittedCodec.admit(codec, "decode", context);
    AdmittedCodec.Cost cost = admitted.decodeCost(streamSize, decodedSize);
    try (var credit = budget.reserve(length + cost.heapBytes(), cost.nativeBytes(), 0, 0)) {
      ByteBuffer prefix =
          ByteBuffer.allocate((int) Math.min(length, decodedSize))
              .order(java.nio.ByteOrder.LITTLE_ENDIAN);
      ByteSource source = (relative, bytes) -> input.readExact(offset + relative, bytes);
      if (codec.decodesWholeBlocks()) {
        // A whole block decodes completely; only the bytes that land inside the prefix are kept.
        admitted.decode(
            source,
            streamSize,
            decodedSize,
            (relative, bytes) -> {
              if (relative < prefix.capacity()) {
                ByteBuffer available = bytes.slice();
                available.limit(
                    (int) Math.min(available.remaining(), prefix.capacity() - relative));
                prefix.position(Math.toIntExact(relative));
                prefix.put(available);
              }
            },
            () -> {},
            credit,
            context);
      } else {
        try (var decoder = admitted.decoder(source, streamSize, decodedSize, credit, context)) {
          while (prefix.hasRemaining() && decoder.read(prefix) >= 0) {
            // Only the bounded prefix is requested; complete validation belongs to content EOF.
          }
        }
      }
      return prefix.flip();
    }
  }

  /**
   * Admits one entry's payload span in archive order without reading or decoding it.
   *
   * <p>A STORED span is the content itself, so its size must equal the decoded size and fit the
   * stored record, which may also hold bounded framing such as a BSA embedded name. A compressed
   * span is the complete encoded stream; its decoded size is checked lazily by the content cursor.
   *
   * @param offset absolute offset of the content or encoded stream, after any framing
   * @param size exact content or encoded stream size
   * @throws ArchiveException FORMAT {@code io.index-record-mismatch} for an out-of-order, excess or
   *     inconsistent record, or a FORMAT span failure if the span leaves the input
   */
  public void add(EntryMetadata metadata, long offset, long size, PayloadCodec codec)
      throws ArchiveException {
    if (expected < 0
        || entries.size() >= expected
        || metadata.ordinal() != entries.size()
        || (codec == PayloadCodec.STORED
            && (size != metadata.decodedSize() || size > metadata.storedSize()))) {
      throw context.failure(FailureKind.FORMAT, "io.index-record-mismatch", null);
    }
    ExactIo.end(offset, size, input.size(), context);
    entries.add(new StoredEntry(metadata, offset, size, codec));
  }

  /** Admits a generated DDS envelope followed by independently bounded texture chunks. */
  public void addDds(
      EntryMetadata metadata,
      byte[] header,
      List<EntryMetadata.DdsChunk> chunks,
      PayloadCodec compressedCodec)
      throws ArchiveException {
    if (compressedCodec != PayloadCodec.ZLIB && compressedCodec != PayloadCodec.LZ4_RAW)
      throw context.failure(FailureKind.INTERNAL, "io.invalid-dds-codec", null);
    if (expected < 0 || entries.size() >= expected || metadata.ordinal() != entries.size())
      throw context.failure(FailureKind.FORMAT, "io.index-record-mismatch", null);
    long decoded = header.length;
    for (EntryMetadata.DdsChunk chunk : chunks) {
      ExactIo.end(
          chunk.payloadOffset(),
          chunk.packedSize() == 0 ? chunk.unpackedSize() : chunk.packedSize(),
          input.size(),
          context);
      decoded = ExactIo.end(decoded, chunk.unpackedSize(), Long.MAX_VALUE, context);
    }
    if (decoded != metadata.decodedSize())
      throw context.failure(FailureKind.FORMAT, "io.index-record-mismatch", null);
    entries.add(
        new StoredEntry(
            metadata,
            0,
            0,
            chunks.stream().anyMatch(c -> c.packedSize() != 0)
                ? compressedCodec
                : PayloadCodec.STORED,
            header.clone(),
            List.copyOf(chunks)));
  }

  /**
   * Locates a diagnostic at an exact wire field of this archive.
   *
   * @param ordinal entry ordinal, or negative for an archive-level field
   * @param name entry display name, or null when there is none
   */
  public DiagnosticLocation location(
      long ordinal, String name, String field, long offset, long length) {
    return new DiagnosticLocation(
        Optional.of(context.path()),
        ordinal < 0 ? OptionalLong.empty() : OptionalLong.of(ordinal),
        Optional.ofNullable(name),
        Optional.of(field),
        Optional.of(new DiagnosticLocation.ByteSpan(offset, length)),
        Optional.empty());
  }

  /**
   * Locates a diagnostic at an archive-level wire field, such as a header field, a folder record or
   * trailing data, which belongs to no entry and so carries no entry ordinal or name.
   */
  public DiagnosticLocation archiveLocation(String field, long offset, long length) {
    return location(-1, null, field, offset, length);
  }

  /**
   * Records a warning that makes the archive a Tolerated Noncanonical Archive. This is the only way
   * to mark an archive noncanonical, so a noncanonical outcome always carries a diagnostic.
   */
  public void noncanonical(
      String identifier, DiagnosticLocation location, Map<String, String> values) {
    // Set before retention, which may omit the diagnostic but must not lose its disposition.
    noncanonical = true;
    warning(identifier, location, values);
  }

  /** Records a warning that leaves the Archive Disposition unchanged. */
  public void warning(String identifier, DiagnosticLocation location, Map<String, String> values) {
    retention.diagnostic(
        new Diagnostic(
            identifier,
            DiagnosticSeverity.WARNING,
            context.operation(),
            context.phase(),
            location,
            new TreeMap<>(values),
            Optional.empty()));
  }

  /**
   * Decodes one complete name and registers its Normalized Name Identity.
   *
   * <p>Undecodable wire bytes are a noncanonical {@code archive-name.undecodable-wire-bytes}
   * warning located at the name bytes; the entry then takes the synthetic display {@code
   * __jbsa_wire__\e<ordinal>} and no identity. A decoded name is folded before its identity is
   * derived, so folding decides which names collide.
   *
   * @param wire the complete name's wire bytes, which must be present
   * @param offset absolute offset of the name bytes, after any length prefix
   * @param fold the family's display folding, applied only to a decoded name
   * @param duplicateIdentifier the family's FORMAT identifier for a repeated identity
   * @throws ArchiveException FORMAT {@code duplicateIdentifier} if the identity repeats
   */
  public Name decodeName(
      WireName wire,
      long ordinal,
      long offset,
      UnaryOperator<String> fold,
      String duplicateIdentifier)
      throws ArchiveException {
    Objects.requireNonNull(wire, "wire");
    String fallback = String.format(Locale.ROOT, "__jbsa_wire__\\e%08x", ordinal);
    String text = decodeComponent(wire, ordinal, fallback, "complete", offset);
    if (text == null) return new Name(fallback, Optional.empty(), false);
    String display = fold.apply(text);
    return new Name(display, identify(display, duplicateIdentifier), true);
  }

  /**
   * Strictly decodes one name component. An undecodable component is a noncanonical {@code
   * archive-name.undecodable-wire-bytes} warning at its bytes and decodes to null; the caller then
   * reports the entry under {@code display}, its family's synthetic name.
   *
   * @param wire the component's wire bytes, or null when the component is absent
   * @param display the entry's display name if this component cannot be decoded
   * @param field the component's field name in the diagnostic location
   * @param offset absolute offset of the component bytes
   * @return the decoded text, or null when the component is absent or undecodable
   */
  public String decodeComponent(
      WireName wire, long ordinal, String display, String field, long offset) {
    if (wire == null) return null;
    try {
      return encoding.newDecoder().decode(ByteBuffer.wrap(wire.bytes())).toString();
    } catch (CharacterCodingException failure) {
      noncanonical(
          "archive-name.undecodable-wire-bytes",
          location(ordinal, display, field, offset, wire.length()),
          Map.of());
      return null;
    }
  }

  /**
   * Derives and registers a decoded display name's Normalized Name Identity.
   *
   * @throws ArchiveException FORMAT {@code duplicateIdentifier} if the identity repeats
   */
  public Optional<NormalizedNameIdentity> identify(String display, String duplicateIdentifier)
      throws ArchiveException {
    Optional<NormalizedNameIdentity> identity = NormalizedNameIdentity.from(display, encoding);
    if (identity.isPresent() && !identities.add(identity.orElseThrow()))
      throw malformed(duplicateIdentifier);
    return identity;
  }

  /**
   * Registers one payload span for the overlap check. An empty span references no bytes, so it can
   * never overlap anything and is not registered.
   */
  public void payload(long start, long end) {
    if (start < end) spans.add(new Span(start, end));
  }

  /**
   * Rejects registered payloads that partially overlap. Identical spans are shared payloads and
   * stay valid.
   *
   * @throws ArchiveException FORMAT {@code overlapIdentifier} at the first partial overlap
   */
  public void checkOverlaps(String overlapIdentifier) throws ArchiveException {
    checkOverlaps(overlapIdentifier, Optional.empty());
  }

  /**
   * Rejects registered payloads that intersect a reserved metadata region or partially overlap each
   * other, checking each span in offset order against the region first.
   *
   * @param reserved the metadata region no payload may intersect
   * @throws ArchiveException FORMAT for the first offending span in offset order
   */
  public void checkOverlaps(String overlapIdentifier, ReservedRegion reserved)
      throws ArchiveException {
    checkOverlaps(overlapIdentifier, Optional.of(reserved));
  }

  /**
   * The one span walk behind both public overlap checks; an empty {@code reserved} checks only
   * partial overlaps between payloads.
   */
  private void checkOverlaps(String overlapIdentifier, Optional<ReservedRegion> reserved)
      throws ArchiveException {
    spans.sort(Comparator.comparingLong(Span::start).thenComparingLong(Span::end));
    Span previous = null;
    for (Span span : spans) {
      if (reserved.isPresent() && reserved.orElseThrow().intersects(span))
        throw malformed(reserved.orElseThrow().identifier());
      if (previous != null && span.start < previous.end && !span.equals(previous))
        throw malformed(overlapIdentifier);
      previous = span;
    }
  }

  /**
   * Diagnoses bytes after the last byte the index uses as noncanonical trailing data, once per
   * archive at the complete trailing span.
   *
   * @param usedEnd end of the last byte the family's index references
   * @param values the family's diagnostic values
   */
  public void trailingData(String identifier, long usedEnd, Map<String, String> values) {
    if (usedEnd < size())
      noncanonical(identifier, archiveLocation("trailingData", usedEnd, size() - usedEnd), values);
  }

  /**
   * A metadata region that no payload may intersect, such as a BA2 filename table.
   *
   * @param start first byte of the region
   * @param end end of the region, exclusive
   * @param identifier the family's FORMAT identifier for a payload inside the region
   */
  public record ReservedRegion(long start, long end, String identifier) {
    /** Requires the family's identifier, so a reserved region always names its failure. */
    public ReservedRegion {
      Objects.requireNonNull(identifier, "identifier");
    }

    /** Whether a payload span shares at least one byte with this region. */
    private boolean intersects(Span span) {
      return span.start < end && span.end > start;
    }
  }

  /** Nonempty payload range; exact duplicate ranges are shared without reading their bytes. */
  private record Span(long start, long end) {}
}
