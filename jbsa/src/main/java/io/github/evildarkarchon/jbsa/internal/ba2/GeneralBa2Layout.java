package io.github.evildarkarchon.jbsa.internal.ba2;

/** Shared General BA2 envelope sizes keyed by the independently retained wire version. */
final class GeneralBa2Layout {
  private GeneralBa2Layout() {}

  /** Returns the complete canonical header extent for an already validated General BA2 version. */
  static int headerSize(long version) {
    return switch ((int) version) {
      case 1 -> 24;
      case 2 -> 32;
      case 3 -> 36;
      default -> throw new IllegalArgumentException("Unsupported General BA2 version: " + version);
    };
  }
}
