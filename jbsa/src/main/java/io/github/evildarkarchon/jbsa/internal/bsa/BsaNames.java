package io.github.evildarkarchon.jbsa.internal.bsa;

import java.nio.charset.StandardCharsets;

/** Byte-defined BSA names and hashes independent of locale. */
public final class BsaNames {
  private BsaNames() {}

  /** Copies ASCII bytes, replacing separators and folding ASCII capitals. */
  public static byte[] canonicalize(byte[] bytes) {
    byte[] result = bytes.clone();
    for (int i = 0; i < result.length; i++) {
      int b = Byte.toUnsignedInt(result[i]);
      if (b > 127) throw new IllegalArgumentException("Unqualified non-ASCII BSA name");
      result[i] = (byte) (b == '/' ? '\\' : b >= 'A' && b <= 'Z' ? b + 32 : b);
    }
    return result;
  }

  /** Reports whether original bytes permit canonical hash comparison. */
  public static boolean ascii(byte[] bytes) {
    for (byte b : bytes) if (b < 0) return false;
    return true;
  }

  /** Reports whether the final-extension split leaves a nonempty stem. */
  public static boolean hasStem(byte[] bytes) {
    return stemLength(bytes, true) > 0;
  }

  /** Computes an Oblivion hash for an already canonical component. */
  public static long hash(byte[] bytes, boolean basename) {
    return hash(bytes, basename, 0x67);
  }

  /** Computes a hash with the family's signed or unsigned byte recurrence. */
  public static long hash(byte[] bytes, boolean basename, int version) {
    int n = stemLength(bytes, basename);
    if (n == 0) return 0;
    int low =
        Byte.toUnsignedInt(bytes[n - 1]) | ((n & 255) << 16) | (Byte.toUnsignedInt(bytes[0]) << 24);
    if (n > 2) low |= Byte.toUnsignedInt(bytes[n - 2]) << 8;
    String extension = new String(bytes, n, bytes.length - n, StandardCharsets.US_ASCII);
    low |=
        switch (extension) {
          case ".kf" -> 0x80;
          case ".nif" -> 0x8000;
          case ".dds" -> 0x8080;
          case ".wav" -> 0x80000000;
          default -> 0;
        };
    int stem = 0, ext = 0;
    for (int i = 1; i < n - 2; i++)
      stem = (version == 0x67 ? bytes[i] : Byte.toUnsignedInt(bytes[i])) + 65599 * stem;
    for (int i = n; i < bytes.length; i++)
      ext = (version == 0x67 ? bytes[i] : Byte.toUnsignedInt(bytes[i])) + 65599 * ext;
    return (Integer.toUnsignedLong(stem + ext) << 32) | Integer.toUnsignedLong(low);
  }

  /** Keeps directory dots in the stem while finding basenames' final extension. */
  private static int stemLength(byte[] bytes, boolean basename) {
    if (basename) for (int i = bytes.length - 1; i >= 0; i--) if (bytes[i] == '.') return i;
    return bytes.length;
  }
}
