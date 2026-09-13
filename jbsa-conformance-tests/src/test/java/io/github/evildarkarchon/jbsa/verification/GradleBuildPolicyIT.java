package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/** Verifies Gradle-native policy against outputs from the real six-project build. */
@Tag("build-policy")
final class GradleBuildPolicyIT {
  private static final List<String> PROJECT_OUTPUT_ROOTS =
      List.of(
          "jbsa-benchmarks/target",
          "jbsa-cli/target",
          "jbsa-conformance-tests/target",
          "jbsa-dist/target",
          "jbsa-test-support/target",
          "jbsa/target");

  /**
   * Requires the generated layout to bind every real project and each canonical consumer artifact.
   *
   * @throws IOException if a generated manifest or artifact cannot be read
   */
  @Test
  void realMultiProjectBuildPublishesTheDeclaredArtifactLayout() throws IOException {
    Path root = reactorRoot();
    String layout = Files.readString(root.resolve("target/compliance/build-layout.json"));

    assertTrue(layout.contains("\"schemaVersion\": 1"));
    assertTrue(layout.contains("\"rootProject\": \"jbsa-parent\""));
    PROJECT_OUTPUT_ROOTS.forEach(
        outputRoot ->
            assertTrue(
                layout.contains("\"" + outputRoot + "\""),
                () -> "Missing real project output root " + outputRoot));
    assertDeclaredArtifact(layout, "library-binary", System.getProperty("jbsa.library.jar"), root);
    assertDeclaredArtifact(
        layout, "library-sources", System.getProperty("jbsa.library.sourcesJar"), root);
    assertDeclaredArtifact(
        layout, "library-javadoc", System.getProperty("jbsa.library.javadocJar"), root);
    assertDeclaredArtifact(
        layout, "library-consumer-pom", System.getProperty("jbsa.library.consumerPom"), root);
    assertDeclaredArtifact(layout, "cli-binary", System.getProperty("jbsa.cli.jar"), root);
  }

  /**
   * Requires production resolution to traverse the library, CLI, and staging project seams.
   *
   * @throws IOException if the generated dependency manifest cannot be read
   */
  @Test
  void realMultiProjectResolutionRetainsEachProductionConsumer() throws IOException {
    String dependencies =
        Files.readString(
            reactorRoot().resolve("target/compliance/resolved-production-dependencies.json"));

    assertTrue(dependencies.contains("\"schemaVersion\": 1"));
    for (String project : List.of(":jbsa", ":jbsa-cli", ":jbsa-dist")) {
      assertTrue(
          dependencies.contains("\"sourceProject\": \"" + project + "\""),
          () -> "Missing production resolution from " + project);
    }
    assertEquals(12, count(dependencies, "\"artifact\": {"));
    assertEquals(3, count(dependencies, "\"fileName\": \"lwjgl-3.4.3.jar\""));
    assertEquals(3, count(dependencies, "\"fileName\": \"lwjgl-lz4-3.4.3.jar\""));
    assertEquals(3, count(dependencies, "\"fileName\": \"lwjgl-3.4.3-natives-windows.jar\""));
    assertEquals(3, count(dependencies, "\"fileName\": \"lwjgl-lz4-3.4.3-natives-windows.jar\""));
  }

  /** Requires one generated output identity to resolve to the artifact supplied by Gradle. */
  private static void assertDeclaredArtifact(
      String layout, String identifier, String artifactProperty, Path root) {
    Path artifact = Path.of(artifactProperty).toAbsolutePath().normalize();
    assertTrue(Files.isRegularFile(artifact), () -> "Missing generated artifact " + artifact);
    String relative =
        root.toAbsolutePath().normalize().relativize(artifact).toString().replace('\\', '/');
    assertTrue(layout.contains("\"id\": \"" + identifier + "\""));
    assertTrue(layout.contains("\"path\": \"" + relative + "\""));
  }

  /** Counts non-overlapping occurrences of one deterministic JSON fragment. */
  private static int count(String text, String fragment) {
    int result = 0;
    for (int offset = text.indexOf(fragment);
        offset >= 0;
        offset = text.indexOf(fragment, offset + 1)) {
      result++;
    }
    return result;
  }

  /** Returns the repository root supplied by the Gradle test task. */
  private static Path reactorRoot() {
    return Path.of(System.getProperty("jbsa.reactor.root"));
  }
}
