package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the shared publication adapter using caller-observable progress and disk outcomes. */
@EnabledOnOs(OS.WINDOWS)
class PublicationProgressTest {
  @TempDir Path directory;

  /** Even an empty atomic set must enter all four phases and exactly the required metric pairs. */
  @Test
  void emptyArchiveSetCompletesEveryApplicablePair() throws Exception {
    var snapshots = new ArrayList<ProgressSnapshot>();
    PublicationTransaction.archives(
        directory.resolve("empty.bsa"),
        List.of(),
        TargetPolicy.FAIL,
        ResourceLimits.standard(),
        new OperationControl(snapshots::add, () -> false));
    assertEquals(
        List.of(
            "PREFLIGHT/ENTRIES",
            "PROCESSING/ENTRIES",
            "PROCESSING/BYTES",
            "PUBLISHING/ARTIFACTS",
            "CLEANUP/ARTIFACTS"),
        snapshots.stream().map(s -> s.phase() + "/" + s.metric()).distinct().toList());
    for (var phase : OperationPhase.values()) {
      var inPhase = snapshots.stream().filter(s -> s.phase() == phase).toList();
      for (var metric : inPhase.stream().map(ProgressSnapshot::metric).distinct().toList()) {
        var pair = inPhase.stream().filter(s -> s.metric() == metric).toList();
        assertEquals(0, pair.getFirst().completed());
        assertEquals(0, pair.getLast().total().orElseThrow());
      }
    }
    assertFalse(Files.exists(directory.resolve("empty.bsa")));
  }

  /** Existing-tree commits count as processing artifacts and never enter publishing progress. */
  @Test
  void existingTreeReportsLogicalPayloadAndCommitCounts() throws Exception {
    Path root = Files.createDirectory(directory.resolve("existing"));
    var snapshots = new ArrayList<ProgressSnapshot>();
    var artifacts =
        PublicationTransaction.extract(
            root,
            List.of(
                new PublicationTransaction.Entry(
                    "file",
                    output -> {
                      output.write(0, ByteBuffer.wrap(new byte[] {1, 2, 3}));
                    })),
            TargetPolicy.FAIL,
            ResourceLimits.standard(),
            new OperationControl(snapshots::add, () -> false));
    assertEquals(
        List.of(new Artifact(root.resolve("file"), 0, ArtifactState.PUBLISHED)), artifacts);
    assertEquals(
        List.of(
            "PREFLIGHT/ENTRIES",
            "PROCESSING/ENTRIES",
            "PROCESSING/BYTES",
            "PROCESSING/ARTIFACTS",
            "CLEANUP/ARTIFACTS"),
        snapshots.stream().map(s -> s.phase() + "/" + s.metric()).distinct().toList());
    assertTerminal(snapshots, OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 1);
    assertTerminal(snapshots, OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 1);
    assertTerminal(snapshots, OperationPhase.PROCESSING, ProgressMetric.BYTES, 3);
    assertTerminal(snapshots, OperationPhase.PROCESSING, ProgressMetric.ARTIFACTS, 1);
    assertTerminal(snapshots, OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 1);
  }

