package io.github.evildarkarchon.jbsa.internal.io;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.internal.io.Lz4Runtime.Provider;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.ClosedChannelException;
import net.jpountz.lz4.LZ4Compressor;
import net.jpountz.lz4.LZ4Exception;
import net.jpountz.lz4.LZ4Factory;
import net.jpountz.lz4.LZ4SafeDecompressor;
import net.jpountz.xxhash.StreamingXXHash32;
import net.jpountz.xxhash.XXHash32;
import net.jpountz.xxhash.XXHashFactory;

/**
 * The portable LZ4 provider: lz4-java's pure-Java safe implementation (JBSA-CODEC-014).
 *
 * <p>Only {@link LZ4Factory#safeInstance()} and {@link XXHashFactory#safeInstance()} are reached,
 * so neither {@code sun.misc.Unsafe} nor lz4-java's bundled JNI libraries are ever used. lz4-java's
 * own frame streams are deliberately not used: they create an {@code
 * XXHashFactory.fastestInstance()} internally for content checksums, which may load JNI. This class
 * therefore writes and parses the LZ4 frame container itself and hands each block to the safe block
 * codec.
 *
 * <p>Every method allocates only heap, reserved against the caller's budget before allocation, and
 * keeps all codec state per call or per decoder, so concurrent calls never share mutable state.
 */
final class PortableLz4 {
  /** The frame magic number, little-endian on the wire. */
  private static final int MAGIC = 0x184D2204;

  /** The frame window the encoder reads and flushes as one block, matching the native profile. */
  private static final int WINDOW = 65536;

  /** The largest block any valid frame may declare (block-maximum identifier 7). */
  private static final int MAX_BLOCK = 4 * 1024 * 1024;

  /**
   * lz4-java's HC compressor allocates a fresh {@code int[32768]} hash table and {@code
   * short[65536]} chain table on every call; this covers both arrays and their headers.
   */
  private static final long HC_STATE_BYTES = 2 * 131072 + 4096;

  /** Heap for one frame encode: bookkeeping, one input window, and one worst-case block. */
  static final long FRAME_ENCODE_HEAP_BYTES =
      4096 + WINDOW + 4 + WINDOW + WINDOW / 255 + 16 + HC_STATE_BYTES;

  /** Heap for one frame decoder: bookkeeping plus a compressed and a decoded maximum block. */
  static final long FRAME_DECODE_HEAP_BYTES = 4096 + 2L * MAX_BLOCK;

  private PortableLz4() {}

  /**
   * The lz4-java entry points, initialized on first portable use so a native-only process never
   * loads lz4-java at all.
   */
  private static final class Safe {
    private static final LZ4Factory LZ4 = LZ4Factory.safeInstance();
    private static final LZ4SafeDecompressor DECOMPRESSOR = LZ4.safeDecompressor();
    private static final XXHashFactory XXHASH = XXHashFactory.safeInstance();
    private static final XXHash32 HASH32 = XXHASH.hash32();
  }

  /**
   * Initializes the safe factories so provider preflight can report a missing lz4-java before any
   * side effect.
   *
   * @throws LinkageError if lz4-java is absent or cannot initialize
   */
  static void admit() {
    // Touching one field runs the holder's static initializer exactly once.
    java.util.Objects.requireNonNull(Safe.DECOMPRESSOR);
  }

  /** Returns the heap one raw encode of {@code decodedSize} bytes reserves. */
  static long rawEncodeHeapBytes(long decodedSize) {
    return 4096 + decodedSize + rawBound(decodedSize) + HC_STATE_BYTES;
  }

  /** Returns the heap one raw decode reserves for its whole stored and decoded block. */
  static long rawDecodeHeapBytes(long storedSize, long decodedSize) {
    return 4096 + storedSize + decodedSize + 16;
  }

  /** Returns the upstream {@code LZ4_compressBound}, which lz4-java also uses. */
  private static long rawBound(long size) {
    return size + size / 255 + 16;
  }

