package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.util.List;
import java.util.Objects;
import java.util.function.ToLongFunction;

/**
 * Immutable request-derived family state returned by {@link FamilyAdapter#admit}. Its data members
 * configure the pipeline, and its functions are pure and repeatable: the same input always yields
 * the same output, so the pipeline may call them during planning and again while staging.
 *
 * @param <K> the family's per-entry wire key
 */
public interface Admitted<K> {
  /** Returns the charset {@link PackSources#plan} uses to encode and admit names. */
  Charset nameCharset();

  /** Returns the diagnostic prefix for pipeline-emitted family IDs, such as {@code tes3}. */
  String diagnosticPrefix();

  /** Returns the split target of {@code PackOptions.Splitting.FamilyDefault}; 0 never splits. */
  long defaultSplitTarget();

  /** Returns whether payloads stream into each part or are stabilized before splitting. */
  Emission emission();

  /** Returns the declarative advisory cost that drives whole-entry split assignment. */
  SplitCost<K> splitCost();

  /** Returns the family's intrinsic Content Sharing scope and comparison basis. */
  Sharing sharing();

  /**
   * Applies post-plan per-entry rules and returns every source in the family's output order.
   *
   * @param sources the complete source plan in Logical Plan Order
   * @param context the PREFLIGHT location for structured failures
   * @throws ArchiveException the first per-entry rule an entry breaks
   */
  List<Planned<K>> plan(List<PackSources.Entry> sources, IoContext context) throws ArchiveException;

  /**
   * Rejects one stabilized record whose exact stored size the wire cannot encode. The pipeline
   * calls this once per entry, in output order, immediately after the record is stabilized and
   * before the next entry is read, so the failure keeps that entry's PROCESSING ordinal. Streaming
   * families never stabilize and keep the default, which accepts every record.
   *
   * @param entry the entry with its exact {@link Planned#storedSize()}
   * @param processing the entry's PROCESSING location for structured failures
   * @throws ArchiveException when the stored record cannot be encoded
   */
  default void checkStored(Planned<K> entry, IoContext processing) throws ArchiveException {
    // Most families have no stored-size rule beyond the per-part layout checks.
  }

  /**
   * Returns the bounded per-entry working heap the pipeline reserves for each planned entry, right
   * after {@link #plan}, and holds until the operation's budget closes. Most families need none.
   */
  default long entryReserveBytes() {
    return 0;
  }

  /**
   * Returns how many leading source bytes {@link #chunk} inspects, or 0 when this family never
   * chunks. A chunking family defers its decoded-size charge to stabilization, because only the
   * chunked envelope knows the decoded extent.
   */
  default int chunkHeadBytes() {
    return 0;
  }

  /**
   * Splits one stabilized source into independently shared and encoded payload slices, from its
   * envelope head alone. Only called when {@link #chunkHeadBytes()} is positive; the pipeline
   * performs the {@link Chunks#rewrite()} and every slice's I/O.
   *
   * @param entry the entry being stabilized, in output order
   * @param head the first {@code min(chunkHeadBytes(), size)} source bytes, positioned at zero
   * @param processing the entry's PROCESSING location for structured failures
   * @throws ArchiveException when the envelope is malformed or cannot be preserved
   */
  default Chunks<K> chunk(Planned<K> entry, ByteBuffer head, IoContext processing)
      throws ArchiveException {
    throw new UnsupportedOperationException("This family does not chunk its payloads");
  }

  /**
   * Returns how to validate one staged part after its handle closes, or null when the family does
   * not read its output back. The pipeline performs the reopen and full decode; this only states
   * the credits it needs and the diagnostic for a nonconforming part.
   *
   * @param part the part's entries, in output order, with their exact stored sizes
   * @param layout the value {@link #layout} returned for this part
   */
  default Readback readback(List<Planned<K>> part, Layout layout) {
    return null;
  }

  /**
   * Computes one part's payload start and encoded metadata extent, rejecting unrepresentable wire
   * fields. Payload offsets are checked against their unshared worst case: each entry's {@link
   * Planned#storedSize()}, which is exact for a stabilized part and predicted for a streamed one.
   *
   * @param part one non-empty part, in output order
   * @throws ArchiveException when a field of this part cannot be encoded
   */
  Layout layout(List<Planned<K>> part, IoContext context) throws ArchiveException;

  /**
   * Returns the positional metadata writes for one placed part. The pipeline applies them after
   * every payload is placed, so no family needs header-first or header-last branching.
   *
   * @param part the part's entries, in output order, with their final payload positions
   * @param layout the value {@link #layout} returned for this part
   */
  List<Patch> tables(List<Placed<K>> part, Layout layout);

