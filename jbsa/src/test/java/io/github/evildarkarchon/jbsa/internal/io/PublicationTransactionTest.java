package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

@EnabledOnOs(OS.WINDOWS)
class PublicationTransactionTest {
  @TempDir Path directory;

  /** Parents removed after accepted cancellation remain affected artifacts with MISSING state. */
  @Test
  void reportsParentsRemovedAfterCancellationBeforeFileCommit() throws Exception {
    Path root = Files.createDirectory(directory.resolve("extract"));
    Path nested = root.resolve("nested");
    var control = new OperationControl(snapshot -> {}, () -> Files.exists(nested));
    ArchiveException failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                PublicationTransaction.extract(
                    root,
                    List.of(new PublicationTransaction.Entry("nested/file", bytes(1))),
                    TargetPolicy.FAIL,
                    ResourceLimits.standard(),
                    control));
    assertFalse(Files.exists(nested));
    assertTrue(failure.artifacts().contains(new Artifact(nested, 0, ArtifactState.MISSING)));
  }

  /** An accepted staging cancellation leaves the next caller buffer untouched. */
  @Test
  void pendingCancellationStopsTheNextStagedWriteWithoutConsumingItsBuffer() throws Exception {
    var cancelled = new AtomicBoolean();
    ByteBuffer pending = ByteBuffer.wrap(new byte[] {2});
    assertThrows(
        ArchiveCancelledException.class,
        () ->
            PublicationTransaction.archives(
                directory.resolve("archive"),
                List.of(
                    output -> {
                      bytes(1).write(output);
                      cancelled.set(true);
                      output.write(1, pending);
                    }),
                TargetPolicy.FAIL,
                ResourceLimits.standard(),
                new OperationControl(snapshot -> {}, cancelled::get)));
    assertEquals(0, pending.position());
  }

  /** Published files no longer consume the operation's retained scratch ceiling. */
  @Test
  void existingTreeScratchLimitCountsRetainedExtentRatherThanPublishedBytes() throws Exception {
    Path root = Files.createDirectory(directory.resolve("extract"));
    var limits = new ResourceLimits(10, 1000, 1000, 1, 10, 10, 10);
    PublicationTransaction.extract(
        root,
        List.of(
            new PublicationTransaction.Entry("first", bytes(1)),
            new PublicationTransaction.Entry("second", bytes(2))),
        TargetPolicy.FAIL,
        limits,
        OperationControl.standard());
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(root.resolve("first")));
    assertArrayEquals(new byte[] {2}, Files.readAllBytes(root.resolve("second")));
  }

  /** Rollback must recognize a replacement owned by another actor before attempting removal. */
  @Test
  void rollbackDoesNotDeleteAnExternallyReplacedPublishedFile() throws Exception {
    Path first = directory.resolve("archive.ba2");
    Path second = directory.resolve("archive2.ba2");
    var files =
        new PublicationTransaction.FileActions() {
          /** Replaces the first installed file just before failing the second installation. */
          @Override
          void move(Path source, Path target) throws IOException {
            if (target.equals(second)) {
              Files.delete(first);
              Files.writeString(first, "external replacement");
              throw new IOException("stop split commit");
            }
            super.move(source, target);
          }
        };
    assertThrows(
        ArchiveException.class,
        () ->
            PublicationTransaction.archives(
                first,
                List.of(bytes(1), bytes(2)),
                TargetPolicy.FAIL,
                ResourceLimits.standard(),
                OperationControl.standard(),
                files));
    assertEquals("external replacement", Files.readString(first));
  }

  /** Removing private staging must not be mistaken for successful destination-root publication. */
  @Test
  void cancelledNewTreeReportsMissingRootAfterStagingWasCleaned() throws Exception {
    var cancelled = new AtomicBoolean();
    Path root = directory.resolve("extract");
    ArchiveException failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                PublicationTransaction.extract(
                    root,
                    List.of(
                        new PublicationTransaction.Entry(
                            "nested/file",
                            output -> {
                              bytes(1).write(output);
                              cancelled.set(true);
                            })),
                    TargetPolicy.FAIL,
                    ResourceLimits.standard(),
                    new OperationControl(snapshot -> {}, cancelled::get)));
    assertFalse(Files.exists(root));
    assertFalse(failure.artifacts().stream().anyMatch(a -> a.state() == ArtifactState.PUBLISHED));
  }

  /** Existing-tree payload failure retains committed siblings and untouched predecessors. */
  @Test
  void existingTreeKeepsCommittedSiblingWhenNextWriterFails() throws Exception {
    Path root = Files.createDirectory(directory.resolve("extract"));
    Path old = Files.writeString(root.resolve("second.txt"), "old");
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.extract(
                    root,
                    List.of(
                        new PublicationTransaction.Entry("nested/first.txt", bytes(1)),
                        new PublicationTransaction.Entry(
                            "second.txt",
                            output -> {
                              throw new IOException("bad payload");
                            })),
                    TargetPolicy.REPLACE,
                    ResourceLimits.standard(),
                    OperationControl.standard()));
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(root.resolve("nested/first.txt")));
    assertEquals("old", Files.readString(old));
    assertTrue(
        failure
            .artifacts()
            .contains(new Artifact(root.resolve("nested/first.txt"), 0, ArtifactState.PUBLISHED)));
    assertTrue(failure.artifacts().contains(new Artifact(old, 1, ArtifactState.UNCHANGED)));
  }

  /** A new extraction tree remains absent during every entry's staging callback. */
  @Test
  void newTreeHasNoVisibleFilesUntilSingleRootMove() throws Exception {
    Path root = directory.resolve("extract");
    PublicationTransaction.extract(
        root,
        List.of(
            new PublicationTransaction.Entry(
                "a/first.txt",
                output -> {
                  assertFalse(Files.exists(root));
                  bytes(1).write(output);
                }),
            new PublicationTransaction.Entry(
                "b/second.txt",
                output -> {
                  assertFalse(Files.exists(root));
                  bytes(2).write(output);
                })),
        TargetPolicy.FAIL,
        ResourceLimits.standard(),
        OperationControl.standard());
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(root.resolve("a/first.txt")));
    assertArrayEquals(new byte[] {2}, Files.readAllBytes(root.resolve("b/second.txt")));
  }

  /** Complete split-plan revalidation must precede even the first publication move. */
  @Test
  void rejectsDestinationMutationDuringStagingBeforeAnyCommit() throws Exception {
    Path first = directory.resolve("archive.ba2");
    Path second = directory.resolve("archive2.ba2");
    var published = new AtomicBoolean();
    var files =
        new PublicationTransaction.FileActions() {
          /** Records whether the first target ever became externally visible. */
          @Override
          void move(Path source, Path target) throws IOException {
            if (target.equals(first)) published.set(true);
            super.move(source, target);
          }
        };
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    first,
                    List.of(
                        bytes(1),
                        output -> {
                          Files.writeString(second, "unrelated");
                          bytes(2).write(output);
                        }),
                    TargetPolicy.FAIL,
                    ResourceLimits.standard(),
                    OperationControl.standard(),
                    files));
    assertFalse(Files.exists(first));
    assertFalse(published.get());
    assertEquals("unrelated", Files.readString(second));
    assertEquals(OperationPhase.PUBLISHING, failure.primaryFailure().phase());
    assertTrue(failure.artifacts().contains(new Artifact(second, 1, ArtifactState.UNCHANGED)));
  }

  /** Cancellation accepted after staging and before the first move discards the pending output. */
  @Test
  void cancellationBeforeFirstMovePreventsAllPublication() throws Exception {
    var cancelled = new AtomicBoolean();
    Path target = directory.resolve("archive.ba2");
    var control = new OperationControl(snapshot -> {}, cancelled::get);
    ArchiveException failure =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                PublicationTransaction.archives(
                    target,
                    List.of(
                        output -> {
                          bytes(1).write(output);
                          cancelled.set(true);
                        }),
                    TargetPolicy.FAIL,
                    ResourceLimits.standard(),
                    control));
    assertFalse(Files.exists(target));
    assertEquals(List.of(new Artifact(target, 0, ArtifactState.MISSING)), failure.artifacts());
  }

  /** Cancellation requested during predecessor backup cannot interrupt the committed split set. */
  @Test
  void cancellationAfterFirstBackupCannotInterruptSplitCommit() throws Exception {
    var cancelled = new AtomicBoolean();
    Path target = Files.writeString(directory.resolve("archive.ba2"), "old");
    var files =
        new PublicationTransaction.FileActions() {
          /** Requests cancellation only after the predecessor has moved into private staging. */
          @Override
          void move(Path source, Path destination) throws IOException {
            super.move(source, destination);
            if (source.equals(target)) cancelled.set(true);
          }
        };
    var result =
        PublicationTransaction.archives(
            target,
            List.of(bytes(1), bytes(2)),
            TargetPolicy.REPLACE,
            ResourceLimits.standard(),
            new OperationControl(snapshot -> {}, cancelled::get),
            files);
    assertEquals(
        List.of(ArtifactState.PUBLISHED, ArtifactState.PUBLISHED),
        result.stream().map(Artifact::state).toList());
  }

  /** A failed restoration transfers the original bytes at their exact backup path to the caller. */
  @Test
  void reportsExactBackupResidualWhenRestorationFailsWithoutDeletingPredecessor() throws Exception {
    Path target = Files.writeString(directory.resolve("archive.ba2"), "old");
    var files =
        new PublicationTransaction.FileActions() {
          /** Blocks both replacement installation and later restoration at the same target. */
          @Override
          void move(Path source, Path destination) throws IOException {
            if (destination.equals(target))
              throw new IOException("installation or restoration blocked");
            super.move(source, destination);
          }
        };
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    target,
                    List.of(bytes(1)),
                    TargetPolicy.REPLACE,
                    ResourceLimits.standard(),
                    OperationControl.standard(),
                    files));
    assertEquals(
        ArtifactState.MISSING,
        failure.artifacts().stream()
            .filter(artifact -> artifact.path().equals(target))
            .findFirst()
            .orElseThrow()
            .state());
    var backups =
        failure.artifacts().stream()
            .filter(a -> a.path().getFileName().toString().equals("backup-0"))
            .toList();
    assertEquals(1, backups.size());
    assertEquals(ArtifactState.RESIDUAL_STAGING, backups.getFirst().state());
    assertEquals("old", Files.readString(backups.getFirst().path()));
    assertFalse(failure.secondaryFailures().isEmpty());
    assertEquals(0, failure.getSuppressed().length);
  }

  /** Split rollback restores all moved predecessors after a later part cannot be installed. */
  @Test
  void restoresEveryPredecessorWhenSplitPublicationFails() throws Exception {
    Path first = Files.writeString(directory.resolve("archive.ba2"), "old one");
    Path second = Files.writeString(directory.resolve("archive2.ba2"), "old two");
    var files =
        new PublicationTransaction.FileActions() {
          /** Fails only the second staged installation while allowing its backup to restore. */
          @Override
          void move(Path source, Path target) throws IOException {
            if (source.getFileName().toString().equals("part-1") && target.equals(second)) {
              throw new IOException("injected installation failure");
            }
            super.move(source, target);
          }
        };
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                PublicationTransaction.archives(
                    first,
                    List.of(bytes(7), bytes(8)),
                    TargetPolicy.REPLACE,
                    ResourceLimits.standard(),
                    OperationControl.standard(),
                    files));
    assertEquals(FailureKind.DESTINATION, failure.kind());
    assertEquals("old one", Files.readString(first));
    assertEquals("old two", Files.readString(second));
    assertEquals(
        List.of(
            new Artifact(first, 0, ArtifactState.RESTORED),
            new Artifact(second, 1, ArtifactState.RESTORED)),
        failure.artifacts());
    try (var paths = Files.list(directory)) {
      assertEquals(2, paths.count());
    }
  }

  /** A literal synthetic writer, independent of archive codecs and proprietary fixtures. */
  private static PublicationTransaction.Writer bytes(int value) {
    return output -> output.write(0, ByteBuffer.wrap(new byte[] {(byte) value}));
  }

  /** Positional staging supports header backpatches and keeps all bytes private until commit. */
  @Test
  void stagesAndBackpatchesBeforeAtomicallyPublishingOneArchive() throws Exception {
    Path destination = directory.resolve("archive.ba2");
    List<Artifact> result =
        PublicationTransaction.archives(
            destination,
            List.of(
                output -> {
                  assertFalse(Files.exists(destination));
                  output.write(4, ByteBuffer.wrap(new byte[] {5, 6}));
                  output.write(0, ByteBuffer.wrap(new byte[] {1, 2, 3, 4}));
                }),
            TargetPolicy.FAIL,
            ResourceLimits.standard(),
            OperationControl.standard());
    assertArrayEquals(new byte[] {1, 2, 3, 4, 5, 6}, Files.readAllBytes(destination));
    assertEquals(List.of(new Artifact(destination, 0, ArtifactState.PUBLISHED)), result);
    try (var children = Files.list(directory)) {
      assertEquals(List.of(destination), children.toList());
    }
  }
}
