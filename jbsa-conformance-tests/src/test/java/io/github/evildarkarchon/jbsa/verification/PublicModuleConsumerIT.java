package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import javax.tools.ToolProvider;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Compiles and runs real named-module consumers using only the library's JPMS exports. */
@Tag("contract")
final class PublicModuleConsumerIT {
  @TempDir Path directory;

  /** A CLI-like and an embedded consumer both learn the same deep module and lifetime contracts. */
  @Test
  void publicExportsSupportBothConsumerStyles() throws Exception {
    String libraryJar = System.getProperty("jbsa.library.jar");
    Path vector =
        Path.of(System.getProperty("jbsa.reactor.root"))
            .resolve("tests/fixtures/tes3/tes3-stored.hex");
    Path archive =
        Files.write(
            directory.resolve("independent.bsa"),
            java.util.HexFormat.of().parseHex(Files.readString(vector).strip()));
    for (String consumer : new String[] {"cli", "embedded"}) {
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
              if (library.detect(Path.of(args[0])).status() != DetectionStatus.SUPPORTED_FAMILY) {
                throw new AssertionError("Recognition unavailable through public exports");
              }
              var inspection = library.inspect(Path.of(args[0]));
              if (inspection.entries().size() != 2 || embeddedRead(Path.of(args[0])) != 4) {
                throw new AssertionError("Public entry inspection or owned reading failed");
              }
              Path extracted = Path.of(args[1]).resolve("extracted");
              cliExtract(ExtractRequest.standard(Path.of(args[0]), extracted));
              Path packed = Path.of(args[1]).resolve("repacked.bsa");
              embeddedPack(PackRequest.standard(packed, ArchiveFamily.TES3_BSA,
                  ArchiveEncoding.tes3(), java.util.List.of(new PackSource.DetectedPath(extracted)),
                  java.util.Optional.empty()), OperationControl.standard());
              if (java.nio.file.Files.mismatch(Path.of(args[0]), packed) != -1) {
                throw new AssertionError("Public extract and repack changed independent fixture bytes");
              }
            }
            /** Reads one payload to EOF while retaining detached metadata after all handles close. */
            static long embeddedRead(Path path) throws Exception {
              EntryMetadata metadata;
              try (OpenArchive archive = BethesdaArchives.standard().open(path, OpenOptions.standard())) {
                ArchiveInspection detached = archive.inspection();
                long count = archive.entryCount();
                if (count == 0) return 0;
                ArchiveEntry entry = archive.entry(0L);
                metadata = entry.metadata();
                try (EntryContent content = entry.openContent()) {
                  java.nio.ByteBuffer window = java.nio.ByteBuffer.allocate(4096);
                  while (content.read(window) != -1) window.clear();
                  content.assessment().orElseThrow();
                }
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
                  archive.toString(),
                  Files.createDirectory(directory.resolve("outputs-" + consumer)).toString())
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
