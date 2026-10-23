package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.Artifact;
import io.github.evildarkarchon.jbsa.ArtifactState;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.OperationPhase;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.ClosedChannelException;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.List;
import java.util.Objects;

/**
 * Operation-scoped positional scratch storage with a fixed heap working window and at most one
 * spill-file handle. Instances are worker-confined; replay uses bounded positional transfers.
 */
public final class SpillBuffer implements AutoCloseable {
  private static final int MEMORY_BYTES = ExactIo.WINDOW_BYTES;
  private final ResourceBudget budget;
  private final IoContext context;
  private final Path scratchParent;
  private final ResourceBudget.Lease memoryLease;
  private final boolean borrowedLease;
  private final long reservedScratch;
  private ByteBuffer memory;
  private FileChannel channel;
  private Path scratchPath;
  private ResourceBudget.Lease handleLease;
  private long size;
  private boolean sealed;
  private boolean closed;

  /** Owns the heap credit acquired before allocating the fixed working window. */
  private SpillBuffer(Path scratchParent, ResourceBudget budget, IoContext context)
      throws IOException {
    this(scratchParent, budget, context, null, -1);
  }

  /** Uses a caller-owned reservation when a worker's entire result cost was admitted first. */
  private SpillBuffer(
      Path scratchParent,
      ResourceBudget budget,
      IoContext context,
      ResourceBudget.Lease precharged,
      long reservedScratch)
      throws IOException {
    this.scratchParent =
        Objects.requireNonNull(scratchParent, "scratchParent").toAbsolutePath().normalize();
    this.budget = Objects.requireNonNull(budget, "budget");
    this.context = Objects.requireNonNull(context, "context");
    this.borrowedLease = precharged != null;
    this.reservedScratch = reservedScratch;
    memoryLease = borrowedLease ? precharged : budget.reserve(MEMORY_BYTES, 0, 0, 0);
    try {
      memory = ByteBuffer.allocate(MEMORY_BYTES);
    } catch (RuntimeException | Error cause) {
      if (!borrowedLease) memoryLease.close();
      throw cause;
    }
  }

  /**
   * Opens scratch under a preflight-validated parent outside the destination tree. No file is
   * created until the fixed heap threshold is exceeded; the caller retains the shared budget.
   *
   * @throws ArchiveException if working-memory admission fails before allocation
   */
  public static SpillBuffer open(Path scratchParent, ResourceBudget budget, IoContext context)
      throws IOException {
    return new SpillBuffer(scratchParent, budget, context);
  }

  /**
   * Borrows a worker reservation containing the fixed heap window, one possible spill handle, and
   * the complete declared result extent. The reservation owner closes it after this buffer closes.
   */
  public static SpillBuffer openPrecharged(
      Path scratchParent,
      ResourceBudget budget,
      IoContext context,
      ResourceBudget.Lease reservation,
      long reservedScratch)
      throws IOException {
    Objects.requireNonNull(reservation, "reservation");
    if (reservedScratch < 0) throw new IllegalArgumentException("Negative scratch reservation");
    return new SpillBuffer(scratchParent, budget, context, reservation, reservedScratch);
  }

  /**
   * Holds the possible spill handle before parallel source admission so later coordinator growth
   * cannot be starved by already-admitted private worker results.
   */
  public void reserveSpillHandle() throws ArchiveException {
    if (closed) throw new IllegalStateException("Scratch is closed");
    if (!borrowedLease && handleLease == null) handleLease = budget.reserve(0, 0, 1, 0);
  }

  /**
   * Appends or backpatches all remaining bytes; gaps and overflowing spans are rejected before
   * admission. Only this worker may write, and sealing permanently disables further writes.
   * Filesystem failure aborts and cleans this owner; scratch-limit rejection preserves its bytes.
   */
  public void write(long offset, ByteBuffer source) throws IOException {
    ensureOpen();
    if (sealed) throw scratchContext().roleFailure("io.sealed-scratch", null);
    long end = checkedEnd(offset, source.remaining(), Long.MAX_VALUE);
    if (offset > size) throw scratchContext().roleFailure("io.invalid-output-span", null);
    if (borrowedLease) {
      if (end > reservedScratch)
        throw scratchContext().failure(FailureKind.POLICY, "io.resource-capacity", null);
    } else memoryLease.growScratch(Math.max(size, end) - size);
    try {
      if (end > MEMORY_BYTES && channel == null) spill();
      if (channel == null) {
        memory.duplicate().position(Math.toIntExact(offset)).put(source);
      } else {
        ExactIo.write(channel, offset, source, scratchContext());
      }
    } catch (IOException cause) {
      throw abort(cause);
    }
    size = Math.max(size, end);
  }

