package io.github.evildarkarchon.jbsa.verification;

import static io.github.evildarkarchon.jbsa.verification.Fallout4V78Observer.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.Channels;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;

/** Decode-only public, independent-validator, oracle, and local evidence for FO4 General v7/v8. */
@Tag("ba2")
final class Fallout4V78GeneralBa2ConformanceIT {
  @TempDir Path directory;

  /** Decodes and extracts every project-authored v7/v8 stored/zlib wire vector. */
  @Test
  void readsAndExtractsProjectAuthoredVersions() throws Exception {
    for (Fixture fixture : fixtures()) {
      Path archivePath = materialize(fixture);
      try (OpenArchive archive =
              BethesdaArchives.standard().open(archivePath, OpenOptions.standard());
          EntryContent content = archive.entry(0).openContent()) {
        assertEquals(ArchiveFamily.FO4_GENERAL_BA2, archive.inspection().metadata().family());
        assertEquals(
            fixture.version(),
            archive.inspection().metadata().encoding().wireVersion().orElseThrow().value());
        assertEquals(fixture.name().replace('/', '\\'), archive.entry(0).metadata().displayName());
        assertArrayEquals(fixture.payload(), Channels.newInputStream(content).readAllBytes());
      }
      Path extracted = directory.resolve("extract-" + fixture.id());
      BethesdaArchives.standard()
          .extract(ExtractRequest.standard(archivePath, extracted), OperationControl.standard());
      assertArrayEquals(fixture.payload(), Files.readAllBytes(extracted.resolve(fixture.name())));
    }
  }

  /** Corroborates all four vectors with the independent bounded General BA2 scanner. */
  @Test
  void independentlyValidatesFallout4Versions() throws Exception {
    for (Fixture fixture : fixtures()) {
      Path archivePath = materialize(fixture);
      Path log = directory.resolve("validator-" + fixture.id() + ".json");
      run(
          List.of(
              "python",
              root().resolve("build/validate-ba2-wire.py").toString(),
              archivePath.toString()),
          log,
          60);
      String observation = Files.readString(log);
      assertTrue(observation.contains("\"family\":\"fo4-gnrl-v" + fixture.version() + "\""));
      assertTrue(
          observation.contains(
              HexFormat.of()
                  .formatHex(MessageDigest.getInstance("SHA-256").digest(fixture.payload()))));
    }
  }

