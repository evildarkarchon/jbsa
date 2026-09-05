package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.TestFactory;

/** Tests the build-only harness through its public PowerShell commands. */
@Tag("conformance-harness")
final class ConformanceHarnessIT {
  /**
   * Exercises real command boundaries, including tampered evidence and incomplete product cases.
   *
   * @return independently reported harness checks, without claiming product conformance
   */
  @TestFactory
  Stream<DynamicTest> publicHarnessCommands() {
    return Stream.of(
            "tests/conformance/test-catalog.ps1",
            "tests/conformance/test-rebaseline.ps1",
            "build/test-conformance-adapters.ps1",
            "build/test-conformance-evidence.ps1",
            "build/test-conformance-execution.ps1",
            "build/test-conformance-runner.ps1")
        .map(script -> DynamicTest.dynamicTest(script, () -> runCheck(script)));
  }

  /**
   * Runs a bounded check with redirected output, killing descendants on timeout before returning.
   *
   * @param script repository-relative public verification command
   * @throws Exception if process startup, capture, or a command assertion fails
   */
  private static void runCheck(String script) throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path output = Files.createTempFile(root.resolve("target"), "conformance-check-", ".log");
    Process process =
        new ProcessBuilder("pwsh", "-NoLogo", "-NoProfile", "-NonInteractive", "-File", script)
            .directory(root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    // PowerShell can wait before script startup when a redirected input pipe remains open.
    process.getOutputStream().close();
    boolean finished = process.waitFor(120, TimeUnit.SECONDS);
    if (!finished) {
      // Descendant tools can retain handles after the script is killed; terminate them first.
      process.descendants().forEach(ProcessHandle::destroyForcibly);
      process.destroyForcibly();
      process.waitFor(10, TimeUnit.SECONDS);
    }
    assertTrue(finished, () -> script + " timed out; see " + output);
    assertEquals(0, process.exitValue(), () -> script + " failed; see " + output);
  }
}
