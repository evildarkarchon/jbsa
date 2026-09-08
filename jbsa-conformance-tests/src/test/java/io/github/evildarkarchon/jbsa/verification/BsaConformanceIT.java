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
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Independent wire and local differential evidence for 0x67, not a full CV1 or binary claim. */
@Tag("bsa")
final class BsaConformanceIT {
  @TempDir Path directory;

  /** Binds this slice evidence to the exact immutable manifest shipped in the public artifact. */
  @Test
  void packagedCodecProfileMatchesEvidenceIdentity() throws Exception {
    try (var stream =
        BethesdaArchives.class.getResourceAsStream("/META-INF/jbsa-codec-profile.json")) {
      assertNotNull(stream);
      byte[] manifest = stream.readAllBytes();
      assertEquals(
          "b9515f305ba223111b790ad06c98580360ac85c40235258316aad2ba001a3fda",
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest)));
      assertTrue(
          new String(manifest, java.nio.charset.StandardCharsets.UTF_8)
              .contains("\"profile_id\":\"jbsa-jdk-zlib-v1\""));
    }
  }

  /** Reproduces all committed vectors and provenance without invoking the product writer. */
  @Test
  void independentlyGeneratedCorpusMatchesCommittedInventory() throws Exception {
    Path generated = directory.resolve("generated");
    run(
        List.of(
            "python",
            root().resolve("build/generate-bsa-fixtures.py").toString(),
            "--output",
            generated.toString()),
        directory.resolve("generation.log"));
    Path committed = root().resolve("tests/fixtures/tes4");
    try (var fresh = Files.list(generated);
        var recorded = Files.list(committed)) {
      var expected = fresh.map(path -> path.getFileName().toString()).sorted().toList();
      var actual =
          recorded
              .map(path -> path.getFileName().toString())
              .filter(name -> !name.equals("README.md"))
              .sorted()
              .toList();
      assertEquals(6, expected.size());
      assertEquals(expected, actual);
      for (String name : expected) {
        assertEquals(-1L, Files.mismatch(generated.resolve(name), committed.resolve(name)), name);
      }
    }
  }

  /**
   * Checks independently authored stored, zlib and mixed payloads through public ownership seams.
   */
  @Test
  void readsAndExtractsIndependentWireVectors() throws Exception {
    for (String mode : List.of("stored", "zlib", "mixed")) {
      Path source = fixture(mode);
      validate(source);
      assertEquals(
          ArchiveFamily.TES4_BSA,
          BethesdaArchives.standard().detect(source).family().orElseThrow());
      assertEquals(
          ArchiveDisposition.CONFORMING,
          BethesdaArchives.standard().inspect(source).assessment().disposition());
      assertArchive(source);
      Path extracted = directory.resolve("extracted-" + mode);
      BethesdaArchives.standard()
          .extract(ExtractRequest.standard(source, extracted), OperationControl.standard());
      assertArrayEquals(payload(0), Files.readAllBytes(extracted.resolve("meshes/a.nif")));
      assertArrayEquals(payload(1), Files.readAllBytes(extracted.resolve("meshes/b.nif")));
    }
  }

  /**
   * Structural truncation is rejected before entry access; decoded-size corruption fails at EOF.
   */
  @Test
  void rejectsIndependentMalformedPayloads() throws Exception {
    ArchiveException truncated =
        assertThrows(
            ArchiveException.class,
            () -> BethesdaArchives.standard().inspect(fixture("truncated-payload")));
    assertEquals(FailureKind.FORMAT, truncated.kind());
    try (OpenArchive archive =
        BethesdaArchives.standard()
            .open(fixture("decoded-size-mismatch"), OpenOptions.standard())) {
      ArchiveException mismatch =
          assertThrows(
              ArchiveException.class,
              () -> {
                try (EntryContent content = archive.entry(0).openContent()) {
                  Channels.newInputStream(content).readAllBytes();
                }
              });
      assertEquals(FailureKind.FORMAT, mismatch.kind());
    }
  }

  /** A separate scanner checks both writer modes; only stored bytes use a fixed wire vector. */
  @Test
  void independentlyValidatesPackedArchives() throws Exception {
    Path sources = sources();
    for (var compression : List.of(PackOptions.Compression.STORED, PackOptions.Compression.ZLIB)) {
      Path output = directory.resolve("packed-" + compression + ".bsa");
      pack(sources, output, compression);
      validate(output);
      assertArchive(output);
      if (compression == PackOptions.Compression.STORED) {
        assertEquals(-1L, Files.mismatch(fixture("stored"), output));
      }
    }
  }

  /** Runs both differential directions for stored and zlib against the pinned local executable. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.bsa.local", matches = "true")
  void pinnedLocalOracleCrossDecodesBothDirections() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isRegularFile(root().resolve("tests/fixtures/local/oracle/BSArch.exe")),
        "UNAVAILABLE: the pinned local oracle is absent");
    Path sources = sources();
    for (String mode : List.of("stored", "zlib")) {
      Path oracleArchive = directory.resolve("oracle-" + mode + ".bsa");
      oracle("pack", sources, oracleArchive, mode, "oracle-to-jbsa-" + mode);
      validate(oracleArchive);
      assertArchive(oracleArchive);
      Path candidate = directory.resolve("candidate-" + mode + ".bsa");
      pack(
          sources,
          candidate,
          mode.equals("stored") ? PackOptions.Compression.STORED : PackOptions.Compression.ZLIB);
      validate(candidate);
      // BSArch requires an existing output directory, unlike the public library request.
      Path extracted = Files.createDirectory(directory.resolve("oracle-extracted-" + mode));
      oracle("unpack", candidate, extracted, mode, "jbsa-to-oracle-" + mode);
      for (String name : List.of("meshes/a.nif", "meshes/b.nif")) {
        assertEquals(-1L, Files.mismatch(sources.resolve(name), extracted.resolve(name)));
      }
    }
  }

  /**
   * Packs sequentially with sharing and splitting disabled to isolate the fixture's wire layout.
   */
  private void pack(Path sources, Path output, PackOptions.Compression compression)
      throws Exception {
    PackRequest defaults =
        PackRequest.standard(
            output,
            ArchiveFamily.TES4_BSA,
            new ArchiveEncoding(
                Optional.of(new WireVersion(103)), Optional.empty(), OptionalLong.empty()),
            List.of(new PackSource.DetectedPath(sources)),
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
                    FlagSelection.AUTOMATIC,
                    FlagSelection.AUTOMATIC),
                Optional.empty()),
            OperationControl.standard());
  }

  /** Confirms serialized ordering and consumes each owned content channel through terminal EOF. */
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

  /** Materializes the two project-authored payloads into a fresh source tree. */
  private Path sources() throws Exception {
    Path sources = Files.createDirectories(directory.resolve("sources/meshes"));
    Files.write(sources.resolve("a.nif"), payload(0));
    Files.write(sources.resolve("b.nif"), payload(1));
    return sources.getParent();
  }

  /** Returns exact source bytes independently of any archive reader. */
  private static byte[] payload(int index) {
    if (index == 1) return HexFormat.of().parseHex("000102ff");
    byte[] bytes = new byte[1024];
    Arrays.fill(bytes, (byte) 'A');
    return bytes;
  }

  /** Decodes committed hexadecimal bytes without calling the production encoder. */
  private Path fixture(String mode) throws Exception {
    return Files.write(
        directory.resolve(mode + ".bsa"),
        HexFormat.of()
            .parseHex(
                Files.readString(root().resolve("tests/fixtures/tes4/bsa-067-" + mode + ".hex"))
                    .strip()));
  }

  /** Retains independent scanner identity, raw streams and semantic comparison under target. */
  private void validate(Path archive) throws Exception {
    Path evidence =
        root()
            .resolve(
                "target/bsa-validator-evidence/"
                    + directory.getFileName()
                    + "/"
                    + archive.getFileName());
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
            archive.toString(),
            "-WorkingDirectory",
            directory.toString(),
            "-EvidenceDirectory",
            evidence.toString()),
        evidence.resolve("adapter.log"));
  }

  /**
   * Invokes the existing oracle trust boundary and retains exact observations for each direction.
   */
  private void oracle(String operation, Path input, Path output, String mode, String direction)
      throws Exception {
    Path evidence =
        root().resolve("target/bsa-local-evidence/" + directory.getFileName() + "/" + direction);
    Files.createDirectories(evidence);
    run(
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
            mode,
            "-WorkingDirectory",
            directory.toString(),
            "-EvidenceDirectory",
            evidence.toString()),
        evidence.resolve("adapter.log"));
  }

  /** Owns each bounded observer process through completion or forcible deadline cleanup. */
  private static void run(List<String> command, Path log) throws Exception {
    Process process =
        new ProcessBuilder(new ArrayList<>(command))
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Observer timed out: " + log);
      assertEquals(0, process.exitValue(), () -> "Observer failed; see " + log);
    } finally {
      // A test deadline or failed assertion must not leave the observing process running.
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static Path root() {
    return Path.of(System.getProperty("jbsa.reactor.root"));
  }
}
