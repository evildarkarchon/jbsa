package io.github.evildarkarchon.jbsa;

import java.util.Collections;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Immutable warning-rejection policy; rejection preserves the diagnostic's WARNING severity.
 *
 * @param rejectedWarningIdentifiers warning identifiers that the caller rejects
 */
public record DiagnosticPolicy(Set<String> rejectedWarningIdentifiers) {
  /** Copies identifiers into natural order and rejects null or blank identifiers. */
  public DiagnosticPolicy {
    Objects.requireNonNull(rejectedWarningIdentifiers, "rejectedWarningIdentifiers");
    var copy = new TreeSet<String>();
    for (String identifier : rejectedWarningIdentifiers) {
      if (Objects.requireNonNull(identifier, "identifier").isBlank()) {
        throw new IllegalArgumentException("Diagnostic identifier must not be blank");
      }
      copy.add(identifier);
    }
    rejectedWarningIdentifiers = Collections.unmodifiableSortedSet(copy);
  }

  /** Returns the default policy accepting all warnings. */
  public static DiagnosticPolicy standard() {
    return new DiagnosticPolicy(Set.of());
  }
}