  /**
   * An observer cancellation at initial preflight must stop before even private destination
   * staging.
   */
  @Test
  void preflightObserverCancellationPreventsDestinationEffects() {
    var cancelled = new AtomicBoolean();
    var probe = new AtomicBoolean();
    var failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                PublicationTransaction.archives(
                    directory.resolve("cancelled"),
                    List.of(output -> output.write(0, ByteBuffer.wrap(new byte[] {1}))),
                    TargetPolicy.FAIL,
                    ResourceLimits.standard(),
                    new OperationControl(
                        snapshot -> {
                          if (snapshot.phase() == OperationPhase.PREFLIGHT) cancelled.set(true);
                        },
                        cancelled::get),
                    new PublicationTransaction.FileActions() {
                      /**
                       * Tracks the first destination capability probe, without replacing its
                       * behavior.
                       */
                      @Override
                      void probeAtomic(Path staging, boolean isDirectory) throws IOException {
                        probe.set(true);
                        super.probeAtomic(staging, isDirectory);
                      }
                    }));
    assertEquals(OperationPhase.PREFLIGHT, failure.primaryFailure().phase());
    assertFalse(probe.get());
  }

  /**
   * Unexpected writer exceptions use checked INTERNAL outcomes and still remove private staging.
   */
  @Test
  void unexpectedWriterFailureSettlesBeforeReturningCheckedOutcome() throws Exception {
    var thrown = new IllegalStateException("writer invariant");
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    directory.resolve("broken"),
                    List.of(
                        output -> {
                          throw thrown;
                        }),
                    TargetPolicy.FAIL,
                    ResourceLimits.standard(),
                    OperationControl.standard()));
    assertEquals(FailureKind.INTERNAL, failure.kind());
    assertSame(thrown, failure.getCause());
    try (var paths = Files.list(directory)) {
      assertTrue(paths.toList().isEmpty());
    }
  }

  /**
   * A post-commit observer failure must retain committed outputs, while cleanup remains mandatory.
   */
  @Test
  void observerFailureAfterCommitDoesNotRollbackSettledPublication() throws Exception {
    Path target = directory.resolve("published");
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    target,
                    List.of(output -> output.write(0, ByteBuffer.wrap(new byte[] {4}))),
                    TargetPolicy.FAIL,
                    ResourceLimits.standard(),
                    new OperationControl(
                        snapshot -> {
                          if (snapshot.phase() == OperationPhase.PUBLISHING
                              && snapshot.completed() == 1)
                            throw new IllegalStateException("after commit");
                        },
                        () -> false)));
    assertEquals(FailureKind.OBSERVER, failure.kind());
    assertArrayEquals(new byte[] {4}, Files.readAllBytes(target));
    assertEquals(List.of(new Artifact(target, 0, ArtifactState.PUBLISHED)), failure.artifacts());
  }

  /** Source candidates and logical entries differ from encoded parts and replayed bytes. */
  @Test
  void packReportRetainsWarningsAndCountsLogicalEntriesAcrossBackpatches() throws Exception {
    var snapshots = new ArrayList<ProgressSnapshot>();
    var session =
        new OperationSession(
            Operation.PACK,
            ResourceLimits.standard(),
            new OperationControl(snapshots::add, () -> false));
    session.begin();
    session.advance(
        ProgressMetric.ENTRIES, 3); // One overlay candidate was replaced before packing.
    Path target = directory.resolve("pack");
    var warning = warning(0);
    var report =
        PublicationTransaction.archives(
            target,
            List.of(
                output -> {
                  output.write(0, ByteBuffer.wrap(new byte[] {1, 2}));
                  output.completedEntry(10);
                  output.write(0, ByteBuffer.wrap(new byte[] {3, 4}));
                  output.completedEntry(20);
                  output.diagnostic(warning);
                }),
            TargetPolicy.FAIL,
            ResourceLimits.standard(),
            session);
    assertEquals(
        new OperationReport(
            Operation.PACK,
            List.of(new Artifact(target, 0, ArtifactState.PUBLISHED)),
            List.of(warning),
            Optional.empty()),
        report);
    assertTerminal(snapshots, OperationPhase.PREFLIGHT, ProgressMetric.ENTRIES, 3);
    assertTerminal(snapshots, OperationPhase.PROCESSING, ProgressMetric.ENTRIES, 2);
    assertTerminal(snapshots, OperationPhase.PROCESSING, ProgressMetric.BYTES, 30);
    assertTerminal(snapshots, OperationPhase.PUBLISHING, ProgressMetric.ARTIFACTS, 1);
  }

  /**
   * Warning rejection cannot disappear behind a full diagnostic budget or publish an atomic set.
   */
  @Test
  void rejectedWarningAtRetentionLimitPreventsAtomicPublication() {
    var limits = new ResourceLimits(10, 1000, 1000, 1000, 10, 1, 0);
    var session =
        new OperationSession(
            Operation.PACK,
            limits,
            new DiagnosticPolicy(Set.of("fixture.noncanonical")),
            OperationControl.standard());
    Path target = directory.resolve("policy");
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    target,
                    List.of(
                        output -> {
                          output.write(0, ByteBuffer.wrap(new byte[] {1}));
                          output.diagnostic(warning(0));
                        }),
                    TargetPolicy.FAIL,
                    limits,
                    session));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(
        "fixture.noncanonical", failure.primaryFailure().diagnosticIdentifier().orElseThrow());
    assertFalse(Files.exists(target));
    assertEquals(1, failure.diagnostics().size());
    assertEquals("1", failure.diagnostics().getFirst().values().get("omittedDiagnostics"));
  }

  /** Late policy rejection in an existing tree retains the completed sibling and its assessment. */
  @Test
  void latePolicyRejectionRetainsExistingTreeSiblingAndAssessment() throws Exception {
    Path root = Files.createDirectory(directory.resolve("partial"));
    var assessment =
        new ArchiveAssessment(
            ArchiveDisposition.CONFORMING, new ValidationExtent.Structure(), List.of());
    var session =
        new OperationSession(
            Operation.EXTRACT,
            ResourceLimits.standard(),
            new DiagnosticPolicy(Set.of("fixture.noncanonical")),
            OperationControl.standard());
    session.assessment(assessment);
    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.extract(
                    root,
                    List.of(
                        new PublicationTransaction.Entry(
                            "first",
                            output -> {
                              output.write(0, ByteBuffer.wrap(new byte[] {1}));
                            }),
                        new PublicationTransaction.Entry(
                            "second",
                            output -> {
                              var packWarning = warning(1);
                              output.diagnostic(
                                  new Diagnostic(
                                      packWarning.identifier(),
                                      packWarning.severity(),
                                      Operation.EXTRACT,
                                      packWarning.phase(),
                                      packWarning.location(),
                                      packWarning.values(),
                                      packWarning.explanation()));
                            })),
                    TargetPolicy.FAIL,
                    ResourceLimits.standard(),
                    session));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(Optional.of(assessment), failure.assessment());
    assertEquals(
        List.of(
            new Artifact(root.resolve("first"), 0, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("second"), 1, ArtifactState.MISSING)),
        failure.artifacts());
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(root.resolve("first")));
    assertFalse(Files.exists(root.resolve("second")));
    assertEquals(DiagnosticSeverity.WARNING, failure.diagnostics().getFirst().severity());
  }

  /** Cancellation raised by publication progress after the final commit cannot undo success. */
  @Test
  void cancellationAfterFinalCommitKeepsSuccessfulTerminalSequence() throws Exception {
    var cancelled = new AtomicBoolean();
    var snapshots = new ArrayList<ProgressSnapshot>();
    Path target = directory.resolve("final");
    var artifacts =
        PublicationTransaction.archives(
            target,
            List.of(output -> output.write(0, ByteBuffer.wrap(new byte[] {1}))),
            TargetPolicy.FAIL,
            ResourceLimits.standard(),
            new OperationControl(
                snapshot -> {
                  snapshots.add(snapshot);
                  if (snapshot.phase() == OperationPhase.PUBLISHING && snapshot.completed() == 1)
                    cancelled.set(true);
                },
                cancelled::get));
    assertEquals(List.of(new Artifact(target, 0, ArtifactState.PUBLISHED)), artifacts);
    assertTerminal(snapshots, OperationPhase.PUBLISHING, ProgressMetric.ARTIFACTS, 1);
    assertTerminal(snapshots, OperationPhase.CLEANUP, ProgressMetric.ARTIFACTS, 1);
  }

  /** Fixture warning represents format evidence; optional prose is deliberately absent. */
  private static Diagnostic warning(long ordinal) {
    return new Diagnostic(
        "fixture.noncanonical",
        DiagnosticSeverity.WARNING,
        Operation.PACK,
        OperationPhase.PROCESSING,
        new DiagnosticLocation(
            Optional.empty(),
            OptionalLong.of(ordinal),
            Optional.empty(),
            Optional.empty(),
            Optional.empty(),
            Optional.empty()),
        new TreeMap<>(),
        Optional.empty());
  }

  /**
   * Checks the independent expected logical count and the pair's monotonic initial/terminal
   * contract.
   */
  private static void assertTerminal(
      List<ProgressSnapshot> snapshots,
      OperationPhase phase,
      ProgressMetric metric,
      long expected) {
    var pair = snapshots.stream().filter(s -> s.phase() == phase && s.metric() == metric).toList();
    assertEquals(0, pair.getFirst().completed());
    assertEquals(expected, pair.getLast().completed());
    assertEquals(expected, pair.getLast().total().orElseThrow());
    long previous = -1;
    for (var snapshot : pair) {
      assertTrue(snapshot.completed() >= previous);
      previous = snapshot.completed();
    }
  }
}
