package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.ResourceLimits;
import java.math.BigInteger;
import java.util.Objects;

/**
 * Per-open resource admission; reservations precede allocations and are released by their owners.
 */
public final class ResourceBudget implements AutoCloseable {
  private final ResourceLimits limits;
  private final IoContext context;
  private final long heapCeiling;
  private final long nativeCeiling;
  private final long handleCeiling;
  private long heap;
  private long nativeBytes;
  private long handles;
  private long scratch;
  private long metadataBytes;
  private boolean closed;

  /** Creates bounded owned-input and decoder capacities without changing public semantic limits. */
  public ResourceBudget(ResourceLimits limits, IoContext context) {
    this(
        limits,
        context,
        Math.min(256L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4),
        Math.max(Lz4Frame.DECODE_NATIVE_BYTES, Lz4Raw.DECODE_NATIVE_BYTES),
        1);
  }

  /**
   * Creates bounded mutation admission with room for sixteen paired source/spill handles in
   * addition to staging and publication handles. The shared budget still limits each operation;
   * higher requested worker counts wait for credits instead of raising this internal ceiling.
   */
  public static ResourceBudget forMutation(ResourceLimits limits, IoContext context) {
    return new ResourceBudget(
        limits,
        context,
        Math.min(256L * 1024 * 1024, Runtime.getRuntime().maxMemory() / 4),
        Math.max(Lz4Frame.DECODE_NATIVE_BYTES, Lz4Raw.ENCODE_NATIVE_BYTES),
        36);
  }

  /** Returns immutable semantic limits to operation-owned cleanup reporters. */
  ResourceLimits limits() {
    return limits;
  }

  /**
   * Uses explicit capacities for deterministic admission tests; every ceiling must be nonnegative.
   */
  ResourceBudget(
      ResourceLimits limits,
      IoContext context,
      long heapCeiling,
      long nativeCeiling,
      long handleCeiling) {
    this.limits = Objects.requireNonNull(limits, "limits");
    this.context = Objects.requireNonNull(context, "context");
    nonnegative(heapCeiling);
    nonnegative(nativeCeiling);
    nonnegative(handleCeiling);
    this.heapCeiling = heapCeiling;
    this.nativeCeiling = nativeCeiling;
    this.handleCeiling = handleCeiling;
  }

  /**
   * Atomically reserves all dimensions or leaves every credit unchanged. The caller closes the
   * lease after releasing the corresponding resources; closing the budget makes subsequent closes
   * inert.
   */
  public synchronized Lease reserve(long heap, long nativeBytes, long handles, long scratch)
      throws ArchiveException {
    ensureOpen();
    nonnegative(heap);
    nonnegative(nativeBytes);
    nonnegative(handles);
    nonnegative(scratch);
    long nextScratch =
        semanticTotal(this.scratch, scratch, limits.maxScratchBytes(), "maxScratchBytes");
    if (heap > heapCeiling - this.heap
        || nativeBytes > nativeCeiling - this.nativeBytes
        || handles > handleCeiling - this.handles) {
      throw capacity();
    }
    // Construct the token before committing credits so allocation failure cannot consume capacity.
    Lease lease = new Lease(heap, nativeBytes, handles, scratch);
    this.heap += heap;
    this.nativeBytes += nativeBytes;
    this.handles += handles;
    this.scratch = nextScratch;
    return lease;
  }

  /** Checks an encoded count before a loader narrows it or allocates the eager index. */
  public synchronized void checkEntries(long count) throws ArchiveException {
    ensureOpen();
    nonnegative(count);
    semanticTotal(0, count, limits.maxEntries(), "maxEntries");
  }

  /**
   * Accounts encoded metadata cumulatively and retains conservative parsed-storage credits until
   * close. Failed admission changes neither the semantic count nor available memory.
   */
  public synchronized void metadata(long encodedBytes) throws ArchiveException {
    ensureOpen();
    nonnegative(encodedBytes);
    long next =
        semanticTotal(metadataBytes, encodedBytes, limits.maxMetadataBytes(), "maxMetadataBytes");
    // Encoded names and tables expand into parsed objects; reserve before decoding or allocating.
    reserve(estimatedHeap(encodedBytes, 8), 0, 0, 0);
    metadataBytes = next;
  }

