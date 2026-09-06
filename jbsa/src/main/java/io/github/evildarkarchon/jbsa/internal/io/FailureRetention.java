package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.math.BigInteger;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.PriorityQueue;
import java.util.TreeMap;

/**
 * Worker-confined bounded failure evidence for already ordered operation outcomes. The caller
 * selects its primary outcome before accepting later cleanup or rollback failures.
 */
public final class FailureRetention {
  private static final String TRUNCATED = "operation.records-truncated";
  private static final Comparator<Diagnostic> DIAGNOSTIC_ORDER =
      FailureRetention::compareDiagnostic;
  private static final Comparator<Failure> FAILURE_ORDER = FailureRetention::compareFailure;
  private final ResourceLimits limits;
  private final Operation operation;
  private final PriorityQueue<Diagnostic> diagnostics =
      new PriorityQueue<>(DIAGNOSTIC_ORDER.reversed());
  private final PriorityQueue<Failure> secondary = new PriorityQueue<>(FAILURE_ORDER.reversed());
  private final Map<Path, Artifact> artifacts = new HashMap<>();
  private Failure primary;
  private String message;
  private Optional<ArchiveAssessment> assessment = Optional.empty();
  private BigInteger omittedDiagnostics = BigInteger.ZERO;
  private BigInteger omittedSecondary = BigInteger.ZERO;
  private boolean unseenDiagnostics;
  private boolean unseenSecondary;

  /** Reserves one diagnostic slot for the single operation-wide truncation summary. */
  public FailureRetention(ResourceLimits limits, Operation operation) {
    this.limits = Objects.requireNonNull(limits, "limits");
    this.operation = Objects.requireNonNull(operation, "operation");
  }

  /**
   * Accepts the first failure as primary and later failures as secondary, retaining the smallest
   * semantic-order records within the configured ceilings. Nested truncation summaries are merged.
   */
  public void accept(ArchiveException failure) {
    Objects.requireNonNull(failure, "failure");
    if (primary == null) {
      primary = failure.primaryFailure();
      message = failure.getMessage();
      assessment = failure.assessment();
    } else {
      retainSecondary(failure.primaryFailure());
    }
    for (Failure additional : failure.secondaryFailures()) retainSecondary(additional);
    for (Diagnostic diagnostic : failure.diagnostics()) {
      if (TRUNCATED.equals(diagnostic.identifier())) {
        Map<String, String> values = diagnostic.values();
        omittedDiagnostics =
            omittedDiagnostics.add(new BigInteger(values.get("omittedDiagnostics")));
        omittedSecondary =
            omittedSecondary.add(new BigInteger(values.get("omittedSecondaryFailures")));
        unseenDiagnostics |= Boolean.parseBoolean(values.get("additionalDiagnosticsMayExist"));
        unseenSecondary |= Boolean.parseBoolean(values.get("additionalSecondaryFailuresMayExist"));
      } else if (!retain(diagnostics, diagnostic, limits.maxDiagnostics() - 1, DIAGNOSTIC_ORDER)) {
        omittedDiagnostics = omittedDiagnostics.add(BigInteger.ONE);
      }
    }
    mergeArtifacts(failure.artifacts());
  }

  /** Returns whether an operational failure has been accepted. */
  public boolean failed() {
    return primary != null;
  }

  /**
   * Returns a detached failure, or null when none was accepted. Supplied settled artifact states
   * override earlier states at the same normalized absolute path; cancellation keeps its subtype.
   */
  public ArchiveException finish(List<Artifact> settledArtifacts) {
    if (primary == null) return null;
    mergeArtifacts(settledArtifacts);
    var orderedDiagnostics = new ArrayList<>(diagnostics);
    if (omittedDiagnostics.signum() > 0
        || omittedSecondary.signum() > 0
        || unseenDiagnostics
        || unseenSecondary) {
      var values = new TreeMap<String, String>();
      values.put("omittedDiagnostics", omittedDiagnostics.toString());
      values.put("omittedSecondaryFailures", omittedSecondary.toString());
      values.put("additionalDiagnosticsMayExist", Boolean.toString(unseenDiagnostics));
      values.put("additionalSecondaryFailuresMayExist", Boolean.toString(unseenSecondary));
      orderedDiagnostics.add(
          new Diagnostic(
              TRUNCATED,
              DiagnosticSeverity.WARNING,
              operation,
              OperationPhase.CLEANUP,
              DiagnosticLocation.operation(),
              values,
              Optional.empty()));
    }
    orderedDiagnostics.sort(DIAGNOSTIC_ORDER);
    var orderedSecondary = new ArrayList<>(secondary);
    orderedSecondary.sort(FAILURE_ORDER);
    var orderedArtifacts = new ArrayList<>(artifacts.values());
    orderedArtifacts.sort(
        Comparator.comparingLong(Artifact::ordinal).thenComparing(a -> a.path().toString()));
    if (primary.kind() == FailureKind.CANCELLED) {
      return new ArchiveCancelledException(
          message, primary, orderedDiagnostics, orderedArtifacts, assessment, orderedSecondary);
    }
    return new ArchiveException(
        message, primary, orderedDiagnostics, orderedArtifacts, assessment, orderedSecondary);
  }

