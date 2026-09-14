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
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Exercises the command process boundary without depending on parser implementation details. */
class MainTest {
  @TempDir Path temporary;

  /** The SSE selector exposes version 105 and family-default LZ4-frame round trips. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void packsAndUnpacksSseLz4Frame() throws Exception {
    for (String codec : List.of("-z", "-z:lz4f")) {
      Path source = Files.createTempDirectory(temporary, "sse-source");
      Files.createDirectories(source.resolve("meshes"));
      Files.writeString(source.resolve("meshes/a.nif"), "payload".repeat(10_000));
      Path archive = temporary.resolve(source.getFileName() + ".bsa");
      Result packed =
          run("pack", source.toString(), archive.toString(), "-sse", codec, "--no-progress");
      assertEquals(0, packed.status(), packed.error());
      assertTrue(packed.output().contains("Selector: -sse"));
      Result dumped = run(archive.toString(), "-dump");
      assertEquals(0, dumped.status(), dumped.error());
      assertTrue(dumped.output().contains("Family: SSE_BSA"));
      assertTrue(dumped.output().contains("Version: 105"));
      assertTrue(dumped.output().contains("Codec: LZ4_FRAME"));
      assertTrue(dumped.output().contains("Folder padding before offset: 0"));
      Path destination = Files.createTempDirectory(temporary, "sse-output");
      Result unpacked = run("unpack", archive.toString(), destination.toString(), "--no-progress");
      assertEquals(0, unpacked.status(), unpacked.error());
      assertEquals("payload".repeat(10_000), Files.readString(destination.resolve("meshes/a.nif")));
    }
  }

  /** Family-specific codec spellings reject contradictions at the invocation boundary. */
  @Test
  void rejectsInvalidSseCodecCombinations() throws Exception {
    for (String[] options :
        List.of(
            new String[] {"-sse", "-z:zlib"},
            new String[] {"-sse", "-z:lz4"},
            new String[] {"-tes4", "-z:lz4f"},
            new String[] {"-sse", "-tes5"})) {
      var args =
          new ArrayList<>(List.of("pack", "missing", temporary.resolve("absent.bsa").toString()));
      args.addAll(List.of(options));
      Result result = run(args.toArray(String[]::new));
      assertEquals(2, result.status(), result.error());
      assertEquals("", result.output());
    }
  }

