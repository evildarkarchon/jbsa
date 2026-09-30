package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;

/** Fresh JVMs isolate process-lifetime native loading and Java 25 host policy. */
// Native LZ4 is qualified and loaded only on the Windows x64 baseline.
@EnabledOnOs(OS.WINDOWS)
final class Lz4LaunchTest {
  /**
   * Missing grants and missing native artifacts leave zlib available, fail native admission
   * deterministically, and pin the portable provider instead (JBSA-CODEC-015). The probe also fails
   * if lz4-java ever loads its JNI library.
   */
  @Test
  void qualifiesClasspathLaunchPolicyAndLazyMissingArtifacts() throws Exception {
    assertEquals("LZ4_OK NATIVE", launch(true, true, List.of(), "lz4"));
    assertEquals("CAPABILITY:native-access PORTABLE", launch(false, true, List.of(), "lz4"));
    assertEquals("CAPABILITY:provider-unavailable PORTABLE", launch(true, false, List.of(), "lz4"));
    assertEquals("ZLIB_OK", launch(false, false, List.of(), "zlib-only"));
    assertEquals(
        "CAPABILITY:provider-unavailable PORTABLE", launch(true, false, List.of(), "no-provider"));
    assertEquals(
        "CAPABILITY:native-configuration PORTABLE",
        launch(true, true, List.of("-Dorg.lwjgl.librarypath=does-not-exist"), "lz4"));
    assertEquals(
        "CAPABILITY:platform PORTABLE", launch(true, true, List.of("-Dos.arch=aarch64"), "lz4"));
  }

  /** LZ4 fails as a capability only when the native adapter and lz4-java are both missing. */
  @Test
  void reportsUnavailableOnlyWithoutEitherProvider() throws Exception {
    assertEquals(
        "CAPABILITY:provider-unavailable UNAVAILABLE",
        launch(true, false, List.of(), "no-lz4-java"));
  }

  /** Resolves the same resource-module roots as the staged launcher and executes real preflight. */
  @Test
  void qualifiesNamedModuleLaunch() throws Exception {
    assertEquals("LZ4_OK NATIVE", launch(true, true, List.of(), "module"));
  }

  /**
   * Builds a classpath from resolved reactor artifacts; the child cannot reuse parent's loaded
   * DLLs.
   */
  private static String launch(boolean grant, boolean natives, List<String> extra, String mode)
      throws Exception {
    String paths =
        System.getProperty("java.class.path")
            + File.pathSeparator
            + System.getProperty("jdk.module.path", "");
    String classpath =
        String.join(
            File.pathSeparator,
            Arrays.stream(paths.split(File.pathSeparator))
                .filter(path -> natives || !path.contains("natives-windows"))
                .filter(path -> !mode.equals("no-provider") || !path.contains("lwjgl"))
                .filter(path -> !mode.equals("no-lz4-java") || !path.contains("lz4-java"))
                .toList());
    var command = new ArrayList<String>();
    command.add(Path.of(System.getProperty("java.home"), "bin", "java.exe").toString());
    command.add("--illegal-native-access=deny");
    if (grant)
      command.add(
          mode.equals("module")
              ? "--enable-native-access=io.github.evildarkarchon.jbsa,org.lwjgl,org.lwjgl.lz4"
              : "--enable-native-access=ALL-UNNAMED");
    command.addAll(extra);
    if (mode.equals("module")) {
      String tests =
          Path.of(Lz4LaunchTest.class.getProtectionDomain().getCodeSource().getLocation().toURI())
              .toString();
      command.addAll(
          List.of(
              "--module-path",
              classpath,
              "--add-modules",
              "org.lwjgl.natives,org.lwjgl.lz4.natives",
              "--patch-module",
              "io.github.evildarkarchon.jbsa=" + tests,
              "--module",
              "io.github.evildarkarchon.jbsa/" + Lz4LaunchProbe.class.getName(),
              mode));
    } else command.addAll(List.of("-cp", classpath, Lz4LaunchProbe.class.getName(), mode));
    Process process =
        new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start();
    try {
      assertTrue(process.waitFor(30, TimeUnit.SECONDS), "native launch timed out");
      String output =
          new String(
                  process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8)
              .trim();
      assertEquals(0, process.exitValue(), output);
      return output;
    } finally {
      process.destroyForcibly();
    }
  }
}
