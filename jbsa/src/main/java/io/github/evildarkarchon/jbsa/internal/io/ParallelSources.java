package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ReadableByteChannel;
import java.nio.file.Path;
import java.util.List;
import java.util.OptionalLong;
import java.util.function.IntFunction;

/**
 * Bounded source prefetch for pack stabilization. Workers retain private raw and optional encoded
 * spools; sharing decisions, split assignment, and archive writes remain on the caller thread.
 */
public final class ParallelSources implements AutoCloseable {
  private static final int WINDOW_BYTES = 65536;
  private final List<PackSources.Entry> sources;
  private final ResourceBudget budget;
  private final IoContext context;
  private final long firstOrdinal;
  private final IntFunction<Encoding> encodings;
  private final OrderedWorkerRunner<PrivateResult> runner;
  private int nextSubmission;
  private int nextConsumption;
  private boolean closed;

  /**
   * Captures a complete Logical Plan Order and an operation-start worker snapshot. An explicit
   * one-worker request keeps the established direct source path.
   */
  public ParallelSources(
      List<PackSources.Entry> sources,
      WorkerSelection.UpTo selection,
      ResourceBudget budget,
      IoContext context) {
    this(sources, selection, budget, context, 0, ignored -> null);
  }

  /** Preserves the operation-wide ordinal when a writer processes one split part at a time. */
  public ParallelSources(
      List<PackSources.Entry> sources,
      WorkerSelection.UpTo selection,
      ResourceBudget budget,
      IoContext context,
      long firstOrdinal) {
    this(sources, selection, budget, context, firstOrdinal, ignored -> null);
  }

  /** Configures optional independent transforms without changing ordered source consumption. */
  public ParallelSources(
      List<PackSources.Entry> sources,
      WorkerSelection.UpTo selection,
      ResourceBudget budget,
      IoContext context,
      long firstOrdinal,
      IntFunction<Encoding> encodings) {
    this.sources = List.copyOf(sources);
    this.budget = budget;
    this.context = context;
    this.firstOrdinal = firstOrdinal;
    this.encodings = encodings;
    long requested = selection.workers();
    long plannedBytes = 0;
    for (PackSources.Entry source : sources) {
      // Include per-entry wrapper and record slack when many tiny zlib streams dominate bytes.
      if (plannedBytes > Long.MAX_VALUE - 64
          || source.size() > Long.MAX_VALUE - plannedBytes - 64) {
        plannedBytes = Long.MAX_VALUE;
        break;
      }
      plannedBytes += source.size() + 64;
    }
    runner =
        requested <= 1
                || sources.size() <= 1
                || !WorkerLimits.hasParallelScratchHeadroom(
                    plannedBytes, budget.limits().maxScratchBytes())
            ? null
            : new OrderedWorkerRunner<>(
                new WorkerSelection.UpTo(requested), sources.size(), budget, context);
  }

  /** Reports whether this operation uses multiple workers for source stabilization. */
  public boolean parallel() {
    return runner != null;
  }

  /** Complete worst-case transformed result cost and its worker-only encoder. */
  public record Encoding(long outputBound, long heapBytes, long nativeBytes, Encoder encoder) {
    /** Rejects missing or negative transform costs before work can be admitted. */
    public Encoding {
      if (outputBound < 0 || heapBytes < 0 || nativeBytes < 0)
        throw new IllegalArgumentException("Negative transform cost");
      java.util.Objects.requireNonNull(encoder, "encoder");
    }
  }

  /** Encodes one independent raw source into a private result under a worker stop checkpoint. */
  @FunctionalInterface
  public interface Encoder {
    /** Writes encoded bytes without choosing sharing ownership or archive output positions. */
    void encode(
        ReadableByteChannel raw,
        long decodedSize,
        SpillBuffer encoded,
        OrderedWorkerRunner.Checkpoint checkpoint,
        IoContext processing)
        throws IOException;
  }

