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
 * Coordinator-confined bounded operation evidence. The first accepted outcome locks the
 * cancellation race, while observed operational failures settle in deterministic semantic order.
 */
public final class FailureRetention {
  private static final String TRUNCATED = "operation.records-truncated";
  private static final Comparator<Diagnostic> DIAGNOSTIC_ORDER =
      FailureRetention::compareDiagnostic;
  private static final Comparator<Failure> FAILURE_ORDER = FailureRetention::compareFailure;
  private final ResourceLimits limits;
  private final Operation operation;
  private final DiagnosticPolicy policy;
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
  private boolean summaryPolicyAccepted;

  /** Reserves one diagnostic slot for the single operation-wide truncation summary. */
  public FailureRetention(ResourceLimits limits, Operation operation) {
    this(limits, operation, DiagnosticPolicy.standard());
  }

  /** Reserves summary capacity and evaluates immutable warning policy before dropping records. */
  public FailureRetention(ResourceLimits limits, Operation operation, DiagnosticPolicy policy) {
    this.limits = Objects.requireNonNull(limits, "limits");
    this.operation = Objects.requireNonNull(operation, "operation");
    this.policy = Objects.requireNonNull(policy, "policy");
  }

  /**
   * Accepts observed failures in any completion order, retaining the smallest semantic-order
   * records within the configured ceilings. Nested truncation summaries are merged.
   */
  public void accept(ArchiveException failure) {
    Objects.requireNonNull(failure, "failure");
    acceptCandidate(failure.primaryFailure(), failure.getMessage());
    failure.assessment().ifPresent(value -> assessment = Optional.of(value));
    for (Failure additional : failure.secondaryFailures())
      acceptCandidate(additional, additional.diagnosticIdentifier().orElse(null));
    for (Diagnostic diagnostic : failure.diagnostics()) diagnostic(diagnostic);
    mergeArtifacts(failure.artifacts());
    rejectTruncation();
  }

  /**
   * Records a diagnostic and returns whether warning policy rejected it. Rejection is accepted as a
   * referencing POLICY candidate before retention, preserving the warning's intrinsic severity.
   */
  public boolean diagnostic(Diagnostic diagnostic) {
    Objects.requireNonNull(diagnostic, "diagnostic");
    boolean rejected =
        diagnostic.severity() == DiagnosticSeverity.WARNING
            && policy.rejectedWarningIdentifiers().contains(diagnostic.identifier());
    if (rejected && !TRUNCATED.equals(diagnostic.identifier())) {
      Failure candidate =
          new Failure(
              FailureKind.POLICY,
              diagnostic.phase(),
              diagnostic.location().entryOrdinal(),
              Optional.of(diagnostic.identifier()),
              Optional.of(diagnostic.location()),
              Optional.empty());
      // Nested failures may already carry this warning's policy candidate; do not duplicate it.
      if (!candidate.equals(primary)) acceptCandidate(candidate, diagnostic.identifier());
    }
    if (TRUNCATED.equals(diagnostic.identifier())) {
      Map<String, String> values = diagnostic.values();
      omittedDiagnostics = omittedDiagnostics.add(new BigInteger(values.get("omittedDiagnostics")));
      omittedSecondary =
          omittedSecondary.add(new BigInteger(values.get("omittedSecondaryFailures")));
      unseenDiagnostics |= Boolean.parseBoolean(values.get("additionalDiagnosticsMayExist"));
      unseenSecondary |= Boolean.parseBoolean(values.get("additionalSecondaryFailuresMayExist"));
    } else if (!retain(diagnostics, diagnostic, limits.maxDiagnostics() - 1, DIAGNOSTIC_ORDER)) {
      omittedDiagnostics = omittedDiagnostics.add(BigInteger.ONE);
    }
    rejectTruncation();
    return rejected;
  }

