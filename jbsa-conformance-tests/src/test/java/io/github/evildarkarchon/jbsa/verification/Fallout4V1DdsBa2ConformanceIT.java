package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import io.github.evildarkarchon.jbsa.fixtures.Fo4DdsV1FixtureGenerator;
import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Independent wire and public decode evidence for Fallout 4 DDS BA2 version 1. */
@Tag("dds")
final class Fallout4V1DdsBa2ConformanceIT {
  private static final byte[] EXPECTED_MIP = HexFormat.of().parseHex("630e873656a6ce50");
  private static final String EXPECTED_MIP_SHA256 =
      "22c32e6b1fc203a2a9e2d469efe300d4d024e530cc3c038e9a752a6ff7891b3d";

  @TempDir Path directory;

  /** Reproduces the committed v1 DX10 wire vector and manifest without product encoding. */
  @Test
  @Tag("archive-fixtures")
  void independentlyGeneratedVersionOneCorpusMatchesCommittedInventory() throws Exception {
    Path generated = directory.resolve("generated-v1");
    Fo4DdsV1FixtureGenerator.materialize(generated);
    Path committed = root().resolve("tests/fixtures/fo4-dds-v1");
    try (var fresh = Files.list(generated);
        var recorded = Files.list(committed)) {
      List<String> expected = fresh.map(path -> path.getFileName().toString()).sorted().toList();
      List<String> actual =
          recorded
              .map(path -> path.getFileName().toString())
              .filter(name -> !name.equals("README.md"))
              .sorted()
              .toList();
      assertEquals(List.of("fo4-dx10-v1-zlib.hex", "manifest.json"), expected);
      assertEquals(expected, actual);
      for (String name : expected) {
        assertEquals(-1L, Files.mismatch(generated.resolve(name), committed.resolve(name)), name);
      }
    }
    byte[] previousWire =
        HexFormat.of()
            .parseHex(
                Files.readString(
                        root()
                            .resolve(
                                "tests/fixtures/synthetic/artifacts/archives/fo4-dx10-v7-zlib.hex"))
                    .strip());
    ByteBuffer.wrap(previousWire).order(ByteOrder.LITTLE_ENDIAN).putInt(4, 1);
    assertArrayEquals(
        previousWire,
        HexFormat.of()
            .parseHex(Files.readString(generated.resolve("fo4-dx10-v1-zlib.hex")).strip()));
    assertThrows(IOException.class, () -> Fo4DdsV1FixtureGenerator.materialize(generated));
  }

  /** Reads, independently validates, and extracts a project-authored version 1 texture. */
  @Test
  void readsAndExtractsIndependentVersionOneWireVector() throws Exception {
    Path source = versionOneFixture();
    validate(source, "fo4-dx10-v1");
    assertEquals(
        ArchiveFamily.FO4_DDS_BA2,
        BethesdaArchives.standard().detect(source).family().orElseThrow());
    try (OpenArchive archive = BethesdaArchives.standard().open(source, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(
          1, archive.inspection().metadata().encoding().wireVersion().orElseThrow().value());
      assertEquals(1, archive.entryCount());
      assertEquals("textures\\checker.dds", archive.entry(0).metadata().displayName());
      byte[] decoded = Channels.newInputStream(content).readAllBytes();
      assertArrayEquals(
          EXPECTED_MIP, Arrays.copyOfRange(decoded, decoded.length - 8, decoded.length));
    }
    Path extracted = directory.resolve("extracted");
    BethesdaArchives.standard()
        .extract(ExtractRequest.standard(source, extracted), OperationControl.standard());
    byte[] decoded = Files.readAllBytes(extracted.resolve("textures/checker.dds"));
    assertArrayEquals(
        EXPECTED_MIP, Arrays.copyOfRange(decoded, decoded.length - 8, decoded.length));
  }

  /** Independently validates the DDS input and public v1 encoded output before decoding it. */
  @Test
  void independentlyValidatesVersionOneEncodedOutput() throws Exception {
    Path source = sourceDds();
    validate(source, "\"target\": \"PC\"");
    Path output = directory.resolve("candidate-v1.ba2");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                output,
                ArchiveFamily.FO4_DDS_BA2,
                new ArchiveEncoding(
                    Optional.of(new WireVersion(1)),
                    Optional.of(Ba2Subtype.DX10),
                    OptionalLong.empty()),
                List.of(new PackSource.NamedFile("textures/checker.dds", source)),
                Optional.of(DdsTarget.PC)),
            OperationControl.standard());
    validate(output, "fo4-dx10-v1");
    try (OpenArchive archive = BethesdaArchives.standard().open(output, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(ArchiveFamily.FO4_DDS_BA2, archive.inspection().metadata().family());
      assertEquals(
          1, archive.inspection().metadata().encoding().wireVersion().orElseThrow().value());
      byte[] decoded = Channels.newInputStream(content).readAllBytes();
      assertArrayEquals(
          EXPECTED_MIP, Arrays.copyOfRange(decoded, decoded.length - 8, decoded.length));
    }
  }