  /** Whether each part's payloads are emitted while it stages or stabilized for the whole set. */
  enum Emission {
    /** Each part reads its sources while it stages; split cost cannot depend on encoded size. */
    STREAMING,
    /** Every payload is stabilized in plan order before splitting (versioned BSA and BA2). */
    STABILIZED
  }

  /** Which advisory payload extent a split charges for one entry. */
  enum PayloadCost {
    /** The declared source size. */
    DECODED,
    /** The stabilized record size, including framing. */
    STORED,
    /** The stabilized record size, charged only for the first owner within a part. */
    UNIQUE_STORED
  }

  /**
   * Declarative whole-entry split cost: {@code fixed + nameCost(key) + payload}. Split cost is per
   * family because it decides part membership, which Binary Conformance fixtures pin.
   */
  record SplitCost<K>(long fixed, ToLongFunction<K> nameCost, PayloadCost payload) {
    /** Rejects a negative constant or a missing function before any split is planned. */
    public SplitCost {
      if (fixed < 0) throw new IllegalArgumentException("Negative split cost");
      Objects.requireNonNull(nameCost, "nameCost");
      Objects.requireNonNull(payload, "payload");
    }
  }

  /** The Content Sharing scope and basis intrinsic to an Archive Family. */
  record Sharing(Scope scope, Basis basis) {
    /** Requires both dimensions. */
    public Sharing {
      Objects.requireNonNull(scope, "scope");
      Objects.requireNonNull(basis, "basis");
    }

    /** Where one stored copy may be referenced from. */
    public enum Scope {
      /** Identical payloads share within one Archive Part only. */
      PER_PART,
      /** Sharing owners are decided once for the whole archive set. */
      ARCHIVE_SET
    }

    /** Which bytes establish equality. */
    public enum Basis {
      /** The decoded source bytes. */
      RAW,
      /** The complete stored record, including framing and the codec discriminator. */
      STORED
    }
  }

  /**
   * One source in output order with its per-entry encoding decisions.
   *
   * @param source the planned source, which also carries the decoded-size prediction
   * @param key the family's wire key
   * @param codec the payload encoding
   * @param frame bytes the stored record carries before the encoded payload; empty when unframed
   * @param storedSize the stored record's size including its frame, or {@link #UNKNOWN_SIZE} until
   *     an encoded payload has been stabilized
   * @param segments the archive-set Content Sharing owners this entry's payload resolved to, in
   *     payload order; empty unless the family shares raw bytes across the whole archive set and
   *     the entry has been stabilized
   */
  record Planned<K>(
      PackSources.Entry source,
      K key,
      Codec codec,
      byte[] frame,
      long storedSize,
      List<Segment> segments) {
    /** The stored size of an encoded record before stabilization has measured it. */
    public static final long UNKNOWN_SIZE = -1;

    /** Requires every member; the frame array is owned by this record. */
    public Planned {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(codec, "codec");
      Objects.requireNonNull(frame, "frame");
      if (storedSize < UNKNOWN_SIZE) throw new IllegalArgumentException("Negative stored size");
      segments = List.copyOf(segments);
    }

    /** An entry whose payload is one unsegmented record of a known or unknown stored size. */
    public Planned(PackSources.Entry source, K key, Codec codec, byte[] frame, long storedSize) {
      this(source, key, codec, frame, storedSize, List.of());
    }

    /**
     * Predicts the stored size from the codec: a stored record is exactly its frame plus the
     * declared source size, while an encoded one stays {@link #UNKNOWN_SIZE} until stabilized.
     */
    public Planned(PackSources.Entry source, K key, Codec codec, byte[] frame) {
      this(
          source,
          key,
          codec,
          frame,
          codec == Codec.STORED ? Math.addExact(frame.length, source.size()) : UNKNOWN_SIZE);
    }

    /** Returns this entry with the exact stored size stabilization measured. */
    public Planned<K> stabilized(long measured) {
      if (measured < 0) throw new IllegalArgumentException("Negative stored size");
      return new Planned<>(source, key, codec, frame, measured, segments);
    }

    /**
     * Returns this entry after raw-basis stabilization resolved its payload to archive-set owners.
     * Its stored size is the sum of the owners' stored sizes: the bytes the entry would occupy if
     * none of its segments were shared within a part.
     *
     * @param resolved the key, possibly extended by {@link Admitted#chunk} with envelope metadata
     * @param owners the entry's segments in payload order, one per chunk slice or one for the whole
     *     payload
     */
    public Planned<K> segmented(K resolved, List<Segment> owners) {
      long total = 0;
      for (Segment segment : owners) total = Math.addExact(total, segment.storedSize());
      return new Planned<>(source, resolved, codec, frame, total, owners);
    }
  }

