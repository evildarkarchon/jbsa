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
  private static final String SETUP_JAVA_VERSION = "java-version: '25.0.4'";
  private static final String WINDOWS_JDK_SHA256 =
      "00c847d804f4a78e9f04f2683faf14fed898535b177b7fc704486cb0284e9283";
  private static final String LINUX_JDK_SHA256 =
      "dbb698396d478e7fa2b1e50f4103324b2a99b90569ee27c33f2261f9215cf41e";

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
    String setupAction =
        Files.readString(root.resolve(".github/actions/setup-qualified-jdk/action.yml"));
    String provisioner = Files.readString(root.resolve("build/provision-qualified-jdk.ps1"));

    for (String gate :
        java.util.List.of(
            "compile", "unit", "architecture", "formatting", "policy", "conformance")) {
      assertTrue(workflow.contains("          - " + gate), () -> "Missing stable CI gate " + gate);
    }
    assertEquals(
        1,
        count(setupAction, SETUP_JAVA_VERSION),
        "The shared action must use a SemVer label for the verified Temurin archive");
    assertEquals(3, count(workflow, "uses: ./.github/actions/setup-qualified-jdk"));
    assertEquals(2, count(workflow, "platform: windows-x64"));
    assertEquals(1, count(workflow, "platform: linux-x64"));
    assertEquals(1, count(setupAction, "distribution: jdkfile"));
    assertEquals(1, count(setupAction, "jdk-file: ${{ env.JBSA_JDK_ARCHIVE }}"));
    assertEquals(1, count(setupAction, "provision-qualified-jdk.ps1"));
    assertEquals(1, count(provisioner, WINDOWS_JDK_SHA256));
    assertEquals(1, count(provisioner, LINUX_JDK_SHA256));
    assertTrue(provisioner.contains("OpenJDK25U-jdk_x64_windows_hotspot_25.0.4.1_1.zip"));
    assertTrue(provisioner.contains("OpenJDK25U-jdk_x64_linux_hotspot_25.0.4.1_1.tar.gz"));
    assertTrue(provisioner.contains("releases/download/jdk-25.0.4.1%2B1"));
    assertTrue(provisioner.contains("Get-FileHash -Algorithm SHA256"));
    assertTrue(provisioner.contains("if ($actual -cne $distribution.Sha256)"));
    assertTrue(workflow.contains("run: .\\gradlew.bat clean verify --no-daemon"));
    assertTrue(workflow.contains("runs-on: windows-2025"));
    assertTrue(workflow.contains("runs-on: ubuntu-latest"));
    assertTrue(workflow.contains("./build/run-ci-gate.ps1 -Gate compile"));
    assertTrue(workflow.contains("./build/run-ci-gate.ps1 -Gate formatting"));
    assertTrue(workflow.contains("./build/run-ci-gate.ps1 -Gate unit"));
    assertTrue(workflow.contains("./gradlew -p build-logic test --no-daemon"));
    assertTrue(workflow.contains("./gradlew --no-daemon :jbsa:integrationTest"));

    assertTrue(setupAction.contains("cache: gradle"));
    assertFalse(setupAction.contains("cache: maven"));
    assertTrue(setupAction.contains("gradle/verification-metadata.xml"));
    assertTrue(setupAction.contains("gradle/wrapper/gradle-wrapper.properties"));
    assertFalse(workflow.contains("gradle/actions/setup-gradle"));
    assertFalse(workflow.contains("--scan"));
    assertFalse(workflow.toLowerCase(java.util.Locale.ROOT).contains("develocity"));
    assertFalse(workflow.toLowerCase(java.util.Locale.ROOT).contains("telemetry"));

    java.util.List<String> actionLines =
        java.util.stream.Stream.concat(workflow.lines(), setupAction.lines())
            .map(String::trim)
            .filter(line -> line.startsWith("uses: ") && !line.startsWith("uses: ./"))
            .toList();
    assertFalse(actionLines.isEmpty());
    actionLines.forEach(
        line -> assertTrue(line.matches("uses: [^@\\s]+@[0-9a-f]{40}(?:\\s+#.*)?"), line));

    int conformanceGate = workflow.indexOf("- name: Run ${{ matrix.gate }} gate");
    int conformanceEvidence = workflow.indexOf("- name: Retain Assurance v2 evidence");
    assertTrue(conformanceGate >= 0 && conformanceEvidence > conformanceGate);
    assertTrue(workflow.contains("if: always() && matrix.gate == 'conformance'"));
    assertTrue(workflow.contains("name: assurance-v2-hosted-evidence"));
    assertTrue(workflow.contains("name: assurance-v2-windows-authoritative-evidence"));
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
