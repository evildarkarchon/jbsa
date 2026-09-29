package io.github.evildarkarchon.jbsa.cli;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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
    assertTrue(dumped.output().contains("Target: PC"));
    assertTrue(dumped.output().contains("Codec: ZLIB"));
    assertTrue(dumped.output().contains("Dimensions: 1x1"));
    assertTrue(dumped.output().contains("DDS flags: 0"));
    assertTrue(dumped.output().contains("Mip range: 0..0"));
    Path xboxNamed = temporary.resolve("dds_xbox.ba2");
    Files.copy(archive, xboxNamed);
    Result xboxInfo = run("--compatibility-profile=bsarch-1.0/v1", xboxNamed.toString());
    assertEquals(0, xboxInfo.status(), xboxInfo.error());
    assertTrue(xboxInfo.output().contains("Target: XBOX"), xboxInfo.output());
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

  /** Fallout 4 v7/v8 archives remain decode-only through dump and unpack CLI operations. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void dumpsAndUnpacksFallout4VersionsSevenAndEight() throws Exception {
    Path workingDirectory = Path.of("").toAbsolutePath();
    Path repository =
        Files.isDirectory(workingDirectory.resolve("tests"))
            ? workingDirectory
            : workingDirectory.resolve("..").normalize();
    Path fixtures = repository.resolve("tests/fixtures/synthetic/artifacts/archives");
    List<DecodeFixture> cases =
        List.of(
            new DecodeFixture(
                7,
                "GNRL",
                fixtures.resolve("fo4-gnrl-v7-stored.ba2"),
                "data/readme.txt",
                "jbsa-v7-stored\n".getBytes(StandardCharsets.UTF_8)),
            new DecodeFixture(
                8,
                "GNRL",
                fixtures.resolve("fo4-gnrl-v8-zlib.hex"),
                "data/compressed.txt",
                "B".repeat(32).getBytes(StandardCharsets.UTF_8)),
            new DecodeFixture(
                7,
                "DX10",
                fixtures.resolve("fo4-dx10-v7-zlib.hex"),
                "textures/checker.dds",
                java.util.HexFormat.of().parseHex("630e873656a6ce50")),
            new DecodeFixture(
                8,
                "DX10",
                fixtures.resolve("fo4-dx10-v8-zlib.hex"),
                "textures/checker.dds",
                java.util.HexFormat.of().parseHex("630e873656a6ce50")));
    for (DecodeFixture fixture : cases) {
      Path archive =
          temporary.resolve("fo4-v" + fixture.version() + "-" + fixture.subtype() + ".ba2");
      if (fixture.source().toString().endsWith(".hex")) {
        Files.write(
            archive, java.util.HexFormat.of().parseHex(Files.readString(fixture.source()).trim()));
      } else {
        Files.copy(fixture.source(), archive);
      }
      Result dumped = run(archive.toString(), "-dump");
      assertEquals(0, dumped.status(), dumped.error());
      assertTrue(dumped.output().contains("Version: " + fixture.version()), dumped.output());
      assertTrue(dumped.output().contains("Subtype: " + fixture.subtype()), dumped.output());
      Path destination =
          Files.createDirectory(temporary.resolve("decoded-" + cases.indexOf(fixture)));
      Result unpacked = run("unpack", archive.toString(), destination.toString(), "--no-progress");
      assertEquals(0, unpacked.status(), unpacked.error());
      byte[] decoded = Files.readAllBytes(destination.resolve(fixture.entry()));
      assertArrayEquals(
          fixture.payload(),
          java.util.Arrays.copyOfRange(
              decoded, fixture.subtype().equals("DX10") ? decoded.length - 8 : 0, decoded.length));
    }
  }

  /** Starfield General CLI codec selection controls v2 versus v3/method-3 wire output. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void packsAndUnpacksStarfieldGeneralVariants() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("sf-input/Data"));
    String payload = "starfield".repeat(200);
    Files.writeString(source.resolve("Entry.bin"), payload);
    List<String> codecs = java.util.Arrays.asList(null, "-z", "-z:zlib", "-z:lz4");
    for (int index = 0; index < codecs.size(); index++) {
      String codec = codecs.get(index);
      Path archive = temporary.resolve("sf-" + index + ".ba2");
      var arguments =
          new ArrayList<>(
              List.of(
                  "pack",
                  source.getParent().toString(),
                  archive.toString(),
                  "-sf1",
                  "-share:no",
                  "--no-progress"));
      if (codec != null) arguments.add(4, codec);
      Result packed = run(arguments.toArray(String[]::new));
      assertEquals(0, packed.status(), packed.error());
      Result dumped = run(archive.toString(), "-dump");
      assertEquals(0, dumped.status(), dumped.error());
      assertTrue(dumped.output().contains("Family: STARFIELD_GENERAL_BA2"));
      assertTrue(dumped.output().contains("Unknown value at 24: 1"), dumped.output());
      assertTrue(
          dumped
              .output()
              .contains("Version: " + (codec != null && codec.equals("-z:lz4") ? 3 : 2)));
      assertTrue(
          dumped
              .output()
              .contains(
                  "Codec: "
                      + (codec == null ? "STORED" : codec.equals("-z:lz4") ? "LZ4_RAW" : "ZLIB")));
      assertTrue(dumped.output().contains("  Compressed: " + (codec != null)), dumped.output());
      if ("-z:lz4".equals(codec)) {
        assertTrue(dumped.output().contains("Compression method: 3"), dumped.output());
      }
      Path destination = Files.createDirectory(temporary.resolve("sf-output-" + index));
      Result unpacked = run("unpack", archive.toString(), destination.toString(), "--no-progress");
      assertEquals(0, unpacked.status(), unpacked.error());
      assertEquals(payload, Files.readString(destination.resolve("Data/Entry.bin")));
    }
  }

  /** Starfield DDS CLI defaults to raw LZ4 and retains the explicit v2 zlib alternative. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void packsAndUnpacksStarfieldDdsVariants() throws Exception {
    Path textures = Files.createDirectories(temporary.resolve("sf-dds-input/Textures"));
    var source = java.nio.ByteBuffer.allocate(160).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    source
        .putInt(0, 0x20534444)
        .putInt(4, 124)
        .putInt(12, 7)
        .putInt(16, 5)
        .putInt(28, 1)
        .putInt(76, 32)
        .putInt(80, 4)
        .putInt(84, 0x31545844);
    for (int index = 128; index < source.capacity(); index++) source.put(index, (byte) index);
    Files.write(textures.resolve("Small.dds"), source.array());
    List<String> codecs = java.util.Arrays.asList(null, "-z", "-z:lz4", "-z:zlib");
    for (int index = 0; index < codecs.size(); index++) {
      String codec = codecs.get(index);
      Path archive = temporary.resolve("sf-dds-" + index + ".ba2");
      var arguments =
          new ArrayList<>(
              List.of(
                  "pack",
                  textures.getParent().toString(),
                  archive.toString(),
                  "-sf1dds",
                  "--no-progress"));
      if (codec != null) arguments.add(4, codec);
      Result packed = run(arguments.toArray(String[]::new));
      assertEquals(0, packed.status(), packed.error());
      var wire =
          java.nio.ByteBuffer.wrap(Files.readAllBytes(archive))
              .order(java.nio.ByteOrder.LITTLE_ENDIAN);
      boolean raw = codec == null || codec.equals("-z") || codec.equals("-z:lz4");
      assertEquals(raw ? 3 : 2, wire.getInt(4));
      if (raw) assertEquals(3, wire.getInt(32));
      Path destination = Files.createDirectory(temporary.resolve("sf-dds-output-" + index));
      Result unpacked = run("unpack", archive.toString(), destination.toString(), "--no-progress");
      assertEquals(0, unpacked.status(), unpacked.error());
      byte[] reconstructed = Files.readAllBytes(destination.resolve("Textures/Small.dds"));
      assertArrayEquals(
          java.util.Arrays.copyOfRange(source.array(), 128, source.capacity()),
          java.util.Arrays.copyOfRange(reconstructed, 128, reconstructed.length));
    }
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
    assertTrue(dumped.output().contains("Folder file count: 1"));
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

  /** Repeating a profile family selector preserves the same priority choice. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileAcceptsRepeatedFamilySelector() throws Exception {
    Path source = Files.createDirectory(temporary.resolve("repeated-family-source"));
    Files.writeString(source.resolve("entry.txt"), "payload");
    Path archive = temporary.resolve("repeated-family.bsa");
    Result packed =
        run(
            "--compatibility-profile=bsarch-1.0/v1",
            "pack",
            source.toString(),
            archive.toString(),
            "-tes3",
            "-tes3");
    assertEquals(0, packed.status(), packed.output() + packed.error());
    assertTrue(run(archive.toString()).output().contains("Family: TES3_BSA"));
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

  /** Repeated codec switches use the first profile value and remain invalid in safe mode. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileRepeatedCodecUsesTheFirstValue() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("repeated-codec-source/meshes"));
    Files.writeString(source.resolve("entry.nif"), "compressible".repeat(500));
    String profile = "--compatibility-profile=bsarch-1.0/v1";
    Path expected = temporary.resolve("first-codec-expected.bsa");
    Path repeated = temporary.resolve("first-codec-repeated.bsa");
    Path reversed = temporary.resolve("first-codec-reversed.bsa");
    Path safe = temporary.resolve("first-codec-safe.bsa");
    assertEquals(
        0,
        run(profile, "pack", source.getParent().toString(), expected.toString(), "-tes4", "-z:zlib")
            .status());
    assertEquals(
        0,
        run(
                profile,
                "pack",
                source.getParent().toString(),
                repeated.toString(),
                "-tes4",
                "-z:zlib",
                "-z:lz4f")
            .status());
    assertArrayEquals(Files.readAllBytes(expected), Files.readAllBytes(repeated));
    assertTrue(
        run(
                    profile,
                    "pack",
                    source.getParent().toString(),
                    reversed.toString(),
                    "-tes4",
                    "-z:lz4f",
                    "-z:zlib")
                .status()
            != 0);
    assertFalse(Files.exists(reversed));
    assertEquals(
        2,
        run("pack", source.getParent().toString(), safe.toString(), "-tes4", "-z:zlib", "-z:lz4f")
            .status());
    assertFalse(Files.exists(safe));
  }

  /** Profile boolean values other than no enable sharing, while safe parsing rejects them. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileBooleanShareHasObservableFirstValueSemantics() throws Exception {
    Path source = Files.createDirectory(temporary.resolve("share-source"));
    Files.writeString(source.resolve("first.txt"), "identical-payload".repeat(100));
    Files.writeString(source.resolve("second.txt"), "identical-payload".repeat(100));
    String profile = "--compatibility-profile=bsarch-1.0/v1";
    Path enabled = temporary.resolve("sharing-enabled.bsa");
    Path permissive = temporary.resolve("sharing-permissive.bsa");
    Path disabled = temporary.resolve("sharing-disabled.bsa");
    Path safe = temporary.resolve("sharing-safe.bsa");
    assertEquals(
        0,
        run(profile, "pack", source.toString(), enabled.toString(), "-tes3", "-share:yes")
            .status());
    assertEquals(
        0,
        run(profile, "pack", source.toString(), permissive.toString(), "-tes3", "-share:other")
            .status());
    assertEquals(
        0,
        run(profile, "pack", source.toString(), disabled.toString(), "-tes3", "-share:no")
            .status());
    assertArrayEquals(Files.readAllBytes(enabled), Files.readAllBytes(permissive));
    assertFalse(java.util.Arrays.equals(Files.readAllBytes(enabled), Files.readAllBytes(disabled)));
    assertEquals(
        2, run("pack", source.toString(), safe.toString(), "-tes3", "-share:other").status());
    assertFalse(Files.exists(safe));
  }

  /** Legacy split parsing caps large values and treats an absent value as the family default. */
  @Test
  void profileSplitParsingRetainsTheQualifiedBoundaries() {
    String[] base = {"--compatibility-profile=bsarch-1.0/v1", "pack", "source", "archive", "-tes3"};
    assertEquals(
        new io.github.evildarkarchon.jbsa.PackOptions.Splitting.UpToBytes(8L << 30),
        Invocation.parse(append(base, "-split:100")).packOptions().splitting());
    assertEquals(
        new io.github.evildarkarchon.jbsa.PackOptions.Splitting.UpToBytes(0),
        Invocation.parse(append(base, "-split:word")).packOptions().splitting());
    assertEquals(
        new io.github.evildarkarchon.jbsa.PackOptions.Splitting.FamilyDefault(),
        Invocation.parse(append(base, "-split")).packOptions().splitting());
    assertEquals(
        new io.github.evildarkarchon.jbsa.PackOptions.Splitting.LegacyPerEntry(),
        Invocation.parse(append(base, "-split:-1")).packOptions().splitting());
    assertThrows(
        IllegalArgumentException.class,
        () -> Invocation.parse(new String[] {"pack", "source", "archive", "-tes3", "-split:100"}));
  }

  /** Explicit zero flags stay literal in safe parsing and become automatic only in the profile. */
  @Test
  void profileZeroFlagsDoNotChangeSafeExplicitZero() {
    String[] safe = {"pack", "source", "archive", "-tes4", "-af:0", "-ff:0"};
    String[] profile = {
      "--compatibility-profile=bsarch-1.0/v1",
      "pack",
      "source",
      "archive",
      "-tes4",
      "-af:0",
      "-ff:0"
    };
    assertEquals(
        new io.github.evildarkarchon.jbsa.FlagSelection.Explicit(0),
        Invocation.parse(safe).packOptions().archiveFlags());
    assertEquals(
        new io.github.evildarkarchon.jbsa.FlagSelection.Explicit(0),
        Invocation.parse(safe).packOptions().fileFlags());
    assertEquals(
        io.github.evildarkarchon.jbsa.FlagSelection.AUTOMATIC,
        Invocation.parse(profile).packOptions().archiveFlags());
    assertEquals(
        io.github.evildarkarchon.jbsa.FlagSelection.AUTOMATIC,
        Invocation.parse(profile).packOptions().fileFlags());
  }

  /** Only an existing malformed archive earns the profile's information-error zero status. */
  @Test
  void profileArchiveInformationFailureExitsZero() throws Exception {
    Path missing = temporary.resolve("missing.bsa");
    Result safe = run(missing.toString());
    assertEquals(1, safe.status());
    Result missingProfiled = run("--compatibility-profile=bsarch-1.0/v1", missing.toString());
    assertEquals(1, missingProfiled.status());
    assertTrue(missingProfiled.output().contains("Error: ["), missingProfiled.output());
    assertEquals("", missingProfiled.error());

    Path malformed = Files.write(temporary.resolve("malformed.bsa"), new byte[] {1, 2, 3, 4});
    Result profiled = run("--compatibility-profile=bsarch-1.0/v1", malformed.toString());
    assertEquals(0, profiled.status());
    assertTrue(profiled.output().contains("Error: ["), profiled.output());
    assertEquals("", profiled.error());
  }

  /** The selected profile places invocation errors on the qualified reference stream. */
  @Test
  void profilePlacesInvalidInvocationOnStandardOutput() throws Exception {
    Path archive = temporary.resolve("invalid.bsa");
    Result invalid =
        run(
            "--compatibility-profile=bsarch-1.0/v1",
            "pack",
            "missing",
            archive.toString(),
            "-tes3",
            "-z");
    assertEquals(2, invalid.status());
    assertTrue(invalid.output().startsWith("Error: [invocation]"), invalid.output());
    assertEquals("", invalid.error());
    assertTrue(Files.notExists(archive));
  }

  /** Empty plus components remain usage errors before profile source omission can run. */
  @Test
  void profileRejectsEmptyPackSourceComponent() throws Exception {
    Path archive = temporary.resolve("empty-component.bsa");
    Result invalid =
        run(
            "--compatibility-profile=bsarch-1.0/v1",
            "pack",
            "missing+",
            archive.toString(),
            "-tes3");
    assertEquals(2, invalid.status());
    assertTrue(invalid.output().contains("Empty pack source component"), invalid.output());
    assertEquals("", invalid.error());
    assertTrue(Files.notExists(archive));
  }

  /** Only the explicit profile ignores unknown long switches after required operands. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileIgnoresUnknownLongTailSwitch() throws Exception {
    Path source = Files.createDirectory(temporary.resolve("source"));
    Files.writeString(source.resolve("entry.txt"), "payload");
    Path safeArchive = temporary.resolve("safe.bsa");
    Result safe =
        run("pack", source.toString(), safeArchive.toString(), "-tes3", "--oracle-unknown");
    assertEquals(2, safe.status());
    assertTrue(Files.notExists(safeArchive));

    Path profileArchive = temporary.resolve("profile.bsa");
    Result profiled =
        run(
            "--compatibility-profile=bsarch-1.0/v1",
            "pack",
            source.toString(),
            profileArchive.toString(),
            "-tes3",
            "--oracle-unknown",
            "extra-tail");
    assertEquals(0, profiled.status(), profiled.output() + profiled.error());
    assertTrue(Files.isRegularFile(profileArchive));
  }

  /** Repeated unknown switches and extra operands remain ignored by the selected profile. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileIgnoresRepeatedUnknownTailArguments() throws Exception {
    Path source = Files.createDirectory(temporary.resolve("repeated-tail-source"));
    Files.writeString(source.resolve("entry.txt"), "payload");
    List<List<String>> tails =
        List.of(List.of("--vendor-flag", "--vendor-flag"), List.of("extra-tail", "extra-tail"));
    for (int index = 0; index < tails.size(); index++) {
      Path archive = temporary.resolve("repeated-tail-" + index + ".bsa");
      var arguments =
          new ArrayList<>(
              List.of(
                  "--compatibility-profile=bsarch-1.0/v1",
                  "pack",
                  source.toString(),
                  archive.toString(),
                  "-tes3"));
      arguments.addAll(tails.get(index));
      Result packed = run(arguments.toArray(String[]::new));
      assertEquals(0, packed.status(), tails.get(index) + ": " + packed.output() + packed.error());
      assertTrue(Files.isRegularFile(archive), archive.toString());
    }
  }

  /** Profiled packing omits unusable roots but still needs an entry after library discovery. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileOmitsUnusablePackSources() throws Exception {
    Path missing = temporary.resolve("missing-source");
    Path valid = Files.createDirectory(temporary.resolve("valid-source"));
    Files.writeString(valid.resolve("entry.txt"), "payload");
    String mixed = missing + "+" + valid;
    Path safeArchive = temporary.resolve("safe.bsa");
    Result safe = run("pack", mixed, safeArchive.toString(), "-tes3");
    assertEquals(1, safe.status(), safe.error());
    assertTrue(Files.notExists(safeArchive));

    Path profiledArchive = temporary.resolve("profiled.bsa");
    Result profiled =
        run(
            "--compatibility-profile=bsarch-1.0/v1",
            "pack",
            mixed,
            profiledArchive.toString(),
            "-tes3");
    assertEquals(0, profiled.status(), profiled.output() + profiled.error());
    Result listed = run(profiledArchive.toString(), "-list");
    assertEquals(0, listed.status(), listed.error());
    assertTrue(listed.output().contains("Entries: 1"));
    assertTrue(listed.output().contains("entry.txt"));

    Path malformed = temporary.resolve("malformed.bsa");
    Files.write(malformed, new byte[] {'B', 'S', 'A', 0, 0x68, 0, 0, 0});
    Path recoveredArchive = temporary.resolve("recovered.bsa");
    Result recovered =
        run(
            "--compatibility-profile=bsarch-1.0/v1",
            "pack",
            malformed + "+" + valid,
            recoveredArchive.toString(),
            "-tes3");
    assertEquals(0, recovered.status(), recovered.output() + recovered.error());
    assertTrue(run(recoveredArchive.toString()).output().contains("Entries: 1"));

    Path empty = Files.createDirectory(temporary.resolve("empty-source"));
    Path emptyArchive = temporary.resolve("empty.bsa");
    Result none =
        run(
            "--compatibility-profile=bsarch-1.0/v1",
            "pack",
            missing + "+" + empty,
            emptyArchive.toString(),
            "-tes3");
    assertEquals(1, none.status(), none.output());
    assertTrue(Files.notExists(emptyArchive));
  }

  /** Profile omission cannot bypass the library's explicit output-source overlap rejection. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileRetainsOutputAliasesForLibrarySafetyPreflight() throws Exception {
    Path valid = Files.createDirectory(temporary.resolve("valid-source"));
    Files.writeString(valid.resolve("entry.txt"), "payload");
    for (String alias : List.of("archive.bsa", "archive2.bsa")) {
      Path archive = temporary.resolve("archive.bsa");
      Result result =
          run(
              "--compatibility-profile=bsarch-1.0/v1",
              "pack",
              temporary.resolve(alias) + "+" + valid,
              archive.toString(),
              "-tes3");
      assertEquals(1, result.status(), result.output());
      assertTrue(result.output().contains("source.output-overlap"), result.output());
      assertTrue(Files.notExists(archive));
    }
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

  /**
   * Every writable family produces identical archive bytes and semantic CLI records with one or
   * automatic workers. The DDS sources use valid, distinct textures so this exercises real chunk
   * compression rather than an empty or rejected pack request.
   */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void workerSelectionPreservesCrossFamilyObservationsAndBytes() throws Exception {
    List<WorkerCase> cases =
        List.of(
            new WorkerCase("-tes3", null, false),
            new WorkerCase("-tes4", "-z:zlib", false),
            new WorkerCase("-fo3", "-z:zlib", false),
            new WorkerCase("-sse", "-z:lz4f", false),
            new WorkerCase("-fo4", "-z:zlib", false),
            new WorkerCase("-fo4dds", "-z:zlib", true),
            new WorkerCase("-sf1", "-z:lz4", false),
            new WorkerCase("-sf1dds", "-z:lz4", true));
    for (WorkerCase testCase : cases) {
      String name = testCase.selector().substring(1);
      Path source = Files.createDirectory(temporary.resolve(name + "-workers-source"));
      Path entries = Files.createDirectory(source.resolve(testCase.dds() ? "Textures" : "meshes"));
      for (int index = 0; index < 4; index++) {
        Path entry = entries.resolve("entry" + index + (testCase.dds() ? ".dds" : ".nif"));
        if (testCase.dds()) {
          Files.write(entry, workerDdsFixture(index));
        } else {
          Files.writeString(entry, ("payload-" + index).repeat(2_048));
        }
      }
      String extension =
          testCase.selector().contains("fo4") || testCase.selector().contains("sf1")
              ? ".ba2"
              : ".bsa";
      Path singleArchive = temporary.resolve(name + "-single" + extension);
      Path automaticArchive = temporary.resolve(name + "-automatic" + extension);
      List<String> singleArguments =
          new ArrayList<>(
              List.of("pack", source.toString(), singleArchive.toString(), testCase.selector()));
      List<String> automaticArguments =
          new ArrayList<>(
              List.of("pack", source.toString(), automaticArchive.toString(), testCase.selector()));
      if (testCase.codec() != null) {
        singleArguments.add(testCase.codec());
        automaticArguments.add(testCase.codec());
      }
      singleArguments.addAll(List.of("-mt:no", "--no-progress"));
      automaticArguments.addAll(List.of("-mt:yes", "--no-progress"));
      Result single = run(singleArguments.toArray(String[]::new));
      Result automatic = run(automaticArguments.toArray(String[]::new));
      assertEquals(0, single.status(), name + ": " + single.error());
      assertEquals(0, automatic.status(), name + ": " + automatic.error());
      assertEquals(
          structuredDiagnostics(single.error()),
          structuredDiagnostics(automatic.error()),
          name + " diagnostics");
      assertArrayEquals(
          Files.readAllBytes(singleArchive), Files.readAllBytes(automaticArchive), name);
      assertEquals(
          normalizedArchiveObservation(single.output(), singleArchive),
          normalizedArchiveObservation(automatic.output(), automaticArchive),
          name + " pack observation");

      Result singleDump = run(singleArchive.toString(), "-dump");
      Result automaticDump = run(automaticArchive.toString(), "-dump");
      assertEquals(0, singleDump.status(), name + ": " + singleDump.error());
      assertEquals(0, automaticDump.status(), name + ": " + automaticDump.error());
      assertEquals(
          normalizedArchiveObservation(singleDump.output(), singleArchive),
          normalizedArchiveObservation(automaticDump.output(), automaticArchive),
          name + " dump observation");

      Path singleDestination = Files.createDirectory(temporary.resolve(name + "-single-output"));
      Path automaticDestination =
          Files.createDirectory(temporary.resolve(name + "-automatic-output"));
      Result singleUnpack =
          run(
              "unpack",
              singleArchive.toString(),
              singleDestination.toString(),
              "-mt:no",
              "--no-progress");
      Result automaticUnpack =
          run(
              "unpack",
              automaticArchive.toString(),
              automaticDestination.toString(),
              "-mt:yes",
              "--no-progress");
      assertEquals(0, singleUnpack.status(), name + ": " + singleUnpack.error());
      assertEquals(0, automaticUnpack.status(), name + ": " + automaticUnpack.error());
      assertEquals(
          normalizedArchiveObservation(singleUnpack.output(), singleDestination),
          normalizedArchiveObservation(automaticUnpack.output(), automaticDestination),
          name + " unpack observation");
      List<Path> singleFiles;
      List<Path> automaticFiles;
      try (var paths = Files.walk(singleDestination)) {
        singleFiles =
            paths.filter(Files::isRegularFile).map(singleDestination::relativize).sorted().toList();
      }
      try (var paths = Files.walk(automaticDestination)) {
        automaticFiles =
            paths
                .filter(Files::isRegularFile)
                .map(automaticDestination::relativize)
                .sorted()
                .toList();
      }
      assertEquals(singleFiles, automaticFiles, name + " extracted tree");
      for (Path entry : singleFiles) {
        assertArrayEquals(
            Files.readAllBytes(singleDestination.resolve(entry)),
            Files.readAllBytes(automaticDestination.resolve(entry)),
            name + " extracted " + entry);
      }
    }
  }

  /** Redirected stderr and --no-progress suppress presentation without changing archive output. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void redirectedProgressDoesNotChangePackResults() throws Exception {
    Path source = Files.createDirectory(temporary.resolve("progress-source"));
    Files.writeString(source.resolve("entry.txt"), "payload".repeat(100));
    Path ordinary = temporary.resolve("progress-ordinary.bsa");
    Path suppressed = temporary.resolve("progress-suppressed.bsa");
    Result first = run("pack", source.toString(), ordinary.toString(), "-tes3");
    Result second = run("pack", source.toString(), suppressed.toString(), "-tes3", "--no-progress");
    assertEquals(0, first.status(), first.output() + first.error());
    assertEquals(0, second.status(), second.output() + second.error());
    assertFalse(first.error().contains("Progress:"));
    assertFalse(second.error().contains("Progress:"));
    assertArrayEquals(Files.readAllBytes(ordinary), Files.readAllBytes(suppressed));
    assertEquals(
        normalizedArchiveObservation(first.output(), ordinary),
        normalizedArchiveObservation(second.output(), suppressed));
  }

  /** Archive and directory sources retain operand order when a basename filter selects entries. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void archiveSourceOverlayAndBasenameFilterReachTheLibrary() throws Exception {
    Path base = Files.createDirectory(temporary.resolve("overlay-base"));
    Files.writeString(base.resolve("common.txt"), "base");
    Files.writeString(base.resolve("old.txt"), "old");
    Files.writeString(base.resolve("excluded.bin"), "excluded");
    Path baseArchive = temporary.resolve("base.bsa");
    Result first = run("pack", base.toString(), baseArchive.toString(), "-tes3");
    assertEquals(0, first.status(), first.error());

    Path overlay = Files.createDirectory(temporary.resolve("overlay-later"));
    Files.writeString(overlay.resolve("common.txt"), "replacement");
    Files.writeString(overlay.resolve("new.txt"), "new");
    Files.writeString(overlay.resolve("selected.BIN"), "selected");
    Files.writeString(overlay.resolve("also-excluded.bin"), "excluded");
    Path combined = temporary.resolve("combined.bsa");
    Result packed =
        run(
            "pack",
            baseArchive + "+" + overlay,
            combined.toString(),
            "-tes3",
            "-f:*.TXT,selected.?in",
            "--no-progress");
    assertEquals(0, packed.status(), packed.error());
    Path destination = Files.createDirectory(temporary.resolve("overlay-output"));
    Result unpacked = run("unpack", combined.toString(), destination.toString());
    assertEquals(0, unpacked.status(), unpacked.error());
    assertEquals("replacement", Files.readString(destination.resolve("common.txt")));
    assertEquals("old", Files.readString(destination.resolve("old.txt")));
    assertEquals("new", Files.readString(destination.resolve("new.txt")));
    assertEquals("selected", Files.readString(destination.resolve("selected.BIN")));
    assertTrue(Files.notExists(destination.resolve("excluded.bin")));
    assertTrue(Files.notExists(destination.resolve("also-excluded.bin")));
  }

  /** Omitting unpack's destination selects the archive's containing directory. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void unpackDefaultsToArchiveContainingDirectory() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("default-source/meshes"));
    Files.writeString(source.resolve("default-entry.txt"), "destination payload");
    Path archive = temporary.resolve("default-destination.bsa");
    Result packed = run("pack", source.getParent().toString(), archive.toString(), "-tes3");
    assertEquals(0, packed.status(), packed.error());

    Result unpacked = run("unpack", archive.toString(), "--no-progress");
    assertEquals(0, unpacked.status(), unpacked.error());
    assertTrue(
        unpacked.output().contains("Destination: " + temporary.toAbsolutePath().normalize()));
    assertTrue(unpacked.output().contains("Published entries: 1"), unpacked.output());
    assertEquals(
        "destination payload", Files.readString(temporary.resolve("meshes/default-entry.txt")));
  }

  /** The complete profile selects implicit replacement for both pack and unpack mutations. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileImplicitlyReplacesPackAndUnpackTargets() throws Exception {
    String profile = "--compatibility-profile=bsarch-1.0/v1";
    Path source = Files.createDirectory(temporary.resolve("replace-source"));
    Path entry = source.resolve("entry.txt");
    Files.writeString(entry, "first");
    Path archive = temporary.resolve("replace-profile.bsa");
    Result first = run(profile, "pack", source.toString(), archive.toString(), "-tes3");
    assertEquals(0, first.status(), first.output() + first.error());
    byte[] predecessor = Files.readAllBytes(archive);

    Files.writeString(entry, "second");
    Result safe = run("pack", source.toString(), archive.toString(), "-tes3");
    assertEquals(1, safe.status(), safe.error());
    assertArrayEquals(predecessor, Files.readAllBytes(archive));
    Result second = run(profile, "pack", source.toString(), archive.toString(), "-tes3");
    assertEquals(0, second.status(), second.output() + second.error());
    assertTrue(!java.util.Arrays.equals(predecessor, Files.readAllBytes(archive)));

    Path destination = Files.createDirectory(temporary.resolve("replace-output"));
    Files.writeString(destination.resolve("entry.txt"), "predecessor");
    Result unpacked = run(profile, "unpack", archive.toString(), destination.toString());
    assertEquals(0, unpacked.status(), unpacked.output() + unpacked.error());
    assertEquals("second", Files.readString(destination.resolve("entry.txt")));
  }

  /** Profile zero flags retain automatic selection and a negative split packs whole entries. */
  @Test
  @EnabledOnOs(OS.WINDOWS)
  void profileZeroFlagsAndNegativeSplitPublishOnePartPerEntry() throws Exception {
    Path source = Files.createDirectories(temporary.resolve("legacy-options-source/meshes"));
    Files.writeString(source.resolve("first.nif"), "first");
    Files.writeString(source.resolve("second.nif"), "second");
    Path archive = temporary.resolve("legacy-options.bsa");
    Result packed =
        run(
            "--compatibility-profile=bsarch-1.0/v1",
            "pack",
            source.getParent().toString(),
            archive.toString(),
            "-tes4",
            "-af:0",
            "-ff:0",
            "-split:-1");
    assertEquals(0, packed.status(), packed.output() + packed.error());
    assertEquals(
        2, packed.output().lines().filter(line -> line.startsWith("Archive part:")).count());
    assertTrue(Files.isRegularFile(archive));
    assertTrue(Files.isRegularFile(temporary.resolve("legacy-options2.bsa")));
    Result first = run(archive.toString());
    assertEquals(0, first.status(), first.error());
    assertTrue(first.output().contains("Entries: 1"), first.output());
    Path automatic = temporary.resolve("automatic-options.bsa");
    Result omittedFlags =
        run(
            "--compatibility-profile=bsarch-1.0/v1",
            "pack",
            source.getParent().toString(),
            automatic.toString(),
            "-tes4",
            "-split:-1");
    assertEquals(0, omittedFlags.status(), omittedFlags.output() + omittedFlags.error());
    assertArrayEquals(Files.readAllBytes(automatic), Files.readAllBytes(archive));
  }

  /** Appends one switch without changing the reusable command operands. */
  private static String[] append(String[] arguments, String option) {
    String[] result = java.util.Arrays.copyOf(arguments, arguments.length + 1);
    result[arguments.length] = option;
    return result;
  }

  /** Returns the semantic CLI records after replacing only the archive path operand. */
  private static String normalizedArchiveObservation(String output, Path archive) {
    return output.replace(archive.toAbsolutePath().normalize().toString(), "<archive>");
  }

  /** Keeps CLI-owned diagnostics while ignoring warnings written by the Java runtime itself. */
  private static List<String> structuredDiagnostics(String stderr) {
    return stderr
        .lines()
        .filter(line -> line.startsWith("Warning: [") || line.startsWith("Error: ["))
        .toList();
  }

  /** Supplies a small valid DXT1 source with distinct payload bytes for worker comparisons. */
  private static byte[] workerDdsFixture(int marker) {
    var texture = java.nio.ByteBuffer.allocate(160).order(java.nio.ByteOrder.LITTLE_ENDIAN);
    texture
        .putInt(0, 0x20534444)
        .putInt(4, 124)
        .putInt(12, 7)
        .putInt(16, 5)
        .putInt(28, 1)
        .putInt(76, 32)
        .putInt(80, 4)
        .putInt(84, 0x31545844);
    for (int index = 128; index < texture.capacity(); index++) {
      texture.put(index, (byte) (index + marker));
    }
    return texture.array();
  }

  /** One selected writable family and its valid compression and source type. */
  private record WorkerCase(String selector, String codec, boolean dds) {}

  /** Launches the modular entry point from the Gradle JARs and captures UTF-8. */
  private Result run(String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    String executable = System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java";
    command.add(Path.of(System.getProperty("java.home"), "bin", executable).toString());
    String cliJar = System.getProperty("jbsa.cli.jar");
    String modulePath;
    if (cliJar == null) {
      if (System.getProperty("os.name").startsWith("Windows")) command.add("-Xrs");
      command.add(
          "--enable-native-access=io.github.evildarkarchon.jbsa.cli,io.github.evildarkarchon.jbsa");
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

  /** One committed decode-only CLI vector and its expected extracted payload suffix. */
  private record DecodeFixture(
      int version, String subtype, Path source, String entry, byte[] payload) {}

  private record Result(int status, String output, String error) {}
}
