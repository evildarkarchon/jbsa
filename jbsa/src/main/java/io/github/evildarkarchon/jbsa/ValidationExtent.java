package io.github.evildarkarchon.jbsa;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/** The immutable boundary of established archive validation evidence. */
public sealed interface ValidationExtent
    permits ValidationExtent.Recognition, ValidationExtent.Structure, ValidationExtent.Payloads {
  /** Recognition evidence only; this does not imply a structural validation claim. */
  record Recognition() implements ValidationExtent {}

  /** Complete structural evidence, without a claim about every payload. */
  record Structure() implements ValidationExtent {}

  /**
   * Structural evidence plus validation of the specified archive-order payload ordinals.
   *
   * @param entryOrdinals nonnegative ordinals, retained in ascending order
   */
  record Payloads(Set<Long> entryOrdinals) implements ValidationExtent {
    /** Copies the selected ordinals, rejecting null or negative values. */
    public Payloads {
      Objects.requireNonNull(entryOrdinals, "entryOrdinals");
      var copy = new TreeSet<Long>();
      for (Long ordinal : entryOrdinals) {
        if (Objects.requireNonNull(ordinal, "entry ordinal") < 0) {
          throw new IllegalArgumentException("Entry ordinals must be nonnegative");
        }
        copy.add(ordinal);
      }
      entryOrdinals = Collections.unmodifiableSortedSet(copy);
    }
  }
}
