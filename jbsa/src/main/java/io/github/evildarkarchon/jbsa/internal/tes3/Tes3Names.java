package io.github.evildarkarchon.jbsa.internal.tes3;

/** Byte-defined TES3 names and hashes, independent of locale and host path rules. */
public final class Tes3Names {
  private Tes3Names() {}

  /**
   * Snapshots the selected operation's archive-name encoding; only an explicit profile reads ACP.
   * Native access or an unavailable charset yields checked capability failure, never a fallback.
   */
  public static java.nio.charset.Charset encoding(
      java.util.Optional<io.github.evildarkarchon.jbsa.CompatibilityProfile> profile,
      io.github.evildarkarchon.jbsa.internal.io.IoContext context)
      throws io.github.evildarkarchon.jbsa.ArchiveException {
    if (profile.isEmpty()) return java.nio.charset.Charset.forName("windows-1252");
    if (!System.getProperty("os.name").startsWith("Windows")
        || !Tes3Names.class.getModule().isNativeAccessEnabled())
      throw context.failure(
          io.github.evildarkarchon.jbsa.FailureKind.CAPABILITY,
          "name.active-ansi-unavailable",
          null);
    try (var arena = java.lang.foreign.Arena.ofConfined()) {
      var lookup = java.lang.foreign.SymbolLookup.libraryLookup("kernel32", arena);
      var function =
          java.lang.foreign.Linker.nativeLinker()
              .downcallHandle(
                  lookup.find("GetACP").orElseThrow(),
                  java.lang.foreign.FunctionDescriptor.of(java.lang.foreign.ValueLayout.JAVA_INT));
      int codePage = (int) function.invokeExact();
      String charset =
          switch (codePage) {
            case 65001 -> "UTF-8";
            case 932 -> "windows-31j";
            case 936 -> "GBK";
            case 949 -> "x-windows-949";
            case 950 -> "x-windows-950";
            case 874 -> "x-windows-874";
            default -> "windows-" + Integer.toUnsignedString(codePage);
          };
      return java.nio.charset.Charset.forName(charset);
    } catch (VirtualMachineError | ThreadDeath fatal) {
      throw fatal;
    } catch (Throwable failure) {
      throw context.failure(
          io.github.evildarkarchon.jbsa.FailureKind.CAPABILITY,
          "name.active-ansi-unavailable",
          failure);
    }
  }

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
