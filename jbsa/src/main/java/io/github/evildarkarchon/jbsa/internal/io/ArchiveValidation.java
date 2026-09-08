package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;

/** Shared host-name evidence independent of format disposition and destination effects. */
public final class ArchiveValidation {
  private ArchiveValidation() {}

  /** Emits each applicable warning once, preserving decoded names and first segment ordinals. */
  public static void name(EntryMetadata entry, IoContext context, FailureRetention retention) {
    String name = entry.displayName();
    String mapped = name.replace('/', '\\');
    if (mapped.startsWith("\\")
        || (mapped.length() >= 3
            && asciiLetter(mapped.charAt(0))
            && mapped.charAt(1) == ':'
            && mapped.charAt(2) == '\\')) {
      warning("archive-name.absolute-path", entry, context, null, -1, retention);
    }
    boolean traversal = false;
    boolean invalid = false;
    String[] segments = mapped.split("\\\\", -1);
    for (int ordinal = 0; ordinal < segments.length; ordinal++) {
      String segment = segments[ordinal];
      if (!traversal && (segment.equals(".") || segment.equals(".."))) {
        warning("archive-name.traversal-segment", entry, context, segment, ordinal, retention);
        traversal = true;
      }
      if (!invalid && windowsInvalidSegment(segment)) {
        warning(
            "archive-name.windows-invalid-segment", entry, context, segment, ordinal, retention);
        invalid = true;
      }
    }
  }

  /** Checks the qualified Windows characters and device basenames without creating a host Path. */
  public static boolean windowsInvalidSegment(String segment) {
    for (int index = 0; index < segment.length(); index++) {
      char value = segment.charAt(index);
      if ((value >= 1 && value <= 31) || "\"*<>?|".indexOf(value) >= 0) return true;
    }
    int dot = segment.indexOf('.');
    String stem = dot < 0 ? segment : segment.substring(0, dot);
    StringBuilder folded = new StringBuilder(stem.length());
    for (int index = 0; index < stem.length(); index++) {
      char value = stem.charAt(index);
      folded.append(value >= 'A' && value <= 'Z' ? (char) (value + ('a' - 'A')) : value);
    }
    stem = folded.toString();
    return Set.of("con", "prn", "aux", "nul").contains(stem)
        || (stem.length() == 4
            && (stem.startsWith("com") || stem.startsWith("lpt"))
            && "123456789\u00b9\u00b2\u00b3".indexOf(stem.charAt(3)) >= 0);
  }

  /** Establishes a new assessment without modifying the loader's detached structural evidence. */
  public static ArchiveInspection structure(
      ArchiveInspection inspection,
      Iterable<EntryMetadata> entries,
      IoContext context,
      FailureRetention retention)
      throws ArchiveException {
    retention.assessment(inspection.assessment());
    for (EntryMetadata entry : entries) name(entry, context, retention);
    ArchiveAssessment assessment =
        new ArchiveAssessment(
            inspection.assessment().disposition(),
            inspection.assessment().extent(),
            retention.diagnostics());
    retention.latestAssessment(assessment);
    ArchiveException failure = retention.finish(List.of());
    if (failure != null) throw failure;
    return new ArchiveInspection(
        inspection.detection(), inspection.metadata(), assessment, inspection.entries());
  }

  /** Records the complete display name separately from a segment's value and unsigned ordinal. */
  private static void warning(
      String identifier,
      EntryMetadata entry,
      IoContext context,
      String segment,
      int ordinal,
      FailureRetention retention) {
    var values = new TreeMap<String, String>();
    if (ordinal >= 0) values.put("segmentOrdinal", Integer.toString(ordinal));
    retention.diagnostic(
        new Diagnostic(
            identifier,
            DiagnosticSeverity.WARNING,
            context.operation(),
            context.phase(),
            new DiagnosticLocation(
                Optional.of(context.path()),
                OptionalLong.of(entry.ordinal()),
                Optional.of(entry.displayName()),
                Optional.ofNullable(segment),
                Optional.empty(),
                Optional.empty()),
            values,
            Optional.empty()));
  }

  /** Drive syntax is ASCII-defined and must not depend on Unicode or the default locale. */
  private static boolean asciiLetter(char value) {
    return (value >= 'A' && value <= 'Z') || (value >= 'a' && value <= 'z');
  }
}
