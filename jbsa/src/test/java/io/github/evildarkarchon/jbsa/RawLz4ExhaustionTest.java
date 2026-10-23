package io.github.evildarkarchon.jbsa;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
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

/**
 * Pins today's raw-LZ4 Resource Limits and resource-capacity exhaustion through the public archive
 * facade, on pack, on read (General and DX10) and on BA2 readback of a staged part (#93). Each
 * outcome asserts the Failure Kind, the Primary Failure, every Conformance Diagnostic with its
 * values and location, and the Secondary Failures in order, as literal lines.
 *
 * <p>Every scenario runs in a fresh JVM ({@link RawLz4ExhaustionProbe}) so it can pin either LZ4
 * provider and fix the heap, which fixes the internal heap ceiling capacity outcomes depend on. A
 * native run happens only on Windows x64; a portable run happens everywhere, forced on Windows by a
 * native library path override.
 */
final class RawLz4ExhaustionTest {
  /** A 64 MiB heap gives a 16 MiB internal heap ceiling, small enough to exhaust on purpose. */
  private static final int SMALL_HEAP_MIB = 64;

  /** A 256 MiB heap gives a 64 MiB ceiling, which every read fixture's pack and readback fits. */
  private static final int READ_HEAP_MIB = 256;

  @TempDir Path temporary;

  /** The encoded block written after the staged source breaches the scratch ceiling. */
  // Native LZ4 is qualified and loaded only on the Windows x64 baseline.
  @EnabledOnOs(OS.WINDOWS)
  @Test
  void packEncodedBlockBreachesScratchUnderNative() throws Exception {
    assertOutcome("pack-scratch", SMALL_HEAP_MIB, false, packScratch("NATIVE"));
  }

  /** The portable provider writes the same block, so it breaches the same ceiling identically. */
  @Test
  void packEncodedBlockBreachesScratchUnderPortable() throws Exception {
    assertOutcome("pack-scratch", SMALL_HEAP_MIB, true, packScratch("PORTABLE"));
  }

  /**
   * A dispatch-limit block encodes natively, then its readback's heap declaration exceeds the
   * capacity; the failure is located at the staged part's artifact.
   */
  // Native LZ4 is qualified and loaded only on the Windows x64 baseline.
  @EnabledOnOs(OS.WINDOWS)
  @Test
  void dispatchLimitBlockExhaustsReadbackUnderNative() throws Exception {
    assertOutcome(
        "pack-dispatch-block",
        SMALL_HEAP_MIB,
        false,
        "PROVIDER NATIVE",
        "FAILURE POLICY",
        "PRIMARY POLICY PROCESSING ordinal=0 id=io.resource-capacity"
            + " [archive=- entry=0 name=- field=- span=- artifact=large.ba2]",
        "DIAGNOSTIC io.resource-capacity ERROR PACK PROCESSING"
            + " [archive=- entry=0 name=- field=- span=- artifact=large.ba2] {}");
  }

  /**
   * The portable encoder's own heap reservation for a dispatch-limit block exceeds the capacity
   * during stabilization, before any readback; the budget's preflight context locates it.
   */
  @Test
  void dispatchLimitBlockExhaustsTheEncoderUnderPortable() throws Exception {
    assertOutcome(
        "pack-dispatch-block",
        SMALL_HEAP_MIB,
        true,
        "PROVIDER PORTABLE",
        "FAILURE POLICY",
        "PRIMARY POLICY PREFLIGHT ordinal=- id=io.resource-capacity"
            + " [archive=large.ba2 entry=- name=- field=- span=- artifact=-]",
        "DIAGNOSTIC io.resource-capacity ERROR PACK PREFLIGHT"
            + " [archive=large.ba2 entry=- name=- field=- span=- artifact=-] {}");
  }

  /** A small block's native readback declares only the source on the heap, so the pack succeeds. */
  // Native LZ4 is qualified and loaded only on the Windows x64 baseline.
  @EnabledOnOs(OS.WINDOWS)
  @Test
  void smallBlockReadbackFitsUnderNative() throws Exception {
    assertOutcome("pack-small-block", SMALL_HEAP_MIB, false, "PROVIDER NATIVE", "OK");
  }

