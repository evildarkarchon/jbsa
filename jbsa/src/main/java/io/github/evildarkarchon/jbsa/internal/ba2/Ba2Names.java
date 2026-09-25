package io.github.evildarkarchon.jbsa.internal.ba2;

import io.github.evildarkarchon.jbsa.EntryMetadata;
import io.github.evildarkarchon.jbsa.WireName;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.Locale;

/** Byte-defined shared BA2 identity independent of locale and host filesystem behavior. */
public final class Ba2Names {
  private Ba2Names() {}

  /** Reports whether bytes qualify for canonical hash comparison. */
  public static boolean ascii(byte[] name) {
    for (byte value : name) if (value < 0) return false;
    return true;
  }

  /** Computes canonical General identity, rejecting unqualified non-ASCII name components. */
  public static EntryMetadata.Ba2Identity identity(byte[] completeName) {
    if (!ascii(completeName)) throw new IllegalArgumentException("Unqualified non-ASCII BA2 name");
    byte[] name = completeName.clone();
    int separator = -1, dot = -1;
    for (int i = 0; i < name.length; i++) {
      if (name[i] == '/') name[i] = '\\';
      if (name[i] >= 'A' && name[i] <= 'Z') name[i] += 32;
      if (name[i] == '\\') {
        separator = i;
        dot = -1;
      } else if (name[i] == '.') dot = i;
    }
    byte[] extension = new byte[4];
    if (dot >= 0) System.arraycopy(name, dot + 1, extension, 0, Math.min(4, name.length - dot - 1));
    return new EntryMetadata.Ba2Identity(
        hash(Arrays.copyOfRange(name, separator + 1, dot < 0 ? name.length : dot)),
        new WireName(extension),
        hash(Arrays.copyOf(name, Math.max(0, separator))),
        0,
        1,
        16);
  }

  /** Computes reflected CRC-32 with initial zero and no final XOR. */
  private static long hash(byte[] bytes) {
    int crc = 0;
    for (byte value : bytes) {
      crc ^= Byte.toUnsignedInt(value);
      for (int bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ ((crc & 1) == 0 ? 0 : 0xEDB88320);
    }
    return Integer.toUnsignedLong(crc);
  }

  /** Produces the normative unresolved spelling without claiming a name identity. */
  public static String synthetic(EntryMetadata.Ba2Identity identity, long ordinal) {
    return String.format(
        Locale.ROOT,
        "__jbsa_hash__\\d%08x\\e%08x-%08x-x%s",
        identity.directoryHash(),
        ordinal,
        identity.baseNameHash(),
        HexFormat.of().formatHex(identity.extension().bytes()));
  }
}
