package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Independent 0x68 game-selector evidence; neither activated CV1 nor Binary Conformance. */
@Tag("bsa")
final class Bsa068ConformanceIT {
  @TempDir Path directory;

  /** Reproduces every committed game/mode vector and its provenance without the product writer. */
  @Test
  void independentlyGeneratedCorpusMatchesCommittedInventory() throws Exception {
    Path generated = directory.resolve("generated");
    run(
        List.of(
            "python",
            root().resolve("build/generate-bsa068-fixtures.py").toString(),
            "--output",
            generated.toString()),
        directory.resolve("generator.log"));
    Path committed = root().resolve("tests/fixtures/bsa068");
    try (var fresh = Files.list(generated);
        var recorded = Files.list(committed)) {
      var expected = fresh.map(path -> path.getFileName().toString()).sorted().toList();
      var actual =
          recorded
              .map(path -> path.getFileName().toString())
              .filter(name -> !name.equals("README.md"))
              .sorted()
              .toList();
      assertEquals(19, expected.size());
      assertEquals(expected, actual);
      for (String name : expected) {
        assertEquals(-1L, Files.mismatch(generated.resolve(name), committed.resolve(name)), name);
      }
    }
    run(
        List.of("python", root().resolve("build/test-bsa068-validator.py").toString()),
        directory.resolve("scanner-tests.log"));
  }

  /** Reads and extracts all game/mode fixtures, with prefix bytes excluded from decoded content. */
  @Test
  void readsAndExtractsIndependentGameVectors() throws Exception {
    for (String game : List.of("fallout3", "new-vegas", "skyrim-le")) {
      for (String mode : List.of("stored", "zlib", "mixed")) {
        for (boolean embedded : List.of(false, true)) {
          String label = game + "-" + mode + (embedded ? "-embedded" : "");
          Path source = fixture(label);
          validate(source);
          assertEquals(
              ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA,
              BethesdaArchives.standard().detect(source).family().orElseThrow());
          assertArchive(source);
          Path destination = directory.resolve("extracted-" + label);
          BethesdaArchives.standard()
              .extract(ExtractRequest.standard(source, destination), OperationControl.standard());
          assertTree(destination);
        }
      }
    }
  }

  /** Separately validates every public encode mode; stored equality remains candidate evidence. */
  @Test
  void independentlyValidatesStoredZlibMixedAndEmbeddedOutput() throws Exception {
    Path source = sources();
    for (String mode : List.of("stored", "zlib", "mixed")) {
      for (boolean embedded : List.of(false, true)) {
        Path output = directory.resolve("candidate-" + mode + "-" + embedded + ".bsa");
        pack(source, output, mode, embedded);
        validate(output);
        assertArchive(output);
        if (mode.equals("stored")) {
          assertEquals(
              -1L,
              Files.mismatch(fixture("fallout3-stored" + (embedded ? "-embedded" : "")), output));
        }
      }
    }
  }

  /** Executes both oracle directions per game and framing; mixed output uses a public override. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.bsa.local", matches = "true")
  void pinnedOracleCrossDecodesEveryGameAndFraming() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isRegularFile(root().resolve("tests/fixtures/local/oracle/BSArch.exe")),
        "UNAVAILABLE: pinned local oracle absent");
    Path source = sources();
    for (String selector : List.of("fo3", "fnv", "tes5")) {
      for (String mode : List.of("stored", "zlib", "mixed")) {
        for (boolean embedded : List.of(false, true)) {
          String label = selector + "-" + mode + "-" + embedded;
          Path oracleArchive = directory.resolve("oracle-" + label + ".bsa");
          oracle("pack", source, oracleArchive, selector, mode, embedded, "oracle-" + label);
          validate(oracleArchive);
          assertArchive(oracleArchive);
          Path candidate = directory.resolve("local-candidate-" + label + ".bsa");
          pack(source, candidate, mode, embedded);
          validate(candidate);
          // The oracle CLI exposes a global zlib switch; mixed entries are requested by JBSA.
          Path extracted = Files.createDirectory(directory.resolve("oracle-extracted-" + label));
          oracle("unpack", candidate, extracted, selector, mode, embedded, "candidate-" + label);
          assertTree(extracted);
        }
      }
    }
  }

  /**
   * Packs the same explicit public model for all three aliases, with deterministic layout controls.
   */
  private void pack(Path source, Path output, String mode, boolean embedded) throws Exception {
    var compression =
        mode.equals("stored") ? PackOptions.Compression.STORED : PackOptions.Compression.ZLIB;
    var defaults =
        PackRequest.standard(
            output,
            ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA,
            new ArchiveEncoding(
                Optional.of(new WireVersion(104)), Optional.empty(), OptionalLong.empty()),
            List.of(new PackSource.DetectedPath(source)),
            Optional.empty());
    var overrides =
        mode.equals("mixed")
            ? Map.of(new NormalizedNameIdentity("meshes\\b.nif"), PackOptions.Compression.STORED)
            : Map.<NormalizedNameIdentity, PackOptions.Compression>of();
    BethesdaArchives.standard()
        .pack(
            new PackRequest(
                output,
                defaults.family(),
                defaults.encoding(),
                defaults.compatibilityProfile(),
                defaults.sources(),
                defaults.targetPolicy(),
                defaults.diagnosticPolicy(),
                defaults.resourceLimits(),
                new WorkerSelection.UpTo(1),
                new PackOptions(
                    List.of(),
                    compression,
                    false,
                    new PackOptions.Splitting.UpToBytes(0),
                    embedded
                        ? new FlagSelection.Explicit(mode.equals("stored") ? 0x183 : 0x187)
                        : FlagSelection.AUTOMATIC,
                    FlagSelection.AUTOMATIC,
                    overrides),
                Optional.empty()),
            OperationControl.standard());
  }

