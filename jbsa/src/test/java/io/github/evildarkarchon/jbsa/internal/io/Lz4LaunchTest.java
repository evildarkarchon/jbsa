package io.github.evildarkarchon.jbsa.internal.io;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/** Fresh JVMs isolate process-lifetime native loading and Java 25 host policy. */
final class Lz4LaunchTest {
  /**
   * Missing grants and missing native artifacts leave zlib available and fail LZ4
   * deterministically.
   */
  @Test
  void qualifiesClasspathLaunchPolicyAndLazyMissingArtifacts() throws Exception {
    assertEquals("LZ4_OK", launch(true, true, List.of(), "lz4"));
    assertEquals("CAPABILITY:native-access", launch(false, true, List.of(), "lz4"));
    assertEquals("CAPABILITY:provider-unavailable", launch(true, false, List.of(), "lz4"));
    assertEquals("ZLIB_OK", launch(false, false, List.of(), "zlib-only"));
    assertEquals("CAPABILITY:provider-unavailable", launch(true, false, List.of(), "no-provider"));
    assertEquals(
        "CAPABILITY:native-configuration",
        launch(true, true, List.of("-Dorg.lwjgl.librarypath=does-not-exist"), "lz4"));
    assertEquals("CAPABILITY:platform", launch(true, true, List.of("-Dos.arch=aarch64"), "lz4"));
  }

  /** Resolves the same resource-module roots as the staged launcher and executes real preflight. */
  @Test
  void qualifiesNamedModuleLaunch() throws Exception {
    assertEquals("LZ4_OK", launch(true, true, List.of(), "module"));
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
