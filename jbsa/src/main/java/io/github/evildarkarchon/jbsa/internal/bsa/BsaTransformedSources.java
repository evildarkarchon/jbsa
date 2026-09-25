package io.github.evildarkarchon.jbsa.internal.bsa;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;

/** Private BSA payload transforms that the caller consumes in complete Logical Plan Order. */
final class BsaTransformedSources implements AutoCloseable {
  private static final int WINDOW_BYTES = 65536;
  private final List<Source> sources;
  private final int version;
  private final ResourceBudget budget;
  private final ResourceLimits limits;
  private final IoContext context;
  private final OrderedWorkerRunner<SpillBuffer> runner;
  private int nextSubmission;
  private int nextConsumption;

  /** One preflight-planned source and its already selected family compression choice. */
  record Source(PackSources.Entry entry, boolean compressed) {}

  /** Starts an operation-owned pool only after names, source sizes, and plan order are complete. */
  BsaTransformedSources(
      List<Source> sources,
      int version,
      long workers,
      ResourceBudget budget,
      ResourceLimits limits,
      IoContext context) {
    this.sources = List.copyOf(sources);
    this.version = version;
    this.budget = budget;
    this.limits = limits;
    this.context = context;
    runner =
        new OrderedWorkerRunner<>(
            new WorkerSelection.UpTo(workers), sources.size(), budget, context);
  }

  /**
   * Admits only the bounded worker window and transfers the next private encoded spool to the
   * coordinator. Caller cancellation is sampled during waits, never on a worker thread.
   */
  OrderedWorkerRunner.Outcome<SpillBuffer> next(OperationSession operation) throws IOException {
    operation.checkpoint(OperationPhase.PROCESSING, OptionalLong.of(nextConsumption));
    while (nextSubmission < sources.size()
        && nextSubmission - nextConsumption < runner.capacity()) {
      int ordinal = nextSubmission;
      Source source = sources.get(ordinal);
      IoContext processing = processing(ordinal);
      long bound = transformedBound(source, processing);
      long codecHeap =
          !source.compressed()
              ? WINDOW_BYTES
              : version == 0x69 ? Lz4Frame.HEAP_BYTES : JdkZlib.ENCODE_HEAP_BYTES;
      long nativeBytes =
          !source.compressed()
              ? 0
              : version == 0x69 ? Lz4Frame.ENCODE_NATIVE_BYTES : JdkZlib.ENCODE_NATIVE_BYTES;
      // The second handle covers a loose source or archive parent while the first covers spill.
      var cost = new OrderedWorkerRunner.Cost(WINDOW_BYTES + codecHeap, nativeBytes, 2, bound);
      if (!runner.trySubmit(
          ordinal,
          cost,
          (checkpoint, lease) -> stage(source, processing, bound, checkpoint, lease))) break;
      nextSubmission++;
    }
    var outcome =
        runner.takeNext(
            () ->
                operation.checkpoint(OperationPhase.PROCESSING, OptionalLong.of(nextConsumption)));
    nextConsumption++;
    if (outcome.failure() != null) {
      try (outcome) {
        Throwable failure = outcome.failure();
        if (failure instanceof VirtualMachineError || failure instanceof ThreadDeath)
          throw (Error) failure;
        if (failure instanceof ArchiveException archive) throw archive;
        if (failure instanceof IOException io)
          throw processing(outcome.ordinal())
              .failure(FailureKind.SOURCE, "operation.source-io", io);
        throw processing(outcome.ordinal())
            .failure(FailureKind.INTERNAL, "operation.internal-failure", failure);
      }
    }
    if (outcome.skipped()) {
      outcome.close();
      operation.checkpoint(OperationPhase.PROCESSING, OptionalLong.of(outcome.ordinal()));
      throw processing(outcome.ordinal())
          .failure(FailureKind.INTERNAL, "operation.internal-failure", null);
    }
    return outcome;
  }

  /** Joins all admitted workers and releases results the coordinator did not take. */
  @Override
  public void close() throws IOException {
    runner.close();
  }

