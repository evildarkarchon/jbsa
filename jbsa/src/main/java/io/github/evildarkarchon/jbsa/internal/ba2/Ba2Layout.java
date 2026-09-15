package io.github.evildarkarchon.jbsa.internal.ba2;

/** Shared BA2 v1-v3 envelope sizes keyed by the independently retained wire version. */
public final class Ba2Layout {
  private Ba2Layout() {}

  /** Returns the complete canonical header extent for an already validated BA2 version. */
  public static int headerSize(long version) {
    return switch ((int) version) {
      case 1 -> 24;
      case 2 -> 32;
      case 3 -> 36;
      default -> throw new IllegalArgumentException("Unsupported BA2 version: " + version);
    };
  }
}
