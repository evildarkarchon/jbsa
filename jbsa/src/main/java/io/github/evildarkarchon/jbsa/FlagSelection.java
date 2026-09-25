package io.github.evildarkarchon.jbsa;

/** Independent automatic classification or literal unsigned 32-bit BSA flag override. */
public sealed interface FlagSelection permits FlagSelection.Automatic, FlagSelection.Explicit {
  /** Automatically derives this flag group from the final admitted entries. */
  FlagSelection AUTOMATIC = new Automatic();

  /** Requests family-specific automatic flag classification. */
  record Automatic() implements FlagSelection {}

  /** Literal override; zero is distinct from automatic selection. */
  record Explicit(long value) implements FlagSelection {
    /**
     * Captures an unsigned 32-bit flag group; family-specific combinations are checked in
     * preflight.
     */
    public Explicit {
      if (value < 0 || value > 0xffff_ffffL) {
        throw new IllegalArgumentException("flags must fit unsigned 32-bit range");
      }
    }
  }
}