  /**
   * Rejects the reserved summary immediately when needed, before any later publication decision.
   * Its CLEANUP key is fixed like the summary itself, independently of worker completion timing.
   */
  private void rejectTruncation() {
    if (!summaryPolicyAccepted
        && truncated()
        && policy.rejectedWarningIdentifiers().contains(TRUNCATED)) {
      summaryPolicyAccepted = true;
      Failure candidate =
          new Failure(
              FailureKind.POLICY,
              OperationPhase.CLEANUP,
              OptionalLong.empty(),
              Optional.of(TRUNCATED),
              Optional.of(DiagnosticLocation.operation()),
              Optional.empty());
      if (!candidate.equals(primary) && !secondary.contains(candidate))
        acceptCandidate(candidate, TRUNCATED);
    }
  }

  /** Reports whether the single reserved summary is required without materializing it. */
  private boolean truncated() {
    return omittedDiagnostics.signum() > 0
        || omittedSecondary.signum() > 0
        || unseenDiagnostics
        || unseenSecondary;
  }

  /** Returns whether an operational failure has been accepted. */
  public boolean failed() {
    return primary != null;
  }

  /** Establishes new immutable validation evidence and admits its diagnostics under policy. */
  public void assessment(ArchiveAssessment value) {
    latestAssessment(value);
    for (Diagnostic diagnostic : value.diagnostics()) diagnostic(diagnostic);
  }

  /**
   * Updates the latest validation boundary without readmitting an already retained diagnostic
   * snapshot. This prevents nested truncation summaries from counting the same omission twice.
   */
  public void latestAssessment(ArchiveAssessment value) {
    assessment = Optional.of(Objects.requireNonNull(value, "assessment"));
  }

  /**
   * Returns a detached failure, or null when none was accepted. Supplied settled artifact states
   * override earlier states at the same normalized absolute path; cancellation keeps its subtype.
   */
  public ArchiveException finish(List<Artifact> settledArtifacts) {
    if (primary == null) return null;
    mergeArtifacts(settledArtifacts);
    var orderedSecondary = new ArrayList<>(secondary);
    orderedSecondary.sort(FAILURE_ORDER);
    if (primary.kind() == FailureKind.CANCELLED) {
      return new ArchiveCancelledException(
          message, primary, diagnostics(), orderedArtifacts(), assessment, orderedSecondary);
    }
    return new ArchiveException(
        message, primary, diagnostics(), orderedArtifacts(), assessment, orderedSecondary);
  }

  /** Returns detached diagnostic evidence, including the single reserved truncation summary. */
  public List<Diagnostic> diagnostics() {
    var orderedDiagnostics = new ArrayList<>(diagnostics);
    if (truncated()) {
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
    return List.copyOf(orderedDiagnostics);
  }

  /** Returns a detached successful report after every artifact state has settled. */
  public OperationReport report(List<Artifact> settledArtifacts) {
    if (failed()) throw new IllegalStateException("An accepted failure cannot produce success");
    mergeArtifacts(settledArtifacts);
    return new OperationReport(operation, orderedArtifacts(), diagnostics(), assessment);
  }

  /** Orders normalized artifact states independently of discovery or cleanup completion timing. */
  private List<Artifact> orderedArtifacts() {
    var orderedArtifacts = new ArrayList<>(artifacts.values());
    orderedArtifacts.sort(
        Comparator.comparingLong(Artifact::ordinal).thenComparing(a -> a.path().toString()));
    return List.copyOf(orderedArtifacts);
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
   * Earlier admitted work may settle after a stop, but cancellation cannot cross the outcome race
   * already won by either class. A displaced primary consumes ordinary secondary retention credit.
   */
  private void acceptCandidate(Failure candidate, String explanation) {
    if (primary == null) {
      primary = candidate;
      message = explanation;
    } else if (primary.kind() != FailureKind.CANCELLED
        && candidate.kind() != FailureKind.CANCELLED
        && FAILURE_ORDER.compare(candidate, primary) < 0) {
      retainSecondary(primary);
      primary = candidate;
      message = explanation;
    } else {
      retainSecondary(candidate);
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
