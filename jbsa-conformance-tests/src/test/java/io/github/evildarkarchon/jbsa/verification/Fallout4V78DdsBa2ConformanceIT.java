package io.github.evildarkarchon.jbsa.verification;

import static io.github.evildarkarchon.jbsa.verification.Fallout4V78Observer.*;
import static org.junit.jupiter.api.Assertions.*;

import io.github.evildarkarchon.jbsa.*;
import java.nio.ByteBuffer;
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

/**
 * Decode-only public, validator, DDS reconstruction, oracle, and local evidence for FO4 DDS v7/v8.
 */
@Tag("dds")
final class Fallout4V78DdsBa2ConformanceIT {
  private static final byte[] EXPECTED_MIP = HexFormat.of().parseHex("630e873656a6ce50");

  @TempDir Path directory;

  /** Decodes, reconstructs, and extracts project-authored zlib vectors for both versions. */
  @Test
  void readsAndExtractsProjectAuthoredVersions() throws Exception {
    for (int version : new int[] {7, 8}) {
      Path archivePath = materialize(version);
      try (OpenArchive archive =
              BethesdaArchives.standard().open(archivePath, OpenOptions.standard());
          EntryContent content = archive.entry(0).openContent()) {
        assertEquals(ArchiveFamily.FO4_DDS_BA2, archive.inspection().metadata().family());
        assertEquals(
            version,
            archive.inspection().metadata().encoding().wireVersion().orElseThrow().value());
        assertEquals("textures\\checker.dds", archive.entry(0).metadata().displayName());
        assertArrayEquals(EXPECTED_MIP, tail(Channels.newInputStream(content).readAllBytes(), 8));
      }
      Path extracted = directory.resolve("extract-v" + version);
      BethesdaArchives.standard()
          .extract(ExtractRequest.standard(archivePath, extracted), OperationControl.standard());
      assertArrayEquals(
          EXPECTED_MIP, tail(Files.readAllBytes(extracted.resolve("textures/checker.dds")), 8));
    }
  }