  /** Rejects truncated v1 names and corrupt zlib chunks without publishing extracted files. */
  @Test
  void rejectsMalformedVersionOneWireVectors() throws Exception {
    byte[] valid = Files.readAllBytes(versionOneFixture());
    Path truncated =
        Files.write(directory.resolve("truncated-v1.ba2"), Arrays.copyOf(valid, valid.length - 1));
    assertEquals(
        FailureKind.FORMAT,
        assertThrows(ArchiveException.class, () -> BethesdaArchives.standard().inspect(truncated))
            .kind());

    byte[] corrupt = valid.clone();
    corrupt[72] ^= 0x40;
    Path source = Files.write(directory.resolve("corrupt-zlib-v1.ba2"), corrupt);
    Path destination = directory.resolve("corrupt-extraction");
    assertEquals(
        FailureKind.FORMAT,
        assertThrows(
                ArchiveException.class,
                () ->
                    BethesdaArchives.standard()
                        .extract(
                            ExtractRequest.standard(source, destination),
                            OperationControl.standard()))
            .kind());
    assertFalse(Files.exists(destination));
  }

  /** Foreign v1 chunk framings fail before extraction can publish a texture. */
  @Test
  void rejectsForeignVersionOneDecodeFraming() throws Exception {
    byte[] valid = Files.readAllBytes(versionOneFixture());
    for (String[] framing :
        List.of(
            new String[] {"lz4-frame", "04224d18"},
            new String[] {"raw-lz4", "ff00ff00"},
            new String[] {"raw-deflate", "f348cdc9"})) {
      byte[] foreign = valid.clone();
      // v1 has no method field; this vector's first chunk starts at byte 72 and must be zlib.
      System.arraycopy(HexFormat.of().parseHex(framing[1]), 0, foreign, 72, 4);
      Path archive = Files.write(directory.resolve(framing[0] + "-v1.ba2"), foreign);
      Path destination = directory.resolve(framing[0] + "-output");
      ArchiveException failure =
          assertThrows(
              ArchiveException.class,
              () ->
                  BethesdaArchives.standard()
                      .extract(
                          ExtractRequest.standard(archive, destination),
                          OperationControl.standard()));
      assertEquals(FailureKind.FORMAT, failure.kind());
      assertFalse(Files.exists(destination));
    }
  }