  /**
   * Counts metadata parsed by a transient source index without retaining its already-released heap
   * credits. The invocation's final output metadata is admitted against this same semantic ledger.
   */
  public synchronized void consumedMetadata(long encodedBytes) throws ArchiveException {
    ensureOpen();
    nonnegative(encodedBytes);
    metadataBytes =
        semanticTotal(metadataBytes, encodedBytes, limits.maxMetadataBytes(), "maxMetadataBytes");
  }

  /** Reserves the conservative per-entry object allowance until budget close, before allocation. */
  public synchronized void entryIndex(long count) throws ArchiveException {
    checkEntries(count);
    reserve(estimatedHeap(count, 512), 0, 0, 0);
  }

  /** Ends the budget lifetime; outstanding leases may still close harmlessly and idempotently. */
  @Override
  public synchronized void close() {
    closed = true;
    heap = nativeBytes = handles = scratch = metadataBytes = 0;
  }

  /**
   * Rejects an unrepresentable internal estimate as capacity exhaustion rather than format damage.
   */
  private long estimatedHeap(long count, long width) throws ArchiveException {
    try {
      return Math.multiplyExact(count, width);
    } catch (ArithmeticException cause) {
      throw capacity();
    }
  }

  /**
   * Adds semantic quantities without losing exact diagnostic evidence when signed long overflows.
   */
  private long semanticTotal(long current, long added, long ceiling, String field)
      throws ArchiveException {
    if (added > ceiling - current) {
      throw context.limit(
          field, ceiling, BigInteger.valueOf(current).add(BigInteger.valueOf(added)).toString());
    }
    return current + added;
  }

  /** Internal capacity failures intentionally omit memory and handle implementation details. */
  private ArchiveException capacity() {
    return context.failure(FailureKind.POLICY, "io.resource-capacity", null);
  }

  /** A closed owner cannot start another allocation lifetime. */
  private void ensureOpen() {
    if (closed) throw new IllegalStateException("Resource budget is closed");
  }

  /** Rejects programmer errors before arithmetic or resource state changes. */
  private static void nonnegative(long quantity) {
    if (quantity < 0) throw new IllegalArgumentException("Resource quantity must be nonnegative");
  }

  /** A single reservation token whose credits can be returned once, from any thread. */
  public final class Lease implements AutoCloseable {
    private final long heap;
    private final long nativeBytes;
    private long handles;
    private long scratch;
    private boolean released;

    /** Records the credits admitted atomically by the owning budget. */
    private Lease(long heap, long nativeBytes, long handles, long scratch) {
      this.heap = heap;
      this.nativeBytes = nativeBytes;
      this.handles = handles;
      this.scratch = scratch;
    }

    /**
     * Reserves additional retained scratch before its extent grows, without allocating another
     * token. Failed admission preserves both the lease and shared budget; closed owners cannot
     * grow.
     *
     * @throws ArchiveException if the cumulative scratch ceiling would be exceeded
     */
    public void growScratch(long added) throws ArchiveException {
      synchronized (ResourceBudget.this) {
        ensureOpen();
        if (released) throw new IllegalStateException("Resource lease is released");
        nonnegative(added);
        long next =
            semanticTotal(
                ResourceBudget.this.scratch, added, limits.maxScratchBytes(), "maxScratchBytes");
        // One mutable reservation keeps streaming bookkeeping independent of the number of writes.
        scratch += added;
        ResourceBudget.this.scratch = next;
      }
    }

    /**
     * Returns transient worker handles after their channel, validation, and identity inspection
     * have closed, while retaining scratch and result-slot credits until ordered publication.
     */
    public void releaseHandles(long count) {
      synchronized (ResourceBudget.this) {
        ensureOpen();
        if (released) throw new IllegalStateException("Resource lease is released");
        nonnegative(count);
        if (count > handles) throw new IllegalArgumentException("Too many handles released");
        handles -= count;
        ResourceBudget.this.handles -= count;
      }
    }

    /** Releases credits once, without checked cleanup failures or reopening a closed budget. */
    @Override
    public void close() {
      synchronized (ResourceBudget.this) {
        if (released) return;
        released = true;
        if (closed) return;
        ResourceBudget.this.heap -= heap;
        ResourceBudget.this.nativeBytes -= nativeBytes;
        ResourceBudget.this.handles -= handles;
        ResourceBudget.this.scratch -= scratch;
      }
    }
  }
}
