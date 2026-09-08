package io.github.evildarkarchon.jbsa.cli;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the command process boundary without depending on parser implementation details. */
class MainTest {
  @TempDir Path temporary;

  @Test
  void noArgumentsPrintsHelpToStandardOutput() throws Exception {
    Result result = run();
    assertEquals(0, result.status());
    assertTrue(result.output().contains("pack <source1+source2+...> <archive>"));
    assertEquals("", result.error());
  }

  @Test
  void invalidTes3InvocationsFailBeforeSourceAccess() throws Exception {
    for (String[] arguments :
        List.of(
            new String[] {"pack", "missing+", "archive.bsa", "-tes3"},
            new String[] {"pack", "missing", "archive.bsa", "-tes3", "-z"},
            new String[] {"pack", "missing", "archive.bsa", "-tes3", "-tes3"},
            new String[] {"pack", "missing", "archive.bsa", "-tes3", "-split:9"},
            new String[] {"pack", "missing", "archive.bsa", "-tes3", "-share:maybe"},
            new String[] {"pack", "missing", "archive.bsa", "-tes3", "-f:*.txt,"},
            new String[] {"--help", "extra"},
            new String[] {"missing.bsa", "-list", "-list"})) {
      Result result = run(arguments);
      assertEquals(2, result.status(), List.of(arguments).toString());
      assertEquals("", result.output());
      assertTrue(result.error().startsWith("Error: [invocation]"));
    }
  }

  @Test
  void packAndDetachedListDumpPreserveNamesAndProduceAnArchive() throws Exception {
    Path source = Files.createDirectory(temporary.resolve("Café sources"));
    Files.writeString(source.resolve("cafe.txt"), "payload", StandardCharsets.UTF_8);
    Path archive = temporary.resolve("result.bsa");
    Result packed =
        run("PaCk", source.toString(), archive.toString(), "-TES3", "-mt:no", "--no-progress");
    assertEquals(0, packed.status(), packed.error());
    assertTrue(Files.isRegularFile(archive));
    assertTrue(packed.output().contains(archive.toAbsolutePath().toString()));
    assertTrue(packed.output().contains("bytes=" + Files.size(archive) + " entries=1"));
    Result listed = run(archive.toString(), "-LIST");
    assertEquals(0, listed.status(), listed.error());
    assertTrue(listed.output().contains("Family: TES3_BSA"));
    assertTrue(listed.output().contains("Entries: 1"));
    assertTrue(listed.output().contains("cafe.txt"));
    Result dumped = run(archive.toString(), "-dump", "-list");
    assertEquals(0, dumped.status(), dumped.error());
    assertEquals(1, dumped.output().lines().filter(line -> line.equals("cafe.txt")).count());
    assertTrue(dumped.output().contains("Decoded size: 7"));
    assertTrue(dumped.output().contains("Name hash:"));
  }

  @Test
  void unpackPublishesPayloadAndRequiresExplicitReplacement() throws Exception {
    Path source = Files.createDirectory(temporary.resolve("input"));
    Files.writeString(source.resolve("entry.txt"), "payload");
    Path archive = temporary.resolve("archive.bsa");
    assertEquals(0, run("pack", source.toString(), archive.toString(), "-tes3").status());
    Path destination = Files.createDirectory(temporary.resolve("output"));
    Result unpacked = run("unpack", archive.toString(), destination.toString(), "--no-progress");
    assertEquals(0, unpacked.status(), unpacked.error());
    assertEquals("payload", Files.readString(destination.resolve("entry.txt")));
    assertTrue(unpacked.output().contains("Published entries: 1"));
    Files.writeString(destination.resolve("entry.txt"), "predecessor");
    Result refused = run("unpack", archive.toString(), destination.toString());
    assertEquals(1, refused.status());
    assertEquals("predecessor", Files.readString(destination.resolve("entry.txt")));
    Result replaced = run("unpack", archive.toString(), destination.toString(), "--replace");
    assertEquals(0, replaced.status(), replaced.error());
    assertEquals("payload", Files.readString(destination.resolve("entry.txt")));
  }

  @Test
  void profileUsesFirstValueAndPermissiveBooleanWhileRetainingTheSourceBoundary() throws Exception {
    Path source = Files.createDirectory(temporary.resolve("profile-source"));
    Files.writeString(source.resolve("entry.txt"), "payload");
    Path archive = temporary.resolve("profile.bsa");
    Result result =
        run(
            "--compatibility-profile=bsarch-1.0/v1",
            "pack",
            source.toString(),
            archive.toString(),
            "-tes3",
            "-mt:other",
            "-mt:no",
            "-split:word",
            "ignored-tail");
    assertEquals(0, result.status(), result.output() + result.error());
    assertTrue(Files.isRegularFile(archive));
    assertEquals("", result.error());
  }

  @Test
  void versionReportsArtifactAndProfileAndOperationalFailuresUseStderr() throws Exception {
    Result version = run("--VERSION");
    assertEquals(0, version.status());
    assertTrue(
        version
            .output()
            .startsWith(
                "JBSA " + Main.class.getModule().getDescriptor().rawVersion().orElseThrow()));
    assertTrue(version.output().contains("bsarch-1.0/v1 SHA-256 9577D821"));
    assertEquals("", version.error());
    Result missing = run(temporary.resolve("missing.bsa").toString());
    assertEquals(1, missing.status());
    assertEquals("", missing.output());
    assertTrue(missing.error().startsWith("Error: [source]"));
    assertTrue(missing.error().contains("phase=PREFLIGHT"));
  }

  /**
   * Launches the actual modular entry point and captures independently redirected UTF-8 streams.
   */
  private Result run(String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
    command.add("--enable-native-access=io.github.evildarkarchon.jbsa");
    command.add("--module-path");
    command.add(
        Path.of("target/classes").toAbsolutePath()
            + java.io.File.pathSeparator
            + Path.of("../jbsa/target/classes").toAbsolutePath());
    command.add("--module");
    command.add("io.github.evildarkarchon.jbsa.cli/io.github.evildarkarchon.jbsa.cli.Main");
    command.addAll(List.of(arguments));
    Path output = Files.createTempFile(temporary, "stdout", ".txt");
    Path error = Files.createTempFile(temporary, "stderr", ".txt");
    Process process =
        new ProcessBuilder(command)
            .redirectOutput(output.toFile())
            .redirectError(error.toFile())
            .start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "CLI must settle");
      return new Result(
          process.exitValue(),
          Files.readString(output, StandardCharsets.UTF_8),
          Files.readString(error, StandardCharsets.UTF_8));
    } finally {
      if (process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private record Result(int status, String output, String error) {}
}
