package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Both stop signals pass every catch site unchanged (ADR-0006). No consumption path that structures
 * its own failures may turn a stop into a Failure Kind: the session's stop reaches the operation
 * template, and a worker's stop reaches its runner.
 */
class StopSignalTest {
  private static final ArchiveEncoding GENERAL =
      new ArchiveEncoding(
          Optional.of(new WireVersion(1)), Optional.of(Ba2Subtype.GNRL), OptionalLong.empty());

  /**
   * The session's stop, raised by a reader's checkpoint inside {@code PackSources.consume} for a
   * generated and a loose source, reaches the template unchanged: the outcome keeps only the
   * failure the session stopped for.
   */
  @Test
  void sessionStopThroughPackSourceConsumptionReachesTheTemplate(@TempDir Path directory)
      throws Exception {
    Path loose = directory.resolve("loose.bin");
    Files.write(loose, new byte[] {1, 2, 3});
    for (int selected = 0; selected < 2; selected++) {
      int index = selected;
      assertStopReachesTemplate(
          directory,
          (session, entries) ->
              PackSources.consume(
                  entries.get(index),
                  channel -> session.checkpoint(OperationPhase.PROCESSING, OptionalLong.of(index)),
                  entries.get(index).processing(index)),
          List.of(
              new PackSource.GeneratedEntry(
                  "Data/generated.bin",
                  3,
                  () -> Channels.newChannel(new ByteArrayInputStream(new byte[] {1, 2, 3}))),
              new PackSource.NamedFile("Data/loose.bin", loose)));
    }
  }

  /** The same stop raised while {@link SourceFile#consume} borrows its input passes unchanged. */
  @Test
  void sessionStopThroughSourceFileConsumptionReachesTheTemplate(@TempDir Path directory)
      throws Exception {
    Path loose = directory.resolve("loose.bin");
    Files.write(loose, new byte[] {1, 2, 3});
    SourceFile source = SourceFile.plan(loose);
    assertStopReachesTemplate(
        directory,
        (session, entries) ->
            source.consume(
                input -> {
                  session.checkpoint(OperationPhase.PROCESSING, OptionalLong.of(0));
                  return null;
                }),
        List.of(new PackSource.NamedFile("Data/loose.bin", loose)));
  }

  /** Each consumption path hands back the very stop it was given, never a structured copy. */
  @Test
  void everyConsumptionPathRethrowsTheSameStop(@TempDir Path directory) throws Exception {
    Path loose = directory.resolve("loose.bin");
    Files.write(loose, new byte[] {1, 2, 3});
    var limits = ResourceLimits.standard();
    var session = new OperationSession(Operation.PACK, limits, OperationControl.standard());
    session.begin();
    try (var budget =
        ResourceBudget.forMutation(
            limits, IoContext.destination(directory.resolve("out.ba2"), Operation.PACK))) {
      List<PackSources.Entry> entries =
          PackSources.plan(
              PackRequest.standard(
                  directory.resolve("out.ba2"),
                  ArchiveFamily.FO4_GENERAL_BA2,
                  GENERAL,
                  List.of(
                      new PackSource.GeneratedEntry(
                          "Data/generated.bin",
                          3,
                          () -> Channels.newChannel(new ByteArrayInputStream(new byte[3]))),
                      new PackSource.NamedFile("Data/loose.bin", loose)),
                  Optional.empty()),
              session,
              budget);
      for (int index = 0; index < entries.size(); index++) {
        for (StopSignal stop : new StopSignal[] {new OperationStopped(), new WorkerStopped()}) {
          PackSources.Entry entry = entries.get(index);
          IoContext processing = entry.processing(index);
          assertSame(
              stop,
              assertThrows(
                  StopSignal.class,
                  () ->
                      PackSources.consume(
                          entry,
                          channel -> {
                            throw stop;
                          },
                          processing)));
        }
      }
      for (StopSignal stop : new StopSignal[] {new OperationStopped(), new WorkerStopped()}) {
        assertSame(
            stop,
            assertThrows(
                StopSignal.class,
                () ->
                    SourceFile.plan(loose)
                        .consume(
                            input -> {
                              throw stop;
                            })));
      }
    }
  }

