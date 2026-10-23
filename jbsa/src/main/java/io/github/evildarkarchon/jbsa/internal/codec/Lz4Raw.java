package io.github.evildarkarchon.jbsa.internal.codec;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.IOException;
import java.util.Objects;

/**
 * Unsplittable raw-block profile with checked admission and independently owned codec buffers. Each
 * call runs through the {@link Lz4Adapter} its {@link AdmittedCodec} selected: native buffers for
 * the LWJGL adapter, heap buffers for the portable {@link PortableLz4} adapter. Admission already
 * pinned that provider, so no call preflights again.
 *
 * <p>This class must stay free of LWJGL and lz4-java references; the adapters own them.
 */
public final class Lz4Raw {
  private static final long MAX_INPUT = 0x7E000000L;
  private static final long DISPATCH_LIMIT = AdmittedCodec.RAW_LZ4_DISPATCH_LIMIT;

  private Lz4Raw() {}

  /**
   * Encodes one admitted raw HC block at the shared level 9 without fallback or splitting. The
   * caller has reserved the admitted codec's encode cost (the complete source, worst-case output
   * and HC state) before the call; callbacks remain caller-owned.
   *
   * @param admitted the admitted raw LZ4 codec, whose adapter runs the call
   * @param lease the caller's reservation of that cost, borrowed and never closed here
   * @throws NullPointerException when {@code lease} is null
   * @throws IllegalArgumentException when {@code admitted} is not the raw LZ4 codec
   */
  public static long encode(
      AdmittedCodec admitted,
      ByteSource source,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context)
      throws IOException {
    Objects.requireNonNull(lease, "lease");
    return encode(admitted, source, decodedSize, sink, checkpoint, lease, context, 9);
  }

  /**
   * Encodes one admitted raw HC block at the family-qualified compression level, on the caller's
   * reservation of its encode cost.
   *
   * @param admitted the admitted raw LZ4 codec, whose adapter runs the call
   * @param lease the caller's reservation of that cost, borrowed and never closed here
   * @throws NullPointerException when {@code lease} is null
   * @throws IllegalArgumentException when {@code admitted} is not the raw LZ4 codec, or the level
   *     is outside 1 through 12
   * @throws ArchiveException POLICY {@code codec.size-limit} or {@code codec.dispatch-limit} before
   *     any effect
   */
  public static long encode(
      AdmittedCodec admitted,
      ByteSource source,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context,
      int compressionLevel)
      throws IOException {
    Objects.requireNonNull(lease, "lease");
    Lz4Adapter adapter = raw(admitted);
    if (compressionLevel < 1 || compressionLevel > 12)
      throw new IllegalArgumentException("Raw LZ4 HC level must be between 1 and 12");
    checkSize(decodedSize, DISPATCH_LIMIT, context);
    return adapter.encodeRaw(
        source, decodedSize, sink, checkpoint, lease, context, compressionLevel);
  }

  /**
   * Decodes one complete raw block on the caller's reservation of its entire memory cost, the
   * admitted codec's decode cost. Source and sink are borrowed; the caller supplies a wire-bounded
   * block and owns destination publication.
   *
   * <p>The caller has already refused the block's sizes through {@link #admitDecode} (by way of
   * {@link AdmittedCodec#checkDecodeSize}). That check has to run before the caller reserves this
   * call's cost, so that an oversized block reports its codec limit rather than capacity, and it is
   * the block's only size check: this method does not repeat it.
   *
   * @param admitted the admitted raw LZ4 codec, whose adapter runs the call
   * @param lease the caller's reservation of that cost, borrowed and never closed here
   * @throws NullPointerException when {@code lease} is null
   * @throws IllegalArgumentException when {@code admitted} is not the raw LZ4 codec
   * @throws ArchiveException a structured codec failure
   */
  public static long decode(
      AdmittedCodec admitted,
      ByteSource source,
      long storedSize,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context)
      throws IOException {
    Objects.requireNonNull(lease, "lease");
    Lz4Adapter adapter = raw(admitted);
    return adapter.decodeRaw(source, storedSize, decodedSize, sink, checkpoint, lease, context);
  }

  /** Returns the adapter the admitted raw LZ4 codec selected, rejecting any other codec. */
  private static Lz4Adapter raw(AdmittedCodec admitted) {
    if (admitted.codec() != PayloadCodec.LZ4_RAW)
      throw new IllegalArgumentException("A raw block needs the admitted raw LZ4 codec");
    return admitted.adapter();
  }

  /**
   * Rejects a raw block above the provider or qualified dispatch limit before its caller reserves
   * the encode cost. A refused block must report {@code codec.dispatch-limit}, not the capacity
   * failure its oversized cost would otherwise raise first.
   *
   * @throws ArchiveException POLICY {@code codec.size-limit} or {@code codec.dispatch-limit}
   */
  public static void admitEncode(long decodedSize, IoContext context) throws ArchiveException {
    checkSize(decodedSize, DISPATCH_LIMIT, context);
  }

  /** Rejects raw-block wire and dispatch sizes before any decoder allocation or output effect. */
  public static void admitDecode(long storedSize, long decodedSize, IoContext context)
      throws ArchiveException {
    // Incompressible blocks grow by the upstream compressBound allowance.
    checkSize(storedSize, AdmittedCodec.rawLz4Bound(DISPATCH_LIMIT), context);
    checkSize(decodedSize, DISPATCH_LIMIT, context);
  }

  /**
   * Rejects unsplittable provider and qualified-dispatch limits before narrowing or output effects.
   */
  private static void checkSize(long size, long dispatchLimit, IoContext context)
      throws ArchiveException {
    if (size < 0 || size > MAX_INPUT)
      throw context.failure(FailureKind.POLICY, "codec.size-limit", null);
    if (size > dispatchLimit)
      throw context.failure(FailureKind.POLICY, "codec.dispatch-limit", null);
  }
}
