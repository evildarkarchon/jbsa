package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Pins the sealed publication plan's contract at the single publish entry: the source-close action
 * runs exactly once, after every stage settles and before the final Publication Commit, in every
 * strategy; and the on-staged hook receives archive entry ordinals in both extraction strategies.
 */
final class PublicationPlanTest {
  @TempDir Path directory;

  /** Each archive-set shape closes its source once, after both parts staged and before any move. */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void archiveSetClosesSourceOnceBeforeItsCommit(boolean processingEntered) throws Exception {
    Path destination = directory.resolve("archive.ba2");
    var staged = new AtomicInteger();
    var closes = new AtomicInteger();
    var operation = session(Operation.PACK);
    var plan =
        new PublicationTransaction.Plan.ArchiveSet(
            destination,
            List.of(counted(staged, 1), counted(staged, 2)),
            TargetPolicy.FAIL,
            processingEntered,
            () -> {
              closes.incrementAndGet();
              assertEquals(2, staged.get());
              assertFalse(Files.exists(destination));
            });
    var report =
        operation.run(
            IoContext.of(destination, Operation.PACK),
            resources -> {
              if (processingEntered) {
                // A stabilizing pack enters PROCESSING itself before any part plan exists (Q10).
                operation.begin();
                operation.completePhase();
                operation.processing(false);
              }
              return new OperationSession.Published(
                  PublicationTransaction.publish(operation, budget(destination), plan));
            });
    assertEquals(1, closes.get());
    assertArrayEquals(new byte[] {1}, Files.readAllBytes(destination));
    assertEquals(2, report.artifacts().size());
  }

  /** An empty archive set still closes its source once, before its empty commit. */
  @Test
  void emptyArchiveSetClosesSourceOnce() throws Exception {
    Path destination = directory.resolve("empty.bsa");
    var closes = new AtomicInteger();
    var operation = session(Operation.PACK);
    operation.run(
        IoContext.of(destination, Operation.PACK),
        resources ->
            new OperationSession.Published(
                PublicationTransaction.publish(
                    operation,
                    budget(destination),
                    new PublicationTransaction.Plan.ArchiveSet(
                        destination,
                        List.of(),
                        TargetPolicy.FAIL,
                        false,
                        closes::incrementAndGet))));
    assertEquals(1, closes.get());
  }

