package io.github.evildarkarchon.jbsa.fixtures;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/**
 * Generates the independently authored archive fixture corpora through one build-only entry point.
 * Each corpus keeps its own wire recipe and reviewed manifest identity.
 */
public final class ArchiveFixtureGenerator {
  private ArchiveFixtureGenerator() {}

  /**
   * Runs all archive fixture generators into a caller-owned empty directory.
   *
   * @param args exactly {@code --output <empty-directory>}
   * @throws IllegalArgumentException if the arguments have another shape
   * @throws IOException if the destination is nonempty or generation fails
   */
  public static void main(String[] args) throws IOException {
    if (args.length != 2 || !"--output".equals(args[0])) {
      throw new IllegalArgumentException(
          "Usage: ArchiveFixtureGenerator --output <empty-directory>");
    }
    materialize(Path.of(args[1]));
  }

  /**
   * Materializes all generator-owned archive corpora beneath a fresh root without replacing any
   * committed fixture or golden.
   *
   * @param outputRoot absent or empty root directory
   * @throws NullPointerException if the destination is null
   * @throws IOException if the destination is nonempty or a corpus cannot be written
   */
  public static void materialize(Path outputRoot) throws IOException {
    Objects.requireNonNull(outputRoot, "outputRoot");
    requireEmptyDestination(outputRoot);
    Files.createDirectories(outputRoot);
    BsaCv1FixtureGenerator.materialize(outputRoot.resolve("bsa067"));
    Bsa068FixtureGenerator.materialize(outputRoot.resolve("bsa068"));
    Bsa069FixtureGenerator.materialize(outputRoot.resolve("bsa069"));
    Fo4DdsV1FixtureGenerator.materialize(outputRoot.resolve("fo4-dds-v1"));
    Fo4GeneralBa2FixtureGenerator.materialize(outputRoot.resolve("fo4-general"));
    StarfieldArchiveFixtureGenerator.materializeDds(outputRoot.resolve("starfield-dds"));
    StarfieldArchiveFixtureGenerator.materializeGeneral(outputRoot.resolve("starfield-general"));
    FixtureCorpusGenerator.materialize(outputRoot.resolve("synthetic"));
    Tes3FixtureGenerator.materialize(outputRoot.resolve("tes3"));
    Tes4FixtureGenerator.materialize(outputRoot.resolve("tes4"));
  }

  /**
   * Rejects in-place generation so the reviewable output remains separate from accepted evidence.
   */
  private static void requireEmptyDestination(Path outputRoot) throws IOException {
    if (!Files.exists(outputRoot)) {
      return;
    }
    if (!Files.isDirectory(outputRoot)) {
      throw new IOException("Fixture output is not a directory: " + outputRoot);
    }
    try (var children = Files.list(outputRoot)) {
      if (children.findAny().isPresent()) {
        throw new IOException("Fixture output directory must be empty: " + outputRoot);
      }
    }
  }
}