  /** Returns the next private raw result, or null for the direct single-worker source path. */
  public Input next(OperationSession operation) throws IOException {
    if (closed) throw new IllegalStateException("Source pipeline is closed");
    if (nextConsumption >= sources.size()) throw new IllegalStateException("No source remains");
    if (runner == null) {
      nextConsumption++;
      return null;
    }
    operation.checkpoint(
        OperationPhase.PROCESSING, OptionalLong.of(firstOrdinal + nextConsumption));
    // A full or credit-starved window is consumed before admission is retried. This prevents a
    // slow first ordinal from allowing unbounded queued and completed later source results.
    while (nextSubmission < sources.size()
        && nextSubmission - nextConsumption < runner.capacity()) {
      int ordinal = nextSubmission;
      PackSources.Entry source = sources.get(ordinal);
      IoContext processing = processing(ordinal);
      Encoding encoding = encodings.apply(ordinal);
      long scratch =
          encoding == null ? source.size() : Math.addExact(source.size(), encoding.outputBound());
      OrderedWorkerRunner.Cost cost =
          new OrderedWorkerRunner.Cost(
              2L * WINDOW_BYTES + (encoding == null ? 0 : WINDOW_BYTES + encoding.heapBytes()),
              encoding == null ? 0 : encoding.nativeBytes(),
              encoding == null ? 2 : 3,
              scratch);
      boolean submitted =
          runner.trySubmit(
              ordinal,
              cost,
              (checkpoint, lease) -> readSource(source, processing, encoding, checkpoint, lease));
      if (!submitted) break;
      nextSubmission++;
    }
    OrderedWorkerRunner.Outcome<PrivateResult> outcome =
        runner.takeNext(
            () ->
                operation.checkpoint(
                    OperationPhase.PROCESSING, OptionalLong.of(firstOrdinal + nextConsumption)));
    nextConsumption++;
    if (outcome.failure() != null) {
      try (outcome) {
        Throwable failure = outcome.failure();
        if (failure instanceof IOException io) throw io;
        if (failure instanceof Error fatal) throw fatal;
        throw processing(outcome.ordinal())
            .failure(FailureKind.INTERNAL, "operation.internal-failure", failure);
      }
    }
    if (outcome.skipped()) {
      outcome.close();
      operation.checkpoint(
          OperationPhase.PROCESSING, OptionalLong.of(firstOrdinal + outcome.ordinal()));
      throw processing(outcome.ordinal())
          .failure(FailureKind.CANCELLED, "operation.cancelled", null);
    }
    return new Input(outcome.result(), outcome);
  }

  /** Joins every admitted worker and closes unconsumed private results before budget settlement. */
  @Override
  public void close() throws IOException {
    if (closed) return;
    closed = true;
    if (runner != null) runner.close();
  }

  /** Returns a processing location whose ordinal is independent of worker completion order. */
  private IoContext processing(int ordinal) {
    return new IoContext(
        context.path(),
        Operation.PACK,
        OperationPhase.PROCESSING,
        OptionalLong.of(firstOrdinal + ordinal));
  }

  /**
   * Reads one declared source and optionally transforms it into fully precharged private spools.
   */
  private PrivateResult readSource(
      PackSources.Entry source,
      IoContext processing,
      Encoding encoding,
      OrderedWorkerRunner.Checkpoint checkpoint,
      ResourceBudget.Lease lease)
      throws IOException {
    SpillBuffer raw =
        SpillBuffer.openPrecharged(
            Path.of(System.getProperty("java.io.tmpdir")),
            budget,
            processing,
            lease,
            source.size());
    SpillBuffer encoded = null;
    try {
      PackSources.consume(
          source,
          input -> {
            ByteBuffer window = ByteBuffer.allocate(WINDOW_BYTES);
            long position = 0;
            int idle = 0;
            while (true) {
              checkpoint.check();
              long remaining = source.size() - position;
              window.clear().limit(remaining >= WINDOW_BYTES ? WINDOW_BYTES : (int) remaining + 1);
              int count = input.read(window);
              if (count < 0) break;
              if (count == 0) {
                if (++idle > 16)
                  throw processing.failure(FailureKind.SOURCE, "io.no-progress", null);
                continue;
              }
              idle = 0;
              if (count > source.size() - position)
                throw processing.failure(FailureKind.SOURCE, "source.length-mismatch", null);
              raw.write(position, window.flip());
              position += count;
            }
            if (position != source.size())
              throw processing.failure(FailureKind.SOURCE, "source.length-mismatch", null);
          },
          processing);
      raw.seal();
      if (encoding != null) {
        encoded =
            SpillBuffer.openPrecharged(
                Path.of(System.getProperty("java.io.tmpdir")),
                budget,
                processing,
                lease,
                encoding.outputBound());
        try (ReadableByteChannel input = channel(raw)) {
          encoding.encoder().encode(input, source.size(), encoded, checkpoint, processing);
        }
        encoded.seal();
      }
      return new PrivateResult(raw, encoded, budget.limits());
    } catch (IOException failure) {
      var failures = new FailureRetention(budget.limits(), Operation.PACK);
      failures.accept(
          failure instanceof ArchiveException archive
              ? archive
              : processing.failure(FailureKind.SOURCE, "operation.source-io", failure));
      if (encoded != null) {
        try {
          encoded.close();
        } catch (ArchiveException cleanup) {
          failures.accept(cleanup);
        }
      }
      try {
        raw.close();
      } catch (ArchiveException cleanup) {
        failures.accept(cleanup);
      }
      throw failures.finish(List.of());
    } catch (RuntimeException | Error failure) {
      // Even an unexpected caller/provider failure cannot strand private spool ownership.
      if (encoded != null) {
        try {
          encoded.close();
        } catch (ArchiveException cleanup) {
          failure.addSuppressed(cleanup);
        }
      }
      try {
        raw.close();
      } catch (ArchiveException cleanup) {
        failure.addSuppressed(cleanup);
      }
      throw failure;
    }
  }