  /**
   * Portable readback charges the part's actual raw block, about twice its size on the heap plus
   * the decoded copy, so a small block fits as it does natively. Until #102 readback charged a
   * dispatch-limit decode (33,624,190 heap bytes) for any raw-LZ4 part, which no heap below about
   * 135 MB could hold, so this scenario exhausted the 16 MiB ceiling.
   */
  @Test
  void smallBlockReadbackFitsUnderPortable() throws Exception {
    assertOutcome("pack-small-block", SMALL_HEAP_MIB, true, "PROVIDER PORTABLE", "OK");
  }

  /**
   * A 6 MiB block's native readback holds only its decoded copy on the heap, and its working set in
   * native memory, so the pack succeeds.
   */
  // Native LZ4 is qualified and loaded only on the Windows x64 baseline.
  @EnabledOnOs(OS.WINDOWS)
  @Test
  void midBlockReadbackFitsUnderNative() throws Exception {
    assertOutcome("pack-mid-block", SMALL_HEAP_MIB, false, "PROVIDER NATIVE", "OK");
  }

  /**
   * Portable readback exhaustion, kept with a larger input since #102: a 6 MiB block encodes in
   * about twice its size on the heap, which fits, but readback holds its stored and decoded block
   * plus the decoded copy, about three times its size, which exhausts the capacity at the staged
   * part. Before #102 the 1 MiB {@code pack-small-block} scenario pinned these lines.
   */
  @Test
  void midBlockExhaustsReadbackUnderPortable() throws Exception {
    assertOutcome(
        "pack-mid-block",
        SMALL_HEAP_MIB,
        true,
        "PROVIDER PORTABLE",
        "FAILURE POLICY",
        "PRIMARY POLICY PROCESSING ordinal=0 id=io.resource-capacity"
            + " [archive=- entry=0 name=- field=- span=- artifact=mid.ba2]",
        "DIAGNOSTIC io.resource-capacity ERROR PACK PROCESSING"
            + " [archive=- entry=0 name=- field=- span=- artifact=mid.ba2] {}");
  }

  /**
   * Opening a raw-LZ4 General entry checks the decoded ceiling before any codec credit; a declared
   * size beyond every heap ceiling then fails the decoded-copy reservation at the same location.
   */
  // Native LZ4 is qualified and loaded only on the Windows x64 baseline.
  @EnabledOnOs(OS.WINDOWS)
  @Test
  void generalReadExhaustsLimitsAndCapacityUnderNative() throws Exception {
    assertOutcome("read-general-limit", READ_HEAP_MIB, false, readGeneralLimit("NATIVE"));
    assertOutcome("read-general-capacity", READ_HEAP_MIB, false, readGeneralCapacity("NATIVE"));
  }

  /** The portable provider reports the same General read outcomes. */
  @Test
  void generalReadExhaustsLimitsAndCapacityUnderPortable() throws Exception {
    assertOutcome("read-general-limit", READ_HEAP_MIB, true, readGeneralLimit("PORTABLE"));
    assertOutcome("read-general-capacity", READ_HEAP_MIB, true, readGeneralCapacity("PORTABLE"));
  }

  /** A raw-LZ4 DX10 texture counts its reconstructed DDS header toward the decoded ceiling. */
  // Native LZ4 is qualified and loaded only on the Windows x64 baseline.
  @EnabledOnOs(OS.WINDOWS)
  @Test
  void ddsReadExhaustsTheDecodedLimitUnderNative() throws Exception {
    assertOutcome("read-dds-limit", READ_HEAP_MIB, false, readDdsLimit("NATIVE"));
  }

  /** The portable provider reports the same DX10 read outcome. */
  @Test
  void ddsReadExhaustsTheDecodedLimitUnderPortable() throws Exception {
    assertOutcome("read-dds-limit", READ_HEAP_MIB, true, readDdsLimit("PORTABLE"));
  }

  /** Expected lines of the scratch breach; the budget's preflight context locates it. */
  private static String[] packScratch(String provider) {
    return new String[] {
      "PROVIDER " + provider,
      "FAILURE POLICY",
      "PRIMARY POLICY PREFLIGHT ordinal=- id=operation.resource-limit"
          + " [archive=scratch.ba2 entry=- name=- field=- span=- artifact=-]",
      "DIAGNOSTIC operation.resource-limit ERROR PACK PREFLIGHT"
          + " [archive=scratch.ba2 entry=- name=- field=- span=- artifact=-]"
          + " {ceiling=1000,field=maxScratchBytes,observed=1020}"
    };
  }

