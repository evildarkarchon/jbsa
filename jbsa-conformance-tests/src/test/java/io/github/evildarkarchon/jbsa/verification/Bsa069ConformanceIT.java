package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
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

/** Independent 0x69 SE/AE evidence; neither activated CV1 nor Binary Conformance. */
@Tag("bsa")
final class Bsa069ConformanceIT {
  @TempDir Path directory;

  /** Binds family evidence to the exact profile manifest and packaged digest sidecar. */
  @Test
  void packagedFamilyCodecProfileMatchesEvidenceIdentity() throws Exception {
    byte[] manifest;
    try (var stream =
        BethesdaArchives.class.getResourceAsStream("/META-INF/jbsa-bsa-069-lz4-profile.json")) {
      assertNotNull(stream);
      manifest = stream.readAllBytes();
    }
    String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest));
    assertEquals("3eb01cfdf11f0052406682b4cb0ffd7814de095d7dd2a543ed9d9441fa41ef85", digest);
    assertTrue(
        new String(manifest, java.nio.charset.StandardCharsets.UTF_8)
            .contains("\"profile_id\":\"jbsa-bsa-069-lz4-v1\""));
    try (var stream =
        BethesdaArchives.class.getResourceAsStream("/META-INF/jbsa-bsa-069-lz4-profile.sha256")) {
      assertNotNull(stream);
      assertEquals(
          digest,
          new String(stream.readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII).strip());
    }
  }

  /** Reproduces every committed SE/AE vector and validates the independent scanner itself. */
  @Test
  void independentlyGeneratedCorpusMatchesCommittedInventory() throws Exception {
    Path generated = directory.resolve("generated");
    run(
        List.of(
            "python",
            root().resolve("build/generate-bsa069-fixtures.py").toString(),
            "--output",
            generated.toString()),
        directory.resolve("generator.log"));
    Path committed = root().resolve("tests/fixtures/bsa069");
    try (var fresh = Files.list(generated);
        var recorded = Files.list(committed)) {
      var expected = fresh.map(path -> path.getFileName().toString()).sorted().toList();
      var actual =
          recorded
              .map(path -> path.getFileName().toString())
              .filter(name -> !name.equals("README.md"))
              .sorted()
              .toList();
      assertEquals(13, expected.size());
      assertEquals(expected, actual);
      for (String name : expected)
        assertEquals(-1L, Files.mismatch(generated.resolve(name), committed.resolve(name)), name);
    }
    run(
        List.of("python", root().resolve("build/test-bsa069-validator.py").toString()),
        directory.resolve("scanner-tests.log"));
  }

  /** Reads, independently validates, and extracts every committed family/mode vector. */
  @Test
  void readsAndExtractsIndependentGameVectors() throws Exception {
    for (String game : List.of("skyrim-se", "skyrim-ae")) {
      for (String mode : List.of("stored", "lz4-frame", "mixed")) {
        for (boolean embedded : List.of(false, true)) {
          String label = game + "-" + mode + (embedded ? "-embedded" : "");
          Path source = fixture(label);
          validate(source);
          assertEquals(
              ArchiveFamily.SSE_BSA,
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

  /** Independently validates public stored, LZ4-frame, mixed, and embedded output. */
  @Test
  void independentlyValidatesEveryEncodeMode() throws Exception {
    Path source = sources();
    for (String mode : List.of("stored", "lz4-frame", "mixed")) {
      for (boolean embedded : List.of(false, true)) {
        Path output = directory.resolve("candidate-" + mode + "-" + embedded + ".bsa");
        pack(source, output, mode, embedded);
        validate(output);
        assertArchive(output);
        if (mode.equals("stored"))
          assertEquals(
              -1L,
              Files.mismatch(fixture("skyrim-se-stored" + (embedded ? "-embedded" : "")), output));
      }
    }
  }

  /** Executes both pinned-oracle directions for stored/LZ4 framing and JBSA mixed output. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.bsa.local", matches = "true")
  void pinnedOracleCrossDecodesEveryFraming() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isRegularFile(root().resolve("tests/fixtures/local/oracle/BSArch.exe")),
        "UNAVAILABLE: pinned local oracle absent");
    Path source = sources();
    for (String mode : List.of("stored", "lz4-frame", "mixed")) {
      for (boolean embedded : List.of(false, true)) {
        String label = mode + "-" + embedded;
        Path oracleArchive = directory.resolve("oracle-" + label + ".bsa");
        oracle("pack", source, oracleArchive, mode, embedded, "oracle-" + label);
        validate(oracleArchive);
        assertArchive(oracleArchive);
        Path candidate = directory.resolve("candidate-local-" + label + ".bsa");
        pack(source, candidate, mode, embedded);
        Path extracted = Files.createDirectory(directory.resolve("oracle-extracted-" + label));
        oracle("unpack", candidate, extracted, mode, embedded, "candidate-" + label);
        assertTree(extracted);
      }
    }
  }

  /** Packs the exact public SSE model with deterministic ordering and framing controls. */
  private void pack(Path source, Path output, String mode, boolean embedded) throws Exception {
    var compression =
        mode.equals("stored") ? PackOptions.Compression.STORED : PackOptions.Compression.LZ4_FRAME;
    var overrides =
        mode.equals("mixed")
            ? Map.of(new NormalizedNameIdentity("meshes\\b.nif"), PackOptions.Compression.STORED)
            : Map.<NormalizedNameIdentity, PackOptions.Compression>of();
    var defaults =
        PackRequest.standard(
            output,
            ArchiveFamily.SSE_BSA,
            new ArchiveEncoding(
                Optional.of(new WireVersion(105)), Optional.empty(), OptionalLong.empty()),
            List.of(new PackSource.DetectedPath(source)),
            Optional.empty());
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

  /** Checks public order and exact payload bytes through terminal content EOF. */
  private void assertArchive(Path source) throws Exception {
    try (var archive = BethesdaArchives.standard().open(source, OpenOptions.standard())) {
      assertEquals(2, archive.entryCount());
      for (int index = 0; index < 2; index++) {
        var entry = archive.entry(index);
        assertEquals("meshes\\" + (index == 0 ? "a.nif" : "b.nif"), entry.metadata().displayName());
        try (var content = entry.openContent()) {
          assertArrayEquals(payload(index), Channels.newInputStream(content).readAllBytes());
          assertTrue(content.assessment().isPresent());
        }
      }
    }
  }

  /** Verifies extracted source files independently of archive interpretation. */
  private void assertTree(Path path) throws Exception {
    assertArrayEquals(payload(0), Files.readAllBytes(path.resolve("meshes/a.nif")));
    assertArrayEquals(payload(1), Files.readAllBytes(path.resolve("meshes/b.nif")));
  }

  /** Materializes redistributable source bytes outside any timed operation. */
  private Path sources() throws Exception {
    Path path = Files.createDirectories(directory.resolve("sources/meshes"));
    Files.write(path.resolve("a.nif"), payload(0));
    Files.write(path.resolve("b.nif"), payload(1));
    return path.getParent();
  }

  /** Returns source content defined independently of an archive implementation. */
  private static byte[] payload(int index) {
    if (index == 1) return HexFormat.of().parseHex("000102ff");
    byte[] bytes = new byte[1024];
    Arrays.fill(bytes, (byte) 'A');
    return bytes;
  }

  /** Decodes one committed hexadecimal vector without invoking a product writer. */
  private Path fixture(String name) throws Exception {
    return Files.write(
        directory.resolve(name + ".bsa"),
        HexFormat.of()
            .parseHex(
                Files.readString(root().resolve("tests/fixtures/bsa069/" + name + ".hex"))
                    .strip()));
  }

  /** Retains independent-validator identity and semantic observations for one archive. */
  private void validate(Path path) throws Exception {
    Path evidence =
        root()
            .resolve(
                "target/bsa069-validator-evidence/"
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
            "bsa-069"),
        evidence.resolve("adapter.log"));
  }

  /** Invokes the digest-pinned oracle and retains the bounded external observation. */
  private void oracle(
      String operation, Path input, Path output, String mode, boolean embedded, String label)
      throws Exception {
    Path evidence =
        root().resolve("target/bsa069-local-evidence/" + directory.getFileName() + "/" + label);
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
                mode.equals("stored") ? "stored" : "lz4-frame",
                "-Selector",
                "sse",
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

  /** Returns the repository root supplied by the conformance test task. */
  private static Path root() {
    return Path.of(System.getProperty("jbsa.reactor.root"));
  }
}
