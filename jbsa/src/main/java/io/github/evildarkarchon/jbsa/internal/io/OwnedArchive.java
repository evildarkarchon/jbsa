package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.AsynchronousCloseException;
import java.nio.channels.ClosedByInterruptException;
import java.nio.channels.ClosedChannelException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;

/** Parent-owned input and eager stored-entry index; no storage seam is exported through JPMS. */
public final class OwnedArchive implements OpenArchive {
  private final Object lifetime = new Object();
  private final ArchiveInput input;
  private final ResourceLimits limits;
  private final IoContext context;
  private final ResourceBudget budget;
  private final ArchiveInspection inspection;
  private final List<StoredEntry> entries;
  private final Set<Content> children = Collections.newSetFromMap(new IdentityHashMap<>());
  private boolean closed;

  /** Adopts the validated index and the sole backing handle after the loader completes. */
  private OwnedArchive(
      ArchiveInput input,
      ResourceLimits limits,
      IoContext context,
      ResourceBudget budget,
      ArchiveInspection inspection,
      List<StoredEntry> entries) {
    this.input = input;
    this.limits = limits;
    this.context = context;
    this.budget = budget;
    this.inspection = inspection;
    this.entries = List.copyOf(entries);
  }

  /** Internal format integration seam; loaders validate metadata without reading payloads. */
  @FunctionalInterface
  public interface IndexLoader {
    /** Returns detached structural evidence after populating the complete bounded index. */
    ArchiveInspection load(IndexBuilder builder) throws IOException;
  }

  /** Opens exactly one input and closes it if eager index construction fails. */
  public static OpenArchive load(
      Path path, ResourceLimits limits, Operation operation, IndexLoader loader)
      throws IOException {
    return load(ArchiveInput.open(path, operation), limits, IoContext.of(path, operation), loader);
  }

  /** Adopts an input for internal format integration and deterministic fault-injection tests. */
  static OpenArchive load(
      ArchiveInput input, ResourceLimits limits, IoContext context, IndexLoader loader)
      throws IOException {
    ResourceBudget budget = new ResourceBudget(limits, context);
    try {
      budget.reserve(0, 0, 1, 0);
      IndexBuilder builder = new IndexBuilder(input, budget, context);
      ArchiveInspection inspection = Objects.requireNonNull(loader.load(builder));
      if (builder.expected != builder.entries.size()
          || inspection.metadata().entryCount() != builder.expected) {
        throw context.failure(FailureKind.FORMAT, "io.index-count-mismatch", null);
      }
      return new OwnedArchive(input, limits, context, budget, inspection, builder.entries);
    } catch (IOException | RuntimeException | Error cause) {
      try {
        input.close();
      } catch (IOException cleanup) {
        cause.addSuppressed(cleanup);
      }
      budget.close();
      throw cause;
    }
  }

  /**
   * Eager index admission; format loaders remain internal and cannot publish an incomplete index.
   */
  public static final class IndexBuilder {
    private final ArchiveInput input;
    private final ResourceBudget budget;
    private final IoContext context;
    private final List<StoredEntry> entries = new ArrayList<>();
    private long expected = -1;

    /** Keeps the handle private so a loader cannot transfer or close parent ownership. */
    private IndexBuilder(ArchiveInput input, ResourceBudget budget, IoContext context) {
      this.input = input;
      this.budget = budget;
      this.context = context;
    }

    /** Checks the encoded count before a format loader allocates or constructs entry metadata. */
    public void declareEntries(long count) throws ArchiveException {
      if (expected != -1) throw new IllegalStateException("Entry count already declared");
      ExactIo.multiply(count, 1, context);
      budget.entryIndex(count);
      if (count > Integer.MAX_VALUE)
        throw context.failure(FailureKind.POLICY, "io.index-capacity", null);
      expected = count;
    }

    /** Reads one encoded header/table window, charging metadata and heap before allocation. */
    public ByteBuffer readMetadata(long offset, int length) throws IOException {
      ExactIo.end(offset, length, input.size(), context);
      if (length > ExactIo.WINDOW_BYTES)
        throw context.failure(FailureKind.POLICY, "io.resource-capacity", null);
      budget.metadata(length);
      ByteBuffer result = ByteBuffer.allocate(length).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      input.readExact(offset, result);
      return result.flip();
    }

    /** Retains an exact encoded name only after validating its full span and allocation credit. */
    public WireName readName(long offset, long length) throws IOException {
      ExactIo.end(offset, length, input.size(), context);
      budget.metadata(length);
      if (length > Integer.MAX_VALUE)
        throw context.failure(FailureKind.POLICY, "io.resource-capacity", null);
      byte[] bytes = new byte[Math.toIntExact(length)];
      input.readExact(offset, ByteBuffer.wrap(bytes));
      return new WireName(bytes);
    }

    /** Validates a stored span without touching payload bytes, retaining archive-order metadata. */
    public void addStored(EntryMetadata metadata, long offset) throws ArchiveException {
      if (expected < 0
          || entries.size() >= expected
          || metadata.ordinal() != entries.size()
          || metadata.storedSize() != metadata.decodedSize()) {
        throw context.failure(FailureKind.FORMAT, "io.index-record-mismatch", null);
      }
      ExactIo.end(offset, metadata.storedSize(), input.size(), context);
      entries.add(new StoredEntry(metadata, offset));
    }
  }

  /** A stored range uses no per-entry payload buffer or decoded cache. */
  private record StoredEntry(EntryMetadata metadata, long offset) {}

