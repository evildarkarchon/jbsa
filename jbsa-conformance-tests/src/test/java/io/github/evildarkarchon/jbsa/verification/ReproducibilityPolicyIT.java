package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Checks candidate identity across reproducibility and distribution staging workflows. */
@Tag("build-policy")
final class ReproducibilityPolicyIT {
  /**
   * Runs both revision-override and default-revision cases through the real script.
   *
   * @throws Exception if the regression process cannot run or its output cannot be read
   */
  @Test
  void rebuildsAndComparesTheRequestedRevision(@TempDir Path temporary) throws Exception {
    assertScriptPasses("test-reproducible-build.ps1", temporary);
  }

  /**
   * Verifies current-version staging and missing-artifact failure through the real script.
   *
   * @throws Exception if the regression process cannot run or its output cannot be read
   */
  @Test
  void stagesCurrentArtifactsAndRejectsMissingInputs(@TempDir Path temporary) throws Exception {
    assertScriptPasses("test-release-staging.ps1", temporary);
  }

  /**
   * Runs a bounded script process and includes captured output on failure.
   *
   * @throws Exception if process creation, waiting, or output reading fails
   */
  private static void assertScriptPasses(String script, Path temporary) throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path output = temporary.resolve("reproducibility.log");
    Process process =
        new ProcessBuilder(
                "pwsh", "-NoProfile", "-File", root.resolve("build").resolve(script).toString())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "Reproducibility regression timed out");
      assertEquals(
          0,
          process.exitValue(),
          () -> {
            try {
              return Files.readString(output);
            } catch (java.io.IOException exception) {
              return exception.toString();
            }
          });
    } finally {
      process.destroyForcibly();
    }
  }
}