  /** Normalizes reporting paths without following links or changing filesystem state. */
  private void mergeArtifacts(List<Artifact> records) {
    for (Artifact artifact : records) {
      Path path = artifact.path().toAbsolutePath().normalize();
      artifacts.put(path, new Artifact(path, artifact.ordinal(), artifact.state()));
    }
  }

  /** Counts every omitted observed failure even when its diagnostic is independently retained. */
  private void retainSecondary(Failure failure) {
    if (!retain(secondary, failure, limits.maxSecondaryFailures(), FAILURE_ORDER)) {
      omittedSecondary = omittedSecondary.add(BigInteger.ONE);
    }
  }

  /**
   * Keeps the earliest semantic records in a bounded max-heap; replacing its last record still
   * means exactly one observed record was omitted. No collection grows beyond its ceiling.
   */
  private static <T> boolean retain(
      PriorityQueue<T> queue, T record, long ceiling, Comparator<T> order) {
    if (queue.size() < ceiling) {
      queue.add(record);
      return true;
    }
    if (ceiling > 0 && order.compare(record, queue.element()) < 0) {
      queue.remove();
      queue.add(record);
    }
    return false;
  }

  /** Orders diagnostics by phase, plan ordinal, structured location, and identifier. */
  private static int compareDiagnostic(Diagnostic left, Diagnostic right) {
    int result = left.phase().compareTo(right.phase());
    if (result == 0)
      result = compareOrdinal(left.location().entryOrdinal(), right.location().entryOrdinal());
    if (result == 0) result = compareLocation(left.location(), right.location());
    if (result == 0) result = left.identifier().compareTo(right.identifier());
    if (result == 0) result = left.values().toString().compareTo(right.values().toString());
    return result;
  }

  /** Orders secondary failures without deriving any key from provider throwable details. */
  private static int compareFailure(Failure left, Failure right) {
    int result = left.phase().compareTo(right.phase());
    if (result == 0) result = compareOrdinal(left.ordinal(), right.ordinal());
    if (result == 0)
      result =
          compareOptional(
              left.diagnosticIdentifier(), right.diagnosticIdentifier(), Comparator.naturalOrder());
    if (result == 0)
      result =
          compareOptional(left.location(), right.location(), FailureRetention::compareLocation);
    return result;
  }

  /** Compares stable structured members in declaration order, with present values first. */
  private static int compareLocation(DiagnosticLocation left, DiagnosticLocation right) {
    int result =
        compareOptional(left.archive(), right.archive(), Comparator.comparing(Path::toString));
    if (result == 0) result = compareOrdinal(left.entryOrdinal(), right.entryOrdinal());
    if (result == 0)
      result = compareOptional(left.entryName(), right.entryName(), Comparator.naturalOrder());
    if (result == 0)
      result = compareOptional(left.field(), right.field(), Comparator.naturalOrder());
    if (result == 0)
      result =
          compareOptional(
              left.byteSpan(),
              right.byteSpan(),
              Comparator.comparingLong(DiagnosticLocation.ByteSpan::offset)
                  .thenComparingLong(DiagnosticLocation.ByteSpan::length));
    if (result == 0)
      result =
          compareOptional(left.artifact(), right.artifact(), Comparator.comparing(Path::toString));
    return result;
  }

  /** Every present ordinal, including Long.MAX_VALUE, sorts before an absent one. */
  private static int compareOrdinal(OptionalLong left, OptionalLong right) {
    if (left.isEmpty()) return right.isEmpty() ? 0 : 1;
    return right.isEmpty() ? -1 : Long.compare(left.getAsLong(), right.getAsLong());
  }

  /** Keeps present semantic keys before missing keys and continues comparisons on equal absence. */
  private static <T> int compareOptional(Optional<T> left, Optional<T> right, Comparator<T> order) {
    if (left.isEmpty()) return right.isEmpty() ? 0 : 1;
    return right.isEmpty() ? -1 : order.compare(left.orElseThrow(), right.orElseThrow());
  }
}