  /**
   * One stabilized slice of an entry's payload, resolved to its archive-set Content Sharing owner.
   *
   * @param rawSize the slice's decoded size
   * @param storedSize the owner's stored size
   * @param codec the owner's codec, which can differ from the entry's own when raw bytes are shared
   *     between a stored entry and a compressed one
   * @param owner the owner's archive-set identity; equal identities are emitted once per part
   */
  record Segment(long rawSize, long storedSize, Codec codec, int owner) {
    /** Rejects negative extents and a missing codec. */
    public Segment {
      if (rawSize < 0 || storedSize < 0 || owner < 0)
        throw new IllegalArgumentException("Negative segment field");
      Objects.requireNonNull(codec, "codec");
    }
  }

  /**
   * One planned entry after placement.
   *
   * @param offset the absolute part position of its stored record, possibly shared; for a segmented
   *     entry, the position of its first segment
   * @param storedSize the stored record's length
   * @param segmentOffsets the absolute part position of each {@link Planned#segments()} owner, in
   *     the same order; empty for an unsegmented entry
   */
  record Placed<K>(Planned<K> planned, long offset, long storedSize, List<Long> segmentOffsets) {
    /** Detaches the segment positions. */
    public Placed {
      segmentOffsets = List.copyOf(segmentOffsets);
    }

    /** One unsegmented placed record. */
    public Placed(Planned<K> planned, long offset, long storedSize) {
      this(planned, offset, storedSize, List.of());
    }
  }

  /**
   * The chunking of one stabilized source, derived purely from its envelope head.
   *
   * @param key the entry's key extended with the envelope metadata its tables need
   * @param payloadStart the source offset where the chunked payload begins; earlier bytes are the
   *     envelope header, which is not stored
   * @param rewrite the normalization applied to the payload before it is sliced
   * @param slices the payload slices, relative to the rewritten payload, in payload order
   * @param decodedBytes the entry's decoded size charged against {@code maxDecodedBytes}
   * @param metadataBytes the envelope metadata charged against {@code maxMetadataBytes}
   */
  record Chunks<K>(
      K key,
      long payloadStart,
      Rewrite rewrite,
      List<Slice> slices,
      long decodedBytes,
      long metadataBytes) {
    /** Requires every member and detaches the slices. */
    public Chunks {
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(rewrite, "rewrite");
      if (payloadStart < 0 || decodedBytes < 0 || metadataBytes < 0)
        throw new IllegalArgumentException("Negative chunk field");
      slices = List.copyOf(slices);
    }
  }

  /** One payload slice relative to the rewritten payload start. */
  record Slice(long offset, long size) {
    /** Rejects a negative range. */
    public Slice {
      if (offset < 0 || size < 0) throw new IllegalArgumentException("Negative slice");
    }
  }

  /** A payload normalization the pipeline applies before slicing. */
  enum Rewrite {
    /** The payload is sliced as read. */
    NONE,
    /** Every three BGR24 source bytes gain a fourth, opaque 0xFF alpha byte (BGRA32). */
    BGR24_TO_BGRA32
  }

  /**
   * How the pipeline validates one staged part: it admits these credits against the operation
   * budget, reopens the part, requires a CONFORMING assessment, and decodes every entry.
   *
   * @param nonconformingId the INTERNAL diagnostic for a part that reopens as nonconforming
   * @param heapBytes the reader's peak heap, including one 64 KiB decode window
   * @param nativeBytes the reader's peak native memory
   * @param handles the file handles the reader holds
   */
  record Readback(String nonconformingId, long heapBytes, long nativeBytes, long handles) {
    /** Requires the identifier and rejects negative credits. */
    public Readback {
      Objects.requireNonNull(nonconformingId, "nonconformingId");
      if (heapBytes < 0 || nativeBytes < 0 || handles < 0)
        throw new IllegalArgumentException("Negative readback credit");
    }
  }

  /**
   * One part's layout.
   *
   * @param payloadStart the absolute position of the first stored payload
   * @param metadataBytes the encoded metadata admitted against {@code maxMetadataBytes}
   */
  record Layout(long payloadStart, long metadataBytes) {}

  /** One positional metadata write; the byte array is owned by this record. */
  record Patch(long position, byte[] bytes) {
    /** Rejects a negative position or missing bytes. */
    public Patch {
      if (position < 0) throw new IllegalArgumentException("Negative patch position");
      Objects.requireNonNull(bytes, "bytes");
    }
  }
}
