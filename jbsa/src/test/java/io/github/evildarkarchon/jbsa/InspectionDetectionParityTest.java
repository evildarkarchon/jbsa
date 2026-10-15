package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HexFormat;
import java.util.Optional;
import java.util.stream.Stream;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * JBSA-DET-001: inspection and open report exactly the recognition evidence that detection reports,
 * including every observed selector byte, for one literal fixture of every Archive Family.
 */
final class InspectionDetectionParityTest {
  @TempDir Path directory;

  // Literal fixtures are copied from the family reader tests, which work their bytes out by hand.
  private static final String TES3 =
      "00010000 0e000000 01000000 01000000 00000000 00000000 6100 0000000030000080 07";
  private static final String TES4 =
      "42534100 67000000 24000000 03000000 01000000 01000000 02000000 02000000 00000000 "
          + "6100016100000000 01000000 36000000 026100 "
          + "6200016200000000 01000000 49000000 6200 07";
  private static final String FO3_FNV_SKYRIM_LE =
      "42534100 68000000 24000000 03000000 01000000 01000000 02000000 02000000 00000000 "
          + "6100016100000000 01000000 36000000 026100 "
          + "6200016200000000 01000000 49000000 6200 07";
  private static final String SSE =
      "42534100 69000000 24000000 03000000 01000000 01000000 02000000 02000000 00000000 "
          + "6100016100000000 01000000 00000000 3e000000 00000000 026100 "
          + "6200016200000000 01000000 51000000 6200 07";
  private static final String FO4_GENERAL =
      "42544458 01000000 474e524c 01000000 3d00000000000000 "
          + "7400bca3 74787400 ce51b53a 00011000 3c00000000000000 00000000 01000000 0df0adba "
          + "07 0700 612f622e747874";
  // Version 3 with method 3 (raw LZ4): the observed prefix reaches the method at offset 32.
  private static final String STARFIELD_GENERAL =
      "42544458 03000000 474e524c 01000000 5900000000000000 0100000000000000 03000000 "
          + "4702f2e5 74787400 7f2cb78c 00011000 4800000000000000 11000000 0f000000 0df0adba "
          + "f0006a6273612d737461726669656c640a 0c00 646174612f7261772e747874";
  // One stored 4x4 BC1 chunk without a filename table.
  private static final String FO4_DDS =
      "42544458 01000000 44583130 01000000 0000000000000000 "
          + "00000000 64647300 00000000 00 01 1800 0400 0400 01 47 00 00 "
          + "4800000000000000 00000000 08000000 0000 0000 0df0adba "
          + "0700000000000000";
  // Version 2 with one zlib chunk without a filename table.
  private static final String STARFIELD_DDS =
      "42544458 02000000 44583130 01000000 0000000000000000 0100000000000000 "
          + "00000000 64647300 00000000 00 01 1800 0400 0400 01 47 00 00 "
          + "5000000000000000 13000000 08000000 0000 0000 0df0adba "
          + "7801010800f7ff070000000000000000400008";
  // Version 3 with method 2: decodable as zlib only under the qualified Compatibility Profile.
  private static final String STARFIELD_GENERAL_FALLBACK =
      "42544458 03000000 474e524c 01000000 5400000000000000 0100000000000000 02000000 "
          + "7400bca3 74787400 ce51b53a 00011000 4800000000000000 0c000000 01000000 0df0adba "
          + "78da010100feff0700080008 0700 612f622e747874";
  // Version 3 with method 0, using the same zlib chunk as the version 2 texture fixture.
  private static final String STARFIELD_DDS_FALLBACK =
      "42544458 03000000 44583130 01000000 0000000000000000 0100000000000000 00000000 "
          + "00000000 64647300 00000000 00 01 1800 0400 0400 01 47 00 00 "
          + "5400000000000000 13000000 08000000 0000 0000 0df0adba "
          + "7801010800f7ff070000000000000000400008";

  /** Supplies one recognized literal per Archive Family under standard options. */
  static Stream<Arguments> families() {
    return Stream.of(
        Arguments.of(ArchiveFamily.TES3_BSA, TES3),
        Arguments.of(ArchiveFamily.TES4_BSA, TES4),
        Arguments.of(ArchiveFamily.FO3_FNV_SKYRIM_LE_BSA, FO3_FNV_SKYRIM_LE),
        Arguments.of(ArchiveFamily.SSE_BSA, SSE),
        Arguments.of(ArchiveFamily.FO4_GENERAL_BA2, FO4_GENERAL),
        Arguments.of(ArchiveFamily.STARFIELD_GENERAL_BA2, STARFIELD_GENERAL),
        Arguments.of(ArchiveFamily.FO4_DDS_BA2, FO4_DDS),
        Arguments.of(ArchiveFamily.STARFIELD_DDS_BA2, STARFIELD_DDS));
  }

  /** Supplies the Starfield version 3 non-method-3 fallbacks for both BA2 subtypes. */
  static Stream<Arguments> fallbacks() {
    return Stream.of(
        Arguments.of(ArchiveFamily.STARFIELD_GENERAL_BA2, STARFIELD_GENERAL_FALLBACK),
        Arguments.of(ArchiveFamily.STARFIELD_DDS_BA2, STARFIELD_DDS_FALLBACK));
  }

  /** Inspection and open retain the full observed prefix that detection reports for the family. */
  @ParameterizedTest
  @MethodSource("families")
  void inspectionReportsDetectedEvidence(ArchiveFamily family, String hex) throws Exception {
    Path path = literal(hex);
    ArchiveDetection detected = BethesdaArchives.standard().detect(path);
    assertEquals(Optional.of(family), detected.family());
    assertParity(detected, path, OpenOptions.standard());
  }

  /** The profile-gated fallback keeps detection's unsupported-variant evidence without a family. */
  @ParameterizedTest
  @MethodSource("fallbacks")
  // The active ANSI code page is read from Windows (GetACP).
  @EnabledOnOs(OS.WINDOWS)
  void qualifiedFallbackReportsDetectedEvidence(ArchiveFamily decodedAs, String hex)
      throws Exception {
    Path path = literal(hex);
    ArchiveDetection detected = BethesdaArchives.standard().detect(path);
    assertEquals(DetectionStatus.UNSUPPORTED_VARIANT, detected.status());
    assertTrue(detected.family().isEmpty());
    OpenOptions qualified =
        new OpenOptions(
            Optional.of(CompatibilityProfile.BSARCH_1_0_V1),
            ResourceLimits.standard(),
            Optional.empty());
    assertParity(detected, path, qualified);
    assertEquals(
        decodedAs, BethesdaArchives.standard().inspect(path, qualified).metadata().family());
  }

  /** Compares the detected evidence with both inspection and an owned open of the same file. */
  private static void assertParity(ArchiveDetection detected, Path path, OpenOptions options)
      throws Exception {
    assertEquals(detected, BethesdaArchives.standard().inspect(path, options).detection());
    try (OpenArchive archive = BethesdaArchives.standard().open(path, options)) {
      assertEquals(detected, archive.inspection().detection());
    }
  }

  /** Writes a literal byte sequence without invoking the implementation's encoder or hash. */
  private Path literal(String hex) throws Exception {
    return Files.write(
        directory.resolve("fixture.bin"), HexFormat.of().parseHex(hex.replace(" ", "")));
  }
}
