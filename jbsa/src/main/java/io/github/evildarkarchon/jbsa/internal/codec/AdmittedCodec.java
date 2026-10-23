package io.github.evildarkarchon.jbsa.internal.codec;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.internal.codec.Lz4Runtime.Provider;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import java.util.Objects;

/**
 * One {@link PayloadCodec} admitted for one operation. Admission performs the codec's runtime
 * capability check once and resolves the LZ4 provider; the value then owns every fact that depends
 * on it: the worst-case bound, the heap and native cost of one encode or decode call, and the Codec
 * Profile identity its failures carry. It is the one definition of those facts, so callers cost a
 * codec through the value they admitted instead of restating a formula.
 *
 * <p>Stored and zlib need only the JDK, so admitting them has no side effect: it never loads or
 * pins an LZ4 provider ({@code Lz4LaunchTest}'s zlib-only launch). Their identity is the pinned
 * provider set's, resolved only when {@link #profile()} is asked, which in practice means only on a
 * failure path.
 *
 * <p>This class must stay free of LWJGL and lz4-java references, including in its static
 * initializer: {@link io.github.evildarkarchon.jbsa.internal.io.ResourceBudget} derives its native
 * ceilings from it, so it loads in processes that never touch LZ4.
 */
public final class AdmittedCodec {
  /** The largest raw block one call encodes or decodes: Starfield's qualified dispatch limit. */
  static final long RAW_LZ4_DISPATCH_LIMIT = 16L * 1024 * 1024;

  /** The versioned BSA 0x69 profile auto-flushes one LZ4 block per source window of this size. */
  private static final long BSA_LZ4_BLOCK_BYTES = 65536;

  /** Heap bookkeeping each LZ4 call or decoder holds besides its buffers. */
  private static final long LZ4_BOOKKEEPING_BYTES = 4096;

  /**
   * The native raw encoder's explicit external HC state, which avoids an unaccounted provider
   * allocation, plus the fixed 32-byte allowance the native adapter has always reserved with it.
   */
  private static final long NATIVE_RAW_HC_STATE_BYTES = 524288 + 32;

  /**
   * lz4-java's HC compressor allocates a fresh {@code int[32768]} hash table and {@code
   * short[65536]} chain table on every call; this covers both arrays and their headers.
   */
  private static final long PORTABLE_HC_STATE_BYTES = 2 * 131072 + 4096;

  /**
   * Heap for one portable frame encode: bookkeeping, one input window, one block header, one
   * worst-case block, and the HC tables.
   */
  static final long PORTABLE_FRAME_ENCODE_HEAP_BYTES =
      LZ4_BOOKKEEPING_BYTES
          + PortableLz4.WINDOW
          + 4
          + rawLz4Bound(PortableLz4.WINDOW)
          + PORTABLE_HC_STATE_BYTES;

  /** Heap for one portable frame decoder: bookkeeping plus a compressed and a decoded block. */
  static final long PORTABLE_FRAME_DECODE_HEAP_BYTES =
      LZ4_BOOKKEEPING_BYTES + 2L * PortableLz4.MAX_BLOCK;

  /**
   * The largest native memory one encode call of any codec under any provider can charge. With
   * {@link #MAX_DECODE_NATIVE_BYTES} it is the module fact the budgets' native ceilings derive
   * from.
   */
  public static final long MAX_ENCODE_NATIVE_BYTES = largestNative(true);

  /** The largest native memory one decode call of any codec under any provider can charge. */
  public static final long MAX_DECODE_NATIVE_BYTES = largestNative(false);

  private final PayloadCodec codec;

  /** The resolved LZ4 provider, or null for the JDK-only codecs, which never resolve one. */
  private final Provider provider;

  private AdmittedCodec(PayloadCodec codec, Provider provider) {
    this.codec = codec;
    this.provider = provider;
  }

  /**
   * One call's resource cost.
   *
   * @param heapBytes heap the call holds at its peak
   * @param nativeBytes native memory the call holds at its peak
   */
  public record Cost(long heapBytes, long nativeBytes) {
    /** Rejects negative costs. */
    public Cost {
      if (heapBytes < 0 || nativeBytes < 0) throw new IllegalArgumentException("Negative cost");
    }
  }