  /** Expected lines of the General decoded-ceiling breach, at the entry without its name. */
  private static String[] readGeneralLimit(String provider) {
    return new String[] {
      "PROVIDER " + provider,
      "FAILURE POLICY",
      "PRIMARY POLICY PROCESSING ordinal=0 id=operation.resource-limit"
          + " [archive=general.ba2 entry=0 name=- field=- span=- artifact=-]",
      "DIAGNOSTIC operation.resource-limit ERROR READ_CONTENT PROCESSING"
          + " [archive=general.ba2 entry=0 name=- field=- span=- artifact=-]"
          + " {ceiling=999,field=maxDecodedBytes,observed=1000}"
    };
  }

  /** Expected lines of the General decoded-copy capacity failure at open. */
  private static String[] readGeneralCapacity(String provider) {
    return new String[] {
      "PROVIDER " + provider,
      "FAILURE POLICY",
      "PRIMARY POLICY PROCESSING ordinal=0 id=io.resource-capacity"
          + " [archive=declared.ba2 entry=0 name=- field=- span=- artifact=-]",
      "DIAGNOSTIC io.resource-capacity ERROR READ_CONTENT PROCESSING"
          + " [archive=declared.ba2 entry=0 name=- field=- span=- artifact=-] {}"
    };
  }

  /**
   * Expected lines of the DX10 decoded-ceiling breach: 2048 payload bytes plus a 128-byte header.
   */
  private static String[] readDdsLimit(String provider) {
    return new String[] {
      "PROVIDER " + provider,
      "FAILURE POLICY",
      "PRIMARY POLICY PROCESSING ordinal=0 id=operation.resource-limit"
          + " [archive=dds.ba2 entry=0 name=- field=- span=- artifact=-]",
      "DIAGNOSTIC operation.resource-limit ERROR READ_CONTENT PROCESSING"
          + " [archive=dds.ba2 entry=0 name=- field=- span=- artifact=-]"
          + " {ceiling=2175,field=maxDecodedBytes,observed=2176}"
    };
  }

  /** Runs one scenario in a fresh JVM and requires exactly the expected output lines. */
  private void assertOutcome(String scenario, int heapMiB, boolean portable, String... expected)
      throws Exception {
    assertEquals(List.of(expected), launch(scenario, heapMiB, portable), scenario);
  }

  /**
   * Launches the probe with a fixed G1 heap, granting native access and pinning the portable
   * provider when asked. G1 reports the requested maximum heap exactly, which the probe checks.
   *
   * @return the probe's standard output lines
   */
  private List<String> launch(String scenario, int heapMiB, boolean portable) throws Exception {
    Path root = Files.createTempDirectory(temporary, scenario);
    String classpath =
        System.getProperty("java.class.path")
            + File.pathSeparator
            + System.getProperty("jdk.module.path", "");
    var command = new ArrayList<String>();
    command.add(
        Path.of(
                System.getProperty("java.home"),
                "bin",
                OS.WINDOWS.isCurrentOs() ? "java.exe" : "java")
            .toString());
    command.addAll(
        List.of("-XX:+UseG1GC", "-Xmx" + heapMiB + "m", "--enable-native-access=ALL-UNNAMED"));
    // A native path override is a native-configuration failure, so preflight pins portable.
    if (portable) command.add("-Dorg.lwjgl.librarypath=does-not-exist");
    command.addAll(
        List.of(
            "-cp",
            classpath,
            RawLz4ExhaustionProbe.class.getName(),
            scenario,
            root.toString(),
            Long.toString(Math.min(256L << 20, ((long) heapMiB << 20) / 4))));
    Path errors = Files.createTempFile(temporary, scenario, ".err");
    Process process = new ProcessBuilder(command).redirectError(errors.toFile()).start();
    try {
      String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(process.waitFor(120, TimeUnit.SECONDS), scenario + " probe timed out");
      assertEquals(0, process.exitValue(), output + Files.readString(errors));
      return output.lines().toList();
    } finally {
      process.destroyForcibly();
    }
  }
}
