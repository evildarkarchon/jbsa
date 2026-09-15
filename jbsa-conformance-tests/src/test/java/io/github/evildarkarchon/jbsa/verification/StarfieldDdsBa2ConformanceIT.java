package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
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

/** Independent wire, public behavior, and local differential evidence for Starfield DDS BA2. */
@Tag("dds")
final class StarfieldDdsBa2ConformanceIT {
  private static final byte[] EXPECTED_MIP = HexFormat.of().parseHex("630e873656a6ce50");

  @TempDir Path directory;

  /** Decodes, reconstructs, and extracts project-authored v2 zlib and v3 raw-LZ4 vectors. */
  @Test
  void readsAndExtractsProjectAuthoredVersions() throws Exception {
    for (Fixture fixture : fixtures()) {
      ArchiveInspection inspection = BethesdaArchives.standard().inspect(fixture.path());
      assertEquals(ArchiveFamily.STARFIELD_DDS_BA2, inspection.metadata().family());
      assertEquals(
          fixture.version(), inspection.metadata().encoding().wireVersion().orElseThrow().value());
      assertEquals(fixture.method(), inspection.metadata().encoding().compressionMethod());
      assertEquals("textures\\a.dds", inspection.entries().getFirst().displayName());
      assertMip(fixture.path());

      Path extracted = directory.resolve("extract-v" + fixture.version());
      BethesdaArchives.standard()
          .extract(ExtractRequest.standard(fixture.path(), extracted), OperationControl.standard());
      assertArrayEquals(
          EXPECTED_MIP,
          tail(Files.readAllBytes(extracted.resolve("textures/a.dds")), EXPECTED_MIP.length));
    }
  }

