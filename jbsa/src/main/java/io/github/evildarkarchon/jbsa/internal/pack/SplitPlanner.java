package io.github.evildarkarchon.jbsa.internal.pack;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.internal.pack.Admitted.Planned;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Split planning: forms whole-entry Archive Parts in output order from a family's declarative
 * {@link Admitted.SplitCost}, the payload cost its {@link Admitted.Emission} case implies, and a
 * resolved byte target. It needs no adapter; the pipeline resolves the request's Splitting arm,
 * including the family default and legacy per-entry splitting, before building one.
 *
 * @param <K> the family's per-entry wire key
 */
final class SplitPlanner<K> {
  private final Admitted.SplitCost<K> cost;
  private final PayloadCost payload;
  private final long target;

  /**
   * Builds a planner for one pack.
   *
   * @param cost the family's fixed and per-name cost terms
   * @param payload the payload term its Emission case implies; see {@link #payloadCost}
   * @param target the resolved byte target; 0 never splits, and legacy per-entry splitting is 1
   */
  SplitPlanner(Admitted.SplitCost<K> cost, PayloadCost payload, long target) {
    this.cost = Objects.requireNonNull(cost);
    this.payload = Objects.requireNonNull(payload);
    this.target = target;
  }

  /**
   * Returns the payload term a split charges under an Emission: the declared size for a streamed
   * part, which cannot know an encoded size before it stages; the exact stored record for per-part
   * records; and an owner's stored size only at its first reference in a part for set-wide owners.
   */
  static PayloadCost payloadCost(Admitted.Emission<?> emission) {
    return switch (emission) {
      case Admitted.Emission.Streamed<?> streamed -> PayloadCost.DECODED;
      case Admitted.Emission.PerPartRecords<?> records -> PayloadCost.STORED;
      case Admitted.Emission.SetOwners<?> owners -> PayloadCost.UNIQUE_STORED;
    };
  }

  /**
   * Forms whole-entry parts in output order. A zero target never splits. A first-owner cost tracks
   * the owners each part already references, and re-costs the entry that opens a new part.
   *
   * @return the non-empty parts, each in output order
   * @throws IllegalStateException when a stored payload cost meets an entry whose stored size is
   *     not known yet
   */
  List<List<Planned<K>>> split(List<Planned<K>> planned) {
    List<List<Planned<K>>> parts = new ArrayList<>();
    List<Planned<K>> current = new ArrayList<>();
    Set<Integer> owners = new HashSet<>();
    long estimated = 0;
    for (Planned<K> entry : planned) {
      long overhead = overhead(entry);
      long cost = Math.addExact(overhead, payloadCost(entry, owners));
      if (target != 0 && !current.isEmpty() && cost > target - estimated) {
        parts.add(List.copyOf(current));
        current.clear();
        owners.clear();
        estimated = 0;
        // Owners the closed part already paid for are charged again in the new one.
        cost = Math.addExact(overhead, payloadCost(entry, owners));
      }
      current.add(entry);
      for (Admitted.Segment segment : entry.segments()) owners.add(segment.owner());
      estimated = Math.addExact(estimated, cost);
    }
    if (!current.isEmpty()) parts.add(List.copyOf(current));
    return parts;
  }

  /**
   * Returns how many parts a stabilized plan will certainly publish before any source is read. When
   * every stored size is predictable and sharing cannot lower a first-owner cost, the split is
   * exact, and each predicted part's layout is admitted now, in output order, so a wire limit fails
   * before any source read. Otherwise the count is known only when no entry can share a part (every
   * entry's minimum cost reaches a nonzero target) or nothing splits; any other plan is certain
   * only of its first part.
   *
   * @param sharing whether the request enables Content Sharing
   * @param layout admits one predicted part's layout
   * @throws ArchiveException the first predicted part whose layout cannot be encoded
   */
  int knownPartCount(List<Planned<K>> planned, boolean sharing, PartLayout<K> layout)
      throws ArchiveException {
    boolean shareable = sharing && payload == PayloadCost.UNIQUE_STORED;
    if (!shareable
        && planned.stream().allMatch(entry -> entry.storedSize() != Planned.UNKNOWN_SIZE)) {
      List<List<Planned<K>>> parts = split(planned);
      for (var part : parts) layout.admit(part);
      return parts.size();
    }
    if (target == 0) return 1;
    for (Planned<K> entry : planned)
      // An unknown payload costs at least zero, so this is the entry's minimum split cost.
      if (overhead(entry) < target) return 1;
    return planned.size();
  }

  /** Returns an entry's fixed and name cost, which every payload term adds to. */
  private long overhead(Planned<K> entry) {
    return Math.addExact(cost.fixed(), cost.nameCost().applyAsLong(entry.key()));
  }

  /**
   * Returns one entry's payload split cost; a stored cost needs the record's known size. A
   * first-owner cost charges each distinct segment owner the part does not reference yet; an
   * unsegmented entry (before stabilization, where nothing can be shared) costs its stored size.
   *
   * @param owners the segment owners the current part already references
   */
  private long payloadCost(Planned<?> entry, Set<Integer> owners) {
    return switch (payload) {
      case DECODED -> entry.source().size();
      case STORED -> knownStoredSize(entry);
      case UNIQUE_STORED -> {
        if (entry.segments().isEmpty()) yield knownStoredSize(entry);
        Set<Integer> counted = new HashSet<>();
        long cost = 0;
        for (Admitted.Segment segment : entry.segments())
          if (!owners.contains(segment.owner()) && counted.add(segment.owner()))
            cost = Math.addExact(cost, segment.storedSize());
        yield cost;
      }
    };
  }

  /** Returns a stored size that splitting may charge, which must already be known. */
  private static long knownStoredSize(Planned<?> entry) {
    if (entry.storedSize() == Planned.UNKNOWN_SIZE)
      throw new IllegalStateException("A stored split cost needs a stabilized record size");
    return entry.storedSize();
  }

  /**
   * Admits one predicted part's layout, the way {@link Admitted#layout} does, before any source is
   * read.
   */
  @FunctionalInterface
  interface PartLayout<K> {
    /**
     * Checks one non-empty part, in output order.
     *
     * @throws ArchiveException when a field of this part cannot be encoded
     */
    void admit(List<Planned<K>> part) throws ArchiveException;
  }

  /**
   * Which advisory payload extent a split charges for one entry. The {@link Admitted.Emission} case
   * implies it, so no adapter declares it.
   */
  enum PayloadCost {
    /** The declared source size. */
    DECODED,
    /** The stabilized record size, including framing. */
    STORED,
    /** The stabilized record size, charged only for the first owner within a part. */
    UNIQUE_STORED
  }
}