  /**
   * Admits one codec for an operation's direction before its first side effect. Stored and zlib
   * admit nothing. Both LZ4 profiles pin the process's provider here: the native LWJGL adapter
   * where it loads, and the portable lz4-java provider everywhere else (JBSA-CODEC-015).
   *
   * @param direction {@code encode} or {@code decode}, recorded in a capability failure
   * @param context the location a capability failure names
   * @throws ArchiveException {@code CAPABILITY codec.unavailable} only when neither LZ4 provider
   *     can be admitted; a BSA frame failure carries the Versioned BSA native profile, since no
   *     provider ran
   * @throws IllegalArgumentException for a direction other than encode or decode
   */
  public static AdmittedCodec admit(PayloadCodec codec, String direction, IoContext context)
      throws ArchiveException {
    Objects.requireNonNull(codec, "codec");
    if (!direction.equals("encode") && !direction.equals("decode"))
      throw new IllegalArgumentException("Codec direction must be encode or decode");
    return switch (codec) {
      case STORED, ZLIB -> new AdmittedCodec(codec, null);
      case BSA_LZ4_FRAME -> new AdmittedCodec(codec, bsaFramePreflight(direction, context));
      case LZ4_RAW -> new AdmittedCodec(codec, Lz4Runtime.preflight("raw-lz4", direction, context));
    };
  }

  /**
   * Pins the shared runtime's provider while binding capability evidence to the Versioned BSA frame
   * profile.
   *
   * @return the provider every later call in this process uses
   */
  private static Provider bsaFramePreflight(String direction, IoContext context)
      throws ArchiveException {
    try {
      return Lz4Runtime.preflight("lz4-frame", direction, context);
    } catch (ArchiveException failure) {
      // No provider ran, so the failure keeps the family's primary (native) profile identity.
      throw Lz4Frame.reprofile(failure, Provider.NATIVE.bsaFrameProfile());
    }
  }

  /**
   * Returns the codec as if admission had resolved {@code provider}, without preflight or pinning.
   * Tests use it to cost and identify both providers in one process; the JDK-only codecs ignore the
   * provider.
   */
  static AdmittedCodec forProvider(PayloadCodec codec, Provider provider) {
    Objects.requireNonNull(provider, "provider");
    return new AdmittedCodec(
        codec, codec == PayloadCodec.STORED || codec == PayloadCodec.ZLIB ? null : provider);
  }

  /** Returns the admitted Payload Codec. */
  public PayloadCodec codec() {
    return codec;
  }

  /** Returns the resolved LZ4 provider, or null for stored and zlib. */
  Provider provider() {
    return provider;
  }

  /**
   * Returns the LZ4 adapter of the provider admission resolved; the LZ4 entries run every call
   * through it, so they never preflight or choose a provider again.
   *
   * @throws IllegalArgumentException for stored and zlib, which resolve no LZ4 provider
   */
  Lz4Adapter adapter() {
    if (provider == null) throw new IllegalArgumentException(codec + " has no LZ4 adapter");
    return Lz4Adapter.of(provider);
  }

  /**
   * Returns the worst-case encoded size of {@code decodedSize} bytes.
   *
   * @throws ArithmeticException when the bound does not fit a signed long
   */
  public long bound(long decodedSize) {
    return switch (codec) {
      case STORED -> decodedSize;
      case ZLIB -> zlibBound(decodedSize);
      case BSA_LZ4_FRAME -> bsaLz4Bound(decodedSize);
      case LZ4_RAW -> rawLz4Bound(decodedSize);
    };
  }

  /** Returns the heap and native cost of one encode call of {@code decodedSize} bytes. */
  public Cost encodeCost(long decodedSize) {
    return encodeCost(codec, provider, decodedSize);
  }

  /** Returns the heap and native cost of one decode call of the given stored and decoded sizes. */
  public Cost decodeCost(long storedSize, long decodedSize) {
    return decodeCost(codec, provider, storedSize, decodedSize);
  }

  /**
   * Returns the largest cost any single decode call can charge. Every codec but raw LZ4 costs the
   * same at any size; raw LZ4's largest call is one block at the dispatch limit.
   */
  public Cost maxDecodeCost() {
    return maxDecodeCost(codec, provider);
  }

  /**
   * Returns the Codec Profile identity this codec's evidence carries. The BSA frame names its
   * Versioned BSA family-layered identity and raw LZ4 its provider's; stored and zlib sit inside
   * the release codec profile, so they name the pinned LZ4 provider set's identity (#89).
   */
  public String profile() {
    return switch (codec) {
      case STORED, ZLIB -> pinnedProfile();
      case BSA_LZ4_FRAME -> provider.bsaFrameProfile();
      case LZ4_RAW -> provider.profile();
    };
  }

  /**
   * Resolves the pinned provider set's identity for a JDK-only codec, only when it is asked for.
   * Successful zlib never asks, so it never touches the LZ4 runtime. If nothing is pinned yet, this
   * pins the set the next LZ4 preflight would have chosen, loading the native adapter only on a
   * host that grants and ships it; {@link Lz4Runtime#selected} never throws, so a zlib failure that
   * asks is itself preserved.
   */
  private static String pinnedProfile() {
    return Lz4Runtime.selected().profile();
  }

