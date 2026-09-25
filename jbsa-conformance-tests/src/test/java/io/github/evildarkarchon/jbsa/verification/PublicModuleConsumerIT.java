package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Compiles and runs real named-module consumers using only the library's JPMS exports. */
@Tag("contract")
@EnabledOnOs(OS.WINDOWS)
final class PublicModuleConsumerIT {
  @TempDir Path directory;

  /**
   * Runs embedded generated-input/owned-read and CLI-like detached-query/extraction flows across
   * TES3, TES4, General BA2, and PC DDS BA2 with the CLI implementation absent.
   */
  @Test
  void publicExportsSupportBothConsumerStyles() throws Exception {
    String libraryJar = System.getProperty("jbsa.library.jar");
    // The CLI and test-support JARs are deliberately absent from the consumer module path.
    for (String consumer : new String[] {"embedded", "cli"}) {
      Path source = Files.createDirectories(directory.resolve("source-" + consumer));
      Path classes = Files.createDirectories(directory.resolve("classes-" + consumer));
      Path descriptor = source.resolve("module-info.java");
      Path main = source.resolve("Main.java");
      Files.writeString(
          descriptor,
          "module consumer." + consumer + " { requires io.github.evildarkarchon.jbsa; }");
      Files.writeString(
          main,
          """
          package consumer;
          import io.github.evildarkarchon.jbsa.*;
          import java.nio.file.Path;
          public final class Main {
            /** Runs each real operation through named-module exports and owned public capabilities. */
            public static void main(String[] args) throws Exception {
              var library = BethesdaArchives.standard();
              Path root = Path.of(args[0]);
              for (ArchiveFamily family : new ArchiveFamily[] {ArchiveFamily.TES3_BSA,
                  ArchiveFamily.TES4_BSA, ArchiveFamily.FO4_GENERAL_BA2, ArchiveFamily.FO4_DDS_BA2}) {
                Path path = root.resolve(family + ".archive");
                byte[] expected = payload(family);
                String name = family == ArchiveFamily.FO4_DDS_BA2 ? "textures/sample.dds" : "meshes/sample.txt";
                if (args[1].equals("embedded")) {
                  long[] opened = {0}, closed = {0};
                  var source = new PackSource.GeneratedEntry(name, expected.length, () -> {
                    opened[0]++;
                    return java.nio.channels.Channels.newChannel(new java.io.ByteArrayInputStream(expected) {
                      /** Records JBSA's release of every generated channel. */
                      public void close() throws java.io.IOException {
                        super.close();
                        closed[0]++;
                      }
                    });
                  });
                  var progress = new java.util.ArrayList<ProgressSnapshot>();
                  checkReport(library.pack(request(path, family, java.util.List.of(source)),
                      new OperationControl(progress::add, () -> false)), Operation.PACK);
                  require(opened[0] > 0 && opened[0] == closed[0], "generated ownership");
                  require(!progress.isEmpty(), "progress");
                  require(embeddedRead(path, expected) == expected.length, "owned payload");
                  Path repacked = root.resolve(family + ".repacked");
                  checkReport(embeddedPack(request(repacked, family,
                      java.util.List.of(new PackSource.DetectedPath(path))), OperationControl.standard()),
                      Operation.PACK);
                  require(java.nio.file.Files.mismatch(path, repacked) == -1, "canonical repack");
                } else {
                  require(library.detect(path).status() == DetectionStatus.SUPPORTED_FAMILY, "detection");
                  var inspection = library.inspect(path);
                  require(inspection.equals(library.inspect(path, OpenOptions.standard())), "defaults");
                  require(inspection.entries().size() == 1, "inspection count");
                  require(inspection.entries().getFirst().family() == family, "family");
                  Path extracted = root.resolve(family + "-extracted");
                  checkReport(cliExtract(ExtractRequest.standard(path, extracted)), Operation.EXTRACT);
                  require(java.util.Arrays.equals(expected,
                      java.nio.file.Files.readAllBytes(extracted.resolve(name))), "extracted bytes");
                  Path cancelled = root.resolve(family + "-cancelled");
                  try {
                    library.extract(ExtractRequest.standard(path, cancelled),
                        new OperationControl(snapshot -> {
                          throw new AssertionError("pre-cancel progress");
                        }, () -> true));
                    throw new AssertionError("cancellation succeeded");
                  } catch (ArchiveCancelledException failure) {
                    require(failure.kind() == FailureKind.CANCELLED, "cancellation kind");
                    require(!java.nio.file.Files.exists(cancelled), "cancellation effects");
                  }
                }
              }
            }
            /** Returns independent canonical bytes, including a 4x4 PC BC1 DDS envelope. */
            static byte[] payload(ArchiveFamily family) {
              if (family != ArchiveFamily.FO4_DDS_BA2) return new byte[] {1, 2, 3, 4};
              var bytes = java.nio.ByteBuffer.allocate(136).order(java.nio.ByteOrder.LITTLE_ENDIAN);
              bytes.putInt(0, 0x20534444).putInt(4, 124).putInt(8, 0xa1007);
              bytes.putInt(12, 4).putInt(16, 4).putInt(20, 8).putInt(24, 1).putInt(28, 1);
              bytes.putInt(76, 32).putInt(80, 4).putInt(84, 0x31545844).putInt(108, 0x1000);
              for (int i = 128; i < 136; i++) bytes.put(i, (byte) i);
              return bytes.array();
            }
            /** Selects family wire encoding and the mandatory explicit DDS encode target. */
            static PackRequest request(Path path, ArchiveFamily family, java.util.List<PackSource> sources) {
              var encoding = family == ArchiveFamily.TES3_BSA ? ArchiveEncoding.tes3()
                  : new ArchiveEncoding(java.util.Optional.of(new WireVersion(
                      family == ArchiveFamily.TES4_BSA ? 103 : 1)),
                      family == ArchiveFamily.TES4_BSA ? java.util.Optional.empty()
                          : java.util.Optional.of(family == ArchiveFamily.FO4_DDS_BA2
                              ? Ba2Subtype.DX10 : Ba2Subtype.GNRL),
                      java.util.OptionalLong.empty());
              var defaults = PackRequest.standard(path, family, encoding, sources,
                  family == ArchiveFamily.FO4_DDS_BA2 ? java.util.Optional.of(DdsTarget.PC)
                      : java.util.Optional.empty());
              if (family == ArchiveFamily.TES3_BSA || family == ArchiveFamily.FO4_DDS_BA2) return defaults;
              var options = defaults.options();
              return new PackRequest(path, family, encoding, defaults.compatibilityProfile(), sources,
                  defaults.targetPolicy(), defaults.diagnosticPolicy(), defaults.resourceLimits(),
                  defaults.workerSelection(), new PackOptions(options.inclusionMasks(),
                      PackOptions.Compression.ZLIB, options.sharing(), options.splitting(),
                      options.archiveFlags(), options.fileFlags()), defaults.ddsTarget());
            }
            /** Checks detached successful publication facts. */
            static void checkReport(OperationReport report, Operation operation) {
              require(report.operation() == operation && !report.artifacts().isEmpty(), "report");
              for (Artifact artifact : report.artifacts()) {
                require(artifact.state() == ArtifactState.PUBLISHED, "publication state");
                require(artifact.path().isAbsolute()
                    && artifact.path().equals(artifact.path().normalize()), "artifact path");
              }
            }
            /** Makes failures in the independently compiled consumer visible to the parent test. */
            static void require(boolean condition, String message) {
              if (!condition) throw new AssertionError(message);
            }
            /** Reads one payload to EOF while retaining detached metadata after all handles close. */
            static long embeddedRead(Path path, byte[] expected) throws Exception {
              EntryMetadata metadata;
              EntryContent outstanding;
              try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
                ArchiveInspection detached = archive.inspection();
                long count = archive.entryCount();
                if (count == 0) return 0;
                ArchiveEntry entry = archive.entry(0L);
                metadata = entry.metadata();
                try (EntryContent content = entry.openContent()) {
                  require(content.assessment().isEmpty(), "premature assessment");
                  require(java.util.Arrays.equals(expected,
                      java.nio.channels.Channels.newInputStream(content).readAllBytes()), "owned bytes");
                  content.assessment().orElseThrow();
                }
                outstanding = entry.openContent();
              }
              require(!outstanding.isOpen(), "parent-owned child lifetime");
              try {
                outstanding.read(java.nio.ByteBuffer.allocate(1));
                throw new AssertionError("closed child accepted a read");
              } catch (java.nio.channels.ClosedChannelException expectedClose) {
                // Parent close invalidates all outstanding content channels.
              }
              return metadata.decodedSize();
            }
            /** Maps a CLI-like operation onto the same synchronous public request seam. */
            static OperationReport cliExtract(ExtractRequest request) throws ArchiveException {
              return BethesdaArchives.standard().extract(request, OperationControl.standard());
            }
            /** Accepts embedded caller control without exposing internal storage or parser types. */
            static OperationReport embeddedPack(PackRequest request, OperationControl control)
                throws ArchiveException {
              return BethesdaArchives.standard().pack(request, control);
            }
          }
          """);
      assertEquals(
          0,
          ToolProvider.getSystemJavaCompiler()
              .run(
                  null,
                  null,
                  null,
                  "--release",
                  "25",
                  "--module-path",
                  libraryJar,
                  "-d",
                  classes.toString(),
                  descriptor.toString(),
                  main.toString()));
      String modulePath = libraryJar + java.io.File.pathSeparator + classes;
      Path output = directory.resolve(consumer + ".txt");
      Process process =
          new ProcessBuilder(
                  Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                  "--enable-native-access=io.github.evildarkarchon.jbsa",
                  "--module-path",
                  modulePath,
                  "--module",
                  "consumer." + consumer + "/consumer.Main",
                  directory.toString(),
                  consumer)
              .redirectErrorStream(true)
              .redirectOutput(output.toFile())
              .start();
      try {
        assertTrue(process.waitFor(30, TimeUnit.SECONDS), "consumer did not finish");
        assertEquals(0, process.exitValue(), Files.readString(output));
      } finally {
        if (process.isAlive()) process.destroyForcibly();
      }
    }
  }
}
