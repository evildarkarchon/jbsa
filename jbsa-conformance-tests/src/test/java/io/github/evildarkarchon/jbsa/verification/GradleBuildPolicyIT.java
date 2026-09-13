package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
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

  /**
   * Requires requirement ownership to use authoritative local-ticket paths rather than retired
   * GitHub issue numbers.
   *
   * @throws IOException if the registry or a referenced local ticket cannot be read
   */
  @Test
  void requirementRegistryUsesExistingLocalImplementationTickets() throws IOException {
    Path root = reactorRoot();
    var registry = RequirementRegistryYaml.parse(root.resolve("docs/spec/requirements.yaml"));
    assertEquals(2, registry.schemaVersion());
    assertFalse(registry.requirements().isEmpty());

    var identifiers = new java.util.HashSet<String>();
    for (var requirement : registry.requirements()) {
      assertTrue(
          identifiers.add(requirement.id()), () -> "Duplicate requirement " + requirement.id());
      assertTrue(
          requirement.implementationTickets() != null
              && !requirement.implementationTickets().isEmpty(),
          () -> "Missing implementation ownership for " + requirement.id());
      requirement.implementationTickets().forEach(ticket -> assertLocalTicket(root, ticket));
      if (requirement.lifecycleState().equals("retired")) {
        assertTrue(
            requirement.retirement() != null, () -> "Missing retirement for " + requirement.id());
        assertLocalTicket(root, requirement.retirement().ticket());
      }
    }

    String migrationTicket =
        ".scratch/migrate-maven-to-gradle/issues/11-migrate-active-instructions.md";
    long migratedBuildRequirements =
        registry.requirements().stream()
            .filter(
                requirement ->
                    requirement.id().matches("JBSA-BUILD-00[1-7]|JBSA-BUILD-010")
                        && requirement.implementationTickets().contains(migrationTicket))
            .count();
    assertEquals(
        8L,
        migratedBuildRequirements,
        "Gradle build requirements must retain their current local migration ownership");
  }

  /** Verifies semantically valid YAML does not depend on the repository's preferred indentation. */
  @Test
  void requirementRegistryParserAcceptsEquivalentYamlFormatting() throws IOException {
    var registry =
        RequirementRegistryYaml.parse(
            """
            schema_version: 2
            specification: {version: 1.0.0, status: normative, authority: docs/spec/README.md}
            requirements:
             - id: JBSA-BUILD-001
               owner: {document: docs/spec/modules-and-build.md, anchor: jbsa-build-001}
               source_decisions: [.scratch/decisions/01-build.md]
               lifecycle_state: active
               verification_class: build-verification
               implementation_tickets: [.scratch/build/issues/01-foundation.md]
               test_evidence: []
            """);

    assertEquals(2, registry.schemaVersion());
    assertEquals("1.0.0", registry.specification().version());
    assertEquals(
        List.of(".scratch/build/issues/01-foundation.md"),
        registry.requirements().getFirst().implementationTickets());
  }

  /** Verifies malformed, incomplete, duplicate, and semantically invalid registries fail closed. */
  @Test
  void requirementRegistryParserRejectsInvalidDocuments() {
    String valid = validRegistryYaml();
    List<String> invalidDocuments =
        List.of(
            valid.replace(
                "   owner: {document: docs/spec/modules-and-build.md, anchor: jbsa-build-001}\n",
                ""),
            valid.replace("anchor: jbsa-build-001", "anchor: wrong-anchor"),
            valid.replace("authority: docs/spec/README.md", "authority: null"),
            valid.replace(
                "source_decisions: [.scratch/decisions/01-build.md]", "source_decisions: null"),
            valid.replace(
                "implementation_tickets: [.scratch/build/issues/01-foundation.md]",
                "implementation_tickets: null"),
            valid.replace("   test_evidence: []\n", ""),
            valid.replace("lifecycle_state: active", "lifecycle_state: unknown"),
            valid.replace("verification_class: build-verification", "verification_class: unknown"),
            valid.replace(
                "owner: {document: docs/spec/modules-and-build.md, anchor: jbsa-build-001}",
                "owner: {document: docs/spec/modules-and-build.md, document: duplicate.md, anchor: jbsa-build-001}"),
            valid.replace("test_evidence: []", "unknown_field: true\n   test_evidence: []"),
            valid + "---\n{}\n",
            "schema_version: [unterminated");

    invalidDocuments.forEach(
        yaml -> assertThrows(IOException.class, () -> RequirementRegistryYaml.parse(yaml)));
  }

  /** Returns one hand-authored valid registry using legal noncanonical YAML presentation. */
  private static String validRegistryYaml() {
    return """
        schema_version: 2
        specification: {version: 1.0.0, status: normative, authority: docs/spec/README.md}
        requirements:
         - id: JBSA-BUILD-001
           owner: {document: docs/spec/modules-and-build.md, anchor: jbsa-build-001}
           source_decisions: [.scratch/decisions/01-build.md]
           lifecycle_state: active
           verification_class: build-verification
           implementation_tickets: [.scratch/build/issues/01-foundation.md]
           test_evidence: []
        """;
  }

  /** Requires one registry ticket path to remain relative, contained by .scratch, and present. */
  private static void assertLocalTicket(Path root, String ticket) {
    Path relative = Path.of(ticket);
    Path scratch = root.resolve(".scratch").toAbsolutePath().normalize();
    Path resolved = root.resolve(relative).toAbsolutePath().normalize();
    assertFalse(relative.isAbsolute(), () -> "Local ticket path must be relative: " + ticket);
    assertTrue(resolved.startsWith(scratch), () -> "Local ticket path escapes .scratch: " + ticket);
    assertTrue(
        Files.isRegularFile(resolved), () -> "Missing local implementation ticket " + ticket);
  }

  /**
   * Requires one generated output identity to resolve to the artifact supplied by Gradle.
   *
   * @param layout generated build-layout JSON text
   * @param identifier output identity expected in the manifest
   * @param artifactPathValue absolute artifact path supplied to the black-box test
   * @param root repository root used to normalize the artifact path
   */
  private static void assertDeclaredArtifact(
      String layout, String identifier, String artifactPathValue, Path root) {
    Path artifact = Path.of(artifactPathValue).toAbsolutePath().normalize();
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