  /**
   * Encodes one admitted raw HC block without splitting. The caller has already checked the
   * dispatch limits; the whole block, its worst-case output and the HC tables are reserved first.
   */
  static long rawEncode(
      JdkZlib.ByteSource source,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context,
      int compressionLevel)
      throws IOException {
    caller(checkpoint::check, context);
    try (var lease = budget.reserve(rawEncodeHeapBytes(decodedSize), 0, 0, 0)) {
      int size = (int) decodedSize;
      byte[] input = new byte[size];
      byte[] output = new byte[(int) rawBound(decodedSize)];
      ByteBuffer window = ByteBuffer.wrap(input);
      caller(() -> source.read(0, window), context);
      if (window.hasRemaining())
        throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      caller(checkpoint::check, context);
      int count;
      try {
        LZ4Compressor compressor = Safe.LZ4.highCompressor(compressionLevel);
        count = compressor.compress(input, 0, size, output, 0, output.length);
      } catch (RuntimeException | LinkageError | AssertionError cause) {
        throw failure(
            FailureKind.INTERNAL,
            "codec.provider-fault",
            "raw-lz4",
            "encode",
            decodedSize,
            -1,
            cause,
            context);
      }
      if (count <= 0)
        throw failure(
            FailureKind.INTERNAL,
            "codec.compression-failed",
            "raw-lz4",
            "encode",
            decodedSize,
            -1,
            null,
            context);
      caller(checkpoint::check, context);
      caller(() -> sink.write(0, ByteBuffer.wrap(output, 0, count)), context);
      return count;
    }
  }

  /**
   * Decodes one complete raw block through the safe decompressor after reserving both buffers.
   * Output that would exceed {@code decodedSize} is rejected by the decompressor itself, so nothing
   * beyond the declared size is ever produced.
   */
  static long rawDecode(
      JdkZlib.ByteSource source,
      long storedSize,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    caller(checkpoint::check, context);
    try (var lease = budget.reserve(rawDecodeHeapBytes(storedSize, decodedSize), 0, 0, 0)) {
      byte[] input = new byte[(int) storedSize];
      byte[] output = new byte[(int) decodedSize];
      ByteBuffer window = ByteBuffer.wrap(input);
      caller(() -> source.read(0, window), context);
      if (window.hasRemaining())
        throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      caller(checkpoint::check, context);
      int count;
      try {
        count = Safe.DECOMPRESSOR.decompress(input, 0, input.length, output, 0, output.length);
      } catch (LZ4Exception malformed) {
        throw failure(
            FailureKind.FORMAT,
            "codec.invalid-data",
            "raw-lz4",
            "decode",
            decodedSize,
            -1,
            malformed,
            context);
      } catch (RuntimeException | LinkageError | AssertionError cause) {
        throw failure(
            FailureKind.INTERNAL,
            "codec.provider-fault",
            "raw-lz4",
            "decode",
            decodedSize,
            -1,
            cause,
            context);
      }
      if (count != decodedSize)
        throw failure(
            FailureKind.FORMAT,
            "codec.size-mismatch",
            "raw-lz4",
            "decode",
            decodedSize,
            count,
            null,
            context);
      caller(checkpoint::check, context);
      caller(() -> sink.write(0, ByteBuffer.wrap(output, 0, count)), context);
      return count;
    }
  }

  /**
   * Encodes one LZ4 frame in bounded windows, flushing each window as one independent block.
   *
   * <p>The versioned-BSA profile (JBSA-BSA-010) uses level 12, a 4 MiB block maximum, and no
   * checksums or content size. The general profile uses level 9, a 64 KiB block maximum, a content
   * checksum and a content size. Unlike the native general profile, its blocks are independent:
   * lz4-java's block compressor has no dictionary input, and JBSA-CODEC-013 claims no
   * cross-provider byte identity.
   *
   * @param admission a worker lease that already covers {@link #FRAME_ENCODE_HEAP_BYTES}, or null
   *     to reserve it here
   */
  static long encodeFrame(
      JdkZlib.ByteSource source,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      ResourceBudget.Lease admission,
      IoContext context,
      boolean bsaProfile)
      throws IOException {
    try (var lease = admission == null ? budget.reserve(FRAME_ENCODE_HEAP_BYTES, 0, 0, 0) : null) {
      LZ4Compressor compressor = Safe.LZ4.highCompressor(bsaProfile ? 12 : 9);
      StreamingXXHash32 contentHash = bsaProfile ? null : Safe.XXHASH.newStreamingHash32(0);
      byte[] input = new byte[WINDOW];
      byte[] block = new byte[4 + compressor.maxCompressedLength(WINDOW)];
      caller(checkpoint::check, context);
      long written = emit(sink, 0, frameHeader(bsaProfile, decodedSize), context);
      for (long read = 0; read < decodedSize; ) {
        caller(checkpoint::check, context);
        int count = (int) Math.min(WINDOW, decodedSize - read);
        ByteBuffer window = ByteBuffer.wrap(input, 0, count);
        long offset = read;
        caller(() -> source.read(offset, window), context);
        if (window.hasRemaining())
          throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
        int encoded = compressor.compress(input, 0, count, block, 4, block.length - 4);
        int header;
        int length;
        // LZ4F stores a block raw, flagged by the high bit, whenever compression does not shrink
        // it.
        if (encoded <= 0 || encoded >= count) {
          System.arraycopy(input, 0, block, 4, count);
          header = count | 0x80000000;
          length = count;
        } else {
          header = encoded;
          length = encoded;
        }
        ByteBuffer.wrap(block, 0, 4).order(ByteOrder.LITTLE_ENDIAN).putInt(header);
        if (contentHash != null) contentHash.update(input, 0, count);
        written = emit(sink, written, ByteBuffer.wrap(block, 0, 4 + length), context);
        read += count;
      }
      caller(checkpoint::check, context);
      ByteBuffer end =
          ByteBuffer.allocate(contentHash == null ? 4 : 8).order(ByteOrder.LITTLE_ENDIAN);
      end.putInt(0);
      if (contentHash != null) end.putInt(contentHash.getValue());
      return emit(sink, written, end.flip(), context);
    } catch (RuntimeException | LinkageError | AssertionError cause) {
      throw failure(
          FailureKind.INTERNAL,
          "codec.provider-fault",
          "lz4-frame",
          "encode",
          decodedSize,
          -1,
          cause,
          context);
    }
  }

