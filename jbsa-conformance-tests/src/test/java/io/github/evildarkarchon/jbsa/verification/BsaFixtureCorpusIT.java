package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.fixtures.BsaCv1FixtureGenerator;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import org.junit.jupiter.api.Test;

/**
 * Checks independently generated TES4 vectors through the public archive and generator boundaries.
 */
final class BsaFixtureCorpusIT {
  /** Regeneration binds every declared fixture and recipe without admitting proposal goldens. */
  @Test
  void generatedTes4CorpusMatchesCommittedRecipes() throws Exception {
    Path root = Files.createTempDirectory("jbsa-tes4-reproduction-");
    try {
      Path staged = root.resolve("corpus");
      BsaCv1FixtureGenerator.materialize(staged);
      Path committed =
          Path.of(System.getProperty("jbsa.reactor.root")).resolve("tests/fixtures/bsa067");
      try (var paths = Files.walk(staged)) {
        List<Path> generated = paths.filter(Files::isRegularFile).toList();
        assertEquals(29, generated.size(), "26 vectors and three generated metadata files");
        for (Path path : generated)
          assertEquals(
              -1L,
              Files.mismatch(path, committed.resolve(staged.relativize(path))),
              staged.relativize(path).toString());
      }
      assertThrows(java.io.IOException.class, () -> BsaCv1FixtureGenerator.materialize(staged));
    } finally {
      deleteTree(root);
    }
  }

  /** Structural and payload faults remain observable failures at the public archive boundary. */
  @Test
  void generatedTes4FaultsAreRejected() throws Exception {
    Path root = Files.createTempDirectory("jbsa-tes4-faults-");
    try {
      BsaCv1FixtureGenerator.materialize(root.resolve("corpus"));
      for (String fault :
          List.of(
              "arithmetic-overflow",
              "decompression-mismatch",
              "equal-name-identities",
              "illegal-tuples",
              "impossible-counts",
              "out-of-range-spans",
              "partial-overlap",
              "truncated-spans")) {
        Path vector = root.resolve("corpus/artifacts/bsa-067-malformed-" + fault + ".hex");
        Path archive = root.resolve(fault + ".bsa");
        Files.write(archive, HexFormat.of().parseHex(Files.readString(vector).strip()));
        assertThrows(
            io.github.evildarkarchon.jbsa.ArchiveException.class,
            () -> {
              try (var opened =
                  io.github.evildarkarchon.jbsa.BethesdaArchives.standard()
                      .open(archive, io.github.evildarkarchon.jbsa.OpenOptions.standard())) {
                for (long ordinal = 0; ordinal < opened.entryCount(); ordinal++) {
                  try (var content = opened.entry(ordinal).openContent()) {
                    ByteBuffer bytes = ByteBuffer.allocate(97);
                    while (content.read(bytes) >= 0) bytes.clear();
                  }
                }
              }
            },
            fault);
      }
    } finally {
      deleteTree(root);
    }
  }

  /** Generated TES4 fixtures decode to the independently declared payloads at the public seam. */
  @Test
  void generatedTes4VectorsExposeDeclaredPayloads() throws Exception {
    Path temporaryRoot = Files.createTempDirectory("jbsa-tes4-fixtures-");
    try {
      BsaCv1FixtureGenerator.materialize(temporaryRoot.resolve("corpus"));
      for (String codec : List.of("stored", "zlib", "mixed")) {
        Path vector = temporaryRoot.resolve("corpus/artifacts/bsa-067-" + codec + ".hex");
        Path archive = temporaryRoot.resolve(codec + ".bsa");
        Files.write(archive, HexFormat.of().parseHex(Files.readString(vector).strip()));
        try (var opened =
            io.github.evildarkarchon.jbsa.BethesdaArchives.standard()
                .open(archive, io.github.evildarkarchon.jbsa.OpenOptions.standard())) {
          assertEquals(2, opened.entryCount());
          assertEquals("meshes\\a.nif", opened.entry(0).metadata().displayName());
          assertEquals("meshes\\b.nif", opened.entry(1).metadata().displayName());
          for (int ordinal = 0; ordinal < 2; ordinal++) {
            try (var content = opened.entry(ordinal).openContent()) {
              var actual = new java.io.ByteArrayOutputStream();
              ByteBuffer window = ByteBuffer.allocate(97);
              while (content.read(window) >= 0) {
                actual.write(window.array(), 0, window.position());
                window.clear();
              }
              assertArrayEquals(
                  ordinal == 0
                      ? "A".repeat(1024).getBytes(StandardCharsets.US_ASCII)
                      : HexFormat.of().parseHex("000102ff"),
                  actual.toByteArray());
            }
          }
        }
      }
    } finally {
      deleteTree(temporaryRoot);
    }
  }

  /** Removes only the test-owned temporary tree after archive handles have closed. */
  private static void deleteTree(Path root) throws Exception {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
    }
  }
}
