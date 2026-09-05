package io.github.evildarkarchon.jbsa;

import java.nio.charset.Charset;
import java.util.Objects;
import java.util.Optional;

/** Locale-independent, structurally safe name identity, distinct from display and wire spelling. */
public record NormalizedNameIdentity(String value) {
  /** Creates an already canonical identity; rejects unsafe or noncanonical values. */
  public NormalizedNameIdentity {
    Objects.requireNonNull(value, "value");
    if (!canonical(value).filter(value::equals).isPresent()) {
      throw new IllegalArgumentException("Identity must be structurally safe and canonical");
    }
  }

  /**
   * Derives an identity from a complete decoded or caller name under its explicit name encoding.
   * Returns empty for unsafe, unmappable, or invalid Unicode text; synthetic names must be omitted
   * by the caller rather than passed as decoded wire text.
   */
  public static Optional<NormalizedNameIdentity> from(String displayName, Charset encoding) {
    Objects.requireNonNull(displayName, "displayName");
    Objects.requireNonNull(encoding, "encoding");
    if (!encoding.newEncoder().canEncode(displayName)) {
      return Optional.empty();
    }
    return canonical(displayName).map(NormalizedNameIdentity::new);
  }

  /**
   * Checks scalar validity and archive structure before applying separator and ASCII case mapping.
   */
  private static Optional<String> canonical(String name) {
    var mapped = name.replace('/', '\\');
    if (mapped.isEmpty() || mapped.indexOf('\u0000') >= 0 || mapped.indexOf(':') >= 0) {
      return Optional.empty();
    }
    for (var segment : mapped.split("\\\\", -1)) {
      if (segment.isEmpty()
          || segment.equals(".")
          || segment.equals("..")
          || segment.endsWith(" ")
          || segment.endsWith(".")) {
        return Optional.empty();
      }
    }
    var result = new StringBuilder(mapped.length());
    for (int offset = 0; offset < mapped.length(); ) {
      int scalar = mapped.codePointAt(offset);
      if (scalar >= Character.MIN_SURROGATE && scalar <= Character.MAX_SURROGATE) {
        return Optional.empty();
      }
      result.appendCodePoint(scalar >= 'A' && scalar <= 'Z' ? scalar + ('a' - 'A') : scalar);
      offset += Character.charCount(scalar);
    }
    return Optional.of(result.toString());
  }
}
