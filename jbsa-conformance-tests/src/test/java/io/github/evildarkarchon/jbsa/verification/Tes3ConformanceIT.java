package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Independent wire and embedded-consumer evidence for the TES3 slice, not a full CV1 claim. */
@Tag("tes3")
final class Tes3ConformanceIT {
  @TempDir Path directory;

  /**
   * Reproduces the complete text-vector inventory, provenance manifest and every decoded digest.
   */
  @Test
  void independentlyGeneratedCorpusMatchesCommittedInventory() throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path generated = directory.resolve("generated");
    Path transcript = directory.resolve("generation.log");
    Process process =
        new ProcessBuilder(
                "pwsh",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                root.resolve("build/generate-tes3-fixtures.ps1").toString(),
                "-OutputDirectory",
                generated.toString())
            .redirectErrorStream(true)
            .redirectOutput(transcript.toFile())
            .start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "Fixture generator timed out");
      assertEquals(0, process.exitValue(), Files.readString(transcript));
    } finally {
      // The test owns its child even if the generator's deadline assertion fails.
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
    Path committed = root.resolve("tests/fixtures/tes3");
    try (var fresh = Files.list(generated);
        var recorded = Files.list(committed)) {
      var expected = fresh.map(path -> path.getFileName().toString()).sorted().toList();
      var actual =
          recorded
              .map(path -> path.getFileName().toString())
              .filter(name -> !name.equals("README.md"))
              .sorted()
              .toList();
      assertEquals(8, expected.size());
      assertEquals(expected, actual, "Every committed corpus object must be manifest-accounted");
      for (String name : expected) {
        assertEquals(-1L, Files.mismatch(generated.resolve(name), committed.resolve(name)), name);
      }
    }
  }

  /** Reads literal names, hashes, sizes and bytes independently authored from the wire contract. */
  @Test
  void readsIndependentStoredWireFixture() throws Exception {
    Path source = fixture("stored");
    runValidator(source);
    BethesdaArchives archives = BethesdaArchives.standard();
    assertEquals(ArchiveFamily.TES3_BSA, archives.detect(source).family().orElseThrow());
    assertEquals(
        ArchiveDisposition.CONFORMING, archives.inspect(source).assessment().disposition());
    try (OpenArchive archive = archives.open(source, OpenOptions.standard())) {
      assertEquals(2, archive.entryCount());
      assertEntry(archive.entry(0), "meshes\\a.nif", 0x54b7713268731608L, "4e494600");
      assertEntry(archive.entry(1), "sound\\b.wav", 0xbb9745d06e756f17L, "00010203ff");
    }
  }

  /** Confirms unsafe structural spans fail at inspection, before content can be requested. */
  @Test
  void rejectsIndependentMalformedSpans() throws Exception {
    for (String scenario : List.of("truncated-payload", "impossible-count", "partial-overlap")) {
      ArchiveException failure =
          assertThrows(
              ArchiveException.class,
              () -> BethesdaArchives.standard().inspect(fixture(scenario)),
              scenario);
      assertEquals(FailureKind.FORMAT, failure.kind(), scenario);
    }
  }

  /** Tolerated input remains readable with its exact warning identifier and severity. */
  @Test
  void diagnosesIndependentNoncanonicalWireFacts() throws Exception {
    for (var scenario :
        List.of(
            List.of("name-offset", "tes3.name-offset-inconsistency"),
            List.of("stored-hash", "tes3.stored-hash-mismatch"),
            List.of("trailing-data", "tes3.trailing-data"))) {
      var assessment = BethesdaArchives.standard().inspect(fixture(scenario.get(0))).assessment();
      assertEquals(ArchiveDisposition.TOLERATED_NONCANONICAL, assessment.disposition());
      var warnings =
          assessment.diagnostics().stream()
              .filter(value -> value.identifier().equals(scenario.get(1)))
              .toList();
      assertEquals(1, warnings.size());
      assertEquals(DiagnosticSeverity.WARNING, warnings.getFirst().severity());
      assertEquals(Operation.INSPECT, warnings.getFirst().operation());
    }
  }

  /** Exercises filesystem publication from independently authored input through the public seam. */
  @Test
  void extractsIndependentPayloadBytes() throws Exception {
    Path destination = directory.resolve("extracted");
    BethesdaArchives.standard()
        .extract(
            ExtractRequest.standard(fixture("stored"), destination), OperationControl.standard());
    assertArrayEquals(
        HexFormat.of().parseHex("4e494600"),
        Files.readAllBytes(destination.resolve("meshes/a.nif")));
    assertArrayEquals(
        HexFormat.of().parseHex("00010203ff"),
        Files.readAllBytes(destination.resolve("sound/b.wav")));
  }

  /** Fixed independent bytes detect canonical ordering, hash, offset and payload writer drift. */
  @Test
  void packsCanonicalStoredWireVector() throws Exception {
    Path mesh = Files.write(directory.resolve("mesh"), HexFormat.of().parseHex("4e494600"));
    Path sound = Files.write(directory.resolve("sound"), HexFormat.of().parseHex("00010203ff"));
    Path output = directory.resolve("canonical.bsa");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                output,
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                List.of(
                    new PackSource.NamedFile("SOUND/B.WAV", sound),
                    new PackSource.NamedFile("Meshes/A.NIF", mesh)),
                Optional.empty()),
            OperationControl.standard());
    assertEquals(-1L, Files.mismatch(fixture("stored"), output));
    runValidator(output);
  }

  /** Opt-in local cross-decodes retain oracle observations and compare the exact source bytes. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.tes3.local", matches = "true")
  void pinnedLocalOracleCrossDecodesBothDirections() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse(
        "true".equals(System.getenv("GITHUB_ACTIONS")),
        "The local oracle must never execute in hosted CI");
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isRegularFile(root.resolve("tests/fixtures/local/oracle/BSArch.exe")),
        "UNAVAILABLE: the pinned local oracle is absent");
    Path sources = directory.resolve("sources");
    Files.createDirectories(sources.resolve("meshes"));
    Files.createDirectories(sources.resolve("sound"));
    Files.write(sources.resolve("meshes/a.nif"), HexFormat.of().parseHex("4e494600"));
    Files.write(sources.resolve("sound/b.wav"), HexFormat.of().parseHex("00010203ff"));
    Path oracleArchive = directory.resolve("oracle.bsa");
    runOracle("pack", sources, oracleArchive, "oracle-to-jbsa");
    runValidator(oracleArchive);
    try (OpenArchive archive =
        BethesdaArchives.standard().open(oracleArchive, OpenOptions.standard())) {
      assertEquals(2, archive.entryCount());
      assertEntry(archive.entry(0), "meshes\\a.nif", 0x54b7713268731608L, "4e494600");
      assertEntry(archive.entry(1), "sound\\b.wav", 0xbb9745d06e756f17L, "00010203ff");
    }
    Path candidate = directory.resolve("candidate.bsa");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                candidate,
                ArchiveFamily.TES3_BSA,
                ArchiveEncoding.tes3(),
                List.of(new PackSource.DetectedPath(sources)),
                Optional.empty()),
            OperationControl.standard());
    Path extracted = directory.resolve("oracle-extracted");
    runValidator(candidate);
    // BSArch requires an existing unpack destination even though the public JBSA request does not.
    Files.createDirectory(extracted);
    runOracle("unpack", candidate, extracted, "jbsa-to-oracle");
    for (String name : List.of("meshes/a.nif", "sound/b.wav")) {
      assertEquals(-1L, Files.mismatch(sources.resolve(name), extracted.resolve(name)), name);
    }
  }

  /**
   * Invokes the existing bounded oracle adapter; its digest check and raw evidence are retained.
   */
  private void runOracle(String operation, Path input, Path output, String direction)
      throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path evidence =
        root.resolve("target/tes3-local-evidence/" + directory.getFileName() + "/" + direction);
    Files.createDirectories(evidence);
    Path transcript = evidence.resolve("adapter.log");
    Process process =
        new ProcessBuilder(
                "pwsh",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                root.resolve("build/run-tes3-oracle.ps1").toString(),
                "-Operation",
                operation,
                "-InputPath",
                input.toString(),
                "-OutputPath",
                output.toString(),
                "-WorkingDirectory",
                directory.toString(),
                "-EvidenceDirectory",
                evidence.toString())
            .redirectErrorStream(true)
            .redirectOutput(transcript.toFile())
            .start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Oracle adapter timed out");
      assertEquals(0, process.exitValue(), () -> "Oracle observation failed; see " + transcript);
    } finally {
      // A failing deadline assertion must not leave an observing process alive after its test.
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  /**
   * Runs a separate specification-authored scanner with pinned identity and literal expectations.
   */
  private void runValidator(Path archive) throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path evidence =
        root.resolve(
            "target/tes3-validator-evidence/"
                + directory.getFileName()
                + "/"
                + archive.getFileName());
    Files.createDirectories(evidence);
    Path transcript = evidence.resolve("adapter.log");
    Process process =
        new ProcessBuilder(
                "pwsh",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                root.resolve("build/run-tes3-validator.ps1").toString(),
                "-InputPath",
                archive.toString(),
                "-WorkingDirectory",
                directory.toString(),
                "-EvidenceDirectory",
                evidence.toString())
            .redirectErrorStream(true)
            .redirectOutput(transcript.toFile())
            .start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Independent validator timed out");
      assertEquals(
          0, process.exitValue(), () -> "Independent validation failed; see " + transcript);
    } finally {
      // Tests own the observing process even if its deadline assertion fails.
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  /** Materializes committed hexadecimal wire data without a production encoder or parser. */
  private Path fixture(String scenario) throws Exception {
    Path vector =
        Path.of(System.getProperty("jbsa.reactor.root"))
            .resolve("tests/fixtures/tes3/tes3-" + scenario + ".hex");
    return Files.write(
        directory.resolve("tes3-" + scenario + ".bsa"),
        HexFormat.of().parseHex(Files.readString(vector).strip()));
  }

  /** Compares public detached facts and consumes the owned channel through terminal EOF. */
  private static void assertEntry(ArchiveEntry entry, String name, long hash, String payload)
      throws Exception {
    var metadata = entry.metadata();
    assertEquals(name, metadata.displayName());
    assertEquals(hash, assertInstanceOf(EntryMetadata.Tes3.class, metadata.facts()).nameHash());
    byte[] expected = HexFormat.of().parseHex(payload);
    assertEquals(expected.length, metadata.storedSize());
    assertEquals(expected.length, metadata.decodedSize());
    try (EntryContent content = entry.openContent()) {
      assertTrue(content.assessment().isEmpty());
      assertArrayEquals(expected, Channels.newInputStream(content).readAllBytes());
      assertTrue(content.assessment().isPresent());
    }
  }
}
