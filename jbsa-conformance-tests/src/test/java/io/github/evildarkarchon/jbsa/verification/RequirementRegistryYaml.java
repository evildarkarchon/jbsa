package io.github.evildarkarchon.jbsa.verification;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.yaml.snakeyaml.LoaderOptions;

/** Parses the normative requirement registry into a strict, typed test model. */
final class RequirementRegistryYaml {
  private static final YAMLMapper MAPPER = createMapper();
  private static final Set<String> LIFECYCLE_STATES = Set.of("active", "retired");
  private static final Set<String> VERIFICATION_CLASSES =
      Set.of(
          "document-review",
          "automated-test",
          "build-verification",
          "conformance-case",
          "performance-case",
          "release-qualification",
          "release-audit");

  private RequirementRegistryYaml() {}

  /**
   * Parses one registry document without depending on its presentation or indentation.
   *
   * @throws IOException when the document is malformed or differs from the supported schema
   */
  static Registry parse(String yaml) throws IOException {
    Registry registry = MAPPER.readValue(yaml, Registry.class);
    validate(registry);
    return registry;
  }

  /**
   * Parses one UTF-8 registry file without depending on its presentation or indentation.
   *
   * @throws IOException when the file cannot be read or differs from the supported schema
   */
  static Registry parse(Path path) throws IOException {
    return parse(Files.readString(path));
  }

  /** Creates a fail-closed mapper with bounded input, nesting, aliases, and duplicate keys. */
  private static YAMLMapper createMapper() {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setAllowRecursiveKeys(false);
    options.setMaxAliasesForCollections(0);
    options.setCodePointLimit(1_000_000);
    options.setNestingDepthLimit(50);
    YAMLMapper mapper = new YAMLMapper(YAMLFactory.builder().loaderOptions(options).build());
    mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper.enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES);
    mapper.enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    return mapper;
  }

  /** Validates required fields and cross-field identities that YAML syntax alone cannot express. */
  private static void validate(Registry registry) throws IOException {
    requireRegistry(registry.schemaVersion() == 2, "Unsupported requirement registry schema.");
    requireRegistry(registry.specification() != null, "Missing specification identity.");
    requireText(registry.specification().version(), "specification version");
    requireRegistry(
        "normative".equals(registry.specification().status()),
        "Specification status must be normative.");
    requireText(registry.specification().authority(), "specification authority");
    requireRegistry(
        registry.requirements() != null && !registry.requirements().isEmpty(),
        "The registry must contain requirements.");

    Set<String> identifiers = new HashSet<>();
    for (Requirement requirement : registry.requirements()) {
      requireRegistry(requirement != null, "Requirement entries must not be null.");
      requireRegistry(
          requirement.id() != null && requirement.id().matches("JBSA-[A-Z0-9]+-[0-9]{3}"),
          "Invalid requirement identifier.");
      requireRegistry(
          identifiers.add(requirement.id()), "Duplicate requirement " + requirement.id());
      requireRegistry(requirement.owner() != null, "Missing owner for " + requirement.id());
      requireText(requirement.owner().document(), "owner document for " + requirement.id());
      requireRegistry(
          requirement.id().toLowerCase(java.util.Locale.ROOT).equals(requirement.owner().anchor()),
          "Owner anchor does not match " + requirement.id());
      requireRegistry(
          requirement.sourceDecisions() != null && !requirement.sourceDecisions().isEmpty(),
          "Missing source decisions for " + requirement.id());
      requireRegistry(
          LIFECYCLE_STATES.contains(requirement.lifecycleState()),
          "Invalid lifecycle state for " + requirement.id());
      requireRegistry(
          VERIFICATION_CLASSES.contains(requirement.verificationClass()),
          "Invalid verification class for " + requirement.id());
      requireRegistry(
          requirement.implementationTickets() != null
              && !requirement.implementationTickets().isEmpty(),
          "Missing implementation tickets for " + requirement.id());
      requireRegistry(
          requirement.testEvidence() != null, "Missing test evidence for " + requirement.id());
      if (requirement.lifecycleState().equals("retired")) {
        requireRegistry(
            requirement.retirement() != null, "Missing retirement for " + requirement.id());
        requireText(requirement.retirement().ticket(), "retirement ticket for " + requirement.id());
        requireText(requirement.retirement().reason(), "retirement reason for " + requirement.id());
      } else {
        requireRegistry(
            requirement.retirement() == null, "Active requirement has retirement metadata.");
      }
    }
  }

  /** Requires one registry string field to contain non-whitespace text. */
  private static void requireText(String value, String field) throws IOException {
    requireRegistry(value != null && !value.isBlank(), "Missing " + field + ".");
  }

  /** Raises a checked parse failure for an invalid registry semantic. */
  private static void requireRegistry(boolean condition, String message) throws IOException {
    if (!condition) {
      throw new IOException(message);
    }
  }

  record Registry(
      @JsonProperty("schema_version") int schemaVersion,
      Specification specification,
      List<Requirement> requirements) {}

  record Specification(String version, String status, String authority) {}

  record Requirement(
      String id,
      Owner owner,
      @JsonProperty("source_decisions") List<String> sourceDecisions,
      @JsonProperty("lifecycle_state") String lifecycleState,
      @JsonProperty("verification_class") String verificationClass,
      @JsonProperty("implementation_tickets") List<String> implementationTickets,
      @JsonProperty("test_evidence") List<String> testEvidence,
      Retirement retirement) {}

  record Owner(String document, String anchor) {}

  record Retirement(String ticket, String reason) {}
}
