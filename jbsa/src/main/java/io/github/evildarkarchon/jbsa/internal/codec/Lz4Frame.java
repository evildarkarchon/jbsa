package io.github.evildarkarchon.jbsa.internal.codec;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Runtime.Provider;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import io.github.evildarkarchon.jbsa.internal.io.ResourceBudget;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Release-pinned streaming LZ4 frame entry points with independently owned, admitted codec state.
 * The versioned-BSA 0x69 profile runs through the {@link Lz4Adapter} its {@link AdmittedCodec}
 * selected, so it never preflights again, and its failures carry that codec's family-layered
 * profile identity.
 *
 * <p>The linked, checksummed, level-9 general profile stays as a qualification-only path, {@link
 * #encodeGeneral}; no archive family packs it.
 *
 * <p>This class must stay free of LWJGL and lz4-java references; the adapters own them.
 */
public final class Lz4Frame {
  /** Native-provider heap bookkeeping per encoder or decoder. */
  public static final long HEAP_BYTES = 4096;

  /** Native-provider memory for one encoder: its compression context and direct windows. */
  public static final long ENCODE_NATIVE_BYTES = 2 * 1024 * 1024 + 3 * 65536;

  /** Native-provider memory for one decoder, bounded by a 4 MiB block plus direct windows. */
  public static final long DECODE_NATIVE_BYTES = 9 * 1024 * 1024 + 3 * 65536;

  private Lz4Frame() {}

  /**
   * Encodes one exact level-12 independent-block versioned-BSA frame on the caller's codec
   * reservation, and binds failures to the admitted codec's profile. Callbacks are borrowed
   * synchronously; all codec state is released on success, failure, or cancellation, and the lease
   * stays the caller's to close.
   *
   * @param admitted the admitted BSA frame codec, whose adapter runs the call
   * @param lease a lease already covering {@code admitted}'s encode cost
   * @throws NullPointerException when {@code lease} is null
   * @throws IllegalArgumentException when {@code admitted} is not the BSA frame codec, or the size
   *     is negative
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
    Lz4Adapter adapter = bsaFrame(admitted);
    if (decodedSize < 0) throw new IllegalArgumentException("Negative decoded size");
    try {
      return adapter.encodeFrame(source, decodedSize, sink, checkpoint, lease, context, true);
    } catch (ArchiveException failure) {
      throw reprofile(failure, admitted.profile());
    }
  }

  /**
   * Creates an incremental decoder after its owner reserves the admitted codec's decode resources.
   * The decoder borrows its positional source and the owner's lease, releases its codec state when
   * closed, and its later content failures retain the admitted codec's profile identity.
   *
   * @param admitted the admitted BSA frame codec, whose adapter runs the decoder
   * @param lease the owner's reservation of {@code admitted}'s decode cost, which outlives the
   *     decoder and is never closed here
   * @throws NullPointerException when {@code lease} is null
   * @throws IllegalArgumentException when {@code admitted} is not the BSA frame codec, or a size is
   *     negative
   */
  public static Decoder decoder(
      AdmittedCodec admitted,
      ByteSource source,
      long storedSize,
      long decodedSize,
      ResourceBudget.Lease lease,
      IoContext context)
      throws ArchiveException {
    Objects.requireNonNull(lease, "lease");
    Lz4Adapter adapter = bsaFrame(admitted);
    if (storedSize < 0 || decodedSize < 0)
      throw new IllegalArgumentException("Negative codec size");
    try {
      return new Reprofiled(
          adapter.frameDecoder(source, storedSize, decodedSize, lease, context),
          admitted.profile());
    } catch (ArchiveException failure) {
      throw reprofile(failure, admitted.profile());
    }
  }

  /**
   * Encodes one linked, checksummed, level-9 general frame through the pinned provider, on the
   * caller's reservation of {@link AdmittedCodec#encodeCost} for the BSA frame under that provider.
   * This is a qualification-only path, so it preflights for itself; its failures carry the
   * provider's own profile identity.
   *
   * @throws NullPointerException when {@code lease} is null
   * @throws IllegalArgumentException when the size is negative
   */
  public static long encodeGeneral(
      ByteSource source,
      long decodedSize,
      ByteSink sink,
      Checkpoint checkpoint,
      ResourceBudget.Lease lease,
      IoContext context)
      throws IOException {
    Objects.requireNonNull(lease, "lease");
    if (decodedSize < 0) throw new IllegalArgumentException("Negative decoded size");
    Provider provider = Lz4Runtime.preflight("lz4-frame", "encode", context);
    return Lz4Adapter.of(provider)
        .encodeFrame(source, decodedSize, sink, checkpoint, lease, context, false);
  }

  /** Returns the adapter the admitted BSA frame codec selected, rejecting any other codec. */
  private static Lz4Adapter bsaFrame(AdmittedCodec admitted) {
    if (admitted.codec() != PayloadCodec.BSA_LZ4_FRAME)
      throw new IllegalArgumentException("The BSA frame needs the admitted BSA frame codec");
    return admitted.adapter();
  }

  /**
   * One content's sequential frame decoder. Its owner reserves the admitted codec's {@link
   * AdmittedCodec#decodeCost} before creating it and closes it exactly once.
   */
  public interface Decoder extends AutoCloseable {
    /**
     * Returns one bounded decoded window, or -1 after validating exact frame consumption.
     *
     * @throws IOException a structured codec, source, or closed-channel failure
     */
    int read(ByteBuffer destination) throws IOException;

    /** Releases the decoder's state; closing is idempotent. */
    @Override
    void close();
  }

  /** Owns one adapter decoder while normalizing its public profile evidence. */
  private static final class Reprofiled implements Decoder {
    private final Decoder delegate;
    private final String profile;

    private Reprofiled(Decoder delegate, String profile) {
      this.delegate = delegate;
      this.profile = profile;
    }

    /** Reads one bounded window and rewrites only the opaque codec-profile value on failure. */
    @Override
    public int read(ByteBuffer destination) throws IOException {
      try {
        return delegate.read(destination);
      } catch (ArchiveException failure) {
        throw reprofile(failure, profile);
      }
    }

    /** Releases the delegated codec state exactly once. */
    @Override
    public void close() {
      delegate.close();
    }
  }

  /**
   * Preserves failure ownership and causes while selecting the family codec-profile identity of the
   * provider that ran, so portable and native evidence stay distinct.
   */
  static ArchiveException reprofile(ArchiveException failure, String profile) {
    List<Diagnostic> diagnostics = new ArrayList<>();
    for (Diagnostic diagnostic : failure.diagnostics()) {
      var values = new TreeMap<>(diagnostic.values());
      values.put("profile", profile);
      diagnostics.add(
          new Diagnostic(
              diagnostic.identifier(),
              diagnostic.severity(),
              diagnostic.operation(),
              diagnostic.phase(),
              diagnostic.location(),
              values,
              diagnostic.explanation()));
    }
    if (failure instanceof ArchiveCancelledException)
      return new ArchiveCancelledException(
          failure.getMessage(),
          failure.primaryFailure(),
          diagnostics,
          failure.artifacts(),
          failure.assessment(),
          failure.secondaryFailures());
    return new ArchiveException(
        failure.getMessage(),
        failure.primaryFailure(),
        diagnostics,
        failure.artifacts(),
        failure.assessment(),
        failure.secondaryFailures());
  }
}