  /**
   * Checks public order and exact payload content through owned entry channels and terminal EOF.
   */
  private void assertArchive(Path source) throws Exception {
    try (OpenArchive archive = BethesdaArchives.standard().open(source, OpenOptions.standard())) {
      assertEquals(2, archive.entryCount());
      for (int index = 0; index < 2; index++) {
        ArchiveEntry entry = archive.entry(index);
        assertEquals("meshes\\" + (index == 0 ? "a.nif" : "b.nif"), entry.metadata().displayName());
        try (EntryContent content = entry.openContent()) {
          assertArrayEquals(payload(index), Channels.newInputStream(content).readAllBytes());
          assertTrue(content.assessment().isPresent());
        }
      }
    }
  }

  /** Verifies both decoded source files independently of archive interpretation. */
  private void assertTree(Path path) throws Exception {
    assertArrayEquals(payload(0), Files.readAllBytes(path.resolve("meshes/a.nif")));
    assertArrayEquals(payload(1), Files.readAllBytes(path.resolve("meshes/b.nif")));
  }

  /** Materializes redistributable synthetic source content outside timing. */
  private Path sources() throws Exception {
    Path path = Files.createDirectories(directory.resolve("sources/meshes"));
    Files.write(path.resolve("a.nif"), payload(0));
    Files.write(path.resolve("b.nif"), payload(1));
    return path.getParent();
  }

  /** Returns source bytes without deriving them from an archive. */
  private static byte[] payload(int index) {
    if (index == 1) return HexFormat.of().parseHex("000102ff");
    byte[] bytes = new byte[1024];
    Arrays.fill(bytes, (byte) 'A');
    return bytes;
  }

  /** Decodes committed text vectors without invoking the production writer. */
  private Path fixture(String name) throws Exception {
    return Files.write(
        directory.resolve(name + ".bsa"),
        HexFormat.of()
            .parseHex(
                Files.readString(root().resolve("tests/fixtures/bsa068/" + name + ".hex"))
                    .strip()));
  }

  /** Retains scanner identity, exact artifact digest, and independent semantic observations. */
  private void validate(Path path) throws Exception {
    Path evidence =
        root()
            .resolve(
                "target/bsa068-validator-evidence/"
                    + directory.getFileName()
                    + "/"
                    + path.getFileName());
    Files.createDirectories(evidence);
    run(
        List.of(
            "pwsh",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-File",
            root().resolve("build/run-bsa-validator.ps1").toString(),
            "-InputPath",
            path.toString(),
            "-WorkingDirectory",
            directory.toString(),
            "-EvidenceDirectory",
            evidence.toString(),
            "-Family",
            "bsa-068"),
        evidence.resolve("adapter.log"));
  }

  /** Invokes the digest-pinned oracle trust boundary and retains each invocation and result. */
  private void oracle(
      String operation,
      Path input,
      Path output,
      String selector,
      String mode,
      boolean embedded,
      String label)
      throws Exception {
    Path evidence =
        root().resolve("target/bsa068-local-evidence/" + directory.getFileName() + "/" + label);
    Files.createDirectories(evidence);
    var command =
        new ArrayList<>(
            List.of(
                "pwsh",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                root().resolve("build/run-bsa-oracle.ps1").toString(),
                "-Operation",
                operation,
                "-InputPath",
                input.toString(),
                "-OutputPath",
                output.toString(),
                "-Compression",
                mode.equals("stored") ? "stored" : "zlib",
                "-Selector",
                selector,
                "-WorkingDirectory",
                directory.toString(),
                "-EvidenceDirectory",
                evidence.toString()));
    if (embedded) command.add("-EmbeddedNames");
    run(command, evidence.resolve("adapter.log"));
  }

  /** Owns bounded external observations through completion or deadline cleanup. */
  private static void run(List<String> command, Path log) throws Exception {
    Process process =
        new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(log.toFile()).start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Observer timed out: " + log);
      assertEquals(0, process.exitValue(), () -> "Observer failed; see " + log);
    } finally {
      // Failed assertions and timeouts must not leave observer children running.
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static Path root() {
    return Path.of(System.getProperty("jbsa.reactor.root"));
  }
}