  /**
   * Rejects truncated metadata and corrupt v7/v8 zlib payloads at their public validation seams.
   */
  @Test
  void rejectsMalformedVersionedInputs() throws Exception {
    for (Fixture fixture :
        fixtures().stream().filter(item -> item.id().endsWith("zlib")).toList()) {
      Path valid = materialize(fixture);
      byte[] raw = Files.readAllBytes(valid);
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class,
                  () ->
                      BethesdaArchives.standard()
                          .inspect(
                              Files.write(
                                  directory.resolve("truncated-" + fixture.id() + ".ba2"),
                                  Arrays.copyOf(raw, raw.length - 1))))
              .kind());
      for (int mode = 0; mode < 8; mode++) {
        byte[] malformed = raw.clone();
        ByteBuffer wire = ByteBuffer.wrap(malformed).order(ByteOrder.LITTLE_ENDIAN);
        switch (mode) {
          case 0 -> wire.putInt(12, -1);
          case 1 -> wire.put(37, (byte) 2);
          case 2 -> wire.putLong(40, -1);
          case 3 -> wire.putLong(40, Long.MAX_VALUE);
          case 4 -> wire.putLong(40, 24);
          case 5 -> wire.putLong(16, 24);
          case 6 -> wire.putLong(40, 61);
          default -> wire.putShort(Math.toIntExact(wire.getLong(16)), (short) -1);
        }
        Path malformedPath =
            Files.write(
                directory.resolve("malformed-" + fixture.id() + "-" + mode + ".ba2"), malformed);
        assertEquals(
            FailureKind.FORMAT,
            assertThrows(
                    ArchiveException.class,
                    () -> BethesdaArchives.standard().inspect(malformedPath))
                .kind());
      }
      long namesOffset = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN).getLong(16);
      raw[Math.toIntExact(namesOffset - 1)] ^= 1;
      assertContentRejected(
          Files.write(directory.resolve("corrupt-" + fixture.id() + ".ba2"), raw));
      int payloadOffset = 60;
      byte[][] foreignPrefixes = {
        HexFormat.of().parseHex("012000dfff"),
        HexFormat.of().parseHex("f0116a6273"),
        HexFormat.of().parseHex("04224d18")
      };
      for (int codec = 0; codec < foreignPrefixes.length; codec++) {
        byte[] foreign = Files.readAllBytes(valid);
        System.arraycopy(
            foreignPrefixes[codec], 0, foreign, payloadOffset, foreignPrefixes[codec].length);
        assertContentRejected(
            Files.write(
                directory.resolve("foreign-codec-" + fixture.id() + "-" + codec + ".ba2"),
                foreign));
      }
    }
  }

  /** Retains v7/v8 missing-name, identity, constant, undecodable-name, and trailing warnings. */
  @Test
  void diagnosesVersionedNoncanonicalInputs() throws Exception {
    for (Fixture fixture :
        fixtures().stream().filter(item -> item.id().endsWith("stored")).toList()) {
      byte[] raw = Files.readAllBytes(materialize(fixture));
      byte[] changed = Arrays.copyOf(raw, raw.length + 1);
      ByteBuffer wire = ByteBuffer.wrap(changed).order(ByteOrder.LITTLE_ENDIAN);
      wire.putInt(24, 0)
          .putInt(28, 0)
          .putInt(32, 0)
          .put(36, (byte) 2)
          .putShort(38, (short) 8)
          .putInt(56, 0);
      ArchiveInspection inspection =
          BethesdaArchives.standard()
              .inspect(
                  Files.write(directory.resolve("warnings-" + fixture.id() + ".ba2"), changed));
      assertEquals(
          ArchiveDisposition.TOLERATED_NONCANONICAL, inspection.assessment().disposition());
      assertTrue(
          inspection.assessment().diagnostics().stream()
              .map(Diagnostic::identifier)
              .collect(java.util.stream.Collectors.toSet())
              .containsAll(
                  java.util.Set.of(
                      "ba2.basename-hash-mismatch",
                      "ba2.extension-mismatch",
                      "ba2.directory-hash-mismatch",
                      "gnrl.nonzero-mod-index",
                      "gnrl.chunk-header-size",
                      "gnrl.sentinel-mismatch",
                      "ba2.trailing-data")));

      byte[] missing = raw.clone();
      ByteBuffer.wrap(missing).order(ByteOrder.LITTLE_ENDIAN).putLong(16, 0);
      ArchiveInspection missingNames =
          BethesdaArchives.standard()
              .inspect(Files.write(directory.resolve("missing-" + fixture.id() + ".ba2"), missing));
      assertEquals(
          1,
          missingNames.assessment().diagnostics().stream()
              .filter(diagnostic -> diagnostic.identifier().equals("ba2.missing-name-table"))
              .count());

      byte[] undecodable = raw.clone();
      long namesOffset = ByteBuffer.wrap(undecodable).order(ByteOrder.LITTLE_ENDIAN).getLong(16);
      undecodable[Math.toIntExact(namesOffset + 2)] = (byte) 0x81;
      ArchiveInspection undecodableName =
          BethesdaArchives.standard()
              .inspect(
                  Files.write(
                      directory.resolve("undecodable-" + fixture.id() + ".ba2"), undecodable));
      assertTrue(
          undecodableName.assessment().diagnostics().stream()
              .anyMatch(
                  diagnostic ->
                      diagnostic.identifier().equals("archive-name.undecodable-wire-bytes")));
    }
  }

  /**
   * Keeps traversal names inspectable while refusing v7/v8 extraction before destination effects.
   */
  @Test
  void refusesVersionedUnsafeExtraction() throws Exception {
    for (Fixture fixture :
        fixtures().stream().filter(item -> item.id().endsWith("stored")).toList()) {
      byte[] raw = Files.readAllBytes(materialize(fixture));
      ByteBuffer wire = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
      int namesOffset = Math.toIntExact(wire.getLong(16));
      byte[] unsafe = "../x/readme.txt".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      assertEquals(Short.toUnsignedInt(wire.getShort(namesOffset)), unsafe.length);
      System.arraycopy(unsafe, 0, raw, namesOffset + 2, unsafe.length);
      Path archivePath = Files.write(directory.resolve("unsafe-" + fixture.id() + ".ba2"), raw);
      assertTrue(
          BethesdaArchives.standard().inspect(archivePath).assessment().diagnostics().stream()
              .anyMatch(diagnostic -> diagnostic.identifier().startsWith("archive-name.")));
      Path destination = directory.resolve("unsafe-output-" + fixture.id());
      assertEquals(
          FailureKind.POLICY,
          assertThrows(
                  ArchiveException.class,
                  () ->
                      BethesdaArchives.standard()
                          .extract(
                              ExtractRequest.standard(archivePath, destination),
                              OperationControl.standard()))
              .kind());
      assertFalse(Files.exists(destination));
    }
  }

  /** Applies entry, metadata, and decoded-byte ceilings before admitting versioned content. */
  @Test
  void enforcesVersionedResourceLimits() throws Exception {
    ResourceLimits defaults = ResourceLimits.standard();
    List<ResourceLimits> limits =
        List.of(
            new ResourceLimits(
                0,
                defaults.maxMetadataBytes(),
                defaults.maxDecodedBytes(),
                defaults.maxScratchBytes(),
                defaults.maxOutputs(),
                defaults.maxDiagnostics(),
                defaults.maxSecondaryFailures()),
            new ResourceLimits(
                defaults.maxEntries(),
                60,
                defaults.maxDecodedBytes(),
                defaults.maxScratchBytes(),
                defaults.maxOutputs(),
                defaults.maxDiagnostics(),
                defaults.maxSecondaryFailures()));
    for (Fixture fixture : fixtures()) {
      for (ResourceLimits limit : limits) {
        OpenOptions options =
            new OpenOptions(java.util.Optional.empty(), limit, java.util.Optional.empty());
        assertEquals(
            FailureKind.POLICY,
            assertThrows(
                    ArchiveException.class,
                    () -> BethesdaArchives.standard().open(materialize(fixture), options))
                .kind());
      }
      ResourceLimits decodedLimit =
          new ResourceLimits(
              defaults.maxEntries(),
              defaults.maxMetadataBytes(),
              0,
              defaults.maxScratchBytes(),
              defaults.maxOutputs(),
              defaults.maxDiagnostics(),
              defaults.maxSecondaryFailures());
      OpenOptions decodedOptions =
          new OpenOptions(java.util.Optional.empty(), decodedLimit, java.util.Optional.empty());
      try (OpenArchive archive =
          BethesdaArchives.standard().open(materialize(fixture), decodedOptions)) {
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
  }

  /** The Conformance Oracle corroborates project-authored v7/v8 Reference Snapshot behavior. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.ba2.local", matches = "true")
  void pinnedOracleDecodesProjectAuthoredVersions() throws Exception {
    assumeLocalOracle();
    for (Fixture fixture : fixtures()) {
      Path archivePath = materialize(fixture);
      Path extracted = Files.createDirectory(directory.resolve("oracle-" + fixture.id()));
      Path evidence = root().resolve("target/ba2-local-evidence/issue47/" + fixture.id());
      run(
          List.of(
              "pwsh",
              "-NoLogo",
              "-NoProfile",
              "-NonInteractive",
              "-File",
              root().resolve("build/run-ba2-oracle.ps1").toString(),
              "-Operation",
              "unpack",
              "-InputPath",
              archivePath.toString(),
              "-OutputPath",
              extracted.toString(),
              "-WorkingDirectory",
              directory.toString(),
              "-EvidenceDirectory",
              evidence.toString()),
          evidence.resolve("adapter.log"),
          60);
      assertArrayEquals(fixture.payload(), Files.readAllBytes(extracted.resolve(fixture.name())));
    }
  }

  /** Observes Creation Kit v8 General output without implying that a v7 General producer exists. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.ba2.local", matches = "true")
  void readsShippingVersionEightCorpusSample() throws Exception {
    Path archivePath =
        root()
            .resolve(
                "tests/fixtures/local/corpus/archives/fo4-gnrl/v8/ccbgsfo4001-pipboy(black) - Main.ba2");
    org.junit.jupiter.api.Assumptions.assumeTrue(Files.isRegularFile(archivePath));
    try (OpenArchive archive =
        BethesdaArchives.standard().open(archivePath, OpenOptions.standard())) {
      assertEquals(ArchiveFamily.FO4_GENERAL_BA2, archive.inspection().metadata().family());
      assertEquals(
          8, archive.inspection().metadata().encoding().wireVersion().orElseThrow().value());
      ByteBuffer window = ByteBuffer.allocate(65536);
      for (long ordinal = 0; ordinal < archive.entryCount(); ordinal++) {
        try (EntryContent content = archive.entry(ordinal).openContent()) {
          while (content.read(window.clear()) >= 0) {}
        }
      }
    }
  }

  /** Materializes a committed binary or hexadecimal fixture into the isolated test directory. */
  private Path materialize(Fixture fixture) throws Exception {
    byte[] bytes =
        fixture.source().toString().endsWith(".hex")
            ? HexFormat.of().parseHex(Files.readString(fixture.source()).trim())
            : Files.readAllBytes(fixture.source());
    return Files.write(directory.resolve(fixture.id() + ".ba2"), bytes);
  }

  /** Requires a recognized archive's first payload to fail exact zlib content validation. */
  private static void assertContentRejected(Path archivePath) throws Exception {
    try (OpenArchive archive =
            BethesdaArchives.standard().open(archivePath, OpenOptions.standard());
        EntryContent content = archive.entry(0).openContent()) {
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class, () -> Channels.newInputStream(content).readAllBytes())
              .kind());
    }
  }

  /** Returns the project-authored selector/codec combinations, not a shipping-v7 claim. */
  private static List<Fixture> fixtures() {
    Path archives = root().resolve("tests/fixtures/synthetic/artifacts/archives");
    return List.of(
        new Fixture(
            "v7-stored",
            archives.resolve("fo4-gnrl-v7-stored.ba2"),
            7,
            "data/readme.txt",
            "jbsa-v7-stored\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        new Fixture(
            "v7-zlib",
            archives.resolve("fo4-gnrl-v7-zlib.ba2"),
            7,
            "data/compressed.txt",
            "A".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        new Fixture(
            "v8-stored",
            archives.resolve("fo4-gnrl-v8-stored.hex"),
            8,
            "data/readme.txt",
            "jbsa-v8-stored\n".getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        new Fixture(
            "v8-zlib",
            archives.resolve("fo4-gnrl-v8-zlib.hex"),
            8,
            "data/compressed.txt",
            "B".repeat(32).getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  /** Immutable independently authored input and its expected decoded semantics. */
  private record Fixture(String id, Path source, long version, String name, byte[] payload) {
    /** Prevents later mutation of expected payload evidence. */
    private Fixture {
      payload = payload.clone();
    }

    /** Returns a defensive copy so expected fixture evidence cannot be mutated by a test. */
    @Override
    public byte[] payload() {
      return payload.clone();
    }
  }
}