  /** Corroborates both versioned texture/chunk records with the independent DDS scanner. */
  @Test
  void independentlyValidatesFallout4Versions() throws Exception {
    for (int version : new int[] {7, 8}) {
      Path archivePath = materialize(version);
      Path log = directory.resolve("validator-v" + version + ".json");
      run(
          List.of(
              "python",
              root().resolve("build/validate-dds-wire.py").toString(),
              archivePath.toString()),
          log,
          60);
      String observation = Files.readString(log);
      assertTrue(observation.contains("\"family\": \"fo4-dx10-v" + version + "\""));
      assertTrue(
          observation.contains(
              HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(EXPECTED_MIP))));
    }
  }

  /** Rejects truncated metadata and corrupt v7/v8 zlib chunks at their public validation seams. */
  @Test
  void rejectsMalformedVersionedInputs() throws Exception {
    for (int version : new int[] {7, 8}) {
      Path valid = materialize(version);
      byte[] raw = Files.readAllBytes(valid);
      assertEquals(
          FailureKind.FORMAT,
          assertThrows(
                  ArchiveException.class,
                  () ->
                      BethesdaArchives.standard()
                          .inspect(
                              Files.write(
                                  directory.resolve("truncated-dx10-v" + version + ".ba2"),
                                  Arrays.copyOf(raw, raw.length - 1))))
              .kind());
      for (int mode = 0; mode < 8; mode++) {
        byte[] malformed = raw.clone();
        ByteBuffer wire = ByteBuffer.wrap(malformed).order(java.nio.ByteOrder.LITTLE_ENDIAN);
        switch (mode) {
          case 0 -> wire.putInt(12, -1);
          case 1 -> wire.put(37, (byte) 0);
          case 2 -> wire.put(37, (byte) 5);
          case 3 -> wire.putLong(48, -1);
          case 4 -> wire.putLong(48, Long.MAX_VALUE);
          case 5 -> wire.putLong(48, 24);
          case 6 -> wire.putShort(64, (short) 1);
          default -> wire.putShort(66, (short) 1);
        }
        Path malformedPath =
            Files.write(
                directory.resolve("malformed-dx10-v" + version + "-" + mode + ".ba2"), malformed);
        assertEquals(
            FailureKind.FORMAT,
            assertThrows(
                    ArchiveException.class,
                    () -> BethesdaArchives.standard().inspect(malformedPath),
                    "version=" + version + ", mode=" + mode)
                .kind(),
            "version=" + version + ", mode=" + mode);
      }
      byte[] decodedMismatch = raw.clone();
      ByteBuffer.wrap(decodedMismatch).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(60, 100);
      assertContentRejected(
          Files.write(
              directory.resolve("decoded-size-mismatch-v" + version + ".ba2"), decodedMismatch));
      long namesOffset = ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(16);
      raw[Math.toIntExact(namesOffset - 1)] ^= 1;
      assertContentRejected(
          Files.write(directory.resolve("corrupt-dx10-v" + version + ".ba2"), raw));
      byte[][] foreignPrefixes = {
        HexFormat.of().parseHex("010800f7ff"),
        HexFormat.of().parseHex("80630e8736"),
        HexFormat.of().parseHex("04224d18")
      };
      for (int codec = 0; codec < foreignPrefixes.length; codec++) {
        byte[] foreign = Files.readAllBytes(valid);
        System.arraycopy(foreignPrefixes[codec], 0, foreign, 72, foreignPrefixes[codec].length);
        assertContentRejected(
            Files.write(
                directory.resolve("foreign-dx10-v" + version + "-" + codec + ".ba2"), foreign));
      }
    }
  }

  /** Retains v7/v8 missing-name, identity, constant, undecodable-name, and trailing warnings. */
  @Test
  void diagnosesVersionedNoncanonicalInputs() throws Exception {
    for (int version : new int[] {7, 8}) {
      byte[] raw = Files.readAllBytes(materialize(version));
      byte[] changed = Arrays.copyOf(raw, raw.length + 1);
      ByteBuffer wire = ByteBuffer.wrap(changed).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      wire.putInt(24, 0)
          .putInt(28, 0)
          .putInt(32, 0)
          .put(36, (byte) 2)
          .putShort(38, (short) 8)
          .putInt(68, 0);
      ArchiveInspection inspection =
          BethesdaArchives.standard()
              .inspect(
                  Files.write(directory.resolve("warnings-dx10-v" + version + ".ba2"), changed));
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
                      "dx10.nonzero-mod-index",
                      "dx10.chunk-header-size",
                      "dx10.sentinel-mismatch",
                      "ba2.trailing-data")));

      byte[] missing = raw.clone();
      ByteBuffer.wrap(missing).order(java.nio.ByteOrder.LITTLE_ENDIAN).putLong(16, 0);
      ArchiveInspection missingNames =
          BethesdaArchives.standard()
              .inspect(
                  Files.write(directory.resolve("missing-dx10-v" + version + ".ba2"), missing));
      assertEquals(
          1,
          missingNames.assessment().diagnostics().stream()
              .filter(diagnostic -> diagnostic.identifier().equals("ba2.missing-name-table"))
              .count());

      byte[] undecodable = raw.clone();
      long namesOffset =
          ByteBuffer.wrap(undecodable).order(java.nio.ByteOrder.LITTLE_ENDIAN).getLong(16);
      undecodable[Math.toIntExact(namesOffset + 2)] = (byte) 0x81;
      ArchiveInspection undecodableName =
          BethesdaArchives.standard()
              .inspect(
                  Files.write(
                      directory.resolve("undecodable-dx10-v" + version + ".ba2"), undecodable));
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
    for (int version : new int[] {7, 8}) {
      byte[] raw = Files.readAllBytes(materialize(version));
      ByteBuffer wire = ByteBuffer.wrap(raw).order(java.nio.ByteOrder.LITTLE_ENDIAN);
      int namesOffset = Math.toIntExact(wire.getLong(16));
      byte[] unsafe = "../xsafe/checker.dds".getBytes(java.nio.charset.StandardCharsets.US_ASCII);
      assertEquals(Short.toUnsignedInt(wire.getShort(namesOffset)), unsafe.length);
      System.arraycopy(unsafe, 0, raw, namesOffset + 2, unsafe.length);
      Path archivePath = Files.write(directory.resolve("unsafe-dx10-v" + version + ".ba2"), raw);
      assertTrue(
          BethesdaArchives.standard().inspect(archivePath).assessment().diagnostics().stream()
              .anyMatch(diagnostic -> diagnostic.identifier().startsWith("archive-name.")));
      Path destination = directory.resolve("unsafe-dx10-output-v" + version);
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

  /** Applies entry, metadata, and reconstructed-byte ceilings to both DDS versions. */
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
                72,
                defaults.maxDecodedBytes(),
                defaults.maxScratchBytes(),
                defaults.maxOutputs(),
                defaults.maxDiagnostics(),
                defaults.maxSecondaryFailures()));
    for (int version : new int[] {7, 8}) {
      for (ResourceLimits limit : limits) {
        OpenOptions options =
            new OpenOptions(java.util.Optional.empty(), limit, java.util.Optional.empty());
        assertEquals(
            FailureKind.POLICY,
            assertThrows(
                    ArchiveException.class,
                    () -> BethesdaArchives.standard().open(materialize(version), options))
                .kind());
      }
      ResourceLimits decodedLimit =
          new ResourceLimits(
              defaults.maxEntries(),
              defaults.maxMetadataBytes(),
              135,
              defaults.maxScratchBytes(),
              defaults.maxOutputs(),
              defaults.maxDiagnostics(),
              defaults.maxSecondaryFailures());
      OpenOptions decodedOptions =
          new OpenOptions(java.util.Optional.empty(), decodedLimit, java.util.Optional.empty());
      try (OpenArchive archive =
          BethesdaArchives.standard().open(materialize(version), decodedOptions)) {
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

  /** The configured DirectXTex build accepts both canonical reconstructed DDS outputs. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.directxtex.path", matches = ".+")
  void directXTexAcceptsBothReconstructions() throws Exception {
    for (int version : new int[] {7, 8}) {
      Path extracted = directory.resolve("texdiag-v" + version);
      BethesdaArchives.standard()
          .extract(
              ExtractRequest.standard(materialize(version), extracted),
              OperationControl.standard());
      run(
          List.of(
              Path.of(System.getProperty("jbsa.directxtex.path")).toString(),
              "info",
              extracted.resolve("textures/checker.dds").toString()),
          root().resolve("target/dds-local-evidence/issue47/texdiag-v" + version + ".log"),
          60);
    }
  }

  /** The digest-pinned oracle independently decodes both project-authored DDS versions. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.dds.local", matches = "true")
  void pinnedOracleDecodesProjectAuthoredVersions() throws Exception {
    assumeLocalOracle();
    for (int version : new int[] {7, 8}) {
      Path extracted = Files.createDirectory(directory.resolve("oracle-v" + version));
      Path evidence = root().resolve("target/dds-local-evidence/issue47/oracle-v" + version);
      run(
          List.of(
              "pwsh",
              "-NoLogo",
              "-NoProfile",
              "-NonInteractive",
              "-File",
              root().resolve("build/run-dds-oracle.ps1").toString(),
              "-Operation",
              "unpack",
              "-InputPath",
              materialize(version).toString(),
              "-OutputPath",
              extracted.toString(),
              "-WorkingDirectory",
              directory.toString(),
              "-EvidenceDirectory",
              evidence.toString()),
          evidence.resolve("adapter.log"),
          60);
      assertArrayEquals(
          EXPECTED_MIP, tail(Files.readAllBytes(extracted.resolve("textures/checker.dds")), 8));
    }
  }

  /** Reads representative shipping v7/v8 texture entries without retaining proprietary bytes. */
  @Test
  @EnabledIfSystemProperty(named = "jbsa.dds.local", matches = "true")
  void readsShippingVersionSevenAndEightSamples() throws Exception {
    List<Path> samples =
        List.of(
            root()
                .resolve(
                    "tests/fixtures/local/corpus/archives/fo4-dx10/v7/Fallout4 - Textures7.ba2"),
            root()
                .resolve(
                    "tests/fixtures/local/corpus/archives/fo4-dx10/v8/ccbgsfo4001-pipboy(black) - Textures.ba2"));
    org.junit.jupiter.api.Assumptions.assumeTrue(samples.stream().allMatch(Files::isRegularFile));
    for (int index = 0; index < samples.size(); index++) {
      try (OpenArchive archive =
          BethesdaArchives.standard().open(samples.get(index), OpenOptions.standard())) {
        assertEquals(ArchiveFamily.FO4_DDS_BA2, archive.inspection().metadata().family());
        assertEquals(
            index + 7,
            archive.inspection().metadata().encoding().wireVersion().orElseThrow().value());
        for (long ordinal : new long[] {0, archive.entryCount() - 1}) {
          MessageDigest digest = MessageDigest.getInstance("SHA-256");
          ByteBuffer window = ByteBuffer.allocate(65536);
          try (EntryContent content = archive.entry(ordinal).openContent()) {
            while (content.read(window.clear()) >= 0) digest.update(window.flip());
          }
          assertEquals(32, digest.digest().length);
        }
      }
    }
  }

  /** Materializes the canonical hexadecimal vector as a `.ba2` archive. */
  private Path materialize(int version) throws Exception {
    Path source =
        root()
            .resolve(
                "tests/fixtures/synthetic/artifacts/archives/fo4-dx10-v" + version + "-zlib.hex");
    return Files.write(
        directory.resolve("fo4-dx10-v" + version + ".ba2"),
        HexFormat.of().parseHex(Files.readString(source).trim()));
  }

  /** Requires a recognized DDS archive's first chunk to fail exact zlib content validation. */
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

  /** Returns the exact suffix carrying opaque DDS mip bytes. */
  private static byte[] tail(byte[] bytes, int count) {
    return Arrays.copyOfRange(bytes, bytes.length - count, bytes.length);
  }
}