  /** Replays an exact bounded range into the caller's remaining buffer. */
  public void read(long offset, ByteBuffer destination) throws IOException {
    ensureOpen();
    long end = checkedEnd(offset, destination.remaining(), size);
    if (channel == null) {
      destination.put(
          memory.duplicate().position(Math.toIntExact(offset)).limit(Math.toIntExact(end)));
    } else {
      try {
        ExactIo.read(channel, size, offset, destination, scratchContext());
      } catch (IOException cause) {
        // Scratch is owned output even when a replay operation happens to be reading it.
        throw abort(scratchContext().ioFailure(cause));
      }
    }
  }

  /** Returns the logical extent as a long, including zero for empty scratch. */
  public long size() {
    return size;
  }

  /** Fixes the representation for repeated replay; this does not force filesystem durability. */
  public void seal() throws IOException {
    ensureOpen();
    sealed = true;
  }

  /**
   * Closes the handle before deleting scratch, then returns all resource credits exactly once. A
   * cleanup failure reports every exact residual path and transfers its ownership to the caller.
   */
  @Override
  public void close() throws ArchiveException {
    if (closed) return;
    closed = true;
    FailureRetention failures = new FailureRetention(budget.limits(), context.operation());
    List<Artifact> artifacts = List.of();
    try {
      if (channel != null) {
        try {
          channel.close();
        } catch (IOException cause) {
          failures.accept(cleanupFailure(cause));
        }
      }
      if (scratchPath != null) {
        try {
          Files.deleteIfExists(scratchPath);
        } catch (IOException cause) {
          artifacts =
              List.of(
                  new Artifact(
                      scratchPath, context.ordinal().orElse(0), ArtifactState.RESIDUAL_STAGING));
          failures.accept(cleanupFailure(cause));
        }
      }
    } finally {
      // Residual reporting transfers ownership, so no credit or later close may retain that path.
      memory = null;
      if (!borrowedLease) memoryLease.close();
      if (handleLease != null) handleLease.close();
    }
    if (failures.failed()) throw failures.finish(artifacts);
  }

  /** Acquires the only scratch handle before creating a file and copies the bounded heap prefix. */
  private void spill() throws IOException {
    if (!borrowedLease && handleLease == null) handleLease = budget.reserve(0, 0, 1, 0);
    try {
      scratchPath = Files.createTempFile(scratchParent, ".jbsa-spill-", ".tmp");
      channel =
          FileChannel.open(
              scratchPath,
              StandardOpenOption.READ,
              StandardOpenOption.WRITE,
              LinkOption.NOFOLLOW_LINKS);
      ExactIo.write(channel, 0, memory.duplicate().limit(Math.toIntExact(size)), scratchContext());
    } catch (IOException cause) {
      throw scratchContext().structure(cause);
    }
  }

  /**
   * Locates scratch diagnostics at the exact owned path, as a destination whatever side the
   * caller's context is on: scratch is data the operation creates, even while it reads it back.
   */
  private IoContext scratchContext() {
    return IoContext.destination(
            scratchPath == null ? scratchParent : scratchPath, context.operation())
        .at(context.phase(), context.ordinal());
  }

  /**
   * Cleanup errors retain provider causes while recording the semantic cleanup phase. Closing a
   * channel or deleting a file raises only provider faults, and this primitive's close throws only
   * structured failures, so it creates the role failure itself; the raw tripwire backs that.
   */
  private ArchiveException cleanupFailure(IOException cause) {
    return scratchContext().at(OperationPhase.CLEANUP).ioFailure(cause);
  }

  /**
   * Failed transfers cannot be replayed safely; settle scratch now and keep cleanup failures
   * secondary so try-with-resources never hides residual paths in suppressed exceptions.
   */
  private ArchiveException abort(IOException cause) {
    ArchiveException primary =
        cause instanceof ArchiveException failure ? failure : scratchContext().ioFailure(cause);
    FailureRetention failures = new FailureRetention(budget.limits(), context.operation());
    failures.accept(primary);
    try {
      close();
    } catch (ArchiveException cleanup) {
      failures.accept(cleanup);
    }
    return failures.finish(List.of());
  }

  /** Classifies invalid owned-scratch ranges as destination failures rather than archive damage. */
  private long checkedEnd(long offset, long length, long extent) throws ArchiveException {
    try {
      return ExactIo.end(offset, length, extent, context);
    } catch (ArchiveException cause) {
      throw scratchContext().roleFailure("io.invalid-output-span", cause);
    }
  }

  /** Keeps use-after-close independent of whether this representation ever reached disk. */
  private void ensureOpen() throws ClosedChannelException {
    if (closed) throw new ClosedChannelException();
  }
}
