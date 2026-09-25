package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Public worker-selection behavior for deterministic archive packing. */
@EnabledOnOs(OS.WINDOWS)
class ParallelPackTest {
  @TempDir Path temporary;

  /** Independent later entries can run while an earlier source blocks, without changing output. */
  @Test
  void packsIndependentEntriesConcurrentlyAndKeepsLogicalBytes() throws Exception {
    byte[] first =
        "first payload".repeat(3000).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] second =
        "second payload".repeat(3000).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    Path sequential = temporary.resolve("sequential.ba2");
    Path parallel = temporary.resolve("parallel.ba2");
    List<PackSource> ordinary =
        List.of(generated("Data/First.bin", first), generated("Data/Second.bin", second));
    BethesdaArchives.standard().pack(request(sequential, ordinary, 1), OperationControl.standard());

    CountDownLatch laterStarted = new CountDownLatch(1);
    AtomicReference<Thread> earlierWorker = new AtomicReference<>();
    AtomicReference<Thread> laterWorker = new AtomicReference<>();
    PackSource earlier =
        new PackSource.GeneratedEntry(
            "Data/First.bin",
            first.length,
            () -> {
              earlierWorker.set(Thread.currentThread());
              try {
                if (!laterStarted.await(5, TimeUnit.SECONDS))
                  throw new IOException("later entry was never admitted");
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
              }
              return Channels.newChannel(new ByteArrayInputStream(first));
            });
    PackSource later =
        new PackSource.GeneratedEntry(
            "Data/Second.bin",
            second.length,
            () -> {
              laterWorker.set(Thread.currentThread());
              laterStarted.countDown();
              return Channels.newChannel(new ByteArrayInputStream(second));
            });
    BethesdaArchives.standard()
        .pack(request(parallel, List.of(earlier, later), 2), OperationControl.standard());

