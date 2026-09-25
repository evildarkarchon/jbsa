package io.github.evildarkarchon.jbsa.internal.ba2;

/** Shared BA2 envelope semantics keyed by the independently retained wire version. */
public final class Ba2Layout {
  private Ba2Layout() {}

  /** Returns the complete wire header extent for an already validated BA2 version. */
  public static int headerSize(long version) {
    return switch ((int) version) {
      case 1, 7, 8 -> 24;
      case 2 -> 32;
      case 3 -> 36;
      default -> throw new IllegalArgumentException("Unsupported BA2 version: " + version);
    };
  }

  /** Returns whether the wire version is routed to a bounded decoder. */
  public static boolean supportsDecode(long version) {
    return version == 1 || version == 2 || version == 3 || version == 7 || version == 8;
  }

  /** Distinguishes the Fallout 4 selector versions from the Starfield versions. */
  public static boolean isFallout4(long version) {
    return version == 1 || version == 7 || version == 8;
  }

  /** Returns whether the envelope carries Starfield's unknown value at offset 24. */
  public static boolean hasExtraHeader(long version) {
    return version == 2 || version == 3;
  }
}
