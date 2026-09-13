package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/** Verifies the stable CI-gate launcher and hosted workflow at their public repository seams. */
@Tag("build-policy")
final class CiGatePolicyIT {
  private static final String PINNED_JAVA = "java-version: '25.0.4+7.0.LTS'";

  /**
   * Executes every stable gate against an isolated wrapper and checks failure propagation.
   *
   * @throws Exception if the regression process cannot run or its output cannot be read
   */
  @Test
  void launcherMapsEveryStableGateToItsExplicitGradleTasks(@TempDir Path temporary)
      throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path output = temporary.resolve("ci-gate.log");
    Process process =
        new ProcessBuilder(
                "pwsh",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                root.resolve("build/test-ci-gate.ps1").toString())
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    try {
      assertTrue(process.waitFor(60, TimeUnit.SECONDS), "CI gate regression timed out");
      assertEquals(0, process.exitValue(), () -> readFailure(output));
    } finally {
      process.destroyForcibly();
    }
  }

  /**
   * Verifies hosted Windows qualification, Linux portability, caching, and evidence policy.
   *
   * @throws Exception if the checked-in workflow cannot be read
   */
  @Test
  void hostedWorkflowSeparatesAuthoritativeWindowsAndPortableLinuxGates() throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    String workflow = Files.readString(root.resolve(".github/workflows/build.yml"));

    for (String gate :
        java.util.List.of(
            "compile", "unit", "architecture", "formatting", "policy", "conformance")) {
      assertTrue(workflow.contains("          - " + gate), () -> "Missing stable CI gate " + gate);
    }
    assertEquals(
        3, count(workflow, PINNED_JAVA), "Every build job must use the pinned Temurin JDK");
    assertTrue(workflow.contains("run: .\\gradlew.bat clean verify --no-daemon"));
    assertTrue(workflow.contains("runs-on: windows-2025"));
    assertTrue(workflow.contains("runs-on: ubuntu-latest"));
    assertTrue(workflow.contains("./build/run-ci-gate.ps1 -Gate compile"));
    assertTrue(workflow.contains("./build/run-ci-gate.ps1 -Gate formatting"));
    assertTrue(workflow.contains("./build/run-ci-gate.ps1 -Gate unit"));
    assertTrue(workflow.contains("./gradlew -p build-logic test --no-daemon"));
    assertTrue(workflow.contains("./gradlew --no-daemon :jbsa:integrationTest"));

    assertTrue(workflow.contains("cache: gradle"));
    assertFalse(workflow.contains("cache: maven"));
    assertTrue(workflow.contains("gradle/verification-metadata.xml"));
    assertTrue(workflow.contains("gradle/wrapper/gradle-wrapper.properties"));
    assertFalse(workflow.contains("gradle/actions/setup-gradle"));
    assertFalse(workflow.contains("--scan"));
    assertFalse(workflow.toLowerCase(java.util.Locale.ROOT).contains("develocity"));
    assertFalse(workflow.toLowerCase(java.util.Locale.ROOT).contains("telemetry"));

    java.util.List<String> actionLines =
        workflow.lines().map(String::trim).filter(line -> line.startsWith("uses: ")).toList();
    assertFalse(actionLines.isEmpty());
    actionLines.forEach(
        line -> assertTrue(line.matches("uses: [^@\\s]+@[0-9a-f]{40}(?:\\s+#.*)?"), line));

    int conformanceGate = workflow.indexOf("- name: Run ${{ matrix.gate }} gate");
    int conformanceEvidence = workflow.indexOf("- name: Retain per-case conformance evidence");
    assertTrue(conformanceGate >= 0 && conformanceEvidence > conformanceGate);
    assertTrue(workflow.contains("if: always() && matrix.gate == 'conformance'"));
    assertTrue(workflow.contains("name: conformance-v1-windows-authoritative-evidence"));
  }

  /** Counts exact non-overlapping occurrences of one workflow fragment. */
  private static int count(String value, String fragment) {
    int total = 0;
    for (int index = 0; (index = value.indexOf(fragment, index)) >= 0; index += fragment.length()) {
      total++;
    }
    return total;
  }

  /** Returns captured subprocess output while preserving the original assertion failure. */
  private static String readFailure(Path output) {
    try {
      return Files.readString(output);
    } catch (java.io.IOException exception) {
      return exception.toString();
    }
  }
}
