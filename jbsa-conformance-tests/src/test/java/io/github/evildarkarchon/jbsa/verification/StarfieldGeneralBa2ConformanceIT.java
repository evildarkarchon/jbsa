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

/** Independent wire, public behavior, and local differential evidence for Starfield General BA2. */
@Tag("ba2")
final class StarfieldGeneralBa2ConformanceIT {
  @TempDir Path directory;

  /** Decodes and extracts project-authored v2 zlib and v3 method-3 raw-LZ4 vectors. */
  @Test
  void readsAndExtractsProjectAuthoredVersions() throws Exception {
    List<Fixture> fixtures =
        List.of(
            new Fixture(versionTwo(), 2, OptionalLong.empty(), "a\\b.txt", new byte[] {7}),
            new Fixture(
                versionThree(),
                3,
                OptionalLong.of(3),
                "data\\raw.txt",
                "jbsa-starfield\n".getBytes(java.nio.charset.StandardCharsets.US_ASCII)));
    for (Fixture fixture : fixtures) {
      ArchiveInspection inspection = BethesdaArchives.standard().inspect(fixture.path());
      assertEquals(ArchiveFamily.STARFIELD_GENERAL_BA2, inspection.metadata().family());
      assertEquals(
          fixture.version(), inspection.metadata().encoding().wireVersion().orElseThrow().value());
      assertEquals(fixture.method(), inspection.metadata().encoding().compressionMethod());
      try (OpenArchive archive =
              BethesdaArchives.standard().open(fixture.path(), OpenOptions.standard());
          EntryContent content = archive.entry(0).openContent()) {
        assertEquals(fixture.name(), archive.entry(0).metadata().displayName());
        assertArrayEquals(fixture.payload(), Channels.newInputStream(content).readAllBytes());
      }
      Path extracted = directory.resolve("extract-v" + fixture.version());
      BethesdaArchives.standard()
          .extract(ExtractRequest.standard(fixture.path(), extracted), OperationControl.standard());
      assertArrayEquals(
          fixture.payload(),
          Files.readAllBytes(extracted.resolve(fixture.name().replace('\\', '/'))));
    }
  }

