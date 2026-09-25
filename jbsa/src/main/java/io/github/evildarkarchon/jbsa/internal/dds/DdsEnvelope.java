package io.github.evildarkarchon.jbsa.internal.dds;

import io.github.evildarkarchon.jbsa.ArchiveException;
import io.github.evildarkarchon.jbsa.DdsTarget;
import io.github.evildarkarchon.jbsa.FailureKind;
import io.github.evildarkarchon.jbsa.internal.io.IoContext;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.List;

/** Checked DDS envelopes and opaque, dimensions-derived payload partitions. */
public final class DdsEnvelope {
  private DdsEnvelope() {}

  /** Span relative to the normalized payload, with inclusive mip bounds. */
  public record Chunk(long offset, long size, int startMip, int endMip) {}

  /** Validated input metadata; BGR24 conversion inserts 255 after each three source bytes. */
  public record Analysis(
      int width,
      int height,
      int mipCount,
      int dxgiFormat,
      boolean cubemap,
      int tileMode,
      int headerSize,
      boolean normalizeBgr24,
      long payloadSize,
      List<Chunk> chunks) {
    /** Detaches the validated chunk list from caller-owned storage. */
    public Analysis {
      chunks = List.copyOf(chunks);
    }
  }

  /**
   * Validates up to 164 header bytes and the complete source size without changing buffer position.
   * Malformed data fails as FORMAT; unpreservable formats, shapes, and targets are UNSUPPORTED.
   */
  public static Analysis analyze(
      ByteBuffer source, long sourceSize, DdsTarget target, IoContext context)
      throws ArchiveException {
    ByteBuffer h = source.slice().order(ByteOrder.LITTLE_ENDIAN);
    if (h.remaining() < 128 || sourceSize < 128 || h.getInt(0) != fourCC("DDS "))
      throw context.failure(FailureKind.FORMAT, "dds.invalid-header", null);
    if (h.getInt(4) != 124 || h.getInt(76) != 32)
      throw context.failure(FailureKind.FORMAT, "dds.legacy-header-size", null);
    int four = h.getInt(84);
    boolean xbox = four == fourCC("XBOX");
    boolean extended = xbox || four == fourCC("DX10");
    int headerSize = xbox ? 164 : extended ? 148 : 128;
    if (h.remaining() < headerSize || sourceSize < headerSize)
      throw context.failure(FailureKind.FORMAT, "dds.truncated-header", null);
    if (xbox != (target == DdsTarget.XBOX))
      throw context.failure(FailureKind.UNSUPPORTED, "dds.target-mismatch", null);
    int width = bounded(h.getInt(16), 65535, false, context);
    int height = bounded(h.getInt(12), 65535, false, context);
    int mips = h.getInt(28) == 0 ? 1 : bounded(h.getInt(28), 255, false, context);
    // A 2D mip chain ends when its largest dimension reaches one.
    if (mips > 32 - Integer.numberOfLeadingZeros(Math.max(width, height)))
      throw context.failure(FailureKind.FORMAT, "dds.invalid-mip-count", null);
    if (Integer.toUnsignedLong(h.getInt(24)) > 1
        || (h.getInt(112) & 0x200000) != 0
        || (extended && (h.getInt(132) != 3 || h.getInt(140) != 1)))
      throw context.failure(FailureKind.UNSUPPORTED, "dds.unsupported-shape", null);
    int cubeCaps = h.getInt(112) & 0xFE00;
    // DDS BA2 can preserve either all six cubemap faces or no cubemap faces.
    if (cubeCaps != 0 && cubeCaps != 0xFE00)
      throw context.failure(FailureKind.FORMAT, "dds.invalid-cubemap-faces", null);
    boolean legacyCube = cubeCaps != 0;
    boolean extendedCube = extended && (h.getInt(136) & 4) != 0;
    // A contradictory extended header cannot identify how many face chains the payload contains.
    if (extended && legacyCube != extendedCube)
      throw context.failure(FailureKind.FORMAT, "dds.cubemap-flags-mismatch", null);
    boolean cube = extended ? extendedCube : legacyCube;
    boolean normalize =
        !extended
            && (h.getInt(80) & 0x40) != 0
            && h.getInt(88) == 24
            && masks(h, 0xFF0000, 0xFF00, 0xFF, 0);
    int format = extended ? h.getInt(128) : normalize ? 88 : legacyFormat(h);
    requireFormat(format, context);
    int tile = xbox ? bounded(h.getInt(148), 255, true, context) : 0;
    try {
      List<Long> sizes = new ArrayList<>();
      long chain = 0;
      int w = width, y = height;
      for (int i = 0; i < mips; i++) {
        long size = mipSize(w, y, format);
        sizes.add(size);
        chain = Math.addExact(chain, size);
        w = Math.max(1, w / 2);
        y = Math.max(1, y / 2);
      }
      long payload = Math.multiplyExact(chain, cube ? 6 : 1);
      long expected = normalize ? Math.multiplyExact(payload / 4, 3) : payload;
      if (sourceSize - headerSize != expected)
        throw context.failure(FailureKind.FORMAT, "dds.payload-size", null);
      int count = 1;
      w = width;
      y = height;
      while (!cube && count < mips && count < 4 && w >= 512 && y >= 512) {
        count++;
        w /= 2;
        y /= 2;
      }
      List<Chunk> chunks = new ArrayList<>();
      long offset = 0;
      for (int i = 0; i < count; i++) {
        long size = i == count - 1 ? payload - offset : sizes.get(i);
        chunks.add(new Chunk(offset, size, i, i == count - 1 ? mips - 1 : i));
        offset = Math.addExact(offset, size);
      }
      return new Analysis(
          width, height, mips, format, cube, tile, headerSize, normalize, payload, chunks);
    } catch (ArithmeticException e) {
      throw context.failure(FailureKind.FORMAT, "dds.size-overflow", e);
    }
  }

