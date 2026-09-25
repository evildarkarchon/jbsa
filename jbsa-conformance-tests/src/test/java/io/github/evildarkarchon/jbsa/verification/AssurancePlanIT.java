package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies the compact Assurance Plan through its public command-line boundary. */
@Tag("assurance-plan")
final class AssurancePlanIT {
  private static final ObjectMapper JSON = new ObjectMapper();

  /**
   * Ensures expansion admits only qualified capabilities and keeps performance work bounded.
   *
   * @throws Exception if the validator cannot execute or its output cannot be inspected
   */
  @Test
  void expandsQualifiedCapabilitiesAndRetainsKnownIncompleteVariants() throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    Path outputDirectory = Files.createTempDirectory(root.resolve("target"), "assurance-plan-");
    Path expanded = outputDirectory.resolve("expanded.json");
    Path log = outputDirectory.resolve("expand.log");

    Process process =
        new ProcessBuilder(
                "python",
                "build/assurance/plan.py",
                "expand",
                "tests/assurance/plan.json",
                "--output",
                expanded.toString())
            .directory(root.toFile())
            .redirectErrorStream(true)
            .redirectOutput(log.toFile())
            .start();
    process.getOutputStream().close();

    boolean finished = process.waitFor(30, TimeUnit.SECONDS);
    if (!finished) {
      process.destroyForcibly();
      process.waitFor(5, TimeUnit.SECONDS);
    }
    assertTrue(finished, () -> "Assurance Plan expansion timed out; see " + log);
    assertEquals(0, process.exitValue(), () -> "Assurance Plan expansion failed; see " + log);

    JsonNode document = JSON.readTree(expanded.toFile());
    Set<String> capabilities = new HashSet<>();
    document
        .path("assurance_scenarios")
        .forEach(scenario -> capabilities.add(scenario.path("capability_id").asText()));

    assertTrue(capabilities.contains("tes3"), capabilities::toString);
    assertTrue(capabilities.contains("bsa-067"), capabilities::toString);
    assertTrue(capabilities.contains("bsa-068"), capabilities::toString);
    assertTrue(capabilities.contains("bsa-069"), capabilities::toString);
    assertTrue(capabilities.contains("fo4-gnrl-v8"), capabilities::toString);
    assertTrue(capabilities.contains("fo4-dx10-v7"), capabilities::toString);
    assertTrue(capabilities.contains("fo4-dx10-v8"), capabilities::toString);
    assertFalse(
        document
            .path("incomplete_scenarios")
            .findValuesAsText("capability_id")
            .contains("bsa-069"));
    assertTrue(
        document
            .path("incomplete_scenarios")
            .findValuesAsText("capability_id")
            .contains("fo4-gnrl-v7"));
    assertTrue(document.path("performance_lanes").size() >= 20);
    assertTrue(document.path("performance_lanes").size() <= 30);
    assertTrue(document.path("traceability").isObject());
  }

  /**
   * Ensures the normative registry makes Assurance v2 active and preserves v1 only as history.
   *
   * @throws Exception if the requirement registry cannot be parsed
   */
  @Test
  void normativeRegistryActivatesAssuranceAndRetiresV1Requirements() throws Exception {
    Path root = Path.of(System.getProperty("jbsa.reactor.root"));
    var registry = RequirementRegistryYaml.parse(root.resolve("docs/spec/requirements.yaml"));
    Set<String> activeAssurance = new HashSet<>();
    int retiredV1 = 0;
    for (var requirement : registry.requirements()) {
      if (requirement.id().startsWith("JBSA-ASR-")
          && requirement.lifecycleState().equals("active")) {
        activeAssurance.add(requirement.id());
      }
      if (requirement.id().matches("JBSA-(CONF|PERF)-[0-9]{3}")) {
        assertEquals("retired", requirement.lifecycleState(), requirement.id());
        retiredV1++;
      }
    }
    assertEquals(
        Set.of(
            "JBSA-ASR-001",
            "JBSA-ASR-002",
            "JBSA-ASR-003",
            "JBSA-ASR-004",
            "JBSA-ASR-005",
            "JBSA-ASR-006",
            "JBSA-ASR-007",
            "JBSA-ASR-008"),
        activeAssurance);
    assertEquals(41, retiredV1);
  }
}