  /** The compatibility profile places SSE after legacy Skyrim and before Fallout 4. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileSelectsSseBetweenVersion104AndFallout4() throws Exception {
    for (String[] order :
        List.of(
            new String[] {"-fo4", "-sse"},
            new String[] {"-sse", "-fo4"},
            new String[] {"-fo4dds", "-sse"})) {
      Path source = Files.createTempDirectory(temporary, "sse-priority-source");
      Files.createDirectories(source.resolve("meshes"));
      Files.writeString(source.resolve("meshes/a.nif"), "one");
      Path archive = temporary.resolve(source.getFileName() + ".bsa");
      var args =
          new ArrayList<>(
              List.of(
                  "--compatibility-profile=bsarch-1.0/v1",
                  "pack",
                  source.toString(),
                  archive.toString()));
      args.addAll(List.of(order));
      Result result = run(args.toArray(String[]::new));
      assertEquals(0, result.status(), result.error());
      assertTrue(run(archive.toString()).output().contains("Family: SSE_BSA"));
    }
  }

  /** Each legacy game spelling selects version 104 and survives as a pack observation. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void packsAndUnpacksVersion104Aliases() throws Exception {
    for (String selector : List.of("-fo3", "-FNV", "-tes5")) {
      for (boolean compressed : List.of(false, true)) {
        Path source = Files.createTempDirectory(temporary, "bsa68-source");
        Files.createDirectory(source.resolve("meshes"));
        Files.writeString(source.resolve("meshes/a.nif"), "payload".repeat(100));
        Path archive = temporary.resolve(source.getFileName() + ".bsa");
        var args =
            new ArrayList<>(
                List.of(
                    "pack",
                    source.toString(),
                    archive.toString(),
                    selector,
                    "-af:103",
                    "-ff:1",
                    "--no-progress"));
        if (compressed) args.add("-z:zlib");
        Result packed = run(args.toArray(String[]::new));
        assertEquals(0, packed.status(), packed.error());
        assertTrue(packed.output().contains("Selector: " + selector), packed.output());
        Result dumped = run(archive.toString(), "-dump");
        assertEquals(0, dumped.status(), dumped.error());
        assertTrue(dumped.output().contains("Family: FO3_FNV_SKYRIM_LE_BSA"));
        assertTrue(dumped.output().contains("Version: 104"));
        assertTrue(dumped.output().contains("Codec: " + (compressed ? "ZLIB" : "STORED")));
        Path destination = Files.createTempDirectory(temporary, "bsa68-output");
        Result unpacked =
            run("unpack", archive.toString(), destination.toString(), "--no-progress");
        assertEquals(0, unpacked.status(), unpacked.error());
        assertEquals("payload".repeat(100), Files.readString(destination.resolve("meshes/a.nif")));
      }
    }
  }

  /** Profile family priority must include version 104 and select its earliest-priority alias. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileSelectsVersion104BetweenTes4AndFallout4() throws Exception {
    for (String[] order :
        List.of(
            new String[] {"-fo4", "-tes5", "-FNV", "-fo3"},
            new String[] {"-fo3", "-FNV", "-tes5", "-fo4"},
            new String[] {"-fo4", "-tes5", "-FNV"},
            new String[] {"-FNV", "-tes5", "-fo4"},
            new String[] {"-fo3", "-tes4"},
            new String[] {"-tes4", "-fo3"})) {
      Path source = Files.createTempDirectory(temporary, "priority-source");
      Files.createDirectories(source.resolve("meshes"));
      Files.writeString(source.resolve("meshes/a.nif"), "one");
      Path archive = temporary.resolve(source.getFileName() + ".bsa");
      var args =
          new ArrayList<>(
              List.of(
                  "--compatibility-profile=bsarch-1.0/v1",
                  "pack",
                  source.toString(),
                  archive.toString()));
      args.addAll(List.of(order));
      Result result = run(args.toArray(String[]::new));
      assertEquals(0, result.status(), result.error());
      boolean tes4 = List.of(order).contains("-tes4");
      assertTrue(
          run(archive.toString())
              .output()
              .contains("Family: " + (tes4 ? "TES4_BSA" : "FO3_FNV_SKYRIM_LE_BSA")));
      if (!tes4)
        assertTrue(
            result
                .output()
                .contains("Selector: " + (List.of(order).contains("-fo3") ? "-fo3" : "-FNV")));
    }
  }

  /**
   * Aliases are still family switches, so safe syntax rejects repetition and incompatible codecs.
   */
  @Test
  void rejectsConflictingVersion104Aliases() throws Exception {
    for (String[] options :
        List.of(
            new String[] {"-fo3", "-fnv"},
            new String[] {"-tes5", "-tes5"},
            new String[] {"-fnv", "-tes4"},
            new String[] {"-fo3", "-z:lz4"},
            new String[] {"-tes5", "-z:lz4f"})) {
      var args =
          new ArrayList<>(List.of("pack", "missing", temporary.resolve("absent.bsa").toString()));
      args.addAll(List.of(options));
      Result result = run(args.toArray(String[]::new));
      assertEquals(2, result.status(), result.error());
      assertEquals("", result.output());
    }
  }

