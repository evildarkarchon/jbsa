package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Exercises detached public outcome values through the exported library interface. */
@Tag("contract")
final class PublicOutcomeContractIT {
  /** Caller mutations cannot rewrite previously established conformance evidence. */
  @Test
  void establishedAssessmentRetainsImmutableDiagnosticsAndCanonicalValues() {
    var values = new TreeMap<String, String>();
    values.put("observed", "4294967296");
    values.put("ceiling", "4096");
    var diagnostic =
        new Diagnostic(
            "operation.resource-limit",
            DiagnosticSeverity.ERROR,
            Operation.EXTRACT,
            OperationPhase.PREFLIGHT,
            DiagnosticLocation.operation(),
            values,
            Optional.of("The resource ceiling was exceeded."));
    var diagnostics = new ArrayList<>(List.of(diagnostic));
    var assessment =
        new ArchiveAssessment(
            ArchiveDisposition.CONFORMING, new ValidationExtent.Structure(), diagnostics);

    values.clear();
    diagnostics.clear();

    assertEquals(List.of(diagnostic), assessment.diagnostics());
    assertEquals(List.of("ceiling", "observed"), List.copyOf(diagnostic.values().keySet()));
    assertEquals("4294967296", diagnostic.values().get("observed"));
    assertThrows(UnsupportedOperationException.class, () -> assessment.diagnostics().clear());
    assertThrows(UnsupportedOperationException.class, () -> diagnostic.values().clear());
  }

  /** The checked outcome retains causes once and detaches every returned record collection. */
  @Test
  void checkedFailureRetainsAssessmentAndSecondaryCausesWithoutSuppression() {
    var cause = new java.io.IOException("source read");
    var cleanupCause = new java.io.IOException("cleanup");
    var primary =
        new Failure(
            FailureKind.SOURCE,
            OperationPhase.PROCESSING,
            OptionalLong.of(4294967296L),
            Optional.empty(),
            Optional.empty(),
            Optional.of(cause));
    var secondary =
        new Failure(
            FailureKind.DESTINATION,
            OperationPhase.CLEANUP,
            OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.of(cleanupCause));
    var secondaries = new ArrayList<>(List.of(secondary));
    var assessment =
        new ArchiveAssessment(
            ArchiveDisposition.CONFORMING, new ValidationExtent.Structure(), List.of());
    var failure =
        new ArchiveException(
            "Cannot finish operation",
            primary,
            List.of(),
            List.of(),
            Optional.of(assessment),
            secondaries);
    secondaries.clear();

    assertInstanceOf(java.io.IOException.class, failure);
    assertEquals(FailureKind.SOURCE, failure.kind());
    assertSame(cause, failure.getCause());
    assertEquals(primary, failure.primaryFailure());
    assertEquals(4294967296L, failure.primaryFailure().ordinal().orElseThrow());
    assertEquals(assessment, failure.assessment().orElseThrow());
    assertEquals(List.of(secondary), failure.secondaryFailures());
    assertSame(cleanupCause, failure.secondaryFailures().getFirst().cause().orElseThrow());
    assertEquals(0, failure.getSuppressed().length);
    assertThrows(UnsupportedOperationException.class, () -> failure.secondaryFailures().clear());
  }

  /**
   * Advisory counters admit large work and reject impossible snapshots before an operation starts.
   */
  @Test
  void progressAndControlUseLongSemanticCountersAndNeverCancelledDefaults() {
    var snapshot =
        new ProgressSnapshot(
            Operation.PACK,
            OperationPhase.PROCESSING,
            ProgressMetric.BYTES,
            4294967296L,
            OptionalLong.of(8589934592L));
    assertEquals(4294967296L, snapshot.completed());
    assertEquals(8589934592L, snapshot.total().orElseThrow());
    assertFalse(OperationControl.standard().cancellationRequested().getAsBoolean());
    assertDoesNotThrow(() -> OperationControl.standard().observer().accept(snapshot));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProgressSnapshot(
                Operation.PACK,
                OperationPhase.PROCESSING,
                ProgressMetric.BYTES,
                2,
                OptionalLong.of(1)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ProgressSnapshot(
                Operation.INSPECT,
                OperationPhase.PREFLIGHT,
                ProgressMetric.ENTRIES,
                0,
                OptionalLong.empty()));
  }

  /** Successful mutation reports retain published artifacts without sharing caller collections. */
  @Test
  void successReportRetainsArtifactsAndRejectsQueryOperation() {
    var artifact =
        new Artifact(java.nio.file.Path.of("result.bsa"), 4294967296L, ArtifactState.PUBLISHED);
    var artifacts = new ArrayList<>(List.of(artifact));
    var report = new OperationReport(Operation.PACK, artifacts, List.of(), Optional.empty());
    artifacts.clear();
    assertEquals(List.of(artifact), report.artifacts());
    assertThrows(UnsupportedOperationException.class, () -> report.artifacts().clear());
    assertThrows(
        IllegalArgumentException.class,
        () -> new OperationReport(Operation.DETECT, List.of(), List.of(), Optional.empty()));
  }

  /** Cancellation is catchable through its sole subtype and cannot carry another primary kind. */
  @Test
  void cancellationHasExactlyItsDedicatedCheckedOutcome() {
    var cancellation =
        new Failure(
            FailureKind.CANCELLED,
            OperationPhase.PREFLIGHT,
            OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    var exception =
        new ArchiveCancelledException(
            "Cancelled", cancellation, List.of(), List.of(), Optional.empty(), List.of());
    assertEquals(FailureKind.CANCELLED, exception.kind());
    assertInstanceOf(ArchiveException.class, exception);
    assertNull(exception.getCause());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArchiveException(
                "Cancelled", cancellation, List.of(), List.of(), Optional.empty(), List.of()));
    var sourceFailure =
        new Failure(
            FailureKind.SOURCE,
            OperationPhase.PREFLIGHT,
            OptionalLong.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new ArchiveCancelledException(
                "Source", sourceFailure, List.of(), List.of(), Optional.empty(), List.of()));
  }

  /** A later caller selection edit cannot change earlier policy or payload validation claims. */
  @Test
  void policyAndPayloadExtentRetainDetachedSelections() {
    var identifiers = new java.util.HashSet<>(Set.of("archive-name.absolute-path"));
    var policy = new DiagnosticPolicy(identifiers);
    var ordinals = new java.util.HashSet<>(Set.of(4294967296L, 0L));
    var extent = new ValidationExtent.Payloads(ordinals);
    identifiers.clear();
    ordinals.clear();
    assertEquals(Set.of("archive-name.absolute-path"), policy.rejectedWarningIdentifiers());
    assertEquals(List.of(0L, 4294967296L), List.copyOf(extent.entryOrdinals()));
    assertTrue(DiagnosticPolicy.standard().rejectedWarningIdentifiers().isEmpty());
    assertThrows(
        UnsupportedOperationException.class, () -> policy.rejectedWarningIdentifiers().clear());
    assertThrows(UnsupportedOperationException.class, () -> extent.entryOrdinals().clear());
    assertThrows(IllegalArgumentException.class, () -> new ValidationExtent.Payloads(Set.of(-1L)));
    assertThrows(
        IllegalArgumentException.class, () -> new DiagnosticLocation.ByteSpan(Long.MAX_VALUE, 1));
  }
}