  /** Owns both staged representations until the coordinator closes the ordered result. */
  private record PrivateResult(SpillBuffer raw, SpillBuffer encoded, ResourceLimits limits)
      implements AutoCloseable {
    /** Settles both private spools even if the first cleanup fails. */
    @Override
    public void close() throws IOException {
      var failures = new FailureRetention(limits, Operation.PACK);
      if (encoded != null) {
        try {
          encoded.close();
        } catch (ArchiveException cleanup) {
          failures.accept(cleanup);
        }
      }
      try {
        raw.close();
      } catch (ArchiveException cleanup) {
        failures.accept(cleanup);
      }
      if (failures.failed()) throw failures.finish(List.of());
    }
  }

  /** Borrows a worker-private staged source until the coordinator has copied or encoded it. */
  public static final class Input implements AutoCloseable {
    private final PrivateResult result;
    private final OrderedWorkerRunner.Outcome<PrivateResult> outcome;

    /** Retains the runner's result-slot and resource credits through ordered consumption. */
    private Input(PrivateResult result, OrderedWorkerRunner.Outcome<PrivateResult> outcome) {
      this.result = result;
      this.outcome = outcome;
    }

    /** Exposes a sequential view of the private spool without transferring its ownership. */
    public ReadableByteChannel channel() {
      return ParallelSources.channel(result.raw());
    }

    /** Reports whether a worker also finished the independent transform for this source. */
    public boolean hasEncoded() {
      return result.encoded() != null;
    }

    /** Exposes the private transformed bytes for ordered coordinator copy into archive scratch. */
    public ReadableByteChannel encodedChannel() {
      if (!hasEncoded()) throw new IllegalStateException("This source was not transformed");
      return ParallelSources.channel(result.encoded());
    }

    /** Returns the exact transformed extent after the worker has sealed its private result. */
    public long encodedSize() {
      if (!hasEncoded()) throw new IllegalStateException("This source was not transformed");
      return result.encoded().size();
    }

    /** Releases the private spools and their result-slot credits after coordinator consumption. */
    @Override
    public void close() throws IOException {
      outcome.close();
    }
  }

  /** Opens a borrowed sequential view without transferring private spool ownership. */
  private static ReadableByteChannel channel(SpillBuffer spool) {
    return new ReadableByteChannel() {
      private long position;

      /** Reads a bounded source window at an absolute position. */
      @Override
      public int read(ByteBuffer bytes) throws IOException {
        if (position == spool.size()) return -1;
        int count = (int) Math.min(bytes.remaining(), spool.size() - position);
        ByteBuffer window = bytes.slice();
        window.limit(count);
        spool.read(position, window);
        bytes.position(bytes.position() + count);
        position += count;
        return count;
      }

      /** The parent input controls the spool lifetime. */
      @Override
      public boolean isOpen() {
        return true;
      }

      /** The enclosing Input closes the private spool after ordered consumption. */
      @Override
      public void close() {
        // The channel borrows the spool; the enclosing result owns cleanup.
      }
    };
  }
}
