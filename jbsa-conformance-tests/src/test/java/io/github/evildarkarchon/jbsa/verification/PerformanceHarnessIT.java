package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/** Exercises small harness checks without executing a Performance Case or generating the corpus. */
@Tag("performance-harness")
final class PerformanceHarnessIT {
  /**
   * Checks the public corpus, result, and Windows instrumentation boundaries.
   *
   * @return deterministic harness checks; none supply performance acceptance evidence
   */
  @TestFactory
  Stream<DynamicTest> harnessCommands() {
    return Stream.of(
            List.of(
                "python",
                "-m",
                "unittest",
                "discover",
                "-s",
                "build/performance",
                "-p",
                "test_*.py"),
            List.of(
                "pwsh",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                "build/test-performance-memory.ps1"))
        .map(
            command -> DynamicTest.dynamicTest(String.join(" ", command), () -> runCheck(command)));
  }

  /**
   * Runs a bounded harness check with captured output and terminates descendants on timeout.
   *
   * @param command executable and distinct arguments; no shell interpretation
   * @throws Exception if startup, capture, or assertions fail
   */
  private static void runCheck(List<String> command) throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path log = Files.createTempFile(root.resolve("target"), "performance-harness-", ".log");
    Process process =
        new ProcessBuilder(command)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    // Closing the unused input avoids PowerShell waiting for redirected input at startup.
    process.getOutputStream().close();
    boolean finished = process.waitFor(180, TimeUnit.SECONDS);
    if (!finished) {
      // Children may retain capture handles after the parent exits, so stop them first.
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
    assertTrue(finished, () -> "Harness check timed out; see " + log);
    assertEquals(0, process.exitValue(), () -> "Harness check failed; see " + log);
  }
}