  /**
   * A parallel extraction worker stopped inside its copy loop, behind an earlier failure, hands its
   * stop to the runner. It is never admitted as a DESTINATION failure of its own entry.
   */
  @Test
  void workerStopInTheExtractionCopyLoopIsNotADestinationFailure(@TempDir Path directory)
      throws Exception {
    var copying = new CountDownLatch(1);
    var limits = ResourceLimits.standard();
    var operation =
        new OperationSession(
            Operation.EXTRACT, limits, DiagnosticPolicy.standard(), OperationControl.standard());
    Path root = directory.resolve("out");
    var failing =
        new PublicationTransaction.Entry(
            "first.bin",
            output -> {
              try {
                assertTrue(copying.await(30, TimeUnit.SECONDS));
              } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
              }
              throw IoContext.source(directory.resolve("input.bsa"), Operation.EXTRACT)
                  .at(OperationPhase.PROCESSING, OptionalLong.of(0))
                  .failure(FailureKind.FORMAT, "fixture.corrupt-payload", null);
            },
            1,
            0);
    var stopped =
        new PublicationTransaction.Entry(
            "second.bin",
            output -> {
              copying.countDown();
              // The copy loop's own checkpoint: it stops once the earlier entry has failed.
              long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(30);
              while (System.nanoTime() < deadline) {
                output.checkpointWork();
                Thread.onSpinWait();
              }
              throw new AssertionError("The worker was never stopped");
            },
            1,
            1);
    var plan =
        new PublicationTransaction.Plan.Extraction(
            root,
            List.of(failing, stopped),
            TargetPolicy.FAIL,
            new WorkerSelection.UpTo(2),
            ordinal -> {},
            PublicationTransaction.SourceClose.NONE);

    var failure =
        assertThrows(
            ArchiveException.class,
            () ->
                Publications.run(
                    operation, plan, limits, new PublicationTransaction.FileActions()));

    assertEquals(FailureKind.FORMAT, failure.kind());
    assertEquals(OptionalLong.of(0), failure.primaryFailure().ordinal());
    assertTrue(
        failure.secondaryFailures().stream()
            .noneMatch(
                secondary ->
                    secondary.ordinal().equals(OptionalLong.of(1))
                        && secondary.phase() == OperationPhase.PROCESSING),
        () -> failure.secondaryFailures().toString());
  }

  /** One way of consuming a planned pack source inside a running pack body. */
  @FunctionalInterface
  private interface Consumption {
    /** Consumes a source whose reader observes the session's checkpoint. */
    void consume(OperationSession session, List<PackSources.Entry> entries) throws IOException;
  }

  /**
   * Plans the sources in a pack body, makes the session fail, then lets the consumption's stop
   * propagate to the template, and checks the outcome holds only that failure.
   */
  private static void assertStopReachesTemplate(
      Path directory, Consumption consumption, List<PackSource> sources) throws Exception {
    var limits = ResourceLimits.standard();
    var session = new OperationSession(Operation.PACK, limits, OperationControl.standard());
    IoContext context = IoContext.destination(directory.resolve("out.ba2"), Operation.PACK);
    ArchiveException reason = context.failure(FailureKind.POLICY, "fixture.stopped-for", null);
    var outcome =
        assertThrows(
            ArchiveException.class,
            () ->
                session.run(
                    context,
                    resources -> {
                      session.begin();
                      ResourceBudget budget = ResourceBudget.forMutation(limits, context);
                      resources.register(budget::close, context);
                      List<PackSources.Entry> entries =
                          PackSources.plan(
                              PackRequest.standard(
                                  directory.resolve("out.ba2"),
                                  ArchiveFamily.FO4_GENERAL_BA2,
                                  GENERAL,
                                  sources,
                                  Optional.empty()),
                              session,
                              budget);
                      session.accept(reason);
                      consumption.consume(session, entries);
                      throw new AssertionError("The consumption did not stop");
                    }));
    assertEquals(reason.primaryFailure(), outcome.primaryFailure());
    assertEquals(List.of(), outcome.secondaryFailures());
  }
}