  /** Encodes one source once into private scratch under its worst-case admission lease. */
  private SpillBuffer stage(
      Source source,
      IoContext processing,
      long bound,
      OrderedWorkerRunner.Checkpoint checkpoint,
      ResourceBudget.Lease lease)
      throws IOException {
    SpillBuffer result =
        SpillBuffer.openPrecharged(
            Path.of(System.getProperty("java.io.tmpdir")), budget, processing, lease, bound);
    try {
      PackSources.consume(
          source.entry(),
          input -> {
            if (!source.compressed()) {
              copyStored(input, source.entry().size(), result, checkpoint, processing);
            } else if (version == 0x69) {
              long[] read = {0};
              BsaLz4Frame.encodePrecharged(
                  (offset, bytes) -> {
                    if (offset != read[0])
                      throw processing.failure(
                          FailureKind.INTERNAL, "operation.internal-failure", null);
                    readExact(input, bytes, checkpoint, processing);
                    read[0] += bytes.position();
                  },
                  source.entry().size(),
                  (offset, bytes) -> result.write(offset, bytes),
                  checkpoint::check,
                  budget,
                  lease,
                  processing);
              requireEnd(input, checkpoint, processing);
            } else {
              JdkZlib.encode(
                  input,
                  source.entry().size(),
                  (offset, bytes) -> result.write(offset, bytes),
                  checkpoint::check,
                  processing);
            }
          },
          processing);
      result.seal();
      return result;
    } catch (IOException failure) {
      var failures = new FailureRetention(limits, Operation.PACK);
      failures.accept(
          failure instanceof ArchiveException archive
              ? archive
              : processing.failure(FailureKind.SOURCE, "operation.source-io", failure));
      try {
        result.close();
      } catch (ArchiveException cleanup) {
        failures.accept(cleanup);
      }
      throw failures.finish(List.of());
    } catch (RuntimeException | Error failure) {
      try {
        result.close();
      } catch (ArchiveException cleanup) {
        // Unchecked VM/programmer failures have no structured operation outcome to own cleanup.
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  /** Exact stored transfer, including a probe after the caller's declared source length. */
  private static void copyStored(
      ReadableByteChannel input,
      long size,
      SpillBuffer result,
      OrderedWorkerRunner.Checkpoint checkpoint,
      IoContext processing)
      throws IOException {
    ByteBuffer bytes = ByteBuffer.allocate(WINDOW_BYTES);
    for (long position = 0; position < size; ) {
      checkpoint.check();
      bytes.clear().limit((int) Math.min(bytes.capacity(), size - position));
      readExact(input, bytes, checkpoint, processing);
      int count = bytes.position();
      result.write(position, bytes.flip());
      position += count;
    }
    requireEnd(input, checkpoint, processing);
  }

  /** Fills one bounded codec window without letting a stalled generated channel spin forever. */
  private static void readExact(
      ReadableByteChannel input,
      ByteBuffer bytes,
      OrderedWorkerRunner.Checkpoint checkpoint,
      IoContext processing)
      throws IOException {
    int idle = 0;
    while (bytes.hasRemaining()) {
      checkpoint.check();
      int count = input.read(bytes);
      if (count < 0) throw processing.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      if (count == 0) {
        if (++idle > 16) throw processing.failure(FailureKind.SOURCE, "io.no-progress", null);
      } else idle = 0;
    }
  }

  /** Probes beyond an exact source so compression cannot hide regenerated excess bytes. */
  private static void requireEnd(
      ReadableByteChannel input, OrderedWorkerRunner.Checkpoint checkpoint, IoContext processing)
      throws IOException {
    ByteBuffer probe = ByteBuffer.allocate(1);
    int idle = 0;
    while (true) {
      checkpoint.check();
      int count = input.read(probe);
      if (count < 0) return;
      if (count > 0) throw processing.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      if (++idle > 16) throw processing.failure(FailureKind.SOURCE, "io.no-progress", null);
    }
  }

  /** Returns a conservative encoded extent before the task is allowed to allocate scratch. */
  private long transformedBound(Source source, IoContext processing) throws ArchiveException {
    long size = source.entry().size();
    if (!source.compressed()) return size;
    try {
      if (version == 0x69) {
        // The pinned BSA profile auto-flushes 64 KiB chunks without block checksums. Each chunk
        // adds at most one four-byte block header; the allowance covers the frame ends.
        return Math.addExact(size, Math.addExact(4L * ((size + 65535) / 65536), 64));
      }
      // zlib's conservative fixed-block bound covers any default-level source and wrapper.
      return Math.addExact(size, Math.addExact((size >> 3) + (size >> 8) + (size >> 9), 22));
    } catch (ArithmeticException failure) {
      throw processing.failure(FailureKind.POLICY, "io.resource-capacity", failure);
    }
  }

  /** Attaches source and codec failures to the task's stable Logical Plan Order ordinal. */
  private IoContext processing(int ordinal) {
    return new IoContext(
        context.path(), Operation.PACK, OperationPhase.PROCESSING, OptionalLong.of(ordinal));
  }
}