  /** Foreign DDS v1 encode codecs fail before source access or destination publication. */
  @Test
  void rejectsForeignVersionOneEncodeCodecsBeforeSourceEffects() throws Exception {
    byte[] dds = Files.readAllBytes(sourceDds());
    for (PackOptions.Compression compression :
        List.of(
            PackOptions.Compression.STORED,
            PackOptions.Compression.LZ4_FRAME,
            PackOptions.Compression.LZ4_RAW)) {
      AtomicInteger opens = new AtomicInteger();
      Path destination = directory.resolve("unsupported-" + compression + ".ba2");
      PackRequest defaults =
          PackRequest.standard(
              destination,
              ArchiveFamily.FO4_DDS_BA2,
              new ArchiveEncoding(
                  Optional.of(new WireVersion(1)),
                  Optional.of(Ba2Subtype.DX10),
                  OptionalLong.empty()),
              List.of(
                  new PackSource.GeneratedEntry(
                      "textures/checker.dds",
                      dds.length,
                      () -> {
                        opens.incrementAndGet();
                        return Channels.newChannel(new java.io.ByteArrayInputStream(dds));
                      })),
              Optional.of(DdsTarget.PC));
      PackOptions selected =
          new PackOptions(
              List.of(),
              compression,
              false,
              new PackOptions.Splitting.UpToBytes(0),
              FlagSelection.AUTOMATIC,
              FlagSelection.AUTOMATIC);
      PackRequest invalid =
          new PackRequest(
              defaults.destination(),
              defaults.family(),
              defaults.encoding(),
              defaults.compatibilityProfile(),
              defaults.sources(),
              defaults.targetPolicy(),
              defaults.diagnosticPolicy(),
              defaults.resourceLimits(),
              defaults.workerSelection(),
              selected,
              defaults.ddsTarget());
      ArchiveException failure =
          assertThrows(
              ArchiveException.class,
              () -> BethesdaArchives.standard().pack(invalid, OperationControl.standard()));
      assertEquals(FailureKind.UNSUPPORTED, failure.kind());
      assertEquals(0, opens.get());
      assertFalse(Files.exists(destination));
    }
  }

  /** Cross-decodes v1 zlib in both directions against the digest-pinned local oracle. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.dds.local", matches = "true")
  void pinnedLocalOracleCrossDecodesVersionOneBothDirections() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    Path executable = root().resolve("tests/fixtures/local/oracle/BSArch.exe");
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isRegularFile(executable), "UNAVAILABLE: the pinned local oracle is absent");
    assertEquals(
        "4C34FE4173A2BD04BA52D5A6357348256EE424573785085FDAFAAB524CF7B0C2",
        HexFormat.of()
            .withUpperCase()
            .formatHex(
                MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(executable))));

    Path source = sourceDds();
    validate(source, "\"target\": \"PC\"");
    Path oracleArchive = directory.resolve("oracle-v1.ba2");
    oracle("pack", source.getParent().getParent(), oracleArchive, "oracle-to-jbsa");
    byte[] oracleWire = Files.readAllBytes(oracleArchive);
    long payloadOffset = ByteBuffer.wrap(oracleWire).order(ByteOrder.LITTLE_ENDIAN).getLong(48);
    assertTrue(payloadOffset >= 72 && payloadOffset < oracleWire.length);
    // The scanner accepts contiguous canonical payloads; BSArch aligns this chunk with zero
    // padding.
    for (int index = 72; index < payloadOffset; index++) assertEquals(0, oracleWire[index]);
    try (OpenArchive archive =
            BethesdaArchives.standard().open(oracleArchive, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(ArchiveFamily.FO4_DDS_BA2, archive.inspection().metadata().family());
      assertEquals(
          1, archive.inspection().metadata().encoding().wireVersion().orElseThrow().value());
      byte[] decoded = Channels.newInputStream(content).readAllBytes();
      assertArrayEquals(
          EXPECTED_MIP, Arrays.copyOfRange(decoded, decoded.length - 8, decoded.length));
    }

    Path candidate = directory.resolve("candidate-oracle-v1.ba2");
    BethesdaArchives.standard()
        .pack(
            PackRequest.standard(
                candidate,
                ArchiveFamily.FO4_DDS_BA2,
                new ArchiveEncoding(
                    Optional.of(new WireVersion(1)),
                    Optional.of(Ba2Subtype.DX10),
                    OptionalLong.empty()),
                List.of(new PackSource.NamedFile("textures/checker.dds", source)),
                Optional.of(DdsTarget.PC)),
            OperationControl.standard());
    validate(candidate, "fo4-dx10-v1");
    Path extracted = Files.createDirectory(directory.resolve("oracle-extracted-v1"));
    oracle("unpack", candidate, extracted, "jbsa-to-oracle");
    Path reconstructed = extracted.resolve("textures/checker.dds");
    validate(reconstructed, "\"target\": \"PC\"");
    byte[] decoded = Files.readAllBytes(reconstructed);
    assertArrayEquals(
        EXPECTED_MIP, Arrays.copyOfRange(decoded, decoded.length - 8, decoded.length));
  }

  /** Writes an independently specified 4x4 BC1 DDS with one known opaque mip block. */
  private Path sourceDds() throws Exception {
    ByteBuffer dds = ByteBuffer.allocate(136).order(ByteOrder.LITTLE_ENDIAN);
    dds.putInt(0, 0x20534444).putInt(4, 124).putInt(8, 0x21007);
    dds.putInt(12, 4).putInt(16, 4).putInt(28, 1);
    dds.putInt(76, 32).putInt(80, 4).putInt(84, 0x31545844).putInt(108, 0x1000);
    dds.position(128);
    dds.put(EXPECTED_MIP);
    Path source = Files.createDirectories(directory.resolve("source/textures"));
    return Files.write(source.resolve("checker.dds"), dds.array());
  }

