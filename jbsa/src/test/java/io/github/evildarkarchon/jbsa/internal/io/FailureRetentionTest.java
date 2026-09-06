package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** Observes deterministic, bounded public exception evidence at the coordinator outcome seam. */
final class FailureRetentionTest {
  /** Earlier logical work settling late must displace a later failure without losing its cause. */
  @Test
  void earlierObservedFailureWinsRegardlessOfCompletionOrder() {
    ArchiveException late =
        failure(FailureKind.SOURCE, OperationPhase.PROCESSING, 9, "source.late");
    ArchiveException early =
        failure(FailureKind.FORMAT, OperationPhase.PROCESSING, 2, "format.early");
    var retention = new FailureRetention(ResourceLimits.standard(), Operation.EXTRACT);
    retention.accept(late);
    retention.accept(early);
    ArchiveException result = retention.finish(List.of());
    assertEquals(early.primaryFailure(), result.primaryFailure());
    assertSame(early.getCause(), result.getCause());
    assertEquals(List.of(late.primaryFailure()), result.secondaryFailures());
    assertEquals(0, result.getSuppressed().length);
  }

  /** A rejected warning must stop the operation even when only the reserved summary fits. */
  @Test
  void warningPolicyRunsBeforeDiagnosticRetention() {
    var limits = new ResourceLimits(1, 1, 1, 1, 1, 1, 0);
    var retention =
        new FailureRetention(
            limits, Operation.EXTRACT, new DiagnosticPolicy(Set.of("archive.warning")));
    Diagnostic warning = warning("archive.warning", 0);
    assertTrue(retention.diagnostic(warning));
    ArchiveException result = retention.finish(List.of());
    assertEquals(FailureKind.POLICY, result.kind());
    assertEquals(Optional.of("archive.warning"), result.primaryFailure().diagnosticIdentifier());
    assertEquals(
        List.of("operation.records-truncated"),
        result.diagnostics().stream().map(Diagnostic::identifier).toList());
    assertEquals("1", result.diagnostics().getFirst().values().get("omittedDiagnostics"));
    assertEquals("0", result.diagnostics().getFirst().values().get("omittedSecondaryFailures"));
  }

  /** Incoming exceptions cannot bypass warning policy or change a warning's intrinsic severity. */
  @Test
  void incomingWarningsCanRejectBeforeALaterOperationalFailure() {
    var retention =
        new FailureRetention(
            ResourceLimits.standard(),
            Operation.EXTRACT,
            new DiagnosticPolicy(Set.of("archive.warning")));
    Diagnostic warning = warning("archive.warning", 0);
    ArchiveException later = failure(FailureKind.SOURCE, OperationPhase.PROCESSING, 0, "source");
    retention.accept(
        new ArchiveException(
            "source",
            later.primaryFailure(),
            List.of(warning),
            List.of(),
            Optional.empty(),
            List.of()));
    ArchiveException result = retention.finish(List.of());
    assertEquals(FailureKind.POLICY, result.kind());
    assertEquals(List.of(warning), result.diagnostics());
    assertEquals(DiagnosticSeverity.WARNING, result.diagnostics().getFirst().severity());
    assertEquals(List.of(later.primaryFailure()), result.secondaryFailures());
  }

  /** Rejecting the reserved summary must become visible before a coordinator can commit. */
  @Test
  void truncationWarningPolicyStopsAtTheFirstOmission() {
    var retention =
        new FailureRetention(
            new ResourceLimits(1, 1, 1, 1, 1, 1, 0),
            Operation.EXTRACT,
            new DiagnosticPolicy(Set.of("operation.records-truncated")));
    retention.diagnostic(warning("accepted.warning", 0));
    assertTrue(retention.failed());
    ArchiveException result = retention.finish(List.of());
    assertEquals(FailureKind.POLICY, result.kind());
    assertEquals(
        Optional.of("operation.records-truncated"), result.primaryFailure().diagnosticIdentifier());
    assertEquals("1", result.diagnostics().getFirst().values().get("omittedDiagnostics"));
    assertEquals("0", result.diagnostics().getFirst().values().get("omittedSecondaryFailures"));
    var outer =
        new FailureRetention(
            new ResourceLimits(1, 1, 1, 1, 1, 1, 0),
            Operation.EXTRACT,
            new DiagnosticPolicy(Set.of("operation.records-truncated")));
    outer.accept(result);
    assertEquals(result.diagnostics(), outer.finish(List.of()).diagnostics());
  }