  /** Builds the magic number and frame descriptor, including its XXH32-derived header checksum. */
  private static ByteBuffer frameHeader(boolean bsaProfile, long decodedSize) {
    ByteBuffer header = ByteBuffer.allocate(4 + 2 + 8 + 1).order(ByteOrder.LITTLE_ENDIAN);
    header.putInt(MAGIC);
    // Version 01 and independent blocks; the general profile adds content size and checksum.
    header.put((byte) (bsaProfile ? 0x60 : 0x6C));
    // Block-maximum identifier 7 (4 MiB) for BSA, 4 (64 KiB) for the general profile.
    header.put((byte) (bsaProfile ? 0x70 : 0x40));
    if (!bsaProfile) header.putLong(decodedSize);
    int descriptorLength = header.position() - 4;
    int checksum = Safe.HASH32.hash(header.array(), 4, descriptorLength, 0);
    header.put((byte) ((checksum >>> 8) & 0xFF));
    return header.flip();
  }

  /**
   * Creates an incremental frame decoder. The caller has reserved {@link #FRAME_DECODE_HEAP_BYTES};
   * the decoder borrows its positional source and holds only heap state.
   */
  static FrameDecoder decoder(
      JdkZlib.ByteSource source, long storedSize, long decodedSize, IoContext context) {
    return new FrameDecoder(source, storedSize, decodedSize, context);
  }

  /**
   * Decodes one ordinary frame to a positional sink, requiring exact compressed and decoded
   * lengths. Decoded bytes beyond the declared size are detected without being published.
   */
  static long decodeFrame(
      JdkZlib.ByteSource source,
      long storedSize,
      long decodedSize,
      JdkZlib.ByteSink sink,
      JdkZlib.Checkpoint checkpoint,
      ResourceBudget budget,
      IoContext context)
      throws IOException {
    try (var lease = budget.reserve(FRAME_DECODE_HEAP_BYTES + WINDOW, 0, 0, 0);
        var decoder = decoder(source, storedSize, decodedSize, context)) {
      ByteBuffer window = ByteBuffer.allocate(WINDOW);
      long produced = 0;
      while (true) {
        caller(checkpoint::check, context);
        window.clear();
        int count = decoder.read(window);
        if (count < 0) return produced;
        produced = emit(sink, produced, window.flip(), context);
      }
    }
  }

  /**
   * One frame's sequential, heap-only decoder. Reads are serialized so a lazy content channel may
   * move between consumer threads; nothing is shared with any other decoder.
   */
  static final class FrameDecoder implements Lz4Frame.Decoder {
    private final JdkZlib.ByteSource source;
    private final long storedSize, decodedSize;
    private final IoContext context;
    private final byte[] word = new byte[8];
    private byte[] compressed, decoded;
    private int blockMax;
    private boolean blockChecksum, contentSizePresent, started, finished, closed;
    private long declaredContentSize;
    private StreamingXXHash32 contentHash;
    private long supplied, decodedTotal;
    private int pendingOffset, pending;

    private FrameDecoder(
        JdkZlib.ByteSource source, long storedSize, long decodedSize, IoContext context) {
      this.source = source;
      this.storedSize = storedSize;
      this.decodedSize = decodedSize;
      this.context = context;
    }