  /** Returns detached structural evidence, including after the owned input has closed. */
  @Override
  public ArchiveInspection inspection() {
    return inspection;
  }

  /** Returns the validated long entry count without requiring a live backing handle. */
  @Override
  public long entryCount() {
    return entries.size();
  }

  /**
   * Retrieves a parent-bound capability while leaving its detached metadata independently usable.
   */
  @Override
  public ArchiveEntry entry(long ordinal) {
    synchronized (lifetime) {
      if (closed) throw new IllegalStateException("Archive is closed");
      if (ordinal < 0 || ordinal >= entries.size())
        throw new IndexOutOfBoundsException("Entry ordinal");
      StoredEntry stored = entries.get(Math.toIntExact(ordinal));
      return new ArchiveEntry() {
        /** Returns detached entry facts without extending the parent handle's lifetime. */
        @Override
        public EntryMetadata metadata() {
          return stored.metadata();
        }

        /** Atomically admits a fresh child against parent close. */
        @Override
        public EntryContent openContent() throws IOException {
          synchronized (lifetime) {
            if (closed) throw new ClosedChannelException();
            if (stored.metadata().decodedSize() > limits.maxDecodedBytes()) {
              throw contentContext(ordinal)
                  .limit(
                      "maxDecodedBytes",
                      limits.maxDecodedBytes(),
                      Long.toString(stored.metadata().decodedSize()));
            }
            ResourceBudget.Lease credit = budget.reserve(512, 0, 0, 0);
            Content content = new Content(stored, credit);
            children.add(content);
            return content;
          }
        }
      };
    }
  }

  /** Creates the consumer-thread operation location for payload and per-channel policy failures. */
  private IoContext contentContext(long ordinal) {
    return new IoContext(
        context.path(),
        Operation.READ_CONTENT,
        OperationPhase.PROCESSING,
        OptionalLong.of(ordinal));
  }

  /**
   * Invalidates children before releasing the handle; concurrent closers observe completed cleanup.
   */
  @Override
  public void close() throws IOException {
    synchronized (lifetime) {
      if (closed) return;
      closed = true;
      for (Content child : children) {
        child.open = false;
        child.credit.close();
      }
      children.clear();
      try {
        input.close();
      } finally {
        budget.close();
      }
    }
  }

  /** Independent sequential cursor; the lifetime lock is never held across an input read. */
  private final class Content implements EntryContent {
    private final StoredEntry stored;
    private final ResourceBudget.Lease credit;
    private final Object reads = new Object();
    private boolean open = true;
    private long position;
    private volatile ArchiveAssessment assessment;

    private Content(StoredEntry stored, ResourceBudget.Lease credit) {
      this.stored = stored;
      this.credit = credit;
    }

    /** Returns terminal EOF evidence when established, retaining it after any subsequent close. */
    @Override
    public Optional<ArchiveAssessment> assessment() {
      return Optional.ofNullable(assessment);
    }

    /** Observes child validity under the same lock that linearizes parent and child close. */
    @Override
    public boolean isOpen() {
      synchronized (lifetime) {
        return open;
      }
    }

    /**
     * Serializes reads of this cursor, while allowing independent sibling reads and parent close.
     */
    @Override
    public int read(ByteBuffer destination) throws IOException {
      Objects.requireNonNull(destination);
      synchronized (reads) {
        synchronized (lifetime) {
          if (!open || closed) throw new ClosedChannelException();
        }
        try {
          if (Thread.currentThread().isInterrupted()) {
            // Even an empty stored entry must not hide a caller-interrupted shared-input lifetime.
            throw new ClosedByInterruptException();
          }
          if (destination.isReadOnly()) throw new java.nio.ReadOnlyBufferException();
          int count =
              (int)
                  Math.min(
                      Math.min(destination.remaining(), ExactIo.WINDOW_BYTES),
                      stored.metadata().decodedSize() - position);
          ByteBuffer window = destination.slice();
          window.limit(count);
          if (count > 0) input.readExact(Math.addExact(stored.offset(), position), window);
          synchronized (lifetime) {
            // Completion and close share a linearization point even if the native read succeeded.
            if (!open || closed) throw new AsynchronousCloseException();
            if (!destination.hasRemaining()) return 0;
            if (count == 0) {
              if (assessment == null)
                assessment =
                    new ArchiveAssessment(
                        inspection.assessment().disposition(),
                        new ValidationExtent.Payloads(Set.of(stored.metadata().ordinal())),
                        inspection.assessment().diagnostics());
              return -1;
            }
            position += count;
            destination.position(destination.position() + count);
            return count;
          }
        } catch (ClosedByInterruptException cause) {
          try {
            OwnedArchive.this.close();
          } catch (IOException cleanup) {
            cause.addSuppressed(cleanup);
          }
          throw cause;
        } catch (ClosedChannelException cause) {
          synchronized (lifetime) {
            if (!input.isOpen()) OwnedArchive.this.close();
          }
          throw new AsynchronousCloseException();
        } catch (ArchiveException cause) {
          synchronized (lifetime) {
            // Failed provider completions must honor the same close decision as successful reads.
            if (!open || closed) throw new AsynchronousCloseException();
            close();
            throw contentContext(stored.metadata().ordinal())
                .failure(
                    cause.kind(), cause.diagnostics().getFirst().identifier(), cause.getCause());
          }
        }
      }
    }

    /**
     * Releases this cursor alone; it never closes the shared input or creates an EOF assessment.
     */
    @Override
    public void close() {
      synchronized (lifetime) {
        open = false;
        children.remove(this);
        credit.close();
      }
    }
  }
}
