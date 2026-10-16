package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Injects publication faults through the single publish entry on the plans production builds: a
 * stabilized archive set, an archive set on the planner's shared budget, and parallel extraction.
 * Identity reads are part of the one filesystem-actions fault seam, so identity changes are
 * provoked deterministically rather than by racing the real filesystem.
 */
final class PublicationFaultsTest {
  /** An identity no real file has: another actor's file now occupies the path. */
  private static final PathIdentity.Snapshot FOREIGN_FILE =
      new PathIdentity.Snapshot("foreign", false, true, false);

  @TempDir Path directory;
  @TempDir Path scratch;

  /**
   * A stabilized set whose second part cannot be installed rolls back its first part, unless that
   * part's identity no longer matches what publication staged: then the file is left alone,
   * reported UNCHANGED, and the identity change is reported as a rollback failure.
   */
  @Test
  void stabilizedRollbackLeavesAPartWhoseIdentityChanged() throws Exception {
    Path first = directory.resolve("archive.ba2");
    Path second = directory.resolve("archive2.ba2");
    var failed = new AtomicBoolean();
    var files =
        new PublicationTransaction.FileActions() {
          /** Fails the second part's installation, after the first part became visible. */
          @Override
          void move(Path source, Path target) throws IOException {
            if (target.equals(second)) {
              failed.set(true);
              throw new IOException("injected installation failure");
            }
            super.move(source, target);
          }

          /** Once the set has failed, another actor appears to own the first part. */
          @Override
          PathIdentity.Snapshot inspect(Path path) throws IOException {
            if (failed.get() && path.equals(first)) return FOREIGN_FILE;
            return super.inspect(path);
          }
        };
    var spoolCloses = new AtomicInteger();
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                stabilized(
                    first, List.of(bytes(1), bytes(2)), TargetPolicy.FAIL, files, spoolCloses));
    // The rollback failure locates at the first part, so it precedes the installation failure.
    assertEquals(
        located(
            FailureKind.DESTINATION,
            OperationPhase.PUBLISHING,
            0,
            "destination.changed-during-rollback"),
        located(failure.primaryFailure()));
    assertEquals(
        List.of(
            located(
                FailureKind.DESTINATION, OperationPhase.PUBLISHING, 1, "operation.destination-io")),
        secondaries(failure));
    assertEquals(
        List.of(
            new Artifact(first, 0, ArtifactState.UNCHANGED),
            new Artifact(second, 1, ArtifactState.MISSING)),
        failure.artifacts());
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(first));
    assertEquals(List.of(first), list(directory));
    assertEquals(1, spoolCloses.get());
  }

  /**
   * A parallel extraction into an existing tree restores a replaced predecessor when its install
   * fails, unless the private backup's identity changed: then the backup is retained at its exact
   * path for the caller, the target stays MISSING, and the changed backup is reported.
   */
  @Test
  void parallelRollbackRetainsAChangedBackup() throws Exception {
    Path root = Files.createDirectory(directory.resolve("extract"));
    Path first = root.resolve("first.txt");
    Path second = Files.writeString(root.resolve("second.txt"), "second predecessor");
    var backup = new AtomicReference<Path>();
    var files =
        new PublicationTransaction.FileActions() {
          /** Records the predecessor's backup, then fails the staged install over it. */
          @Override
          void move(Path source, Path target) throws IOException {
            if (target.equals(second) && !source.equals(backup.get()))
              throw new IOException("injected installation failure");
            super.move(source, target);
            if (source.equals(second)) backup.set(target);
          }

          /** Another actor appears to have replaced the private backup. Workers call this too. */
          @Override
          PathIdentity.Snapshot inspect(Path path) throws IOException {
            if (path.equals(backup.get())) return FOREIGN_FILE;
            return super.inspect(path);
          }
        };
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(
                new PublicationTransaction.Entry("first.txt", bytes(1), 1, 2),
                new PublicationTransaction.Entry("second.txt", bytes(2), 1, 5)),
            TargetPolicy.REPLACE,
            new WorkerSelection.UpTo(4),
            ordinal -> {},
            PublicationTransaction.SourceClose.NONE);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> Publications.run(session(Operation.EXTRACT), plan, limits(), files));
    // Both PUBLISHING failures locate at the second entry; the identifier orders them.
    assertEquals(
        located(
            FailureKind.DESTINATION, OperationPhase.PUBLISHING, 5, "destination.changed-backup"),
        located(failure.primaryFailure()));
    // The retained backup keeps the private staging directory from being removed.
    assertEquals(
        List.of(
            located(
                FailureKind.DESTINATION, OperationPhase.PUBLISHING, 5, "operation.destination-io"),
            located(
                FailureKind.DESTINATION, OperationPhase.CLEANUP, 5, "operation.destination-io")),
        secondaries(failure));
    // Staging is the root's sibling ".jbsa-…", so within an ordinal it sorts before "extract".
    Path staging = backup.get().getParent();
    assertEquals(
        List.of(
            new Artifact(staging, 2, ArtifactState.RESIDUAL_STAGING),
            new Artifact(first, 2, ArtifactState.PUBLISHED),
            new Artifact(backup.get(), 5, ArtifactState.RESIDUAL_STAGING),
            new Artifact(second, 5, ArtifactState.MISSING)),
        failure.artifacts());
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(first));
    assertFalse(Files.exists(second));
    assertEquals("second predecessor", Files.readString(backup.get()));
    assertEquals(List.of(backup.get()), list(staging));
  }

  /**
   * Target-state settlement reports what is on disk after rollback: a restored predecessor whose
   * identity no longer matches its backup was replaced by another actor, so it is UNCHANGED rather
   * than RESTORED. The set runs on the budget its planner shares.
   */
  @Test
  void settlementReportsARestoredTargetWhoseIdentityChanged() throws Exception {
    Path first = Files.writeString(directory.resolve("archive.ba2"), "old one");
    Path second = Files.writeString(directory.resolve("archive2.ba2"), "old two");
    var restored = new AtomicBoolean();
    var files =
        new PublicationTransaction.FileActions() {
          /** Fails the second part's install, and notes when the first predecessor is back. */
          @Override
          void move(Path source, Path target) throws IOException {
            if (source.getFileName().toString().equals("part-1") && target.equals(second))
              throw new IOException("injected installation failure");
            super.move(source, target);
            if (source.getFileName().toString().equals("backup-0")) restored.set(true);
          }

          /** After its restoration, another actor appears to own the first target. */
          @Override
          PathIdentity.Snapshot inspect(Path path) throws IOException {
            if (restored.get() && path.equals(first)) return FOREIGN_FILE;
            return super.inspect(path);
          }
        };
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> planned(first, List.of(bytes(7), bytes(8)), TargetPolicy.REPLACE, files));
    assertEquals(
        located(FailureKind.DESTINATION, OperationPhase.PUBLISHING, 1, "operation.destination-io"),
        located(failure.primaryFailure()));
    assertEquals(List.of(), secondaries(failure));
    assertEquals(
        List.of(
            new Artifact(first, 0, ArtifactState.UNCHANGED),
            new Artifact(second, 1, ArtifactState.RESTORED)),
        failure.artifacts());
    assertEquals("old one", Files.readString(first));
    assertEquals("old two", Files.readString(second));
    assertEquals(List.of(first, second), list(directory));
  }

  /**
   * Settlement after a successful parallel extraction reports a published file whose identity no
   * longer matches its staged identity as UNCHANGED: publication does not claim another actor's
   * replacement as its own output.
   */
  @Test
  void parallelSettlementReportsAPublishedFileWhoseIdentityChanged() throws Exception {
    Path root = directory.resolve("extract");
    Path first = root.resolve("a/first.txt");
    Path second = root.resolve("b/second.txt");
    var published = new AtomicBoolean();
    var files =
        new PublicationTransaction.FileActions() {
          /** Notes when the new root has become visible. */
          @Override
          void move(Path source, Path target) throws IOException {
            super.move(source, target);
            if (target.equals(root)) published.set(true);
          }

          /** After the root move, another actor appears to own the second file. */
          @Override
          PathIdentity.Snapshot inspect(Path path) throws IOException {
            if (published.get() && path.equals(second)) return FOREIGN_FILE;
            return super.inspect(path);
          }
        };
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(
                new PublicationTransaction.Entry("a/first.txt", bytes(1), 1, 2),
                new PublicationTransaction.Entry("b/second.txt", bytes(2), 1, 5)),
            TargetPolicy.FAIL,
            new WorkerSelection.UpTo(4),
            ordinal -> {},
            PublicationTransaction.SourceClose.NONE);
    var report = Publications.run(session(Operation.EXTRACT), plan, limits(), files);
    assertEquals(
        List.of(
            new Artifact(root, 2, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("a"), 2, ArtifactState.PUBLISHED),
            new Artifact(first, 2, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("b"), 5, ArtifactState.PUBLISHED),
            new Artifact(second, 5, ArtifactState.UNCHANGED)),
        report.artifacts());
    assertArrayEquals(new byte[] {2}, Files.readAllBytes(second));
  }

  /**
   * A directory created for an existing-tree entry is admitted only if it is still the directory
   * publication made. When it is not, the failure is located where publication observed it, at that
   * entry's processing, and the created directory is removed again.
   */
  @Test
  void changedCreatedDirectoryIsLocatedAtItsEntry() throws Exception {
    Path root = Files.createDirectory(directory.resolve("extract"));
    Path nested = root.resolve("nested");
    var files =
        new PublicationTransaction.FileActions() {
          /** Once created, the new directory appears to be another actor's file. */
          @Override
          PathIdentity.Snapshot inspect(Path path) throws IOException {
            if (path.equals(nested) && Files.isDirectory(nested)) return FOREIGN_FILE;
            return super.inspect(path);
          }
        };
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(
                new PublicationTransaction.Entry("a", bytes(1), 1, 1),
                new PublicationTransaction.Entry("nested/b", bytes(2), 1, 4)),
            TargetPolicy.FAIL,
            new WorkerSelection.UpTo(4),
            ordinal -> {},
            PublicationTransaction.SourceClose.NONE);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> Publications.run(session(Operation.EXTRACT), plan, limits(), files));
    assertEquals(
        located(
            FailureKind.DESTINATION,
            OperationPhase.PROCESSING,
            4,
            "extraction.destination-changed"),
        located(failure.primaryFailure()));
    assertEquals(List.of(), secondaries(failure));
    assertEquals(
        List.of(
            new Artifact(root.resolve("a"), 1, ArtifactState.PUBLISHED),
            new Artifact(nested, 4, ArtifactState.MISSING),
            new Artifact(nested.resolve("b"), 4, ArtifactState.MISSING)),
        failure.artifacts());
    assertEquals(List.of(root.resolve("a")), list(root));
  }

  /**
   * The new root is rechecked immediately before its single move. A root that appears after the
   * last target was rechecked is reported at that commit, not at preflight, and nothing becomes
   * visible.
   */
  @Test
  void rootChangedBeforeItsMoveIsLocatedAtTheCommit() throws Exception {
    Path root = directory.resolve("extract");
    Path last = root.resolve("b");
    var lastRechecked = new AtomicBoolean();
    var files =
        new PublicationTransaction.FileActions() {
          /** After the last target's commit recheck, another actor appears to own the root. */
          @Override
          PathIdentity.Snapshot inspect(Path path) throws IOException {
            if (path.equals(last)) lastRechecked.set(true);
            if (lastRechecked.get() && path.equals(root))
              return new PathIdentity.Snapshot("foreign", true, false, false);
            return super.inspect(path);
          }
        };
    var closes = new AtomicInteger();
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(
                new PublicationTransaction.Entry("a", bytes(1), 1, 2),
                new PublicationTransaction.Entry("b", bytes(2), 1, 5)),
            TargetPolicy.FAIL,
            new WorkerSelection.UpTo(4),
            ordinal -> {},
            closes::incrementAndGet);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> Publications.run(session(Operation.EXTRACT), plan, limits(), files));
    assertEquals(
        located(
            FailureKind.DESTINATION,
            OperationPhase.PUBLISHING,
            5,
            "extraction.destination-changed"),
        located(failure.primaryFailure()));
    assertEquals(List.of(), secondaries(failure));
    assertEquals(
        List.of(
            new Artifact(root, 2, ArtifactState.MISSING),
            new Artifact(root.resolve("a"), 2, ArtifactState.MISSING),
            new Artifact(last, 5, ArtifactState.MISSING)),
        failure.artifacts());
    assertEquals(List.of(), list(directory));
    assertEquals(1, closes.get());
  }

  /**
   * When a stabilized set can neither install its part nor restore the predecessor it backed up,
   * the caller's only copy of the predecessor is retained at its exact private path.
   */
  @Test
  void stabilizedRestorationFailureRetainsTheBackup() throws Exception {
    Path target = Files.writeString(directory.resolve("archive.ba2"), "old");
    var backup = new AtomicReference<Path>();
    var files =
        new PublicationTransaction.FileActions() {
          /** Blocks both the installation and the restoration at the target. */
          @Override
          void move(Path source, Path destination) throws IOException {
            if (destination.equals(target)) throw new IOException("injected blocked target");
            super.move(source, destination);
            if (source.equals(target)) backup.set(destination);
          }
        };
    var spoolCloses = new AtomicInteger();
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> stabilized(target, List.of(bytes(1)), TargetPolicy.REPLACE, files, spoolCloses));
    var publishing =
        located(FailureKind.DESTINATION, OperationPhase.PUBLISHING, 0, "operation.destination-io");
    assertEquals(publishing, located(failure.primaryFailure()));
    // The restoration failure, then the staging directory the retained backup keeps nonempty.
    assertEquals(
        List.of(
            publishing,
            located(
                FailureKind.DESTINATION, OperationPhase.CLEANUP, 0, "operation.destination-io")),
        secondaries(failure));
    Path staging = backup.get().getParent();
    assertEquals(
        List.of(
            new Artifact(staging, 0, ArtifactState.RESIDUAL_STAGING),
            new Artifact(backup.get(), 0, ArtifactState.RESIDUAL_STAGING),
            new Artifact(target, 0, ArtifactState.MISSING)),
        failure.artifacts());
    assertEquals("old", Files.readString(backup.get()));
    assertEquals(List.of(staging), list(directory));
    assertEquals(List.of(backup.get()), list(staging));
    assertEquals(1, spoolCloses.get());
  }

  /**
   * A cleanup failure after a committed set on the planner's shared budget keeps the published
   * parts and reports the exact private paths it could not remove.
   */
  @Test
  void sharedBudgetCleanupFailureKeepsPublishedPartsAndExactResiduals() throws Exception {
    Path first = directory.resolve("archive.ba2");
    Path second = directory.resolve("archive2.ba2");
    var files =
        new PublicationTransaction.FileActions() {
          /** Leaves the atomic-move probe behind, so its staging directory stays nonempty. */
          @Override
          void delete(Path path) throws IOException {
            if (path.getFileName().toString().equals("atomic-ready"))
              throw new IOException("injected cleanup failure");
            super.delete(path);
          }
        };
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> planned(first, List.of(bytes(1), bytes(2)), TargetPolicy.FAIL, files));
    var cleanup =
        located(FailureKind.DESTINATION, OperationPhase.CLEANUP, 1, "operation.destination-io");
    assertEquals(cleanup, located(failure.primaryFailure()));
    assertEquals(List.of(cleanup), secondaries(failure));
    Path staging =
        failure.artifacts().stream()
            .filter(artifact -> artifact.state() == ArtifactState.RESIDUAL_STAGING)
            .findFirst()
            .orElseThrow()
            .path();
    assertEquals(
        List.of(
            new Artifact(staging, 0, ArtifactState.RESIDUAL_STAGING),
            new Artifact(staging.resolve("atomic-ready"), 0, ArtifactState.RESIDUAL_STAGING),
            new Artifact(first, 0, ArtifactState.PUBLISHED),
            new Artifact(second, 1, ArtifactState.PUBLISHED)),
        failure.artifacts());
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(first));
    assertArrayEquals(new byte[] {2}, Files.readAllBytes(second));
    assertEquals(List.of(staging.resolve("atomic-ready")), list(staging));
  }

  /**
   * A parallel extraction whose single root move fails publishes nothing: every planned file and
   * the root are MISSING, and its private tree is removed.
   */
  @Test
  void parallelRootMoveFailurePublishesNothing() throws Exception {
    Path root = directory.resolve("extract");
    var files =
        new PublicationTransaction.FileActions() {
          /** Fails the one move that would make the staged tree visible. */
          @Override
          void move(Path source, Path target) throws IOException {
            if (target.equals(root)) throw new IOException("injected root move failure");
            super.move(source, target);
          }
        };
    var closes = new AtomicInteger();
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(
                new PublicationTransaction.Entry("a", bytes(1), 1, 2),
                new PublicationTransaction.Entry("b", bytes(2), 1, 5)),
            TargetPolicy.FAIL,
            new WorkerSelection.UpTo(4),
            ordinal -> {},
            closes::incrementAndGet);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> Publications.run(session(Operation.EXTRACT), plan, limits(), files));
    assertEquals(
        located(FailureKind.DESTINATION, OperationPhase.PUBLISHING, 5, "operation.destination-io"),
        located(failure.primaryFailure()));
    assertEquals(List.of(), secondaries(failure));
    assertEquals(
        List.of(
            new Artifact(root, 2, ArtifactState.MISSING),
            new Artifact(root.resolve("a"), 2, ArtifactState.MISSING),
            new Artifact(root.resolve("b"), 5, ArtifactState.MISSING)),
        failure.artifacts());
    assertEquals(List.of(), list(directory));
    assertEquals(1, closes.get());
  }

  /**
   * A cleanup failure after a parallel extraction's root move keeps the published tree and reports
   * the exact private paths it could not remove.
   */
  @Test
  void parallelCleanupFailureKeepsThePublishedTreeAndExactResiduals() throws Exception {
    Path root = directory.resolve("extract");
    var files =
        new PublicationTransaction.FileActions() {
          /** Leaves the atomic-move probe behind, so its staging directory stays nonempty. */
          @Override
          void delete(Path path) throws IOException {
            if (path.getFileName().toString().equals("atomic-ready"))
              throw new IOException("injected cleanup failure");
            super.delete(path);
          }
        };
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(
                new PublicationTransaction.Entry("a/one", bytes(1), 1, 2),
                new PublicationTransaction.Entry("b/two", bytes(2), 1, 5)),
            TargetPolicy.FAIL,
            new WorkerSelection.UpTo(4),
            ordinal -> {},
            PublicationTransaction.SourceClose.NONE);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> Publications.run(session(Operation.EXTRACT), plan, limits(), files));
    var cleanup =
        located(FailureKind.DESTINATION, OperationPhase.CLEANUP, 5, "operation.destination-io");
    assertEquals(cleanup, located(failure.primaryFailure()));
    assertEquals(List.of(cleanup), secondaries(failure));
    Path staging =
        failure.artifacts().stream()
            .filter(artifact -> artifact.state() == ArtifactState.RESIDUAL_STAGING)
            .findFirst()
            .orElseThrow()
            .path();
    assertEquals(
        List.of(
            new Artifact(staging, 2, ArtifactState.RESIDUAL_STAGING),
            new Artifact(staging.resolve("atomic-ready"), 2, ArtifactState.RESIDUAL_STAGING),
            new Artifact(root, 2, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("a"), 2, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("a/one"), 2, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("b"), 5, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("b/two"), 5, ArtifactState.PUBLISHED)),
        failure.artifacts());
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(root.resolve("a/one")));
    assertArrayEquals(new byte[] {2}, Files.readAllBytes(root.resolve("b/two")));
    assertEquals(List.of(staging.resolve("atomic-ready")), list(staging));
  }

  /**
   * Runs a streamed archive-set plan on the budget its planner shares, the way the Pack Pipeline
   * does: per-entry planner leases and the layout's metadata stay charged until the budget closes,
   * and the coordinator's windows stay reserved across publication.
   */
  private OperationReport planned(
      Path destination,
      List<PublicationTransaction.Writer> writers,
      TargetPolicy policy,
      PublicationTransaction.FileActions files)
      throws ArchiveException {
    var operation = session(Operation.PACK);
    IoContext context = IoContext.of(destination, Operation.PACK);
    var plan =
        new PublicationTransaction.Plan.ArchiveSet(
            destination, writers, policy, false, PublicationTransaction.SourceClose.NONE);
    return operation.run(
        context,
        resources -> {
          ResourceBudget budget = ResourceBudget.forMutation(limits(), context);
          resources.register(budget::close);
          operation.begin();
          for (int index = 0; index < writers.size(); index++) budget.reserve(1024, 0, 0, 0);
          budget.metadata(64);
          try (var windows = budget.reserve(65536, 0, 0, 0)) {
            return new OperationSession.Published(
                PublicationTransaction.publish(operation, budget, plan, files));
          }
        });
  }

  /** Runs a stabilized archive-set plan the way the Pack Pipeline does. */
  private OperationReport stabilized(
      Path destination,
      List<PublicationTransaction.Writer> writers,
      TargetPolicy policy,
      PublicationTransaction.FileActions files,
      AtomicInteger spoolCloses)
      throws ArchiveException {
    var operation = session(Operation.PACK);
    IoContext context = IoContext.of(destination, Operation.PACK);
    return operation.run(
        context,
        resources -> {
          ResourceBudget budget = ResourceBudget.forMutation(ResourceLimits.standard(), context);
          resources.register(budget::close);
          operation.begin();
          // Stabilization is processing work, entered before any part plan exists (Q10).
          operation.completePhase();
          operation.processing(false);
          SpillBuffer stable = SpillBuffer.open(scratch, budget, context);
          resources.register(stable::close);
          var plan =
              new PublicationTransaction.Plan.ArchiveSet(
                  destination,
                  writers,
                  policy,
                  true,
                  () -> {
                    spoolCloses.incrementAndGet();
                    stable.close();
                  });
          return new OperationSession.Published(
              PublicationTransaction.publish(operation, budget, plan, files));
        });
  }

  /** A fresh session with the standard Diagnostic Policy that records nothing. */
  private static OperationSession session(Operation operation) {
    return new OperationSession(
        operation,
        ResourceLimits.standard(),
        DiagnosticPolicy.standard(),
        OperationControl.standard());
  }

  /** The standard Resource Limits every plan here runs under. */
  private static ResourceLimits limits() {
    return ResourceLimits.standard();
  }

  /** A failure's observable location and identity, with its cause dropped. */
  private static Failure located(Failure failure) {
    return new Failure(
        failure.kind(),
        failure.phase(),
        failure.ordinal(),
        failure.diagnosticIdentifier(),
        Optional.empty(),
        Optional.empty());
  }

  /** Every Secondary Failure of an outcome, in retention order, with causes dropped. */
  private static List<Failure> secondaries(ArchiveException failure) {
    return failure.secondaryFailures().stream().map(PublicationFaultsTest::located).toList();
  }

  /** The expected form of {@link #located(Failure)}. */
  private static Failure located(
      FailureKind kind, OperationPhase phase, long ordinal, String identifier) {
    return new Failure(
        kind,
        phase,
        OptionalLong.of(ordinal),
        Optional.of(identifier),
        Optional.empty(),
        Optional.empty());
  }

  /** Lists a directory's immediate children in name order. */
  private static List<Path> list(Path parent) throws IOException {
    try (var paths = Files.list(parent)) {
      return paths.sorted().toList();
    }
  }

  /** A literal one-byte writer, independent of archive codecs. */
  private static PublicationTransaction.Writer bytes(int value) {
    return output -> output.write(0, ByteBuffer.wrap(new byte[] {(byte) value}));
  }
}