  /** Emits v2 for stored/zlib and v3 method 3 for raw-LZ4 while preserving ordered content. */
  @Test
  void writesCodecSelectedHeadersAndRoundTrips() throws Exception {
    for (PackOptions.Compression compression :
        List.of(
            PackOptions.Compression.STORED,
            PackOptions.Compression.ZLIB,
            PackOptions.Compression.LZ4_RAW)) {
      Path archive = pack(compression, Map.of());
      ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(archive)).order(ByteOrder.LITTLE_ENDIAN);
      int version = compression == PackOptions.Compression.LZ4_RAW ? 3 : 2;
      assertEquals(version, wire.getInt(4));
      assertEquals(1, wire.getLong(24));
      if (version == 3) assertEquals(3, wire.getInt(32));
      assertArchive(archive);
    }
  }

  /** Keeps stored entries in a v3 method-3 archive while raw-LZ4 owns compressed entries. */
  @Test
  void writesMixedStoredAndRawLz4Entries() throws Exception {
    Path archive =
        pack(
            PackOptions.Compression.LZ4_RAW,
            Map.of(new NormalizedNameIdentity("meshes\\b.nif"), PackOptions.Compression.STORED));
    ByteBuffer wire = ByteBuffer.wrap(Files.readAllBytes(archive)).order(ByteOrder.LITTLE_ENDIAN);
    assertTrue(wire.getInt(60) > 0);
    assertEquals(0, wire.getInt(96));
    assertArchive(archive);
  }

  /** Rejects non-method-3 input by default and safely contains invalid raw-LZ4 payloads. */
  @Test
  void rejectsUnsupportedMethodAndInvalidRawLz4() throws Exception {
    Path unsupported =
        root().resolve("tests/fixtures/synthetic/artifacts/malformed/unsupported-v3-method.ba2");
    ArchiveException methodFailure =
        assertThrows(
            ArchiveException.class,
            () -> BethesdaArchives.standard().open(unsupported, OpenOptions.standard()));
    assertEquals(FailureKind.UNSUPPORTED, methodFailure.kind());

    byte[] invalid = Files.readAllBytes(versionThree());
    ByteBuffer.wrap(invalid).order(ByteOrder.LITTLE_ENDIAN).putInt(60, 1);
    Path invalidPath = Files.write(directory.resolve("invalid-raw.ba2"), invalid);
    try (OpenArchive archive =
            BethesdaArchives.standard().open(invalidPath, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      ByteBuffer destination = ByteBuffer.allocate(16);
      ArchiveException failure =
          assertThrows(ArchiveException.class, () -> content.read(destination));
      assertEquals(FailureKind.FORMAT, failure.kind());
      assertEquals(0, destination.position());
    }
  }

  /**
   * Validates both Starfield wire versions with a scanner independent of JBSA and its generator.
   */
  @Test
  void independentlyValidatesStarfieldWireVersions() throws Exception {
    for (Path archive :
        List.of(
            versionTwo(),
            versionThree(),
            pack(PackOptions.Compression.ZLIB, Map.of()),
            pack(PackOptions.Compression.LZ4_RAW, Map.of()))) {
      Path log = directory.resolve("validator-" + archive.getFileName() + ".json");
      run(
          List.of(
              "python",
              root().resolve("build/validate-ba2-wire.py").toString(),
              archive.toString()),
          log);
      String observation = Files.readString(log);
      assertTrue(observation.contains("\"family\":\"sf-gnrl-v"));
      assertTrue(observation.contains("\"compression_method\":"));
    }
  }

  /** Binds family adoption to the level-12 profile layered over the qualified runtime. */
  @Test
  void packagedProfileIdentifiesLevelTwelveRawLz4() throws Exception {
    try (var stream =
        BethesdaArchives.class.getResourceAsStream(
            "/META-INF/jbsa-starfield-general-lz4-profile.json")) {
      assertNotNull(stream);
      byte[] manifest = stream.readAllBytes();
      assertEquals(
          "7bf05e18f8baf343fd7ff3252b8544b6782c5940ec883bbf7e931254ba354bc9",
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(manifest)));
      String profile = new String(manifest, java.nio.charset.StandardCharsets.UTF_8);
      assertTrue(profile.contains("\"profile_id\":\"jbsa-starfield-general-lz4-v1\""));
      assertTrue(profile.contains("\"level\":12"));
    }
  }

  /**
   * Runs both digest-pinned oracle directions for stored, zlib, and raw-LZ4 when locally enabled.
   */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.ba2.local", matches = "true")
  void pinnedLocalOracleCrossDecodesEveryStarfieldCodec() throws Exception {
    org.junit.jupiter.api.Assumptions.assumeFalse("true".equals(System.getenv("GITHUB_ACTIONS")));
    org.junit.jupiter.api.Assumptions.assumeTrue(
        Files.isRegularFile(root().resolve("tests/fixtures/local/oracle/BSArch.exe")),
        "UNAVAILABLE: the pinned local oracle is absent");
    Path sources = sources();
    for (String codec : List.of("stored", "zlib", "raw-lz4")) {
      Path oracleArchive = directory.resolve("oracle-" + codec + ".ba2");
      oracle("pack", sources, oracleArchive, codec, "oracle-to-jbsa-" + codec);
      assertArchive(oracleArchive);
      PackOptions.Compression compression =
          switch (codec) {
            case "stored" -> PackOptions.Compression.STORED;
            case "zlib" -> PackOptions.Compression.ZLIB;
            default -> PackOptions.Compression.LZ4_RAW;
          };
      Path candidate = pack(compression, Map.of());
      Path extracted = Files.createDirectory(directory.resolve("oracle-output-" + codec));
      oracle("unpack", candidate, extracted, codec, "jbsa-to-oracle-" + codec);
      for (String name : List.of("meshes/a.nif", "meshes/b.nif"))
        assertEquals(-1L, Files.mismatch(sources.resolve(name), extracted.resolve(name)));
    }
  }

  /** Packs two deterministic source entries through the public Starfield General boundary. */
  private Path pack(
      PackOptions.Compression compression,
      Map<NormalizedNameIdentity, PackOptions.Compression> overrides)
      throws Exception {
    boolean raw = compression == PackOptions.Compression.LZ4_RAW;
    Path output = directory.resolve("packed-" + compression + "-" + overrides.size() + ".ba2");
    var defaults =
        PackRequest.standard(
            output,
            ArchiveFamily.STARFIELD_GENERAL_BA2,
            new ArchiveEncoding(
                Optional.of(new WireVersion(raw ? 3 : 2)),
                Optional.of(Ba2Subtype.GNRL),
                raw ? OptionalLong.of(3) : OptionalLong.empty()),
            List.of(new PackSource.DetectedPath(sources())),
            Optional.empty());
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
                    FlagSelection.AUTOMATIC,
                    overrides),
                Optional.empty()),
            OperationControl.standard());
    return output;
  }

  /** Confirms the two deterministic entries and consumes their payload validation to EOF. */
  private void assertArchive(Path archivePath) throws Exception {
    try (OpenArchive archive =
        BethesdaArchives.standard().open(archivePath, OpenOptions.standard())) {
      assertEquals(ArchiveFamily.STARFIELD_GENERAL_BA2, archive.inspection().metadata().family());
      assertEquals(2, archive.entryCount());
      for (int index = 0; index < 2; index++) {
        try (EntryContent content = archive.entry(index).openContent()) {
          assertArrayEquals(payload(index), Channels.newInputStream(content).readAllBytes());
          assertTrue(content.assessment().isPresent());
        }
      }
    }
  }

  /** Materializes the independently authored version-2 hexadecimal vector. */
  private Path versionTwo() throws Exception {
    return Files.write(
        directory.resolve("starfield-v2.ba2"),
        HexFormat.of()
            .parseHex(
                Files.readString(
                        root()
                            .resolve(
                                "tests/fixtures/starfield-general/starfield-general-v2-zlib.hex"))
                    .strip()));
  }

  /** Returns the committed project-authored raw-LZ4 archive from the synthetic corpus. */
  private static Path versionThree() {
    return root().resolve("tests/fixtures/synthetic/artifacts/archives/sf-gnrl-v3-m3-raw-lz4.ba2");
  }

  /** Materializes deterministic source bytes independently of the archive encoder. */
  private Path sources() throws Exception {
    Path folder = Files.createDirectories(directory.resolve("sources/meshes"));
    Files.write(folder.resolve("a.nif"), payload(0));
    Files.write(folder.resolve("b.nif"), payload(1));
    return folder.getParent();
  }

  /** Returns deterministic independent source bytes for one logical ordinal. */
  private static byte[] payload(int index) {
    if (index == 1) return HexFormat.of().parseHex("000102ff");
    return "starfield-general".repeat(64).getBytes(java.nio.charset.StandardCharsets.US_ASCII);
  }

  /** Invokes the digest-pinned local oracle without importing its behavior into this test. */
  private void oracle(String operation, Path input, Path output, String codec, String direction)
      throws Exception {
    Path evidence = root().resolve("target/ba2-local-evidence/issue45/" + direction);
    Files.createDirectories(evidence);
    run(
        List.of(
            "pwsh",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-File",
            root().resolve("build/run-ba2-oracle.ps1").toString(),
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

  /** Owns an independent observer process through completion and deadline cleanup. */
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
      // A failed assertion or timeout must not leave a build-owned observer running.
      if (process.isAlive()) process.destroyForcibly();
    }
  }

  /** Resolves committed fixtures and build tools from the configured reactor root. */
  private static Path root() {
    return Path.of(System.getProperty("jbsa.reactor.root"));
  }

  /** Immutable expected semantics for one independently authored archive. */
  private record Fixture(
      Path path, long version, OptionalLong method, String name, byte[] payload) {
    /** Prevents mutation of expected fixture bytes after construction. */
    private Fixture {
      payload = payload.clone();
    }

    /** Returns an independent payload copy for every assertion. */
    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }
}
