package io.github.evildarkarchon.jbsa.fixtures;

import java.io.IOException;
import java.nio.file.Path;

/** Generates independent Skyrim SE and AE BSA wire vectors for conformance testing. */
public final class Bsa069FixtureGenerator {
  private Bsa069FixtureGenerator() {}

  /**
   * Reproduces the committed vector inventory in an absent or empty directory.
   *
   * @param destination caller-owned output directory
   * @throws IOException if the destination is nonempty or cannot be written
   */
  public static void materialize(Path destination) throws IOException {
    LegacyBsaFixtureWire.materialize(destination, LegacyBsaFixtureWire.Family.BSA069);
  }

  /**
   * Runs the standalone BSA 0x69 fixture generator.
   *
   * @param args exactly {@code --output <directory>}
   * @throws IllegalArgumentException if the command shape is invalid
   * @throws IOException if generation fails
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 2 || !"--output".equals(args[0])) {
      throw new IllegalArgumentException("Usage: Bsa069FixtureGenerator --output <directory>");
    }
    materialize(Path.of(args[1]));
  }
}
