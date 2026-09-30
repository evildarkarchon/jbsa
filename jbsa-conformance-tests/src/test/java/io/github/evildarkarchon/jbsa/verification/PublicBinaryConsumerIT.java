package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledOnOs;
import org.junit.jupiter.api.condition.OS;
import org.junit.jupiter.api.io.TempDir;

/** Runs previously compiled caller bytecode against the current public library JAR. */
@Tag("contract")
@EnabledOnOs(OS.WINDOWS)
final class PublicBinaryConsumerIT {
  @TempDir Path directory;

  /**
   * Links and exercises the reviewed consumer without recompiling it against the candidate library.
   *
   * @throws Exception if the fixture, process, or public API behavior is unavailable
   */
  @Test
  void previouslyCompiledConsumerLinksAndRunsAgainstCurrentModule() throws Exception {
    URL resource = getClass().getResource("/api-stability/consumer.jar");
    assertNotNull(resource, "Missing reviewed binary consumer fixture");
    Path consumerJar = Path.of(resource.toURI());
    Path libraryJar = Path.of(System.getProperty("jbsa.library.jar"));
    // The library requires the portable LZ4 provider, which its consumer POM declares at compile
    // scope, so every module-path consumer carries it (JBSA-CODEC-014).
    Path lz4Java =
        Path.of(
            net.jpountz.lz4.LZ4Factory.class
                .getProtectionDomain()
                .getCodeSource()
                .getLocation()
                .toURI());
    Path output = directory.resolve("consumer.log");
    Process process =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                // The modular library uses native Windows path identity during publication.
                "--enable-native-access=io.github.evildarkarchon.jbsa",
                "--module-path",
                consumerJar + File.pathSeparator + libraryJar + File.pathSeparator + lz4Java,
                "--module",
                "consumer.api.stability/consumer.apistability.Main",
                directory.toString())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      process.getOutputStream().close();
      assertTrue(
          process.waitFor(90, TimeUnit.SECONDS), () -> "Binary consumer timed out: " + output);
      assertEquals(0, process.exitValue(), Files.readString(output));
    } finally {
      if (process.isAlive()) {
        // A failed assertion or timeout must not leave the external consumer running.
        process.descendants().forEach(ProcessHandle::destroyForcibly);
        process.destroyForcibly();
      }
    }
  }
}
