package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.evildarkarchon.jbsa.fixtures.ArchiveFixtureGenerator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks the single public command for materializing independently authored archive corpora. */
@Tag("archive-fixtures")
final class ArchiveFixtureGenerationIT {
  @TempDir Path directory;

  /** Generation writes every supported corpus under one caller-owned empty root. */
  @Test
  void materializesEveryArchiveCorpus() throws Exception {
    Path output = directory.resolve("archive-fixtures");

    ArchiveFixtureGenerator.materialize(output);

    try (var children = Files.list(output)) {
      assertEquals(
          List.of(
              "bsa067",
              "bsa068",
              "bsa069",
              "fo4-dds-v1",
              "fo4-general",
              "starfield-dds",
              "starfield-general",
              "synthetic",
              "tes3",
              "tes4"),
          children.map(path -> path.getFileName().toString()).sorted().toList());
    }
    for (String corpus :
        List.of(
            "bsa067",
            "bsa068",
            "bsa069",
            "fo4-dds-v1",
            "fo4-general",
            "starfield-dds",
            "starfield-general",
            "synthetic",
            "tes3",
            "tes4")) {
      assertTrue(Files.isRegularFile(output.resolve(corpus).resolve("manifest.json")), corpus);
    }
  }

  /** Regeneration reproduces every committed Starfield v2/v3 vector and its manifest bytes. */
  @Test
  void reproducesStarfieldVersions() throws Exception {
    Path output = directory.resolve("archive-fixtures");
    ArchiveFixtureGenerator.materialize(output);
    Path committed = Path.of(System.getProperty("jbsa.reactor.root")).resolve("tests/fixtures");
    for (String corpus : List.of("starfield-general", "starfield-dds")) {
      Path generatedRoot = output.resolve(corpus);
      Path committedRoot = committed.resolve(corpus);
      List<String> generated;
      try (var paths = Files.list(generatedRoot)) {
        generated = paths.map(path -> path.getFileName().toString()).sorted().toList();
      }
      List<String> expected;
      try (var paths = Files.list(committedRoot)) {
        expected =
            paths
                .map(path -> path.getFileName().toString())
                .filter(name -> !name.equals("README.md"))
                .sorted()
                .toList();
      }
      assertEquals(expected, generated, corpus);
      for (String name : generated) {
        assertEquals(
            -1L,
            Files.mismatch(generatedRoot.resolve(name), committedRoot.resolve(name)),
            corpus + "/" + name);
      }
    }
  }

  /** Regeneration cannot silently overwrite a reviewed fixture or add partial files beside it. */
  @Test
  void rejectsNonemptyOutputBeforeGenerating() throws Exception {
    Path output = directory.resolve("archive-fixtures");
    Files.createDirectories(output);
    Path existing = Files.writeString(output.resolve("reviewed.txt"), "reviewed");

    assertThrows(IOException.class, () -> ArchiveFixtureGenerator.materialize(output));

    assertEquals("reviewed", Files.readString(existing));
    try (var children = Files.list(output)) {
      assertEquals(
          List.of("reviewed.txt"), children.map(path -> path.getFileName().toString()).toList());
    }
  }
}
