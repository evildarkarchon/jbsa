package io.github.evildarkarchon.jbsa.internal.pack;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.JdkZlib;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Frame;
import io.github.evildarkarchon.jbsa.internal.io.OperationSession;
import io.github.evildarkarchon.jbsa.internal.io.PackSources;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.Channels;
import java.nio.channels.ReadableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import org.junit.jupiter.api.Test;

/**
 * Direct tests of the merged parallel transform stage: its worker failure mapping (D1, D2), its
 * codec-map encodings (D3), its single headroom rule (D4), and its encoded-only results.
 */
class TransformStageTest {
  private static final Path TARGET = Path.of("unused.mem").toAbsolutePath();
  private static final IoContext CONTEXT = IoContext.of(TARGET, Operation.PACK);
  private static final IoContext PROCESSING =
      new IoContext(TARGET, Operation.PACK, OperationPhase.PROCESSING, OptionalLong.of(4));

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
   * D2: structured failures pass through, an unstructured I/O failure is SOURCE, any other
   * non-fatal throwable (including a non-fatal Error) is INTERNAL, and a VM error is rethrown.
   */
  @Test
  void workerFailuresMapOneWayForEveryFamily() {
    var structured = CONTEXT.failure(FailureKind.FORMAT, "format.example", null);
    assertSame(structured, TransformStage.failure(structured, PROCESSING));

    var io = new IOException("disk");
    var source = TransformStage.failure(io, PROCESSING);
    assertEquals(FailureKind.SOURCE, source.kind());
    assertEquals(
        Optional.of("operation.source-io"), source.primaryFailure().diagnosticIdentifier());
    assertEquals(OptionalLong.of(4), source.primaryFailure().ordinal());
    assertSame(io, source.getCause());

    for (Throwable internal :
        new Throwable[] {new AssertionError("bug"), new IllegalStateException("bug")}) {
      var failure = TransformStage.failure(internal, PROCESSING);
      assertEquals(FailureKind.INTERNAL, failure.kind(), internal.toString());
      assertEquals(
          Optional.of("operation.internal-failure"),
          failure.primaryFailure().diagnosticIdentifier());
      assertSame(internal, failure.getCause());
    }

    var fatal = new OutOfMemoryError("fatal");
    assertSame(
        fatal,
        assertThrows(OutOfMemoryError.class, () -> TransformStage.failure(fatal, PROCESSING)));
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

  /** D2 through real workers: an unstructured I/O failure is SOURCE {@code operation.source-io}. */
  @Test
  void workerIoFailureIsSourceIoAtItsOrdinal() throws Exception {
    var failure =
        firstFailure(
            (raw, size, encoded, checkpoint, lease, processing) -> {
              throw new IOException("encoder disk");
            });
    assertEquals(FailureKind.SOURCE, failure.kind());
    assertEquals(
        Optional.of("operation.source-io"), failure.primaryFailure().diagnosticIdentifier());
    assertEquals(OptionalLong.of(7), failure.primaryFailure().ordinal());
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
        try (var input = stage.next(operation)) {
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
      assertNull(TransformStage.encoding(Codec.STORED, 1000, true, budget));
      var zlib = TransformStage.encoding(Codec.ZLIB, 1000, true, budget);
      assertEquals(1000 + 125 + 3 + 1 + 22, zlib.outputBound());
      assertEquals(JdkZlib.ENCODE_HEAP_BYTES, zlib.heapBytes());
      assertEquals(JdkZlib.ENCODE_NATIVE_BYTES, zlib.nativeBytes());
      assertTrue(zlib.retainsRaw());
      var lz4 = TransformStage.encoding(Codec.BSA_LZ4_FRAME, 65537, false, budget);
      assertEquals(65537 + 2 * 4 + 64, lz4.outputBound());
      assertEquals(Lz4Frame.HEAP_BYTES, lz4.heapBytes());
      assertEquals(Lz4Frame.ENCODE_NATIVE_BYTES, lz4.nativeBytes());
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

  /** Reports whether a two-worker stage over the sources would use workers under a ceiling. */
  private static boolean parallel(List<PackSources.Entry> sources, long maxScratch, boolean zlib)
      throws Exception {
    var standard = ResourceLimits.standard();
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
                sources,
                new WorkerSelection.UpTo(2),
                budget,
                CONTEXT,
                0,
                ordinal ->
                    zlib
                        ? TransformStage.encoding(
                            Codec.ZLIB, sources.get(ordinal).size(), true, budget)
                        : null)) {
      return stage.parallel();
    }
  }

  /**
   * Runs a two-worker stage whose first entry (operation ordinal 7) fails in its encoder, and
   * returns what the caller sees when it consumes that entry.
   */
  private static ArchiveException firstFailure(TransformStage.Encoder encoder) throws Exception {
    var limits = ResourceLimits.standard();
    var operation = new OperationSession(Operation.PACK, limits, OperationControl.standard());
    operation.begin();
    try (var budget = ResourceBudget.forMutation(limits, CONTEXT);
        var stage =
            new TransformStage(
                List.of(entry("a", "abc"), entry("b", "de")),
                new WorkerSelection.UpTo(2),
                budget,
                CONTEXT,
                7,
                ordinal ->
                    ordinal == 0 ? new TransformStage.Encoding(16, 0, 0, true, encoder) : null)) {
      assertTrue(stage.parallel());
      return assertThrows(ArchiveException.class, () -> stage.next(operation));
    }
  }

  /** A planned source with fixed ASCII bytes. */
  private static PackSources.Entry entry(String name, String content) {
    byte[] bytes = content.getBytes(StandardCharsets.US_ASCII);
    return new PackSources.Entry(
        name,
        name,
        name.getBytes(StandardCharsets.US_ASCII),
        0,
        bytes.length,
        reader -> reader.read(Channels.newChannel(new ByteArrayInputStream(bytes))));
  }

  /** Reads exactly {@code size} ASCII bytes from a borrowed channel. */
  private static String read(ReadableByteChannel channel, int size) throws IOException {
    var bytes = ByteBuffer.allocate(size);
    while (bytes.hasRemaining()) if (channel.read(bytes) < 0) break;
    return new String(bytes.array(), 0, bytes.position(), StandardCharsets.US_ASCII);
  }
}