  /** Emits the canonical Starfield header and chunk codec selected by the pack request. */
  @Test
  void writesCodecSelectedHeadersAndRoundTrips() throws Exception {
    for (PackOptions.Compression compression :
        List.of(PackOptions.Compression.ZLIB, PackOptions.Compression.LZ4_RAW)) {
      Path archive = pack(compression);
      ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(archive)).order(ByteOrder.LITTLE_ENDIAN);
      int version = compression == PackOptions.Compression.LZ4_RAW ? 3 : 2;
      assertEquals(version, wire.getInt(4));
      assertEquals(1, wire.getLong(24));
      if (version == 3) assertEquals(3, wire.getInt(32));
      assertMip(archive);
    }
  }

  /** Corroborates both committed and freshly encoded versions with the independent wire scanner. */
  @Test
  void independentlyValidatesStarfieldWireVersions() throws Exception {
    for (Path archive :
        List.of(
            fixtures().get(0).path(),
            fixtures().get(1).path(),
            pack(PackOptions.Compression.ZLIB),
            pack(PackOptions.Compression.LZ4_RAW))) {
      Path log = directory.resolve("validator-" + archive.getFileName() + ".json");
      run(
          List.of(
              "python",
              root().resolve("build/validate-dds-wire.py").toString(),
              archive.toString()),
          log);
      String observation = Files.readString(log);
      assertTrue(observation.contains("\"family\": \"sf-dx10-v"), observation);
      assertTrue(observation.contains("\"payload_sha256\""), observation);
    }
  }

  /** Contains invalid selectors and codec bytes and enforces the decoded-content ceiling. */
  @Test
  void rejectsUnsupportedMethodInvalidRawLz4AndResourceLimits() throws Exception {
    byte[] unsupportedBytes = Files.readAllBytes(fixtures().get(1).path());
    ByteBuffer.wrap(unsupportedBytes).order(ByteOrder.LITTLE_ENDIAN).putInt(32, 2);
    Path unsupported = Files.write(directory.resolve("unsupported-method.ba2"), unsupportedBytes);
    assertEquals(
        FailureKind.UNSUPPORTED,
        assertThrows(
                ArchiveException.class,
                () -> BethesdaArchives.standard().open(unsupported, OpenOptions.standard()))
            .kind());

    byte[] invalidBytes = Files.readAllBytes(fixtures().get(1).path());
    invalidBytes[84] = 0x70;
    Path invalid = Files.write(directory.resolve("invalid-raw-lz4.ba2"), invalidBytes);
    try (OpenArchive archive = BethesdaArchives.standard().open(invalid, OpenOptions.standard())) {
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class,
                  () -> {
                    try (EntryContent content = archive.entry(0).openContent()) {
                      Channels.newInputStream(content).readAllBytes();
                    }
                  })
              .kind());
    }

    ResourceLimits standard = ResourceLimits.standard();
    OpenOptions bounded =
        new OpenOptions(
            Optional.empty(),
            new ResourceLimits(
                standard.maxEntries(),
                standard.maxMetadataBytes(),
                135,
                standard.maxScratchBytes(),
                standard.maxOutputs(),
                standard.maxDiagnostics(),
                standard.maxSecondaryFailures()),
            Optional.empty());
    try (OpenArchive archive =
        BethesdaArchives.standard().open(fixtures().get(1).path(), bounded)) {
      assertEquals(
          FailureKind.POLICY,
          assertThrows(
                  ArchiveException.class,
                  () -> {
                    try (EntryContent content = archive.entry(0).openContent()) {
                      Channels.newInputStream(content).readAllBytes();
                    }
                  })
              .kind());
    }
  }

  /** Preserves opaque mip bytes across the version, codec, and reconstruction interactions. */
  @Test
  void reconstructsOpaqueMipPayloadAcrossVersionAndCodecInteractions() throws Exception {
    for (PackOptions.Compression compression :
        List.of(PackOptions.Compression.ZLIB, PackOptions.Compression.LZ4_RAW)) {
      Path archive = pack(compression);
      Path extracted = directory.resolve("interaction-" + compression);
      BethesdaArchives.standard()
          .extract(ExtractRequest.standard(archive, extracted), OperationControl.standard());
      byte[] reconstructed = Files.readAllBytes(extracted.resolve("textures/a.dds"));
      assertArrayEquals(EXPECTED_MIP, tail(reconstructed, EXPECTED_MIP.length));
      assertEquals(136, reconstructed.length);
    }
  }

  /** Binds DDS family adoption to its one-block-per-chunk HC level-12 raw-LZ4 profile. */
  @Test
  void packagedProfileIdentifiesLevelTwelveRawLz4() throws Exception {
    try (var stream =
        BethesdaArchives.class.getResourceAsStream(
            "/META-INF/jbsa-starfield-dds-lz4-profile.json")) {
      assertNotNull(stream);
      byte[] manifest = stream.readAllBytes();
      assertEquals(
          "9512e3246a202ecbf2249a394f0b8ded09d6acfd6388293c71b5321d17488e9a",
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest)));
      String profile = new String(manifest, java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(profile.contains("\"profile_id\":\"jbsa-starfield-dds-lz4-v1\""));
      assertTrue(profile.contains("\"level\":12"));
      assertTrue(profile.contains("\"blocks_per_chunk\":1"));
      assertTrue(profile.contains("\"complete_input\":true"));
      assertTrue(profile.contains("\"exact_decoded_size\":true"));
    }
  }

  /** DirectXTex accepts all independently generated DDS files after both Starfield round trips. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.directxtex.path", matches = ".+")
  void directXTexAcceptsEveryStarfieldReconstruction() throws Exception {
    Path source = root().resolve("target/dds-validator-fixtures/source");
    assertTrue(Files.isDirectory(source), "Run build/generate-dds-fixtures.py first");
    for (PackOptions.Compression compression :
        List.of(PackOptions.Compression.ZLIB, PackOptions.Compression.LZ4_RAW)) {
      Path archive = pack(compression, source, "directxtex-" + compression);
      Path extracted = directory.resolve("directxtex-output-" + compression);
      BethesdaArchives.standard()
          .extract(ExtractRequest.standard(archive, extracted), OperationControl.standard());
      List<Path> reconstructed;
      try (var files = Files.walk(extracted)) {
        reconstructed = files.filter(path -> path.toString().endsWith(".dds")).sorted().toList();
      }
      assertEquals(33, reconstructed.size());
      for (int index = 0; index < reconstructed.size(); index++) {
        runTexdiag(reconstructed.get(index), compression + "-" + index);
      }
    }
  }

  /** Runs both digest-pinned oracle directions for v2 zlib and v3 raw-LZ4 when enabled. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.dds.local", matches = "true")
  void pinnedLocalOracleCrossDecodesBothDirections() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isRegularFile(root().resolve("tests/fixtures/local/oracle/BSArch.exe")),
        "UNAVAILABLE: the pinned local oracle is absent");
    Path source = source();
    for (String codec : List.of("zlib", "raw-lz4")) {
      Path oracleArchive = directory.resolve("oracle-" + codec + ".ba2");
      oracle("pack", source, oracleArchive, codec, "oracle-to-jbsa-" + codec);
      assertMip(oracleArchive);

      PackOptions.Compression compression =
          codec.equals("zlib") ? PackOptions.Compression.ZLIB : PackOptions.Compression.LZ4_RAW;
      Path candidate = pack(compression);
      Path extracted = Files.createDirectory(directory.resolve("oracle-output-" + codec));
      oracle("unpack", candidate, extracted, codec, "jbsa-to-oracle-" + codec);
      assertEquals(
          -1L,
          Files.mismatch(source.resolve("textures/a.dds"), extracted.resolve("textures/a.dds")));
      runTexdiagIfConfigured(extracted.resolve("textures/a.dds"), codec);
    }
  }

  /** Packs the independent DDS source through the public Starfield DDS boundary. */
  private Path pack(PackOptions.Compression compression) throws Exception {
    return pack(compression, source(), "packed-" + compression);
  }

  /** Packs one DDS source tree with an explicit Starfield codec and deterministic output name. */
  private Path pack(PackOptions.Compression compression, Path source, String outputName)
      throws Exception {
    boolean raw = compression == PackOptions.Compression.LZ4_RAW;
    Path output = directory.resolve(outputName + ".ba2");
    var defaults =
        PackRequest.standard(
            output,
            ArchiveFamily.STARFIELD_DDS_BA2,
            new ArchiveEncoding(
                Optional.of(new WireVersion(raw ? 3 : 2)),
                Optional.of(Ba2Subtype.DX10),
                raw ? OptionalLong.of(3) : OptionalLong.empty()),
            List.of(new PackSource.DetectedPath(source)),
            Optional.of(DdsTarget.PC));
    BethesdaArchives.standard()
        .pack(
            new PackRequest(
                defaults.destination(),
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
                defaults.ddsTarget()),
            OperationControl.standard());
    return output;
  }

  /** Confirms reconstructed content consumes the complete public entry channel. */
  private static void assertMip(Path archivePath) throws Exception {
    try (OpenArchive archive =
            BethesdaArchives.standard().open(archivePath, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(ArchiveFamily.STARFIELD_DDS_BA2, archive.inspection().metadata().family());
      byte[] dds = Channels.newInputStream(content).readAllBytes();
      assertArrayEquals(EXPECTED_MIP, tail(dds, EXPECTED_MIP.length));
      assertTrue(content.assessment().isPresent());
    }
  }

  /** Materializes a canonical reconstructed DDS source independently of the encoder under test. */
  private Path source() throws Exception {
    Path root = Files.createDirectories(directory.resolve("source/textures"));
    Path source = root.resolve("a.dds");
    if (!Files.exists(source)) {
      BethesdaArchives.standard()
          .extract(
              ExtractRequest.standard(fixtures().get(0).path(), root.getParent()),
              OperationControl.standard());
    }
    return root.getParent();
  }

  /** Materializes both independently authored hexadecimal archives. */
  private List<Fixture> fixtures() throws Exception {
    return List.of(
        new Fixture(materialize("starfield-dds-v2-zlib.hex"), 2, OptionalLong.empty()),
        new Fixture(materialize("starfield-dds-v3-raw-lz4.hex"), 3, OptionalLong.of(3)));
  }

  /** Decodes one committed textual wire vector into a test-owned binary archive. */
  private Path materialize(String name) throws Exception {
    Path output = directory.resolve(name.replace(".hex", ".ba2"));
    if (!Files.exists(output)) {
      Files.write(
          output,
          HexFormat.of()
              .parseHex(
                  Files.readString(root().resolve("tests/fixtures/starfield-dds").resolve(name))
                      .strip()));
    }
    return output;
  }

  /** Invokes the digest-pinned local oracle without importing its behavior into this test. */
  private void oracle(String operation, Path input, Path output, String codec, String direction)
      throws Exception {
    Path evidence = root().resolve("target/dds-local-evidence/issue46/" + direction);
    Files.createDirectories(evidence);
    run(
        List.of(
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
            codec,
            "-Family",
            "sf1",
            "-WorkingDirectory",
            directory.toString(),
            "-EvidenceDirectory",
            evidence.toString()),
        evidence.resolve("adapter.log"));
  }

  /** Applies the explicitly configured independent DDS envelope validator to local output. */
  private void runTexdiagIfConfigured(Path dds, String codec) throws Exception {
    String configured = System.getProperty("jbsa.directxtex.path");
    if (configured != null && !configured.isBlank()) {
      runTexdiag(dds, codec);
    }
  }

  /** Runs the explicitly selected DirectXTex build and retains its observation log. */
  private void runTexdiag(Path dds, String label) throws Exception {
    String configured = System.getProperty("jbsa.directxtex.path");
    assertNotNull(configured);
    run(
        List.of(Path.of(configured).toString(), "info", dds.toString()),
        root().resolve("target/dds-local-evidence/issue46/texdiag-" + label + ".log"));
  }

  /** Owns an independent observer process through completion and deadline cleanup. */
  private static void run(List<String> command, Path log) throws Exception {
    Files.createDirectories(log.getParent());
    Process process =
        new ProcessBuilder(new ArrayList<>(command))
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Observer timed out: " + log);
      assertEquals(0, process.exitValue(), () -> "Observer failed; see " + log);
    } finally {
      // A failed assertion or timeout must not leave a build-owned observer running.
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  /** Returns an exact suffix without coupling expected opaque bytes to DDS envelope selection. */
  private static byte[] tail(byte[] bytes, int count) {
    return Arrays.copyOfRange(bytes, bytes.length - count, bytes.length);
  }

  /** Resolves committed fixtures and build tools from the configured reactor root. */
  private static Path root() {
    return Path.of(System.getProperty("jbsa.reactor.root"));
  }

  /** Immutable expected header selectors for one independently authored archive. */
  private record Fixture(Path path, long version, OptionalLong method) {}
}
