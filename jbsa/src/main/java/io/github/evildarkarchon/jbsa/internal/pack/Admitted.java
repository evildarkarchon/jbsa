package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
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
   */
  record Planned<K>(PackSources.Entry source, K key, Codec codec, byte[] frame, long storedSize) {
    /** The stored size of an encoded record before stabilization has measured it. */
    public static final long UNKNOWN_SIZE = -1;

    /** Requires every member; the frame array is owned by this record. */
    public Planned {
      Objects.requireNonNull(source, "source");
      Objects.requireNonNull(key, "key");
      Objects.requireNonNull(codec, "codec");
      Objects.requireNonNull(frame, "frame");
      if (storedSize < UNKNOWN_SIZE) throw new IllegalArgumentException("Negative stored size");
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
      return new Planned<>(source, key, codec, frame, measured);
    }
  }

  /**
   * One planned entry after placement.
   *
   * @param offset the absolute part position of its stored record, possibly shared
   * @param storedSize the stored record's length
   */
  record Placed<K>(Planned<K> planned, long offset, long storedSize) {}

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
