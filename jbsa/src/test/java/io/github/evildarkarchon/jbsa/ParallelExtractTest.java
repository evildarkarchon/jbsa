package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Public extraction equivalence across sequential and operation-owned platform workers. */
final class ParallelExtractTest {
  @TempDir Path directory;

  /** Parallel extraction stages on named workers while preserving ordered public evidence. */
  @Test
  void parallelWorkersPreserveSequentialExtraction() throws Exception {
    Path first = Files.write(directory.resolve("first.bin"), new byte[] {7});
    Path second = Files.write(directory.resolve("second.bin"), new byte[] {8});
    Path source = directory.resolve("input.bsa");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                source,
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                List.of(
                    new PackSource.NamedFile("a", first), new PackSource.NamedFile("b", second)),
                Optional.empty()),
            OperationControl.standard());
    Path sequential = directory.resolve("sequential");
    Path parallel = directory.resolve("parallel");
    List<ProgressSnapshot> oneProgress = new ArrayList<>();
    List<ProgressSnapshot> fourProgress = new ArrayList<>();
    AtomicBoolean sawPlatformWorker = new AtomicBoolean();

    OperationReport one =
        BethesdaArchives.standard()
            .extract(
                request(source, sequential, 1),
                new OperationControl(oneProgress::add, () -> false));
    OperationReport four =
        BethesdaArchives.standard()
            .extract(
                request(source, parallel, 4),
                new OperationControl(
                    snapshot -> {
                      fourProgress.add(snapshot);
                      if (snapshot.phase() == OperationPhase.PROCESSING
                          && snapshot.metric() == ProgressMetric.ENTRIES
                          && snapshot.completed() == 1) {
                        sawPlatformWorker.set(
                            Thread.getAllStackTraces().keySet().stream()
                                .anyMatch(
                                    thread ->
                                        thread.isAlive()
                                            && !thread.isDaemon()
                                            && thread.getName().startsWith("jbsa-extract-")
                                            && thread.getName().contains("-worker-")));
                      }
                    },
                    () -> false));

    assertTrue(sawPlatformWorker.get(), "parallel work must use operation-owned platform workers");
    assertArrayEquals(
        Files.readAllBytes(sequential.resolve("a")), Files.readAllBytes(parallel.resolve("a")));
    assertArrayEquals(
        Files.readAllBytes(sequential.resolve("b")), Files.readAllBytes(parallel.resolve("b")));
    assertEquals(
        new ValidationExtent.Payloads(Set.of(0L, 1L)), four.assessment().orElseThrow().extent());
    assertEquals(one.assessment(), four.assessment());
    assertEquals(one.diagnostics(), four.diagnostics());
    assertEquals(oneProgress, fourProgress);
    assertEquals(
        one.artifacts().stream().map(Artifact::state).toList(),
        four.artifacts().stream().map(Artifact::state).toList());
  }

  /** Compressed siblings share the opened archive's bounded decoder capacity without failing. */
  @Test
  void parallelCompressedEntriesWaitForDecoderCapacity() throws Exception {
    byte[] payload = "parallel-compressed-payload".repeat(1024).getBytes(StandardCharsets.US_ASCII);
    List<PackSource> sources = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      Path file = Files.write(directory.resolve("source-" + index + ".bin"), payload);
      sources.add(new PackSource.NamedFile("Data/Entry" + index + ".bin", file));
    }
    Path archive = directory.resolve("compressed.ba2");
    PackRequest standard =
        PackRequest.standard(
            archive,
            ArchiveFamily.FO4_GENERAL_BA2,
            new ArchiveEncoding(
                Optional.of(new WireVersion(1)),
                Optional.of(Ba2Subtype.GNRL),
                OptionalLong.empty()),
            sources,
            Optional.empty());
    PackOptions zlib =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    BethesdaArchives.standard()
        .pack(
            new PackRequest(
                standard.destination(),
                standard.family(),
                standard.encoding(),
                standard.compatibilityProfile(),
                standard.sources(),
                standard.targetPolicy(),
                standard.diagnosticPolicy(),
                standard.resourceLimits(),
                new WorkerSelection.UpTo(1),
                zlib,
                standard.ddsTarget()),
            OperationControl.standard());

    OperationReport report =
        BethesdaArchives.standard()
            .extract(
                request(archive, directory.resolve("compressed-output"), 4),
                OperationControl.standard());
    for (int index = 0; index < 4; index++)
      assertArrayEquals(
          payload,
          Files.readAllBytes(directory.resolve("compressed-output/Data/Entry" + index + ".bin")));
    assertEquals(
        new ValidationExtent.Payloads(Set.of(0L, 1L, 2L, 3L)),
        report.assessment().orElseThrow().extent());
  }

  /** Cancellation while a worker is active drains it and removes private staging before return. */
  @Test
  void cancellationDuringWorkerReadSettlesBeforeReturn() throws Exception {
    byte[] payload = new byte[64 * 1024 * 1024];
    Path input = Files.write(directory.resolve("large.bin"), payload);
    Path archive = directory.resolve("large.bsa");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                archive,
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                List.of(new PackSource.NamedFile("large.bin", input)),
                Optional.empty()),
            OperationControl.standard());
    Path destination = directory.resolve("cancelled-output");
    AtomicBoolean processing = new AtomicBoolean();
    AtomicBoolean sawActiveWorker = new AtomicBoolean();
    AtomicInteger processingProbes = new AtomicInteger();
    OperationControl control =
        new OperationControl(
            snapshot -> {
              if (snapshot.phase() == OperationPhase.PROCESSING) processing.set(true);
            },
            () -> {
              if (!processing.get() || processingProbes.incrementAndGet() == 1) return false;
              long deadline = System.nanoTime() + 5_000_000_000L;
              while (System.nanoTime() < deadline) {
                if (activeExtractWorker()) {
                  sawActiveWorker.set(true);
                  return true;
                }
                Thread.onSpinWait();
              }
              return true;
            });

    assertThrows(
        ArchiveCancelledException.class,
        () -> BethesdaArchives.standard().extract(request(archive, destination, 4), control));
    assertTrue(sawActiveWorker.get(), "cancellation must occur after worker admission");
    assertFalse(Files.exists(destination));
    assertTrue(
        Thread.getAllStackTraces().keySet().stream()
            .noneMatch(
                thread ->
                    thread.isAlive()
                        && thread.getName().startsWith("jbsa-extract-")
                        && thread.getName().contains("-worker-")),
        "the operation must join every worker before returning");
    try (var files = Files.list(directory)) {
      assertTrue(
          files.noneMatch(path -> path.getFileName().toString().startsWith(".jbsa-")),
          "failed extraction must clean private staging");
    }
  }

  /** Closed worker channels return handle credits while staged files await atomic publication. */
  @Test
  void stagesManyEntriesWithoutRetainingClosedHandleCredits() throws Exception {
    List<PackSource> sources = new ArrayList<>();
    for (int index = 0; index < 40; index++) {
      byte value = (byte) index;
      sources.add(
          new PackSource.GeneratedEntry(
              "Data/Entry" + index + ".bin",
              1,
              () -> Channels.newChannel(new ByteArrayInputStream(new byte[] {value}))));
    }
    Path archive = directory.resolve("forty.bsa");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                archive, ArchiveFamily.TES3_BSA, ArchiveEncoding.tes3(), sources, Optional.empty()),
            OperationControl.standard());
    Path destination = directory.resolve("forty-output");
    BethesdaArchives.standard()
        .extract(request(archive, destination, 8), OperationControl.standard());
    // TES3 stores canonical lowercase names, so the extracted spelling is lowercase on every host.
    try (var files = Files.list(destination.resolve("data"))) {
      assertEquals(40, files.count());
    }
  }

  /** A failing observer stops ordered delivery and settles workers before extraction returns. */
  @Test
  void failingObserverStopsParallelExtractionBeforePublication() throws Exception {
    List<PackSource> sources = new ArrayList<>();
    for (int index = 0; index < 4; index++) {
      byte value = (byte) index;
      sources.add(
          new PackSource.GeneratedEntry(
              "Data/Entry" + index + ".bin",
              1,
              () -> Channels.newChannel(new ByteArrayInputStream(new byte[] {value}))));
    }
    Path archive = directory.resolve("observer.bsa");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                archive, ArchiveFamily.TES3_BSA, ArchiveEncoding.tes3(), sources, Optional.empty()),
            OperationControl.standard());
    Path destination = directory.resolve("observer-output");
    AtomicInteger delivered = new AtomicInteger();
    OperationControl control =
        new OperationControl(
            snapshot -> {
              if (snapshot.phase() == OperationPhase.PROCESSING
                  && snapshot.metric() == ProgressMetric.ENTRIES
                  && snapshot.completed() == 1) {
                delivered.incrementAndGet();
                throw new IllegalStateException("observer fault");
              }
            },
            () -> false);
    ArchiveException failure =
        assertThrows(
            ArchiveException.class,
            () -> BethesdaArchives.standard().extract(request(archive, destination, 4), control));
    assertEquals(FailureKind.OBSERVER, failure.kind());
    assertEquals(1, delivered.get());
    assertFalse(Files.exists(destination));
    assertFalse(
        Thread.getAllStackTraces().keySet().stream()
            .anyMatch(
                thread ->
                    thread.isAlive()
                        && thread.getName().startsWith("jbsa-extract-")
                        && thread.getName().contains("-worker-")));
  }

  /** A blocked observer serializes later progress and delays ordered publication. */
  @Test
  void blockingObserverBackpressuresParallelExtraction() throws Exception {
    Path sourceFile = Files.write(directory.resolve("blocked.bin"), new byte[] {3});
    Path archive = directory.resolve("blocked.bsa");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                archive,
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                List.of(
                    new PackSource.NamedFile("Data/A.bin", sourceFile),
                    new PackSource.NamedFile("Data/B.bin", sourceFile)),
                Optional.empty()),
            OperationControl.standard());
    CountDownLatch observerEntered = new CountDownLatch(1);
    CountDownLatch releaseObserver = new CountDownLatch(1);
    AtomicInteger laterSnapshots = new AtomicInteger();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Path destination = directory.resolve("blocked-output");
    Thread caller =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    BethesdaArchives.standard()
                        .extract(
                            request(archive, destination, 2),
                            new OperationControl(
                                snapshot -> {
                                  if (snapshot.phase() == OperationPhase.PROCESSING
                                      && snapshot.metric() == ProgressMetric.ENTRIES
                                      && snapshot.completed() == 1) {
                                    observerEntered.countDown();
                                    try {
                                      if (!releaseObserver.await(5, TimeUnit.SECONDS))
                                        throw new IllegalStateException("observer not released");
                                    } catch (InterruptedException interrupted) {
                                      Thread.currentThread().interrupt();
                                      throw new IllegalStateException(interrupted);
                                    }
                                  } else if (observerEntered.getCount() == 0) {
                                    laterSnapshots.incrementAndGet();
                                  }
                                },
                                () -> false));
                  } catch (Throwable cause) {
                    failure.set(cause);
                  }
                });
    try {
      assertTrue(observerEntered.await(5, TimeUnit.SECONDS));
      assertEquals(0, laterSnapshots.get());
      assertFalse(Files.exists(destination));
    } finally {
      releaseObserver.countDown();
      caller.join(5000);
    }
    assertFalse(caller.isAlive());
    assertNull(failure.get());
    assertTrue(laterSnapshots.get() > 0);
    assertTrue(Files.exists(destination.resolve("data/a.bin")));
  }

  /**
   * A sparse selection extracts the same files, Artifacts, Validation Extent, progress, and
   * diagnostics whether one worker or several stage it.
   */
  @Test
  void sparseSelectionParallelMatchesSequentialOutcome() throws Exception {
    Path archive = storedArchive("sparse.bsa", 5);
    Path sequential = directory.resolve("sparse-sequential");
    Path parallel = directory.resolve("sparse-parallel");
    List<ProgressSnapshot> oneProgress = new ArrayList<>();
    List<ProgressSnapshot> fourProgress = new ArrayList<>();

    OperationReport one =
        BethesdaArchives.standard()
            .extract(
                request(archive, sequential, SPARSE, 1),
                new OperationControl(oneProgress::add, () -> false));
    OperationReport four =
        BethesdaArchives.standard()
            .extract(
                request(archive, parallel, SPARSE, 4),
                new OperationControl(fourProgress::add, () -> false));

    // Every extracted file reports its archive entry ordinal. The root and the shared parent
    // directory are created for the first selected entry, so they carry that entry's ordinal.
    Set<Artifact> expected = new java.util.HashSet<>();
    expected.add(new Artifact(Path.of(""), SPARSE.ordinals().getFirst(), ArtifactState.PUBLISHED));
    // TES3 orders entries by name hash, so each archive ordinal's name comes from the archive.
    for (EntryMetadata entry : BethesdaArchives.standard().inspect(archive).entries()) {
      // Archive names use backslash separators; extraction resolves them as path separators.
      String name = entry.displayName().replace('\\', '/');
      boolean selected = SPARSE.ordinals().contains(entry.ordinal());
      assertEquals(selected, Files.exists(sequential.resolve(name)), name);
      assertEquals(selected, Files.exists(parallel.resolve(name)), name);
      if (selected) {
        assertArrayEquals(
            Files.readAllBytes(sequential.resolve(name)),
            Files.readAllBytes(parallel.resolve(name)));
        expected.add(new Artifact(Path.of(name), entry.ordinal(), ArtifactState.PUBLISHED));
        expected.add(
            new Artifact(
                Path.of(name).getParent(), SPARSE.ordinals().getFirst(), ArtifactState.PUBLISHED));
      }
    }
    assertEquals(expected, Set.copyOf(relative(sequential, one.artifacts())));
    assertEquals(expected, Set.copyOf(relative(parallel, four.artifacts())));
    assertEquals(
        new ValidationExtent.Payloads(Set.of(1L, 3L, 4L)), one.assessment().orElseThrow().extent());
    assertEquals(one.assessment(), four.assessment());
    assertEquals(one.diagnostics(), four.diagnostics());
    assertEquals(oneProgress, fourProgress);
    assertEquals(relative(sequential, one.artifacts()), relative(parallel, four.artifacts()));
  }

  /**
   * A corrupt payload in a later selected entry fails at that entry's archive ordinal with the same
   * Primary Failure, Secondary Failures, Artifacts, and assessment in both strategies.
   */
  @Test
  void sparseSelectionParallelMatchesSequentialFailureLocation() throws Exception {
    Path archive = compressedArchive("sparse-corrupt.ba2", 5);
    corruptPayload(archive, 4);
    Path sequential = directory.resolve("corrupt-sequential");
    Path parallel = directory.resolve("corrupt-parallel");

    ArchiveException one =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(request(archive, sequential, SPARSE, 1), OperationControl.standard()));
    ArchiveException four =
        assertThrows(
            ArchiveException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(request(archive, parallel, SPARSE, 4), OperationControl.standard()));

    assertEquals(FailureKind.FORMAT, one.kind());
    assertEquals(OperationPhase.PROCESSING, one.primaryFailure().phase());
    assertEquals(OptionalLong.of(4), one.primaryFailure().ordinal());
    assertEquals(located(one.primaryFailure()), located(four.primaryFailure()));
    assertEquals(
        one.secondaryFailures().stream().map(ParallelExtractTest::located).toList(),
        four.secondaryFailures().stream().map(ParallelExtractTest::located).toList());
    assertEquals(one.diagnostics(), four.diagnostics());
    assertEquals(one.assessment(), four.assessment());
    assertEquals(relative(sequential, one.artifacts()), relative(parallel, four.artifacts()));
    assertFalse(Files.exists(sequential));
    assertFalse(Files.exists(parallel));
  }

  /**
   * Cancellation accepted on entering PROCESSING is located at the first selected entry's archive
   * ordinal in both strategies.
   */
  @Test
  void sparseSelectionParallelMatchesSequentialCancellationLocation() throws Exception {
    Path archive = storedArchive("sparse-cancel.bsa", 5);
    Path sequential = directory.resolve("cancel-sequential");
    Path parallel = directory.resolve("cancel-parallel");

    ArchiveException one =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(request(archive, sequential, SPARSE, 1), cancelOnProcessing()));
    ArchiveException four =
        assertThrows(
            ArchiveCancelledException.class,
            () ->
                BethesdaArchives.standard()
                    .extract(request(archive, parallel, SPARSE, 4), cancelOnProcessing()));

    assertEquals(OperationPhase.PROCESSING, one.primaryFailure().phase());
    assertEquals(OptionalLong.of(1), one.primaryFailure().ordinal());
    assertEquals(located(one.primaryFailure()), located(four.primaryFailure()));
    assertEquals(relative(sequential, one.artifacts()), relative(parallel, four.artifacts()));
    assertFalse(Files.exists(sequential));
    assertFalse(Files.exists(parallel));
  }

  /** Sparse archive ordinals whose selection positions (0, 1, 2) differ from them. */
  private static final EntrySelection.Ordinals SPARSE =
      new EntrySelection.Ordinals(List.of(1L, 3L, 4L));

  /** Packs a stored TES3 archive whose entry {@code i} is named Data/Entry{i}.bin. */
  private Path storedArchive(String name, int count) throws Exception {
    List<PackSource> sources = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      byte value = (byte) index;
      sources.add(
          new PackSource.GeneratedEntry(
              "Data/Entry" + index + ".bin",
              1,
              () -> Channels.newChannel(new ByteArrayInputStream(new byte[] {value}))));
    }
    Path archive = directory.resolve(name);
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                archive, ArchiveFamily.TES3_BSA, ArchiveEncoding.tes3(), sources, Optional.empty()),
            OperationControl.standard());
    return archive;
  }

  /** Packs a zlib-compressed general BA2 whose entry {@code i} is named Data/Entry{i}.bin. */
  private Path compressedArchive(String name, int count) throws Exception {
    List<PackSource> sources = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      byte[] payload =
          ("compressed-payload-" + index).repeat(256).getBytes(StandardCharsets.US_ASCII);
      sources.add(
          new PackSource.NamedFile(
              "Data/Entry" + index + ".bin",
              Files.write(directory.resolve(name + "-source-" + index + ".bin"), payload)));
    }
    Path archive = directory.resolve(name);
    PackRequest standard =
        PackRequest.standard(
            archive,
            ArchiveFamily.FO4_GENERAL_BA2,
            new ArchiveEncoding(
                Optional.of(new WireVersion(1)),
                Optional.of(Ba2Subtype.GNRL),
                OptionalLong.empty()),
            sources,
            Optional.empty());
    PackOptions zlib =
        new PackOptions(
            List.of(),
            PackOptions.Compression.ZLIB,
            false,
            new PackOptions.Splitting.UpToBytes(0),
            FlagSelection.AUTOMATIC,
            FlagSelection.AUTOMATIC);
    BethesdaArchives.standard()
        .pack(
            new PackRequest(
                standard.destination(),
                standard.family(),
                standard.encoding(),
                standard.compatibilityProfile(),
                standard.sources(),
                standard.targetPolicy(),
                standard.diagnosticPolicy(),
                standard.resourceLimits(),
                new WorkerSelection.UpTo(1),
                zlib,
                standard.ddsTarget()),
            OperationControl.standard());
    return archive;
  }

  /**
   * Zeroes one entry's packed zlib stream in place, so its header check fails when the payload is
   * decoded while the archive's structure stays valid for opening.
   */
  private static void corruptPayload(Path archive, long ordinal) throws Exception {
    EntryMetadata metadata =
        BethesdaArchives.standard().inspect(archive).entries().get(Math.toIntExact(ordinal));
    assertEquals(ordinal, metadata.ordinal());
    var general = (EntryMetadata.GeneralBa2) metadata.facts();
    assertTrue(general.packedSize() > 0, "the corrupted entry must be compressed");
    try (var channel =
        java.nio.channels.FileChannel.open(archive, java.nio.file.StandardOpenOption.WRITE)) {
      channel.write(
          java.nio.ByteBuffer.allocate(Math.toIntExact(general.packedSize())),
          general.payloadOffset());
    }
  }

  /** Cancels at the first PROCESSING snapshot, which only the coordinator thread delivers. */
  private static OperationControl cancelOnProcessing() {
    AtomicBoolean cancelled = new AtomicBoolean();
    return new OperationControl(
        snapshot -> {
          if (snapshot.phase() == OperationPhase.PROCESSING) cancelled.set(true);
        },
        cancelled::get);
  }

  /**
   * Reduces a failure to its observable location, dropping the cause because throwables compare by
   * identity.
   */
  private static List<Object> located(Failure failure) {
    return List.of(
        failure.kind(),
        failure.phase(),
        failure.ordinal(),
        failure.diagnosticIdentifier(),
        failure.location());
  }

  /** Re-roots Artifacts under their destination so two destinations can be compared. */
  private static List<Artifact> relative(Path destination, List<Artifact> artifacts) {
    Path root = destination.toAbsolutePath().normalize();
    return artifacts.stream()
        .map(
            artifact ->
                new Artifact(
                    root.relativize(artifact.path()), artifact.ordinal(), artifact.state()))
        .toList();
  }

  /** Detects a named platform worker inside its private stage or payload transfer. */
  private static boolean activeExtractWorker() {
    return Thread.getAllStackTraces().entrySet().stream()
        .anyMatch(
            entry ->
                entry.getKey().isAlive()
                    && entry.getKey().getName().startsWith("jbsa-extract-")
                    && entry.getKey().getName().contains("-worker-")
                    && java.util.Arrays.stream(entry.getValue())
                        .anyMatch(
                            frame ->
                                frame.getMethodName().equals("stageWorker")
                                    || frame.getMethodName().equals("transferParallel")));
  }

  /** Selects a worker limit without changing the public extraction policies under test. */
  private static ExtractRequest request(Path source, Path destination, long workers) {
    return request(source, destination, EntrySelection.ALL, workers);
  }

  /** Selects entries and a worker limit without changing the other public extraction policies. */
  private static ExtractRequest request(
      Path source, Path destination, EntrySelection selection, long workers) {
    return new ExtractRequest(
        source,
        destination,
        selection,
        TargetPolicy.FAIL,
        DiagnosticPolicy.standard(),
        new WorkerSelection.UpTo(workers),
        OpenOptions.standard());
  }
}