  /** DDS family selection defaults to compressed PC output and exposes texture chunk facts. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void packsDdsWithMandatoryDefaultCompression() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("dds-input/Textures"));
    var bytes = java.nio.ByteBuffer.allocate(136).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    bytes
        .putInt(0, 0x20534444)
        .putInt(4, 124)
        .putInt(12, 1)
        .putInt(16, 1)
        .putInt(28, 1)
        .putInt(76, 32)
        .putInt(80, 4)
        .putInt(84, 0x31545844);
    Files.write(source.resolve("Small.dds"), bytes.array());
    Path archive = temporary.resolve("dds.ba2");
    Result packed = run("pack", source.getParent().toString(), archive.toString(), "-fo4dds");
    assertEquals(0, packed.status(), packed.error());
    Result dumped = run(archive.toString(), "-dump");
    assertEquals(0, dumped.status(), dumped.error());
    assertTrue(dumped.output().contains("Subtype: DX10"));
    assertTrue(dumped.output().contains("Codec: ZLIB"));
    assertTrue(dumped.output().contains("Dimensions: 1x1"));
    assertTrue(dumped.output().contains("Mip range: 0..0"));
    Files.writeString(source.resolve("Other.txt"), "not a texture");
    Path rejected = temporary.resolve("non-dds.ba2");
    Result invalid = run("pack", source.getParent().toString(), rejected.toString(), "-fo4dds");
    assertEquals(1, invalid.status(), invalid.error());
    assertTrue(Files.notExists(rejected));
  }

  /** General BA2 commands expose compressed payloads through the public process boundary. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void packsAndUnpacksFallout4GeneralZlib() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("fo4-input/Meshes"));
    Files.writeString(source.resolve("A.nif"), "payload".repeat(100));
    Path archive = temporary.resolve("fo4.ba2");
    Result packed =
        run(
            "pack",
            source.getParent().toString(),
            archive.toString(),
            "-fo4",
            "-z",
            "-share:no",
            "--no-progress");
    assertEquals(0, packed.status(), packed.error());
    Result dumped = run(archive.toString(), "-dump");
    assertEquals(0, dumped.status(), dumped.error());
    assertTrue(dumped.output().contains("Family: FO4_GENERAL_BA2"));
    assertTrue(dumped.output().contains("Codec: ZLIB"));
    assertTrue(dumped.output().contains("Subtype: GNRL"));
    assertTrue(dumped.output().contains("Directory hash:"));
    Path destination = Files.createDirectory(temporary.resolve("fo4-output"));
    Result unpacked = run("unpack", archive.toString(), destination.toString(), "--no-progress");
    assertEquals(0, unpacked.status(), unpacked.error());
    assertEquals("payload".repeat(100), Files.readString(destination.resolve("Meshes/A.nif")));
  }

  /** Inapplicable BA2 flags and conflicting families fail before filesystem source access. */
  @Test
  void rejectsInvalidGeneralBa2Invocations() throws Exception {
    for (String[] options :
        List.of(
            new String[] {"-fo4", "-af:0"},
            new String[] {"-fo4", "-ff:0"},
            new String[] {"-fo4", "-z:lz4"},
            new String[] {"-fo4", "-tes4"},
            new String[] {"-fo4", "-fo4"})) {
      var args =
          new ArrayList<>(List.of("pack", "missing", temporary.resolve("absent.ba2").toString()));
      args.addAll(List.of(options));
      Result result = run(args.toArray(String[]::new));
      assertEquals(2, result.status(), result.error());
      assertEquals("", result.output());
    }
  }

  /** TES4 switches reach the public packer and inspection renders actual compression and flags. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void packsAndUnpacksTes4ZlibWithDetachedDump() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("tes4-input/meshes"));
    Files.writeString(source.resolve("a.nif"), "payload".repeat(100));
    Path archive = temporary.resolve("tes4.bsa");
    Result packed =
        run(
            "pack",
            source.getParent().toString(),
            archive.toString(),
            "-tes4",
            "-z",
            "-af:0x603",
            "-ff:1",
            "-share:no",
            "--no-progress");
    assertEquals(0, packed.status(), packed.error());
    Result dumped = run(archive.toString(), "-dump");
    assertEquals(0, dumped.status(), dumped.error());
    assertTrue(dumped.output().contains("Family: TES4_BSA"));
    assertTrue(dumped.output().contains("Compressed entries: 1"));
    assertTrue(dumped.output().contains("Codec: ZLIB"));
    assertTrue(dumped.output().contains("Folder hash:"));
    assertTrue(dumped.output().contains("Archive flags:"));
    Path destination = Files.createDirectory(temporary.resolve("tes4-output"));
    Result unpacked = run("unpack", archive.toString(), destination.toString(), "--no-progress");
    assertEquals(0, unpacked.status(), unpacked.error());
    assertEquals("payload".repeat(100), Files.readString(destination.resolve("meshes/a.nif")));
  }

  @Test
  void noArgumentsPrintsHelpToStandardOutput() throws Exception {
    Result result = run();
    assertEquals(0, result.status());
    assertTrue(result.output().contains("pack <source1+source2+...> <archive>"));
    assertEquals("", result.error());
  }

  /** Safe family/codec parsing rejects contradictory requests before touching missing sources. */
  @Test
  void rejectsInvalidTes4SelectorsAndFlags() throws Exception {
    for (String[] options :
        List.of(
            new String[] {"-tes4", "-tes3"},
            new String[] {"-tes4", "-z:lz4"},
            new String[] {"-tes4", "-z:lz4f"},
            new String[] {"-tes4", "-af:100000000"},
            new String[] {"-tes4", "-ff:-1"},
            new String[] {"-tes4", "-af:"},
            new String[] {"-tes4", "-z", "-z:zlib"})) {
      var args =
          new ArrayList<>(List.of("pack", "missing", temporary.resolve("absent.bsa").toString()));
      args.addAll(List.of(options));
      assertEquals(2, run(args.toArray(String[]::new)).status(), args.toString());
    }
  }

