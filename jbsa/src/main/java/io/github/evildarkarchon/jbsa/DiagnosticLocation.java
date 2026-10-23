package io.github.evildarkarchon.jbsa;

import java.nio.file.Path;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;

/**
 * Structured evidence location; absent members identify an operation-wide diagnostic.
 *
 * <p>A path is placed by its side of the operation, whatever the Failure Kind: an Operation Source
 * goes in {@code archive}, an Operation Destination in {@code artifact}. A generated pack source
 * has no path, so it is located by its entry ordinal and name alone.
 *
 * @param archive the Operation Source the operation read, when applicable: a source archive, or a
 *     pack source file
 * @param entryOrdinal decoded archive-order entry ordinal
 * @param entryName complete display name when applicable
 * @param field format field or offending name segment when applicable
 * @param byteSpan encoded byte span when applicable
 * @param artifact the affected Operation Destination artifact, when applicable, including staged
 *     and scratch data the operation reads back
 */
public record DiagnosticLocation(
    Optional<Path> archive,
    OptionalLong entryOrdinal,
    Optional<String> entryName,
    Optional<String> field,
    Optional<ByteSpan> byteSpan,
    Optional<Path> artifact) {
  /** Checks optional members and rejects negative entry ordinals. */
  public DiagnosticLocation {
    Objects.requireNonNull(archive, "archive");
    Objects.requireNonNull(entryOrdinal, "entryOrdinal");
    Objects.requireNonNull(entryName, "entryName");
    Objects.requireNonNull(field, "field");
    Objects.requireNonNull(byteSpan, "byteSpan");
    Objects.requireNonNull(artifact, "artifact");
    if (entryOrdinal.isPresent() && entryOrdinal.getAsLong() < 0) {
      throw new IllegalArgumentException("Entry ordinal must be nonnegative");
    }
  }

  /** Returns the location of a diagnostic applying to the operation as a whole. */
  public static DiagnosticLocation operation() {
    return new DiagnosticLocation(
        Optional.empty(),
        OptionalLong.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty(),
        Optional.empty());
  }

  /**
   * An encoded half-open byte span whose end is representable as a nonnegative long.
   *
   * @param offset starting byte offset
   * @param length number of bytes
   */
  public record ByteSpan(long offset, long length) {
    /** Rejects negative coordinates or a span whose end overflows. */
    public ByteSpan {
      if (offset < 0 || length < 0 || offset > Long.MAX_VALUE - length) {
        throw new IllegalArgumentException("Byte span must have a representable nonnegative end");
      }
    }
  }
}