    assertArrayEquals(Files.readAllBytes(sequential), Files.readAllBytes(parallel));
    assertNotNull(earlierWorker.get());
    assertNotNull(laterWorker.get());
    assertNotSame(earlierWorker.get(), laterWorker.get());
    assertFalse(earlierWorker.get().isDaemon());
    assertFalse(laterWorker.get().isDaemon());
  }

  /** Stored sharing and whole-entry splits keep identical bytes across the archive families. */
  @Test
  void keepsSharingAndSplitChoicesIndependentOfWorkerCount() throws Exception {
    byte[] repeated =
        "shared bytes".repeat(200).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] distinct =
        "third bytes".repeat(250).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    List<PackSource> sources =
        List.of(
            generated("Data/First.bin", repeated),
            generated("Data/Second.bin", repeated),
            generated("Data/Third.bin", distinct));
    for (ArchiveFamily family :
        List.of(ArchiveFamily.TES3_BSA, ArchiveFamily.TES4_BSA, ArchiveFamily.FO4_GENERAL_BA2)) {
      for (long split : new long[] {0, 1}) {
        String stem = family.name().toLowerCase() + "-" + split;
        PackOptions options =
            new PackOptions(
                List.of(),
                PackOptions.Compression.STORED,
                true,
                new PackOptions.Splitting.UpToBytes(split),
                FlagSelection.AUTOMATIC,
                FlagSelection.AUTOMATIC);
        OperationReport single =
            BethesdaArchives.standard()
                .pack(
                    request(temporary.resolve(stem + "-one.bsa"), sources, 1, family, options),
                    OperationControl.standard());
        OperationReport parallel =
            BethesdaArchives.standard()
                .pack(
                    request(temporary.resolve(stem + "-four.bsa"), sources, 4, family, options),
                    OperationControl.standard());
        assertEquals(single.archiveParts().size(), parallel.archiveParts().size(), stem);
        assertEquals(
            single.artifacts().stream().map(Artifact::state).toList(),
            parallel.artifacts().stream().map(Artifact::state).toList(),
            stem);
        assertEquals(single.diagnostics(), parallel.diagnostics(), stem);
        for (int index = 0; index < single.archiveParts().size(); index++) {
          var firstPart = single.archiveParts().get(index);
          var secondPart = parallel.archiveParts().get(index);
          assertEquals(firstPart.entryCount(), secondPart.entryCount(), stem);
          assertArrayEquals(
              Files.readAllBytes(firstPart.path()), Files.readAllBytes(secondPart.path()), stem);
        }
      }
    }
  }

  /** An earlier failing ordinal wins even when a later worker reports its failure first. */
  @Test
  void selectsFailureInLogicalOrderAndSettlesWorkers() throws Exception {
    PackSource ordinaryFirst = failing("Data/First.bin", "first");
    PackSource ordinarySecond = failing("Data/Second.bin", "second");
    ArchiveException sequential =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(
                            temporary.resolve("failed-one.ba2"),
                            List.of(ordinaryFirst, ordinarySecond),
                            1),
                        OperationControl.standard()));

    CountDownLatch laterFailed = new CountDownLatch(1);
    PackSource delayedFirst =
        new PackSource.GeneratedEntry(
            "Data/First.bin",
            1,
            () -> {
              try {
                if (!laterFailed.await(5, TimeUnit.SECONDS))
                  throw new IOException("later failure was never observed");
              } catch (InterruptedException failure) {
                Thread.currentThread().interrupt();
                throw new IOException(failure);
              }
              throw new IOException("first");
            });
    PackSource immediateSecond =
        new PackSource.GeneratedEntry(
            "Data/Second.bin",
            1,
            () -> {
              laterFailed.countDown();
              throw new IOException("second");
            });
    ArchiveException parallel =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(
                            temporary.resolve("failed-two.ba2"),
                            List.of(delayedFirst, immediateSecond),
                            2),
                        OperationControl.standard()));
    assertEquals(sequential.kind(), parallel.kind());
    assertEquals(sequential.primaryFailure().phase(), parallel.primaryFailure().phase());
    assertEquals(sequential.primaryFailure().ordinal(), parallel.primaryFailure().ordinal());
    assertEquals(
        sequential.primaryFailure().diagnosticIdentifier(),
        parallel.primaryFailure().diagnosticIdentifier());
    assertEquals(sequential.secondaryFailures().size(), parallel.secondaryFailures().size());
    assertFalse(Files.exists(temporary.resolve("failed-two.ba2")));
    assertFalse(
        Thread.getAllStackTraces().keySet().stream()
            .anyMatch(
                thread ->
                    thread.isAlive()
                        && thread.getName().startsWith("jbsa-pack-")
                        && thread.getName().contains("-worker-")));
  }

  /** Independent zlib transforms may finish out of order without changing BA2 sharing or bytes. */
  @Test
  void parallelZlibTransformsKeepCanonicalBa2Bytes() throws Exception {
    byte[] shared =
        "parallel zlib data".repeat(5000).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] other =
        "another stream".repeat(7000).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    List<PackSource> sources =
        List.of(
            generated("Data/A.bin", shared),
            generated("Data/B.bin", other),
            generated("Data/C.bin", shared));
    PackOptions options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            true,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    OperationReport one =
        BethesdaArchives.standard()
            .pack(
                request(
                    temporary.resolve("zlib-one.ba2"),
                    sources,
                    1,
                    ArchiveFamily.FO4_GENERAL_BA2,
                    options),
                OperationControl.standard());
    OperationReport four =
        BethesdaArchives.standard()
            .pack(
                request(
                    temporary.resolve("zlib-four.ba2"),
                    sources,
                    4,
                    ArchiveFamily.FO4_GENERAL_BA2,
                    options),
                OperationControl.standard());
    assertEquals(one.archiveParts().size(), four.archiveParts().size());
    assertArrayEquals(
        Files.readAllBytes(one.archiveParts().getFirst().path()),
        Files.readAllBytes(four.archiveParts().getFirst().path()));
  }

  /** A known single-output collision rejects the plan before admitting source workers. */
  @Test
  void preflightsKnownDestinationBeforeWorkerSourceEffects() throws Exception {
    Path target = temporary.resolve("existing.ba2");
    Files.write(target, new byte[] {9, 8, 7});
    AtomicInteger opens = new AtomicInteger();
    PackSource source =
        new PackSource.GeneratedEntry(
            "Data/Entry.bin",
            1,
            () -> {
              opens.incrementAndGet();
              return Channels.newChannel(new ByteArrayInputStream(new byte[] {1}));
            });
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(request(target, List.of(source), 4), OperationControl.standard()));
    assertEquals(FailureKind.POLICY, failure.kind());
    assertEquals(0, opens.get());
    assertArrayEquals(new byte[] {9, 8, 7}, Files.readAllBytes(target));
  }

  /** A slow first ordinal prevents source admission beyond the two-worker result window. */
  @Test
  void appliesBackpressureToOutOfOrderSourceCompletion() throws Exception {
    CountDownLatch firstEntered = new CountDownLatch(1);
    CountDownLatch releaseFirst = new CountDownLatch(1);
    AtomicInteger opened = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    List<PackSource> sources = new java.util.ArrayList<>();
    for (int index = 0; index < 12; index++) {
      int ordinal = index;
      sources.add(
          new PackSource.GeneratedEntry(
              "Data/Entry" + index + ".bin",
              1,
              () -> {
                opened.incrementAndGet();
                if (ordinal == 0) {
                  firstEntered.countDown();
                  try {
                    if (!releaseFirst.await(5, TimeUnit.SECONDS))
                      throw new IOException("first source was not released");
                  } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException(interrupted);
                  }
                }
                return Channels.newChannel(new ByteArrayInputStream(new byte[] {(byte) ordinal}));
              }));
    }
    Thread caller =
        Thread.ofPlatform()
            .name("pack-backpressure-caller")
            .start(
                () -> {
                  try {
                    BethesdaArchives.standard()
                        .pack(
                            request(temporary.resolve("backpressure.ba2"), sources, 2),
                            OperationControl.standard());
                  } catch (Throwable cause) {
                    failure.set(cause);
                  }
                });
    try {
      assertTrue(firstEntered.await(5, TimeUnit.SECONDS));
      Thread.sleep(200);
      assertTrue(opened.get() <= 4, "admitted work exceeded 2N while ordinal zero blocked");
    } finally {
      releaseFirst.countDown();
      caller.join(5000);
    }
    assertFalse(caller.isAlive());
    assertNull(failure.get());
    assertEquals(12, opened.get());
  }

  /** Parallel zlib and LZ4 frame transforms preserve BSA bytes and decoded content. */
  @Test
  void parallelCompressedBsaMatchesSingleWorker() throws Exception {
    byte[] first =
        "versioned bsa source".repeat(4000).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    byte[] second =
        "another bsa source".repeat(3500).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
    List<PackSource> sources =
        List.of(generated("meshes/a.nif", first), generated("meshes/b.nif", second));
    for (ArchiveFamily family : List.of(ArchiveFamily.TES4_BSA, ArchiveFamily.SSE_BSA)) {
      PackOptions options =
          new PackOptions(
              List.of(),
              family == ArchiveFamily.SSE_BSA
                  ? PackOptions.Compression.LZ4_FRAME
                  : PackOptions.Compression.ZLIB,
              false,
              new PackOptions.Splitting.UpToBytes(0),
              FlagSelection.AUTOMATIC,
              FlagSelection.AUTOMATIC);
      Path single = temporary.resolve(family.name() + "-single.bsa");
      Path parallel = temporary.resolve(family.name() + "-parallel.bsa");
      BethesdaArchives.standard()
          .pack(request(single, sources, 1, family, options), OperationControl.standard());
      BethesdaArchives.standard()
          .pack(request(parallel, sources, 4, family, options), OperationControl.standard());
      assertArrayEquals(Files.readAllBytes(single), Files.readAllBytes(parallel), family.name());
      try (OpenArchive archive =
          BethesdaArchives.standard().open(parallel, OpenOptions.standard())) {
        assertEquals(2, archive.entryCount());
        for (int index = 0; index < 2; index++) {
          try (EntryContent content = archive.entry(index).openContent()) {
            byte[] bytes = Channels.newInputStream(content).readAllBytes();
            assertTrue(
                java.util.Arrays.equals(bytes, first) || java.util.Arrays.equals(bytes, second));
          }
        }
      }
    }
  }

  /** Many transformed results cannot consume the coordinator's reserved spill handle. */
  @Test
  void reservesCoordinatorSpillHandleBeforeHighWorkerAdmission() throws Exception {
    byte[] payload = new byte[128 * 1024];
    for (int index = 0; index < payload.length; index++)
      payload[index] = (byte) (index * 17 + (index >>> 8));
    List<PackSource> sources = new java.util.ArrayList<>();
    for (int index = 0; index < 20; index++)
      sources.add(generated("Data/Entry" + index + ".bin", payload));
    PackOptions options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    OperationReport report =
        BethesdaArchives.standard()
            .pack(
                request(
                    temporary.resolve("many-workers.ba2"),
                    sources,
                    16,
                    ArchiveFamily.FO4_GENERAL_BA2,
                    options),
                OperationControl.standard());
    assertEquals(1, report.archiveParts().size());
    assertEquals(20, report.archiveParts().getFirst().entryCount());
  }

  /** Separate invocations own separate worker pools and can progress at the same time. */
  @Test
  void independentPackOperationsDoNotShareAHiddenWorkerCap() throws Exception {
    CountDownLatch fourEntered = new CountDownLatch(4);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> firstFailure = new AtomicReference<>();
    AtomicReference<Throwable> secondFailure = new AtomicReference<>();
    List<PackSource> sources = new java.util.ArrayList<>();
    for (int index = 0; index < 2; index++) {
      sources.add(
          new PackSource.GeneratedEntry(
              "Data/Entry" + index + ".bin",
              1,
              () -> {
                fourEntered.countDown();
                try {
                  if (!release.await(5, TimeUnit.SECONDS))
                    throw new IOException("independent operation did not reach its workers");
                } catch (InterruptedException interrupted) {
                  Thread.currentThread().interrupt();
                  throw new IOException(interrupted);
                }
                return Channels.newChannel(new ByteArrayInputStream(new byte[] {1}));
              }));
    }
    Thread first =
        Thread.ofPlatform()
            .start(() -> packConcurrent(temporary.resolve("first.ba2"), sources, firstFailure));
    Thread second =
        Thread.ofPlatform()
            .start(() -> packConcurrent(temporary.resolve("second.ba2"), sources, secondFailure));
    try {
      assertTrue(fourEntered.await(5, TimeUnit.SECONDS));
    } finally {
      release.countDown();
      first.join(5000);
      second.join(5000);
    }
    assertFalse(first.isAlive());
    assertFalse(second.isAlive());
    assertNull(firstFailure.get());
    assertNull(secondFailure.get());
    assertArrayEquals(
        Files.readAllBytes(temporary.resolve("first.ba2")),
        Files.readAllBytes(temporary.resolve("second.ba2")));
  }

  /** A tight scratch ceiling has the same outcome for one and two zlib workers. */
  @Test
  void workerResultCreditsDoNotChangeTightScratchSuccess() throws Exception {
    byte[] bytes = new byte[1024 * 1024];
    List<PackSource> sources =
        List.of(generated("Data/A.bin", bytes), generated("Data/B.bin", bytes));
    PackOptions options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    ResourceLimits standard = ResourceLimits.standard();
    ResourceLimits tight =
        new ResourceLimits(
            standard.maxEntries(),
            standard.maxMetadataBytes(),
            standard.maxDecodedBytes(),
            5L * 1024 * 1024 / 2,
            standard.maxOutputs(),
            standard.maxDiagnostics(),
            standard.maxSecondaryFailures());
    PackRequest one =
        withLimits(
            request(
                temporary.resolve("scratch-one.ba2"),
                sources,
                1,
                ArchiveFamily.FO4_GENERAL_BA2,
                options),
            tight);
    PackRequest two =
        withLimits(
            request(
                temporary.resolve("scratch-two.ba2"),
                sources,
                2,
                ArchiveFamily.FO4_GENERAL_BA2,
                options),
            tight);
    BethesdaArchives.standard().pack(one, OperationControl.standard());
    BethesdaArchives.standard().pack(two, OperationControl.standard());
    assertArrayEquals(Files.readAllBytes(one.destination()), Files.readAllBytes(two.destination()));
  }

  /** Caller interruption waits for workers and is restored without becoming cancellation. */
  @Test
  void callerInterruptionDoesNotAbandonAdmittedWork() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean interruptedOnReturn = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    PackSource slow =
        new PackSource.GeneratedEntry(
            "Data/Slow.bin",
            1,
            () -> {
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new IOException("caller never released source");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
              }
              return Channels.newChannel(new ByteArrayInputStream(new byte[] {1}));
            });
    Path target = temporary.resolve("interrupted.ba2");
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    BethesdaArchives.standard()
                        .pack(
                            request(
                                target,
                                List.of(slow, generated("Data/Fast.bin", new byte[] {2})),
                                2),
                            OperationControl.standard());
                    interruptedOnReturn.set(Thread.currentThread().isInterrupted());
                  } catch (Throwable cause) {
                    failure.set(cause);
                  }
                });
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      caller.interrupt();
    } finally {
      release.countDown();
      caller.join(5000);
    }
    assertFalse(caller.isAlive());
    assertNull(failure.get());
    assertTrue(interruptedOnReturn.get());
    assertTrue(Files.exists(target));
  }

  /** An unchecked generated-source worker fault becomes a structured operation failure. */
  @Test
  void workerExceptionIsStructuredAndSettled() throws Exception {
    PackSource broken =
        new PackSource.GeneratedEntry(
            "Data/Broken.bin",
            1,
            () -> {
              throw new IllegalStateException("caller factory bug");
            });
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .pack(
                        request(
                            temporary.resolve("worker-fault.ba2"),
                            List.of(broken, generated("Data/Other.bin", new byte[] {2})),
                            2),
                        OperationControl.standard()));
    assertEquals(FailureKind.INTERNAL, failure.kind());
    assertEquals(
        Optional.of("operation.internal-failure"), failure.primaryFailure().diagnosticIdentifier());
    assertFalse(Files.exists(temporary.resolve("worker-fault.ba2")));
  }

  /** Cancellation during a parallel LZ4 frame encode stops at bounded worker checkpoints. */
  @Test
  void cancelsParallelLz4EncodingBeforePublication() throws Exception {
    CountDownLatch firstRead = new CountDownLatch(1);
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    PackSource slow =
        new PackSource.GeneratedEntry(
            "meshes/slow.nif", 1024 * 1024, () -> slowZeroChannel(1024 * 1024, firstRead));
    PackOptions options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.LZ4_FRAME,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    Path target = temporary.resolve("cancel-lz4.bsa");
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    BethesdaArchives.standard()
                        .pack(
                            request(
                                target,
                                List.of(slow, generated("meshes/fast.nif", new byte[1024 * 1024])),
                                2,
                                ArchiveFamily.SSE_BSA,
                                options),
                            new OperationControl(snapshot -> {}, cancelled::get));
                  } catch (Throwable failure) {
                    outcome.set(failure);
                  }
                });
    try {
      assertTrue(firstRead.await(5, TimeUnit.SECONDS));
      cancelled.set(true);
    } finally {
      caller.join(5000);
    }
    assertFalse(caller.isAlive());
    assertInstanceOf(ArchiveCancelledException.class, outcome.get());
    assertFalse(Files.exists(target));
  }

  /** A nested observer operation cannot restore the outer caller interrupt before publication. */
  @Test
  void nestedOperationKeepsOuterInterruptDeferredUntilItsReturn() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean nestedRan = new AtomicBoolean();
    AtomicBoolean interruptedOnReturn = new AtomicBoolean();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    PackSource slow =
        new PackSource.GeneratedEntry(
            "Data/Slow.bin",
            1,
            () -> {
              entered.countDown();
              try {
                if (!release.await(5, TimeUnit.SECONDS))
                  throw new IOException("source not released");
              } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new IOException(interrupted);
              }
              return Channels.newChannel(new ByteArrayInputStream(new byte[] {1}));
            });
    Path outer = temporary.resolve("outer.ba2");
    Path nested = temporary.resolve("nested.ba2");
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    BethesdaArchives.standard()
                        .pack(
                            request(
                                outer,
                                List.of(slow, generated("Data/Fast.bin", new byte[] {2})),
                                2),
                            new OperationControl(
                                snapshot -> {
                                  if (snapshot.phase() == OperationPhase.PROCESSING
                                      && snapshot.metric() == ProgressMetric.ENTRIES
                                      && snapshot.completed() == 0
                                      && nestedRan.compareAndSet(false, true)) {
                                    try {
                                      BethesdaArchives.standard()
                                          .pack(
                                              request(
                                                  nested,
                                                  List.of(
                                                      generated("Data/Nested.bin", new byte[] {3})),
                                                  1),
                                              OperationControl.standard());
                                    } catch (ArchiveException problem) {
                                      throw new IllegalStateException(problem);
                                    }
                                  }
                                },
                                () -> false));
                    interruptedOnReturn.set(Thread.currentThread().isInterrupted());
                  } catch (Throwable problem) {
                    failure.set(problem);
                  }
                });
    try {
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      caller.interrupt();
    } finally {
      release.countDown();
      caller.join(5000);
    }
    assertFalse(caller.isAlive());
    assertNull(failure.get());
    assertTrue(nestedRan.get());
    assertTrue(interruptedOnReturn.get());
    assertTrue(Files.exists(outer));
    assertTrue(Files.exists(nested));
  }

  /**
   * Requests cancellation after observing native LZ4 work and discards its uncommitted result. The
   * coordinator may sample the request after the worker leaves the native call.
   */
  @Test
  void cancellationRequestedDuringNativeLz4CallDiscardsResult() throws Exception {
    byte[] payload = new byte[8 * 1024 * 1024];
    new java.util.Random(48).nextBytes(payload);
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicReference<Throwable> outcome = new AtomicReference<>();
    AtomicReference<String> workerStack = new AtomicReference<>("");
    PackOptions options =
        new PackOptions(
            List.of(),
            PackOptions.Compression.LZ4_FRAME,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    Path target = temporary.resolve("native-cancel.bsa");
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    BethesdaArchives.standard()
                        .pack(
                            request(
                                target,
                                List.of(
                                    generated("meshes/a.nif", payload),
                                    generated("meshes/b.nif", payload)),
                                2,
                                ArchiveFamily.SSE_BSA,
                                options),
                            new OperationControl(snapshot -> {}, cancelled::get));
                  } catch (Throwable failure) {
                    outcome.set(failure);
                  }
                });
    try {
      long deadline = System.nanoTime() + 10_000_000_000L;
      boolean nativeObserved = false;
      while (!nativeObserved && System.nanoTime() < deadline) {
        nativeObserved = nativeLz4Worker(workerStack);
        Thread.onSpinWait();
      }
      assertTrue(nativeObserved, workerStack.get());
      cancelled.set(true);
    } finally {
      caller.join(20_000);
    }
    assertFalse(caller.isAlive());
    assertInstanceOf(ArchiveCancelledException.class, outcome.get());
    assertFalse(Files.exists(target));
  }

  /** Selects the Fallout 4 General writer while changing only the worker ceiling. */
  private static PackRequest request(Path target, List<PackSource> sources, long workers) {
    return request(target, sources, workers, ArchiveFamily.FO4_GENERAL_BA2, PackOptions.standard());
  }

  /** Selects family wire values and deterministic options for worker-limit comparisons. */
  private static PackRequest request(
      Path target,
      List<PackSource> sources,
      long workers,
      ArchiveFamily family,
      PackOptions options) {
    ArchiveEncoding encoding =
        switch (family) {
          case TES3_BSA -> ArchiveEncoding.tes3();
          case TES4_BSA, SSE_BSA ->
              new ArchiveEncoding(
                  Optional.of(new WireVersion(family == ArchiveFamily.SSE_BSA ? 0x69 : 0x67)),
                  Optional.empty(),
                  java.util.OptionalLong.empty());
          case FO4_GENERAL_BA2 ->
              new ArchiveEncoding(
                  Optional.of(new WireVersion(1)),
                  Optional.of(Ba2Subtype.GNRL),
                  java.util.OptionalLong.empty());
          default -> throw new IllegalArgumentException("Unsupported test family");
        };
    PackRequest standard =
        PackRequest.standard(target, family, encoding, sources, Optional.empty());
    return new PackRequest(
        standard.destination(),
        standard.family(),
        standard.encoding(),
        standard.compatibilityProfile(),
        standard.sources(),
        standard.targetPolicy(),
        standard.diagnosticPolicy(),
        standard.resourceLimits(),
        new WorkerSelection.UpTo(workers),
        options,
        standard.ddsTarget());
  }

  /** Supplies repeatable source bytes without a filesystem source dependency. */
  private static PackSource generated(String name, byte[] bytes) {
    return new PackSource.GeneratedEntry(
        name, bytes.length, () -> Channels.newChannel(new ByteArrayInputStream(bytes)));
  }

  /** Supplies a declared source whose factory reports an I/O failure on invocation. */
  private static PackSource failing(String name, String reason) {
    return new PackSource.GeneratedEntry(
        name,
        1,
        () -> {
          throw new IOException(reason);
        });
  }

  /** Captures any concurrent invocation failure for assertion on the JUnit coordinator thread. */
  private static void packConcurrent(
      Path target, List<PackSource> sources, AtomicReference<Throwable> failure) {
    try {
      BethesdaArchives.standard().pack(request(target, sources, 2), OperationControl.standard());
    } catch (Throwable cause) {
      failure.set(cause);
    }
  }

  /** Replaces only the semantic scratch ceiling in an otherwise identical request. */
  private static PackRequest withLimits(PackRequest source, ResourceLimits limits) {
    return new PackRequest(
        source.destination(),
        source.family(),
        source.encoding(),
        source.compatibilityProfile(),
        source.sources(),
        source.targetPolicy(),
        source.diagnosticPolicy(),
        limits,
        source.workerSelection(),
        source.options(),
        source.ddsTarget());
  }

  /** Provides a slow bounded generated channel so cancellation reaches an active codec worker. */
  private static java.nio.channels.ReadableByteChannel slowZeroChannel(
      int size, CountDownLatch firstRead) {
    return new java.nio.channels.ReadableByteChannel() {
      private int position;

      /** Supplies small slices until the declared stream ends, signalling its first read. */
      @Override
      public int read(java.nio.ByteBuffer bytes) {
        if (position == size) return -1;
        firstRead.countDown();
        java.util.concurrent.locks.LockSupport.parkNanos(5_000_000L);
        int count = Math.min(bytes.remaining(), Math.min(4096, size - position));
        for (int index = 0; index < count; index++) bytes.put((byte) 0);
        position += count;
        return count;
      }

      /** Keeps the synthetic source open until the packer closes its transferred channel. */
      @Override
      public boolean isOpen() {
        return true;
      }

      /** The synthetic channel owns no external resource. */
      @Override
      public void close() {
        // Generated bytes have no backing handle to release.
      }
    };
  }

  /** Observes a named worker inside the native LZ4 frame update, retaining a diagnostic stack. */
  private static boolean nativeLz4Worker(AtomicReference<String> observedStack) {
    for (var entry : Thread.getAllStackTraces().entrySet()) {
      if (!entry.getKey().isAlive()
          || !entry.getKey().getName().startsWith("jbsa-pack-")
          || !entry.getKey().getName().contains("-worker-")) continue;
      String stack = java.util.Arrays.toString(entry.getValue());
      if (stack.contains("Lz4Frame.encode")) observedStack.set(stack);
      for (StackTraceElement frame : entry.getValue())
        if (frame.getClassName().startsWith("org.lwjgl.util.lz4.")
            && frame.getMethodName().contains("compressUpdate")) return true;
    }
    return false;
  }
}
