package io.github.evildarkarchon.jbsa.fixtures;

import java.io.IOException;
import java.nio.file.Path;

/** Generates independent TES4 / Oblivion BSA wire vectors for conformance testing. */
public final class Tes4FixtureGenerator {
  private Tes4FixtureGenerator() {}

  /**
   * Reproduces the committed vector inventory in an absent or empty directory.
   *
   * @param destination caller-owned output directory
   * @throws IOException if the destination is nonempty or cannot be written
   */
  public static void materialize(Path destination) throws IOException {
    LegacyBsaFixtureWire.materialize(destination, LegacyBsaFixtureWire.Family.TES4);
  }

  /**
   * Runs the standalone TES4 fixture generator.
   *
   * @param args exactly {@code --output <directory>}
   * @throws IllegalArgumentException if the command shape is invalid
   * @throws IOException if generation fails
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 2 || !"--output".equals(args[0])) {
      throw new IllegalArgumentException("Usage: Tes4FixtureGenerator --output <directory>");
    }
    materialize(Path.of(args[1]));
  }
}