  /** Synthesizes a zeroed canonical header; original source header bytes are not retained. */
  public static byte[] canonicalHeader(
      int width,
      int height,
      int mipCount,
      int dxgiFormat,
      boolean cubemap,
      int tileMode,
      DdsTarget target,
      IoContext context)
      throws ArchiveException {
    bounded(width, 65535, false, context);
    bounded(height, 65535, false, context);
    int mips = mipCount == 0 ? 1 : bounded(mipCount, 255, false, context);
    bounded(tileMode, 255, true, context);
    requireFormat(dxgiFormat, context);
    boolean xbox = target == DdsTarget.XBOX;
    boolean extended = xbox || !legacyWritable(dxgiFormat);
    ByteBuffer h =
        ByteBuffer.allocate(xbox ? 164 : extended ? 148 : 128).order(ByteOrder.LITTLE_ENDIAN);
    long size;
    try {
      size =
          blockBytes(dxgiFormat) != 0
              ? mipSize(width, height, dxgiFormat)
              : Math.multiplyExact((long) width, bitsPerPixel(dxgiFormat) / 8);
    } catch (ArithmeticException e) {
      throw context.failure(FailureKind.FORMAT, "dds.size-overflow", e);
    }
    if (size > 0xFFFFFFFFL) throw context.failure(FailureKind.FORMAT, "dds.size-overflow", null);
    h.putInt(0, fourCC("DDS "));
    h.putInt(4, 124);
    h.putInt(8, 0x21007 | (blockBytes(dxgiFormat) != 0 ? 0x80000 : 8));
    h.putInt(12, height);
    h.putInt(16, width);
    h.putInt(20, (int) size);
    h.putInt(24, 1);
    h.putInt(28, mips);
    h.putInt(76, 32);
    h.putInt(108, 0x1000 | (mips > 1 ? 0x400008 : 0) | (cubemap ? 8 : 0));
    h.putInt(112, cubemap ? 0xFE00 : 0);
    if (extended) {
      h.putInt(80, 4);
      h.putInt(84, fourCC(xbox ? "XBOX" : "DX10"));
      h.putInt(128, dxgiFormat);
      h.putInt(132, 3);
      h.putInt(136, cubemap ? 4 : 0);
      h.putInt(140, 1);
      if (xbox) {
        h.putInt(148, tileMode);
        h.putInt(160, 10705);
      }
    } else writeLegacyPixelFormat(h, dxgiFormat);
    return h.array();
  }

  /** Returns specification metadata bits per pixel, or zero for unknown numeric DXGI values. */
  public static int bitsPerPixel(int f) {
    if (f >= 1 && f <= 4) return 128;
    if (f >= 5 && f <= 8) return 96;
    if ((f >= 9 && f <= 22) || f == 102 || f == 108 || f == 109) return 64;
    if ((f >= 23 && f <= 47)
        || (f >= 67 && f <= 69)
        || (f >= 87 && f <= 93)
        || f == 100
        || f == 101
        || f == 107) return 32;
    if (f == 104 || f == 105) return 24;
    if ((f >= 48 && f <= 59) || f == 85 || f == 86 || f == 114 || f == 115) return 16;
    if (f == 103 || f == 106 || f == 110) return 12;
    if ((f >= 60 && f <= 65)
        || (f >= 73 && f <= 78)
        || (f >= 82 && f <= 84)
        || (f >= 94 && f <= 99)
        || (f >= 111 && f <= 113)) return 8;
    if ((f >= 70 && f <= 72) || (f >= 79 && f <= 81)) return 4;
    return f == 66 ? 1 : 0;
  }