  /**
   * Returns one raw LZ4 block's worst-case compressed size: upstream {@code LZ4_compressBound},
   * which lz4-java also uses. This is the one definition every raw-LZ4 buffer and limit derives
   * from.
   *
   * @throws ArithmeticException when the bound does not fit a signed long
   */
  static long rawLz4Bound(long size) {
    return Math.addExact(size, size / 255 + 16);
  }

  /**
   * zlib's conservative fixed-block bound, which covers any default-level source plus its wrapper.
   * It is the one zlib bound for every family (D3).
   */
  private static long zlibBound(long size) {
    return Math.addExact(size, Math.addExact((size >> 3) + (size >> 8) + (size >> 9), 22));
  }

  /**
   * The pinned BSA LZ4 profile auto-flushes 64 KiB chunks without block checksums. Each chunk adds
   * at most one four-byte block header; the 64-byte allowance covers the frame header and end mark.
   */
  private static long bsaLz4Bound(long size) {
    return Math.addExact(
        size, Math.addExact(4L * ((size + BSA_LZ4_BLOCK_BYTES - 1) / BSA_LZ4_BLOCK_BYTES), 64));
  }

  /**
   * Returns one encode call's cost under an explicit provider. Every caller reserves exactly this
   * and lends the lease to the encoder, which never reserves for itself, so the published cost and
   * the charge cannot drift apart (#96).
   *
   * @param provider the provider that runs the call; ignored by stored and zlib
   */
  static Cost encodeCost(PayloadCodec codec, Provider provider, long decodedSize) {
    return switch (codec) {
      case STORED -> new Cost(0, 0);
      case ZLIB -> new Cost(JdkZlib.ENCODE_HEAP_BYTES, JdkZlib.ENCODE_NATIVE_BYTES);
      case BSA_LZ4_FRAME ->
          provider == Provider.NATIVE
              ? new Cost(Lz4Frame.HEAP_BYTES, Lz4Frame.ENCODE_NATIVE_BYTES)
              : new Cost(PORTABLE_FRAME_ENCODE_HEAP_BYTES, 0);
      case LZ4_RAW -> {
        // The whole source block and its worst-case output, plus the HC state, held at once.
        long working = Math.addExact(decodedSize, rawLz4Bound(decodedSize));
        yield provider == Provider.NATIVE
            ? new Cost(LZ4_BOOKKEEPING_BYTES, working + NATIVE_RAW_HC_STATE_BYTES)
            : new Cost(LZ4_BOOKKEEPING_BYTES + working + PORTABLE_HC_STATE_BYTES, 0);
      }
    };
  }

  /**
   * Returns one decode call's cost under an explicit provider. Raw LZ4 holds its whole stored and
   * decoded block in one working set, native under LWJGL and heap under lz4-java; its cursor's
   * decoded copy is the reader's own charge, not part of this cost.
   *
   * @param provider the provider that runs the call; ignored by stored and zlib
   */
  static Cost decodeCost(PayloadCodec codec, Provider provider, long storedSize, long decodedSize) {
    return switch (codec) {
      case STORED -> new Cost(0, 0);
      case ZLIB -> new Cost(JdkZlib.DECODE_HEAP_BYTES, JdkZlib.DECODE_NATIVE_BYTES);
      case BSA_LZ4_FRAME ->
          provider == Provider.NATIVE
              ? new Cost(Lz4Frame.HEAP_BYTES, Lz4Frame.DECODE_NATIVE_BYTES)
              : new Cost(PORTABLE_FRAME_DECODE_HEAP_BYTES, 0);
      case LZ4_RAW -> {
        long working = Math.addExact(Math.addExact(storedSize, decodedSize), 16);
        yield provider == Provider.NATIVE
            ? new Cost(LZ4_BOOKKEEPING_BYTES, working)
            : new Cost(LZ4_BOOKKEEPING_BYTES + working, 0);
      }
    };
  }

  /** Returns the largest single decode call's cost under an explicit provider. */
  private static Cost maxDecodeCost(PayloadCodec codec, Provider provider) {
    return decodeCost(codec, provider, rawLz4Bound(RAW_LZ4_DISPATCH_LIMIT), RAW_LZ4_DISPATCH_LIMIT);
  }

  /** Returns the largest native cost of one call in a direction, over every codec and provider. */
  private static long largestNative(boolean encode) {
    long largest = 0;
    for (PayloadCodec codec : PayloadCodec.values())
      for (Provider provider : Provider.values()) {
        Cost cost =
            encode
                ? encodeCost(codec, provider, RAW_LZ4_DISPATCH_LIMIT)
                : maxDecodeCost(codec, provider);
        largest = Math.max(largest, cost.nativeBytes());
      }
    return largest;
  }
}
