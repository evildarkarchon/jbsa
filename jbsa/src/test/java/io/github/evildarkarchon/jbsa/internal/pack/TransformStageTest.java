package io.github.evildarkarchon.jbsa.internal.pack;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.AdmittedCodec;
import io.github.evildarkarchon.jbsa.internal.codec.PayloadCodec;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.OperationSession;
import io.github.evildarkarchon.jbsa.internal.io.OperationStopped;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

/**
 * Direct tests of the merged parallel transform stage: its worker failure mapping (D1, D2), its
 * codec-map encodings (D3), its single headroom rule (D4), its encoded-only results, and the
 * settlement of a stopped worker's cleanup evidence (ADR-0006).
 */
class TransformStageTest {
  private static final Path TARGET = Path.of("unused.mem").toAbsolutePath();
  private static final Path SOURCE = Path.of("Data", "a.bin").toAbsolutePath();
  private static final IoContext CONTEXT = IoContext.destination(TARGET, Operation.PACK);
  private static final IoContext PROCESSING =
      IoContext.destination(TARGET, Operation.PACK)
          .at(OperationPhase.PROCESSING, OptionalLong.of(4));

  /** D1: a skipped outcome reaching the caller is INTERNAL at its ordinal, never CANCELLED. */
  @Test
  void skippedOutcomeIsInternal() {
    var failure = TransformStage.skipped(PROCESSING);
    assertEquals(FailureKind.INTERNAL, failure.kind());
    assertEquals(
        Optional.of("operation.internal-failure"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertEquals(OptionalLong.of(4), failure.primaryFailure().ordinal());
  }

  /**
   * D2 through real workers: a non-fatal Error other than an assertion propagates, as it does from
   * the operation template, instead of becoming INTERNAL.
   */
  @Test
  void workerNonAssertionErrorPropagates() throws Exception {
    var error = new LinkageError("not an assertion");
    var limits = ResourceLimits.standard();
    var operation = new OperationSession(Operation.PACK, limits, OperationControl.standard());
    operation.begin();
    try (var budget = ResourceBudget.forMutation(limits, CONTEXT);
        var stage =
            new TransformStage(
                operation,
                List.of(entry("a", "abc"), entry("b", "de")),
                new WorkerSelection.UpTo(2),
                budget,
                CONTEXT,
                7,
                ordinal ->
                    ordinal == 0
                        ? new TransformStage.Encoding(
                            16,
                            0,
                            0,
                            true,
                            (raw, size, encoded, checkpoint, lease, processing) -> {
                              throw error;
                            })
                        : null)) {
      assertSame(error, assertThrows(LinkageError.class, stage::next));
    }
  }

  /** D2 through real workers: a non-fatal Error in an encoder is INTERNAL at its own ordinal. */
  @Test
  void workerErrorIsInternalAtItsOrdinal() throws Exception {
    var failure =
        firstFailure(
            (raw, size, encoded, checkpoint, lease, processing) -> {
              throw new AssertionError("encoder bug");
            });
    assertEquals(FailureKind.INTERNAL, failure.kind());
    assertEquals(
        Optional.of("operation.internal-failure"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(OperationPhase.PROCESSING, failure.primaryFailure().phase());
    assertEquals(OptionalLong.of(7), failure.primaryFailure().ordinal());
  }

  /**
   * D2 through real workers: an unstructured I/O failure is SOURCE {@code operation.source-io},
   * located at the source the entry reads rather than at the pack's destination (ADR-0006).
   */
  @Test
  void workerIoFailureIsSourceIoAtItsSource() throws Exception {
    var failure =
        firstFailure(
            entry("a", "abc", IoContext.source(SOURCE, Operation.PACK)),
            (raw, size, encoded, checkpoint, lease, processing) -> {
              throw new IOException("encoder disk");
            });
    assertEquals(FailureKind.SOURCE, failure.kind());
    assertEquals(
        Optional.of("operation.source-io"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(OptionalLong.of(7), failure.primaryFailure().ordinal());
    var location = failure.primaryFailure().location().orElseThrow();
    assertEquals(Optional.of(SOURCE), location.archive());
    assertEquals(Optional.empty(), location.artifact());
    assertEquals(OptionalLong.of(7), location.entryOrdinal());
  }

  /** A generated source has no path, so its failure carries only its ordinal and entry name. */
  @Test
  void generatedSourceFailureCarriesOrdinalAndNameWithoutAPath() throws Exception {
    var failure =
        firstFailure(
            entry("Data/a.bin", "abc", IoContext.generated("Data/a.bin", Operation.PACK)),
            (raw, size, encoded, checkpoint, lease, processing) -> {
              throw new IOException("encoder disk");
            });
    assertEquals(FailureKind.SOURCE, failure.kind());
    var location = failure.primaryFailure().location().orElseThrow();
    assertEquals(Optional.empty(), location.archive());
    assertEquals(Optional.empty(), location.artifact());
    assertEquals(OptionalLong.of(7), location.entryOrdinal());
    assertEquals(Optional.of("Data/a.bin"), location.entryName());
  }

  /**
   * A worker stopped behind an earlier failure closes its spilled spool while unwinding. When that
   * close leaves the spill file behind, its exact residual path still reaches the session through
   * the runner, instead of being dropped with the discarded work. The runner settles it whether
   * closing drains the stopped outcome or the coordinator consumes it as skipped first.
   *
   * @param consumeSkipped whether the coordinator consumes the stopped outcome through {@code
   *     next()} before closing the stage
   */
  @ParameterizedTest
  @ValueSource(booleans = {false, true})
  void stoppedWorkersResidualSpoolReachesTheSession(boolean consumeSkipped, @TempDir Path scratch)
      throws Exception {
    int spilled = 64 * 1024 + 1;
    int trickle = 100_000;
    var stalled = new CountDownLatch(1);
    var released = new CountDownLatch(1);
    Path[] residual = new Path[1];
    Runnable[] restore = new Runnable[1];
    // Ordinal 0 fails once ordinal 1 has spilled, after making that spill file undeletable.
    var failing =
        entry(
            "a",
            1,
            (reader, processing) -> {
              try {
                assertTrue(stalled.await(30, TimeUnit.SECONDS));
                try (var children = Files.list(scratch)) {
                  residual[0] = children.findFirst().orElseThrow();
                }
                restore[0] = denyDeletion(scratch, residual[0]);
              } catch (InterruptedException interrupted) {
                throw new AssertionError(interrupted);
              } finally {
                released.countDown();
              }
              throw new IOException("first source");
            });
    // Ordinal 1 spills past the memory window, then trickles bytes until its checkpoint stops it.
    var stopped =
        entry(
            "b",
            spilled + trickle,
            (reader, processing) ->
                reader.read(
                    new ReadableByteChannel() {
                      private long delivered;

                      @Override
                      public int read(ByteBuffer bytes) throws IOException {
                        if (delivered == spilled) {
                          stalled.countDown();
                          try {
                            assertTrue(released.await(30, TimeUnit.SECONDS));
                            Thread.sleep(1);
                          } catch (InterruptedException interrupted) {
                            throw new AssertionError(interrupted);
                          }
                        }
                        int count =
                            delivered < spilled
                                ? (int) Math.min(bytes.remaining(), spilled - delivered)
                                : Math.min(bytes.remaining(), 1);
                        bytes.position(bytes.position() + count);
                        delivered += count;
                        return count;
                      }

                      @Override
                      public boolean isOpen() {
                        return true;
                      }

                      @Override
                      public void close() {
                        // The stage borrows this channel; the payload owns nothing to release.
                      }
                    }));
    var limits = ResourceLimits.standard();
    var operation = new OperationSession(Operation.PACK, limits, OperationControl.standard());
    try {
      var outcome =
          assertThrows(
              ArchiveException.class,
              () ->
                  operation.run(
                      CONTEXT,
                      resources -> {
                        operation.begin();
                        ResourceBudget budget = ResourceBudget.forMutation(limits, CONTEXT);
                        resources.register(budget::close, CONTEXT);
                        var stage =
                            new TransformStage(
                                operation,
                                List.of(failing, stopped),
                                new WorkerSelection.UpTo(2),
                                budget,
                                CONTEXT,
                                0,
                                ignored -> null,
                                scratch);
                        assertTrue(stage.parallel());
                        var first = assertThrows(ArchiveException.class, stage::next);
                        if (consumeSkipped) {
                          // Closing the skipped outcome settles the evidence; the session stops.
                          assertThrows(OperationStopped.class, stage::next);
                          stage.close();
                        } else {
                          // Draining it while closing settles the evidence, then stops the caller.
                          assertThrows(OperationStopped.class, stage::close);
                        }
                        throw first;
                      }));
      assertEquals(FailureKind.SOURCE, outcome.kind());
      assertEquals(OptionalLong.of(0), outcome.primaryFailure().ordinal());
      assertTrue(
          outcome.artifacts().stream()
              .anyMatch(
                  artifact ->
                      artifact.path().equals(residual[0])
                          && artifact.ordinal() == 1
                          && artifact.state() == ArtifactState.RESIDUAL_STAGING),
          () -> outcome.artifacts().toString());
      assertTrue(
          outcome.secondaryFailures().stream()
              .anyMatch(
                  secondary ->
                      secondary.phase() == OperationPhase.CLEANUP
                          && secondary.kind() == FailureKind.DESTINATION),
          () -> outcome.secondaryFailures().toString());
    } finally {
      // Reporting the residual transferred its ownership; the test performs that cleanup.
      if (restore[0] != null) restore[0].run();
      if (residual[0] != null) Files.deleteIfExists(residual[0]);
    }
  }

  /**
   * Makes one file undeletable until the returned action runs: the DOS read-only attribute on
   * Windows, or a read-only parent directory on a POSIX host.
   */
  private static Runnable denyDeletion(Path directory, Path file) throws IOException {
    if (Files.getFileStore(directory).supportsFileAttributeView("posix")) {
      var permissions = Files.getPosixFilePermissions(directory);
      Files.setPosixFilePermissions(directory, PosixFilePermissions.fromString("r-xr-xr-x"));
      return () -> {
        try {
          Files.setPosixFilePermissions(directory, permissions);
        } catch (IOException failure) {
          throw new java.io.UncheckedIOException(failure);
        }
      };
    }
    Files.setAttribute(file, "dos:readonly", true);
    return () -> {
      try {
        Files.setAttribute(file, "dos:readonly", false);
      } catch (IOException failure) {
        throw new java.io.UncheckedIOException(failure);
      }
    };
  }

  /** An encoding that does not retain raw bytes streams its source and keeps only its output. */
  @Test
  void encodedOnlyResultsKeepNoRawSpool() throws Exception {
    var limits = ResourceLimits.standard();
    var operation = new OperationSession(Operation.PACK, limits, OperationControl.standard());
    operation.begin();
    try (var budget = ResourceBudget.forMutation(limits, CONTEXT);
        var stage =
            new TransformStage(
                operation,
                List.of(entry("a", "abc"), entry("b", "de")),
                new WorkerSelection.UpTo(2),
                budget,
                CONTEXT,
                0,
                ordinal ->
                    new TransformStage.Encoding(
                        16,
                        0,
                        0,
                        false,
                        (input, size, encoded, checkpoint, lease, processing) -> {
                          // Upper-cases the source so the encoded spool is distinguishable.
                          byte[] bytes = new byte[(int) size];
                          input.read(ByteBuffer.wrap(bytes));
                          encoded.write(
                              0,
                              ByteBuffer.wrap(
                                  new String(bytes, StandardCharsets.US_ASCII)
                                      .toUpperCase()
                                      .getBytes(StandardCharsets.US_ASCII)));
                        }))) {
      assertTrue(stage.parallel());
      for (String expected : new String[] {"ABC", "DE"}) {
        try (var input = stage.next()) {
          assertTrue(input.hasEncoded());
          assertEquals(expected.length(), input.encodedSize());
          assertEquals(expected, read(input.encodedChannel(), expected.length()));
          assertThrows(IllegalStateException.class, input::channel);
        }
      }
    }
  }

  /** D3: the codec map supplies one conservative zlib bound and the BSA LZ4 frame bound. */
  @Test
  void encodingsComeFromTheCodecMap() throws Exception {
    try (var budget = ResourceBudget.forMutation(ResourceLimits.standard(), CONTEXT)) {
      assertNull(TransformStage.encoding(admit(PayloadCodec.STORED), 1000, true, budget));
      AdmittedCodec zlibCodec = admit(PayloadCodec.ZLIB);
      var zlib = TransformStage.encoding(zlibCodec, 1000, true, budget);
      assertEquals(1000 + 125 + 3 + 1 + 22, zlib.outputBound());
      assertEquals(zlibCodec.encodeCost(1000).heapBytes(), zlib.heapBytes());
      assertEquals(zlibCodec.encodeCost(1000).nativeBytes(), zlib.nativeBytes());
      assertTrue(zlib.retainsRaw());
      AdmittedCodec frame = admit(PayloadCodec.BSA_LZ4_FRAME);
      var lz4 = TransformStage.encoding(frame, 65537, false, budget);
      assertEquals(65537 + 2 * 4 + 64, lz4.outputBound());
      // The frame encoder's working set belongs to whichever provider admission pinned.
      assertEquals(frame.encodeCost(65537).heapBytes(), lz4.heapBytes());
      assertEquals(frame.encodeCost(65537).nativeBytes(), lz4.nativeBytes());
      assertFalse(lz4.retainsRaw());
    }
  }

  /**
   * D4: workers run only while the sum of every entry's codec bound (its size when untransformed)
   * fits a sixth of the scratch ceiling.
   */
  @Test
  void parallelHeadroomSumsCodecBounds() throws Exception {
    var sources = List.of(entry("a", "0123456789"), entry("b", "0123456789"));
    assertTrue(parallel(sources, 6 * 20, false));
    assertFalse(parallel(sources, 6 * 20 - 1, false));
    // Each 10-byte zlib bound is 10 + 1 + 22 = 33.
    assertTrue(parallel(sources, 6 * 66, true));
    assertFalse(parallel(sources, 6 * 66 - 1, true));
  }

  /**
   * The Content Sharing shortlist key separates a stored record from a compressed one whose size
   * and digest coincide, so identical framed bytes can never alias across that boundary.
   */
  @Test
  void sharingKeyDiscriminatesStoredFromCompressedRecords() {
    byte[] digest = new byte[32];
    assertNotEquals(ContentSharing.key(9, digest, false), ContentSharing.key(9, digest, true));
    assertEquals(ContentSharing.key(9, digest, true), ContentSharing.key(9, digest.clone(), true));
  }

  /** Admits a codec for encoding, as the pipeline's requiredCodecs step does. */
  private static AdmittedCodec admit(PayloadCodec codec) throws ArchiveException {
    return AdmittedCodec.admit(codec, "encode", CONTEXT);
  }

  /** Reports whether a two-worker stage over the sources would use workers under a ceiling. */
  private static boolean parallel(List<PackSources.Entry> sources, long maxScratch, boolean zlib)
      throws Exception {
    var standard = ResourceLimits.standard();
    AdmittedCodec zlibCodec = admit(PayloadCodec.ZLIB);
    var limits =
        new ResourceLimits(
            standard.maxEntries(),
            standard.maxMetadataBytes(),
            standard.maxDecodedBytes(),
            maxScratch,
            standard.maxOutputs(),
            standard.maxDiagnostics(),
            standard.maxSecondaryFailures());
    try (var budget = ResourceBudget.forMutation(limits, CONTEXT);
        var stage =
            new TransformStage(
                new OperationSession(Operation.PACK, limits, OperationControl.standard()),
                sources,
                new WorkerSelection.UpTo(2),
                budget,
                CONTEXT,
                0,
                ordinal ->
                    zlib
                        ? TransformStage.encoding(
                            zlibCodec, sources.get(ordinal).size(), true, budget)
                        : null)) {
      return stage.parallel();
    }
  }

  /**
   * Runs a two-worker stage whose first entry (operation ordinal 7) fails in its encoder, and
   * returns what the caller sees when it consumes that entry.
   */
  private static ArchiveException firstFailure(TransformStage.Encoder encoder) throws Exception {
    return firstFailure(entry("a", "abc"), encoder);
  }

  /** Runs the same stage with a chosen first entry, whose source location locates the failure. */
  private static ArchiveException firstFailure(
      PackSources.Entry first, TransformStage.Encoder encoder) throws Exception {
    var limits = ResourceLimits.standard();
    var operation = new OperationSession(Operation.PACK, limits, OperationControl.standard());
    operation.begin();
    try (var budget = ResourceBudget.forMutation(limits, CONTEXT);
        var stage =
            new TransformStage(
                operation,
                List.of(first, entry("b", "de")),
                new WorkerSelection.UpTo(2),
                budget,
                CONTEXT,
                7,
                ordinal ->
                    ordinal == 0 ? new TransformStage.Encoding(16, 0, 0, true, encoder) : null)) {
      assertTrue(stage.parallel());
      return assertThrows(ArchiveException.class, () -> stage.next());
    }
  }

  /** A planned generated source with fixed ASCII bytes. */
  private static PackSources.Entry entry(String name, String content) {
    return entry(name, content, IoContext.generated(name, Operation.PACK));
  }

  /** A planned source with fixed ASCII bytes, read from the given source location. */
  private static PackSources.Entry entry(String name, String content, IoContext location) {
    byte[] bytes = content.getBytes(StandardCharsets.US_ASCII);
    return new PackSources.Entry(
        name,
        name,
        name.getBytes(StandardCharsets.US_ASCII),
        0,
        bytes.length,
        (reader, processing) -> reader.read(Channels.newChannel(new ByteArrayInputStream(bytes))),
        location);
  }

  /** A planned generated source of a declared size, with a payload of its own. */
  private static PackSources.Entry entry(String name, long size, PackSources.Payload payload) {
    return new PackSources.Entry(
        name,
        name,
        name.getBytes(StandardCharsets.US_ASCII),
        0,
        size,
        payload,
        IoContext.generated(name, Operation.PACK));
  }

  /** Reads exactly {@code size} ASCII bytes from a borrowed channel. */
  private static String read(ReadableByteChannel channel, int size) throws IOException {
    var bytes = ByteBuffer.allocate(size);
    while (bytes.hasRemaining()) if (channel.read(bytes) < 0) break;
    return new String(bytes.array(), 0, bytes.position(), StandardCharsets.US_ASCII);
  }
}