  /**
   * The explicitly selected compatibility profile resolves multiple implemented families by
   * priority.
   */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileChoosesTes3BeforeTes4RegardlessOfSwitchOrder() throws Exception {
    for (String[] order :
        List.of(new String[] {"-tes4", "-tes3"}, new String[] {"-tes3", "-tes4"})) {
      Path source = Files.createTempDirectory(temporary, "profile-source");
      Files.writeString(source.resolve("a.txt"), "one");
      Path archive = temporary.resolve(source.getFileName() + ".bsa");
      var args =
          new ArrayList<>(
              List.of(
                  "--compatibility-profile=bsarch-1.0/v1",
                  "pack",
                  source.toString(),
                  archive.toString()));
      args.addAll(List.of(order));
      Result result = run(args.toArray(String[]::new));
      assertEquals(0, result.status(), result.error());
      assertTrue(run(archive.toString()).output().contains("Family: TES3_BSA"));
    }
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
  @EnabledOnOs(OS.WINDOWS)
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
  @EnabledOnOs(OS.WINDOWS)
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
  @EnabledOnOs(OS.WINDOWS)
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
  @EnabledOnOs(OS.WINDOWS)
  void versionReportsArtifactAndProfileAndOperationalFailuresUseStderr() throws Exception {
    Result version = run("--VERSION");
    assertEquals(0, version.status());
    assertTrue(version.output().startsWith("JBSA " + System.getProperty("jbsa.version")));
    assertTrue(version.output().contains("bsarch-1.0/v1 SHA-256 9577D821"));
    assertEquals("", version.error());
    Result missing = run(temporary.resolve("missing.bsa").toString());
    assertEquals(1, missing.status());
    assertEquals("", missing.output());
    assertTrue(missing.error().startsWith("Error: [source]"));
    assertTrue(missing.error().contains("phase=PREFLIGHT"));
  }

  /** Launches the modular entry point from the Gradle JARs and captures UTF-8. */
  private Result run(String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
    String cliJar = System.getProperty("jbsa.cli.jar");
    String modulePath;
    if (cliJar == null) {
      command.add("--enable-native-access=io.github.evildarkarchon.jbsa");
      modulePath =
          Path.of("target/classes").toAbsolutePath()
              + java.io.File.pathSeparator
              + Path.of("../jbsa/target/classes").toAbsolutePath();
    } else {
      int argumentCount = Integer.parseInt(System.getProperty("jbsa.cli.jvmArgument.count"));
      for (int index = 0; index < argumentCount; index++) {
        command.add(System.getProperty("jbsa.cli.jvmArgument." + index));
      }
      modulePath =
          Path.of(cliJar).toAbsolutePath()
              + java.io.File.pathSeparator
              + Path.of(System.getProperty("jbsa.library.jar")).toAbsolutePath()
              + java.io.File.pathSeparator
              + System.getProperty("jbsa.cli.runtimeClasspath");
    }
    command.add("--module-path");
    command.add(modulePath);
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