  /**
   * Every extraction strategy (new root or existing tree, one worker or several) closes its source
   * once, after the last entry staged and before the commit that makes the last entry visible.
   */
  @ParameterizedTest
  @ValueSource(strings = {"new-1", "new-4", "existing-1", "existing-4"})
  void extractionClosesSourceOnceBeforeItsFinalCommit(String strategy) throws Exception {
    boolean existing = strategy.startsWith("existing");
    long workers = Long.parseLong(strategy.substring(strategy.indexOf('-') + 1));
    Path root = directory.resolve("out");
    if (existing) Files.createDirectory(root);
    var staged = new AtomicInteger();
    var closes = new AtomicInteger();
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(
                new PublicationTransaction.Entry("a", counted(staged, 1), 1, 2),
                new PublicationTransaction.Entry("b", counted(staged, 2), 1, 5)),
            TargetPolicy.FAIL,
            new WorkerSelection.UpTo(workers),
            ordinal -> {},
            () -> {
              closes.incrementAndGet();
              assertEquals(2, staged.get());
              assertFalse(Files.exists(root.resolve("b")));
            });
    Publications.run(
        session(Operation.EXTRACT),
        plan,
        ResourceLimits.standard(),
        new PublicationTransaction.FileActions());
    assertEquals(1, closes.get());
    assertArrayEquals(new byte[] {2}, Files.readAllBytes(root.resolve("b")));
  }

  /** An empty selection closes its source once, both into a new root and into an existing tree. */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void emptyExtractionClosesSourceOnce(boolean existing) throws Exception {
    Path root = directory.resolve("out");
    if (existing) Files.createDirectory(root);
    var closes = new AtomicInteger();
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(),
            TargetPolicy.FAIL,
            new WorkerSelection.UpTo(4),
            ordinal -> {},
            () -> {
              closes.incrementAndGet();
              assertEquals(existing, Files.exists(root));
            });
    Publications.run(
        session(Operation.EXTRACT),
        plan,
        ResourceLimits.standard(),
        new PublicationTransaction.FileActions());
    assertEquals(1, closes.get());
    assertTrue(Files.isDirectory(root));
  }

  /** A failure before the final commit leaves the source to its caller's registered close. */
  @Test
  void stagingFailureLeavesSourceCloseToItsOwner() {
    Path root = directory.resolve("out");
    var closes = new AtomicInteger();
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(
                new PublicationTransaction.Entry(
                    "a",
                    output -> {
                      throw new IOException("write");
                    },
                    1,
                    0)),
            TargetPolicy.FAIL,
            new WorkerSelection.UpTo(1),
            ordinal -> {},
            closes::incrementAndGet);
    assertThrows(
        ArchiveException.class,
        () ->
            Publications.run(
                session(Operation.EXTRACT),
                plan,
                ResourceLimits.standard(),
                new PublicationTransaction.FileActions()));
    assertEquals(0, closes.get());
  }

  /**
   * Both extraction strategies report each staged entry's archive ordinal to the hook, in Logical
   * Plan Order, and locate its Artifact by that ordinal.
   */
  @ParameterizedTest
  @ValueSource(longs = {1, 4})
  void onStagedReceivesArchiveOrdinalsInPlanOrder(long workers) throws Exception {
    Path root = directory.resolve("out");
    List<Long> staged = new CopyOnWriteArrayList<>();
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(
                new PublicationTransaction.Entry("a", bytes(1), 1, 1),
                new PublicationTransaction.Entry("b", bytes(2), 1, 3),
                new PublicationTransaction.Entry("c", bytes(3), 1, 4)),
            TargetPolicy.FAIL,
            new WorkerSelection.UpTo(workers),
            staged::add,
            PublicationTransaction.SourceClose.NONE);
    var report =
        Publications.run(
            session(Operation.EXTRACT),
            plan,
            ResourceLimits.standard(),
            new PublicationTransaction.FileActions());
    assertEquals(List.of(1L, 3L, 4L), staged);
    assertEquals(
        List.of(
            new Artifact(root, 1, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("a"), 1, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("b"), 3, ArtifactState.PUBLISHED),
            new Artifact(root.resolve("c"), 4, ArtifactState.PUBLISHED)),
        report.artifacts());
  }

  /**
   * An extraction entry always carries its staged extent and archive ordinal; neither is a hole.
   */
  @Test
  void entriesRejectMissingExtentOrOrdinalAndPlansKeepArchiveOrder() {
    PublicationTransaction.Writer writer = bytes(1);
    assertThrows(
        IllegalArgumentException.class, () -> new PublicationTransaction.Entry("a", writer, -1, 0));
    assertThrows(
        IllegalArgumentException.class, () -> new PublicationTransaction.Entry("a", writer, 1, -1));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new PublicationTransaction.Plan.Extraction(
                directory,
                List.of(
                    new PublicationTransaction.Entry("a", writer, 1, 3),
                    new PublicationTransaction.Entry("b", writer, 1, 3)),
                TargetPolicy.FAIL,
                new WorkerSelection.UpTo(1),
                ordinal -> {},
                PublicationTransaction.SourceClose.NONE));
  }

  /** A fresh session with the standard Diagnostic Policy that records nothing. */
  private static OperationSession session(Operation operation) {
    return new OperationSession(
        operation,
        ResourceLimits.standard(),
        DiagnosticPolicy.standard(),
        OperationControl.standard());
  }

  /** A pack-shaped mutation budget, located at the destination with no ordinal. */
  private static ResourceBudget budget(Path destination) {
    return ResourceBudget.forMutation(
        ResourceLimits.standard(), IoContext.of(destination, Operation.PACK));
  }

  /** Writes one byte and then counts the completed stage; workers may run it concurrently. */
  private static PublicationTransaction.Writer counted(AtomicInteger staged, int value) {
    return output -> {
      bytes(value).write(output);
      staged.incrementAndGet();
    };
  }

  /** A literal one-byte writer, independent of archive codecs. */
  private static PublicationTransaction.Writer bytes(int value) {
    return output -> output.write(0, ByteBuffer.wrap(new byte[] {(byte) value}));
  }
}
