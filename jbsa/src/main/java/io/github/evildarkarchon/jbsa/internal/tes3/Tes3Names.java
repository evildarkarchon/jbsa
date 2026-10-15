package io.github.evildarkarchon.jbsa.internal.tes3;

/** Byte-defined TES3 names and hashes, independent of locale and host path rules. */
public final class Tes3Names {
  private Tes3Names() {}

  /** Copies and canonicalizes ASCII name bytes; rejects unqualified non-ASCII mappings. */
  public static byte[] canonicalize(byte[] name) {
    byte[] result = name.clone();
    for (int index = 0; index < result.length; index++) {
      int value = Byte.toUnsignedInt(result[index]);
      if (value > 127) throw new IllegalArgumentException("Unqualified non-ASCII TES3 name");
      result[index] =
          (byte) (value == '/' ? '\\' : value >= 'A' && value <= 'Z' ? value + 32 : value);
    }
    return result;
  }

  /** Returns the complete unsigned hash bit pattern for already canonical name bytes. */
  public static long hash(byte[] canonicalName) {
    int middle = canonicalName.length / 2;
    int low = 0;
    int high = 0;
    for (int index = 0; index < middle; index++) {
      low ^= Byte.toUnsignedInt(canonicalName[index]) << ((index * 8) & 31);
    }
    for (int index = middle; index < canonicalName.length; index++) {
      int shifted = Byte.toUnsignedInt(canonicalName[index]) << (((index - middle) * 8) & 31);
      high = Integer.rotateRight(high ^ shifted, shifted & 31);
    }
    return (Integer.toUnsignedLong(high) << 32) | Integer.toUnsignedLong(low);
  }
}