  /** The first accepted outcome fixes cancellation independently of ordinary semantic ordering. */
  @Test
  void cancellationCannotCrossAnAlreadyAcceptedOutcome() {
    ArchiveException operational = failure(FailureKind.SOURCE, OperationPhase.CLEANUP, 9, "source");
    Failure cancelled =
        new Failure(
            FailureKind.CANCELLED,
            OperationPhase.PREFLIGHT,
            OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    var cancellation =
        new ArchiveCancelledException(
            "cancelled", cancelled, List.of(), List.of(), Optional.empty(), List.of());
    var cancellationFirst = new FailureRetention(ResourceLimits.standard(), Operation.EXTRACT);
    cancellationFirst.accept(cancellation);
    cancellationFirst.accept(operational);
    assertInstanceOf(ArchiveCancelledException.class, cancellationFirst.finish(List.of()));
    assertEquals(
        List.of(operational.primaryFailure()),
        cancellationFirst.finish(List.of()).secondaryFailures());
    var failureFirst = new FailureRetention(ResourceLimits.standard(), Operation.EXTRACT);
    failureFirst.accept(operational);
    failureFirst.accept(cancellation);
    assertEquals(FailureKind.SOURCE, failureFirst.finish(List.of()).kind());
    assertEquals(List.of(cancelled), failureFirst.finish(List.of()).secondaryFailures());
  }

  /** A nested earlier failure participates in selection, with exact bounded omission evidence. */
  @Test
  void nestedCandidatesAndDisplacedPrimariesShareTheSecondaryLimit() {
    var retention =
        new FailureRetention(new ResourceLimits(1, 1, 1, 1, 1, 4, 1), Operation.EXTRACT);
    ArchiveException first = failure(FailureKind.SOURCE, OperationPhase.PROCESSING, 9, "nine");
    ArchiveException second = failure(FailureKind.SOURCE, OperationPhase.PROCESSING, 5, "five");
    ArchiveException earliest = failure(FailureKind.FORMAT, OperationPhase.PREFLIGHT, 0, "zero");
    retention.accept(first);
    retention.accept(
        new ArchiveException(
            "nested",
            second.primaryFailure(),
            List.of(),
            List.of(),
            Optional.empty(),
            List.of(earliest.primaryFailure())));
    ArchiveException result = retention.finish(List.of());
    assertEquals(earliest.primaryFailure(), result.primaryFailure());
    assertEquals(List.of(second.primaryFailure()), result.secondaryFailures());
    assertEquals("1", result.diagnostics().getFirst().values().get("omittedSecondaryFailures"));
  }

  /** Establishing payload evidence must not mutate prior structure evidence or lose warnings. */
  @Test
  void warningsAndLatestAssessmentSurviveSuccessAndLaterFailure() {
    var retention = new FailureRetention(ResourceLimits.standard(), Operation.EXTRACT);
    Diagnostic later = warning("warning.later", 8);
    Diagnostic earlier = warning("warning.earlier", 2);
    var structure =
        new ArchiveAssessment(
            ArchiveDisposition.CONFORMING, new ValidationExtent.Structure(), List.of(later));
    retention.assessment(structure);
    retention.diagnostic(earlier);
    var payload =
        new ArchiveAssessment(
            ArchiveDisposition.CONFORMING,
            new ValidationExtent.Payloads(Set.of(2L)),
            retention.diagnostics());
    retention.latestAssessment(payload);
    OperationReport report = retention.report(List.of());
    assertEquals(List.of(earlier, later), report.diagnostics());
    assertSame(payload, report.assessment().orElseThrow());
    assertEquals(List.of(later), structure.diagnostics());
    retention.accept(failure(FailureKind.DESTINATION, OperationPhase.CLEANUP, 1, "cleanup"));
    ArchiveException result = retention.finish(List.of());
    assertEquals(report.diagnostics(), result.diagnostics());
    assertSame(payload, result.assessment().orElseThrow());
    assertThrows(UnsupportedOperationException.class, () -> report.diagnostics().clear());
  }

  /** Present maximum ordinals precede absence, and equal absent keys continue at the next key. */
  @Test
  void missingFailureKeysDoNotMaskLaterOrderingKeys() {
    var retention = new FailureRetention(ResourceLimits.standard(), Operation.EXTRACT);
    Failure absent =
        new Failure(
            FailureKind.INTERNAL,
            OperationPhase.PROCESSING,
            OptionalLong.empty(),
            Optional.of("a"),
            Optional.empty(),
            Optional.empty());
    ArchiveException maximum =
        failure(FailureKind.SOURCE, OperationPhase.PROCESSING, Long.MAX_VALUE, "z");
    retention.accept(
        new ArchiveException("absent", absent, List.of(), List.of(), Optional.empty(), List.of()));
    retention.accept(maximum);
    assertEquals(maximum.primaryFailure(), retention.finish(List.of()).primaryFailure());
    var absentOrdinals = new FailureRetention(ResourceLimits.standard(), Operation.EXTRACT);
    Failure later =
        new Failure(
            FailureKind.SOURCE,
            OperationPhase.PROCESSING,
            OptionalLong.empty(),
            Optional.of("z"),
            Optional.empty(),
            Optional.empty());
    absentOrdinals.accept(
        new ArchiveException("later", later, List.of(), List.of(), Optional.empty(), List.of()));
    absentOrdinals.accept(
        new ArchiveException("earlier", absent, List.of(), List.of(), Optional.empty(), List.of()));
    assertEquals(absent, absentOrdinals.finish(List.of()).primaryFailure());
  }

  /** Supplies a format warning with a stable archive-order location. */
  private static Diagnostic warning(String id, long ordinal) {
    return new Diagnostic(
        id,
        DiagnosticSeverity.WARNING,
        Operation.EXTRACT,
        OperationPhase.PREFLIGHT,
        new DiagnosticLocation(
            Optional.empty(),
            OptionalLong.of(ordinal),
            Optional.of("entry"),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()),
        new TreeMap<>(),
        Optional.empty());
  }

  /** Creates independent semantic candidates with a provider cause outside their ordering keys. */
  private static ArchiveException failure(
      FailureKind kind, OperationPhase phase, long ordinal, String id) {
    Failure candidate =
        new Failure(
            kind,
            phase,
            OptionalLong.of(ordinal),
            Optional.of(id),
            Optional.empty(),
            Optional.of(new IOException(id)));
    return new ArchiveException(id, candidate, List.of(), List.of(), Optional.empty(), List.of());
  }
}