  /** Decodes the committed, independently serialized version-1 DX10 archive. */
  private Path versionOneFixture() throws Exception {
    byte[] wire =
        HexFormat.of()
            .parseHex(
                Files.readString(root().resolve("tests/fixtures/fo4-dds-v1/fo4-dx10-v1-zlib.hex"))
                    .strip());
    return Files.write(directory.resolve("fo4-dx10-v1-zlib.ba2"), wire);
  }

  /**
   * Runs one pinned Fallout 4 DDS oracle direction and retains its adapter evidence.
   *
   * @param operation pack or unpack at the external CLI boundary
   * @param input input directory or archive
   * @param output output archive or preexisting directory
   * @param direction stable evidence label for this differential direction
   */
  private void oracle(String operation, Path input, Path output, String direction)
      throws Exception {
    Path evidence =
        root()
            .resolve(
                "target/dds-local-evidence/issue50/" + directory.getFileName() + "/" + direction);
    Files.createDirectories(evidence);
    Path log = evidence.resolve("adapter.log");
    Process process =
        new ProcessBuilder(
                "pwsh",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                root().resolve("build/run-dds-oracle.ps1").toString(),
                "-Operation",
                operation,
                "-InputPath",
                input.toString(),
                "-OutputPath",
                output.toString(),
                "-Compression",
                "zlib",
                "-Family",
                "fo4",
                "-WorkingDirectory",
                directory.toString(),
                "-EvidenceDirectory",
                evidence.toString())
            .directory(root().toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    // Closing the unused input pipe avoids a PowerShell startup wait with redirected streams.
    process.getOutputStream().close();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Oracle timed out; see " + log);
      assertEquals(0, process.exitValue(), () -> "Oracle rejected the archive; see " + log);
    } finally {
      if (process.isAlive()) {
        // A timeout must stop descendants before the adapter process returns ownership.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }
  }

  /** Requires the independent scanner to accept a DDS input or exact v1 archive envelope. */
  private void validate(Path source, String expectedIdentity) throws Exception {
    Path log = directory.resolve(source.getFileName() + ".validation.json");
    Process process =
        new ProcessBuilder(
                "python",
                root().resolve("build/validate-dds-wire.py").toString(),
                source.toString())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Scanner timed out; see " + log);
      assertEquals(0, process.exitValue(), () -> "Scanner rejected the archive; see " + log);
      String observation = Files.readString(log);
      assertTrue(observation.contains(expectedIdentity), observation);
      assertTrue(observation.contains(EXPECTED_MIP_SHA256), observation);
    } finally {
      // A test failure must not leave the independent scanner running.
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  private static Path root() {
    return Path.of(System.getProperty("jbsa.reactor.root"));
  }
}