  /** Resolves specified FourCC mappings before recognizing legacy channel layouts. */
  private static int legacyFormat(ByteBuffer h) {
    int four = h.getInt(84);
    if (four == fourCC("DXT1")) return 71;
    if (four == fourCC("DXT3")) return 74;
    if (four == fourCC("DXT5")) return 77;
    if (four == fourCC("ATI1") || four == fourCC("BC4U")) return 80;
    if (four == fourCC("BC4S")) return 81;
    if (four == fourCC("ATI2") || four == fourCC("BC5U")) return 83;
    if (four == fourCC("BC5S")) return 84;
    int flags = h.getInt(80), bits = h.getInt(88);
    if ((flags & 0x20040) != 0) {
      if (bits == 32) return (flags & 1) == 0 ? 88 : h.getInt(92) == 255 ? 28 : 87;
      if (bits == 16) {
        if (masks(h, 0xF800, 0x7E0, 0x1F, 0)) return 85;
        if (masks(h, 0x7C00, 0x3E0, 0x1F, 0x8000)) return 86;
        if ((flags & 0x20000) != 0) return 49;
      }
      if (bits == 8 && (flags & 0x20000) != 0) return 61;
    }
    return bits == 8 && (flags & 2) != 0 ? 65 : 0;
  }

  /** Compares all channel masks before admitting one of the recognized packed pixel layouts. */
  private static boolean masks(ByteBuffer h, int r, int g, int b, int a) {
    return h.getInt(92) == r && h.getInt(96) == g && h.getInt(100) == b && h.getInt(104) == a;
  }

  /** Rejects formats outside the exact writable allow-list, independently of metadata math. */
  public static void requireFormat(int f, IoContext context) throws ArchiveException {
    if (!legacyWritable(f)
        && f != 29
        && f != 72
        && f != 75
        && f != 78
        && f != 91
        && f != 93
        && f != 95
        && f != 96
        && f != 98
        && f != 99) throw context.failure(FailureKind.UNSUPPORTED, "dds.unsupported-format", null);
  }

  /** Selects exactly the legacy reconstruction forms admitted by DDS-008 and DDS-012. */
  private static boolean legacyWritable(int f) {
    return f == 71 || f == 74 || f == 77 || f == 80 || f == 81 || f == 83 || f == 84 || f == 28
        || f == 87 || f == 88 || f == 85 || f == 86 || f == 49 || f == 65 || f == 61;
  }

  /** Computes each mip independently so tiny and odd BC dimensions retain complete blocks. */
  private static long mipSize(int w, int h, int f) {
    int block = blockBytes(f);
    return block != 0
        ? Math.multiplyExact(Math.multiplyExact(1L + (w - 1) / 4, 1L + (h - 1) / 4), block)
        : Math.multiplyExact(Math.multiplyExact((long) w, h), bitsPerPixel(f) / 8);
  }

  /** Returns bytes per BC block, or zero for formats requiring uncompressed pixel math. */
  private static int blockBytes(int f) {
    if (f >= 70 && f <= 72 || f >= 79 && f <= 81) return 8;
    return f >= 73 && f <= 78 || f >= 82 && f <= 84 || f >= 94 && f <= 99 ? 16 : 0;
  }

  /** Rejects unsigned values that cannot be represented in a narrower BA2 metadata field. */
  private static int bounded(int value, int maximum, boolean zero, IoContext context)
      throws ArchiveException {
    if (Integer.toUnsignedLong(value) > maximum || (!zero && value == 0))
      throw context.failure(FailureKind.FORMAT, "dds.unrepresentable-metadata", null);
    return value;
  }

  /** Encodes four ASCII characters in the little-endian DDS field order. */
  private static int fourCC(String v) {
    return v.charAt(0) | v.charAt(1) << 8 | v.charAt(2) << 16 | v.charAt(3) << 24;
  }

  /** Writes canonical legacy channel masks or FourCC for the eligible formats. */
  private static void writeLegacyPixelFormat(ByteBuffer h, int f) {
    String four =
        switch (f) {
          case 71 -> "DXT1";
          case 74 -> "DXT3";
          case 77 -> "DXT5";
          case 80 -> "BC4U";
          case 81 -> "BC4S";
          case 83 -> "BC5U";
          case 84 -> "BC5S";
          default -> null;
        };
    if (four != null) {
      h.putInt(80, 4);
      h.putInt(84, fourCC(four));
      return;
    }
    int[] pixel =
        switch (f) {
          case 28 -> new int[] {0x41, 32, 0xFF, 0xFF00, 0xFF0000, 0xFF000000};
          case 87 -> new int[] {0x41, 32, 0xFF0000, 0xFF00, 0xFF, 0xFF000000};
          case 88 -> new int[] {0x40, 32, 0xFF0000, 0xFF00, 0xFF, 0};
          case 85 -> new int[] {0x40, 16, 0xF800, 0x7E0, 0x1F, 0};
          case 86 -> new int[] {0x41, 16, 0x7C00, 0x3E0, 0x1F, 0x8000};
          case 49 -> new int[] {0x20001, 16, 0xFF, 0, 0, 0xFF00};
          case 65 -> new int[] {2, 8, 0, 0, 0, 0xFF};
          case 61 -> new int[] {0x20000, 8, 0xFF, 0, 0, 0};
          default -> throw new IllegalArgumentException("Nonlegacy DDS format");
        };
    h.putInt(80, pixel[0]);
    for (int i = 1; i < pixel.length; i++) h.putInt(84 + i * 4, pixel[i]);
  }
}