    /** Returns one bounded decoded window and validates exact frame consumption at terminal EOF. */
    @Override
    public synchronized int read(ByteBuffer destination) throws IOException {
      if (closed) throw new ClosedChannelException();
      if (!destination.hasRemaining()) return 0;
      if (finished) return -1;
      try {
        if (!started) {
          readHeader();
          started = true;
        }
        while (pending == 0) {
          if (!nextBlock()) {
            finish();
            finished = true;
            return -1;
          }
        }
        int count = Math.min(destination.remaining(), pending);
        destination.put(decoded, pendingOffset, count);
        pendingOffset += count;
        pending -= count;
        return count;
      } catch (ArchiveException failure) {
        throw failure;
      } catch (RuntimeException | LinkageError | AssertionError cause) {
        throw failure(
            FailureKind.INTERNAL,
            "codec.provider-fault",
            "lz4-frame",
            "decode",
            decodedSize,
            decodedTotal,
            cause,
            context);
      }
    }

    /** Releases the heap buffers; later reads report a closed channel. */
    @Override
    public synchronized void close() {
      closed = true;
      compressed = null;
      decoded = null;
    }

    /**
     * Parses and checks the frame descriptor, then sizes the block buffers to its declared maximum.
     * Skippable and legacy frames cannot represent an archive payload and are rejected.
     */
    private void readHeader() throws IOException {
      if (readInt() != MAGIC) throw invalid("codec.invalid-data", null);
      readExact(word, 0, 2);
      int flg = word[0] & 0xFF;
      int bd = word[1] & 0xFF;
      // Version must be 01; FLG bit 1 and the BD bits outside the block-maximum field are reserved.
      int blockId = (bd >>> 4) & 7;
      if ((flg >>> 6) != 1 || (flg & 0x02) != 0 || (bd & 0x8F) != 0 || blockId < 4)
        throw invalid("codec.invalid-data", null);
      boolean independent = (flg & 0x20) != 0;
      blockChecksum = (flg & 0x10) != 0;
      contentSizePresent = (flg & 0x08) != 0;
      boolean contentChecksum = (flg & 0x04) != 0;
      boolean dictionaryId = (flg & 0x01) != 0;
      int optional = (contentSizePresent ? 8 : 0) + (dictionaryId ? 4 : 0);
      byte[] descriptor = new byte[2 + optional];
      descriptor[0] = (byte) flg;
      descriptor[1] = (byte) bd;
      if (optional > 0) readExact(descriptor, 2, optional);
      readExact(word, 0, 1);
      int checksum = Safe.HASH32.hash(descriptor, 0, descriptor.length, 0);
      if ((byte) ((checksum >>> 8) & 0xFF) != word[0]) throw invalid("codec.invalid-data", null);
      if (!independent) {
        // A linked-block frame is valid LZ4, but lz4-java's safe decompressor cannot see the
        // previous block's 64 KiB window, so this provider cannot decode it at all.
        throw unsupported();
      }
      if (contentSizePresent)
        declaredContentSize =
            ByteBuffer.wrap(descriptor, 2, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
      if (contentChecksum) contentHash = Safe.XXHASH.newStreamingHash32(0);
      blockMax = 1 << (2 * blockId + 8);
      compressed = new byte[blockMax];
      decoded = new byte[blockMax];
    }

    /** Decodes the next block into the pending window; returns false at the frame's end mark. */
    private boolean nextBlock() throws IOException {
      int header = readInt();
      // Only the exact zero header is the end mark. 0x80000000 is an empty uncompressed block,
      // which the reference LZ4 1.10.0 frame decoder (the native provider) accepts as a no-op, so
      // rejecting it here would make the two providers disagree about the same archive.
      if (header == 0) return false;
      boolean raw = (header & 0x80000000) != 0;
      int size = header & 0x7FFFFFFF;
      if (size > blockMax) throw invalid("codec.invalid-data", null);
      byte[] target = raw ? decoded : compressed;
      readExact(target, 0, size);
      if (blockChecksum && readInt() != Safe.HASH32.hash(target, 0, size, 0))
        throw invalid("codec.invalid-data", null);
      int count;
      if (raw) count = size;
      else {
        try {
          count = Safe.DECOMPRESSOR.decompress(compressed, 0, size, decoded, 0, blockMax);
        } catch (LZ4Exception malformed) {
          throw invalid("codec.invalid-data", malformed);
        }
      }
      if (count > decodedSize - decodedTotal)
        throw failure(
            FailureKind.FORMAT,
            "codec.size-mismatch",
            "lz4-frame",
            "decode",
            decodedSize,
            decodedTotal + count,
            null,
            context);
      if (contentHash != null) contentHash.update(decoded, 0, count);
      decodedTotal += count;
      pendingOffset = 0;
      pending = count;
      return true;
    }

    /** Verifies the optional content checksum and size, then exact decoded and stored lengths. */
    private void finish() throws IOException {
      if (contentHash != null && readInt() != contentHash.getValue())
        throw invalid("codec.invalid-data", null);
      if (contentSizePresent && declaredContentSize != decodedTotal)
        throw invalid("codec.invalid-data", null);
      if (decodedTotal != decodedSize || supplied != storedSize)
        throw failure(
            FailureKind.FORMAT,
            "codec.size-mismatch",
            "lz4-frame",
            "decode",
            decodedSize,
            decodedTotal,
            null,
            context);
    }

    /** Reads one little-endian 32-bit wire field. */
    private int readInt() throws IOException {
      readExact(word, 0, 4);
      return ByteBuffer.wrap(word, 0, 4).order(ByteOrder.LITTLE_ENDIAN).getInt();
    }

    /**
     * Reads exactly {@code length} stored bytes; a frame that needs bytes past the record is
     * truncated and therefore invalid, never a reason to read beyond the stored extent.
     */
    private void readExact(byte[] target, int offset, int length) throws IOException {
      if (length > storedSize - supplied) throw invalid("codec.invalid-data", null);
      ByteBuffer window = ByteBuffer.wrap(target, offset, length);
      long position = supplied;
      caller(() -> source.read(position, window), context);
      if (window.hasRemaining())
        throw context.failure(FailureKind.SOURCE, "source.length-mismatch", null);
      supplied += length;
    }

    /** Builds a FORMAT failure carrying the decoded prefix length as the actual size. */
    private ArchiveException invalid(String identifier, Throwable cause) {
      return failure(
          FailureKind.FORMAT,
          identifier,
          "lz4-frame",
          "decode",
          decodedSize,
          decodedTotal,
          cause,
          context);
    }

    /**
     * Reports a well-formed frame this provider cannot decode. It is a provider capability limit,
     * not corrupt data, so it is never retried through the native provider (JBSA-CODEC-015).
     */
    private ArchiveException unsupported() {
      ArchiveException base =
          failure(
              FailureKind.CAPABILITY,
              "codec.unavailable",
              "lz4-frame",
              "decode",
              decodedSize,
              -1,
              null,
              context);
      Diagnostic diagnostic = base.diagnostics().getFirst();
      var values = new java.util.TreeMap<>(diagnostic.values());
      values.put("capabilityCause", "dependent-blocks");
      return new ArchiveException(
          base.getMessage(),
          base.primaryFailure(),
          java.util.List.of(
              new Diagnostic(
                  diagnostic.identifier(),
                  diagnostic.severity(),
                  diagnostic.operation(),
                  diagnostic.phase(),
                  diagnostic.location(),
                  values,
                  diagnostic.explanation())),
          java.util.List.of(),
          java.util.Optional.empty(),
          java.util.List.of());
    }
  }

  /** Publishes one encoded or decoded window at a checked, wide positional offset. */
  private static long emit(JdkZlib.ByteSink sink, long offset, ByteBuffer bytes, IoContext context)
      throws IOException {
    int count = bytes.remaining();
    if (count > 0) caller(() -> sink.write(offset, bytes), context);
    return Math.addExact(offset, count);
  }

  /** Builds a normalized failure bound to the portable profile identity. */
  private static ArchiveException failure(
      FailureKind kind,
      String identifier,
      String codec,
      String direction,
      long expected,
      long actual,
      Throwable cause,
      IoContext context) {
    return Lz4Runtime.failure(
        Provider.PORTABLE, context, kind, identifier, codec, direction, expected, actual, cause);
  }

  /** Distinguishes borrowed callback faults from codec provider failures. */
  private static void caller(Callback callback, IoContext context) throws IOException {
    try {
      callback.run();
    } catch (RuntimeException | AssertionError cause) {
      throw context.failure(FailureKind.INTERNAL, "operation.internal-failure", cause);
    }
  }

  /** A synchronous borrowed operation callback whose resources remain caller-owned. */
  @FunctionalInterface
  private interface Callback {
    /** Executes one callback without extending its lifetime. */
    void run() throws IOException;
  }
}
