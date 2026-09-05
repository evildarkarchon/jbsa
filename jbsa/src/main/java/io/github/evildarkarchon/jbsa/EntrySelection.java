package io.github.evildarkarchon.jbsa;

import java.util.List;

/**
 * Extraction selection by stable archive ordinal; operation preflight validates archive membership.
 */
public sealed interface EntrySelection permits EntrySelection.All, EntrySelection.Ordinals {
  /** Selects every entry. */
  EntrySelection ALL = new All();

  /** All archive entries in decoded archive order. */
  record All() implements EntrySelection {}

  /** Explicit zero-based entry ordinals; an empty list selects no entries. */
  record Ordinals(List<Long> ordinals) implements EntrySelection {
    /** Copies the selection and rejects negative or repeated ordinals. */
    public Ordinals {
      ordinals = List.copyOf(ordinals);
      if (ordinals.stream().anyMatch(ordinal -> ordinal < 0)
          || ordinals.stream().distinct().count() != ordinals.size()) {
        throw new IllegalArgumentException("ordinals must be distinct and nonnegative");
      }
    }
  }
}
