package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("build-policy")
final class CompliancePolicyIT {
  private static final List<String> REQUIRED_POLICY_FILES =
      List.of(
          "LICENSE",
          "LICENSES/Apache-2.0.txt",
          "LICENSES/CC0-1.0.txt",
          "NOTICE",
          "RELEASE-NOTES.md",
          "THIRD-PARTY-NOTICES.md",
          "CONTRIBUTING.md",
          ".github/pull_request_template.md",
          "build/verify-external-contribution.ps1",
          "README.md",
          "REUSE.toml",
          "docs/reference-use.md",
          "tests/fixtures/synthetic/README.md",
          "compliance/dependency-inventory.json",
          "compliance/native-payload-inventory.json");

  /**
   * Verifies the repository policy-material set required by JBSA-LIC-001 through -003 and -010.
   *
   * @throws IOException if a required policy file cannot be read
   */
  @Test
  void repositoryCarriesTheRequiredLicenseAndReferenceUseMaterials() throws IOException {
    for (String relativePath : REQUIRED_POLICY_FILES) {
      assertTrue(
          Files.isRegularFile(reactorRoot().resolve(relativePath)),
          () -> "Missing compliance policy material: " + relativePath);
    }

    assertFileContains("LICENSE", "Apache License");
    assertFileContains("LICENSE", "Version 2.0, January 2004");
    assertFileContains("LICENSES/CC0-1.0.txt", "CC0 1.0 Universal");
    assertFileContains("CONTRIBUTING.md", "Signed-off-by:");
    assertFileContains("CONTRIBUTING.md", "source and fixture provenance");
    assertFileContains("CONTRIBUTING.md", "No contributor license agreement");
    assertFileContains("CONTRIBUTING.md", "Maintainer commits may omit sign-off");
    assertFileContains("docs/reference-use.md", "copy, mechanically translate, or preserve");
    assertFileContains("README.md", "fd1e36020b2b5b6217e553dc0038983146a2e2dd");
    assertFileContains("RELEASE-NOTES.md", "fd1e36020b2b5b6217e553dc0038983146a2e2dd");
    assertFileContains("NOTICE", "Copyright 2026 evildarkarchon");
    assertFileContains("REUSE.toml", "SPDX-License-Identifier");
    assertFileContains("tests/fixtures/synthetic/README.md", "CC0-1.0");
    assertFileContains(
        ".github/workflows/build.yml",
        "fsfe/reuse-action@676e2d560c9a403aa252096d99fcab3e1132b0f5");
    assertFileContains(".github/workflows/build.yml", "./build/verify-external-contribution.ps1");
    assertFileContains(
        ".github/pull_request_template.md",
        "[ ] I declare the source provenance used for this change.");
  }

  /**
   * Verifies the reusable compliance command accepts the checked-in inventories and repository.
   *
   * @throws Exception if the verifier process cannot run or does not finish within its deadline
   */
  @Test
  void repositoryComplianceAuditPasses() throws Exception {
    AuditResult result = runComplianceAudit(null);
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("Compliance verification passed"), result.output());
  }

  /**
   * Verifies the build emits the production-only SBOM and notice input required by JBSA-LIC-011.
   *
   * @throws IOException if a generated compliance artifact cannot be read
   */
  @Test
  void buildGeneratesSbomAndThirdPartyNotices() throws IOException {
    Path complianceOutput = reactorRoot().resolve("target/compliance");
    Path sbom = complianceOutput.resolve("jbsa.cdx.json");
    Path notices = complianceOutput.resolve("THIRD-PARTY-NOTICES.md");
    Path releaseNotes = complianceOutput.resolve("RELEASE-NOTES.md");

    assertTrue(Files.isRegularFile(sbom), () -> "Missing aggregate CycloneDX SBOM: " + sbom);
    String sbomText = Files.readString(sbom);
    assertTrue(sbomText.contains("\"bomFormat\" : \"CycloneDX\""), sbomText);
    assertTrue(sbomText.contains("\"specVersion\" : \"1.6\""), sbomText);
    assertTrue(sbomText.contains("\"name\" : \"jbsa\""), sbomText);
    assertTrue(sbomText.contains("\"name\" : \"jbsa-cli\""), sbomText);
    assertFalse(sbomText.contains("\"name\" : \"jbsa-test-support\""), sbomText);
    assertFalse(sbomText.contains("\"name\" : \"jbsa-conformance-tests\""), sbomText);
    assertFalse(sbomText.contains("\"name\" : \"jbsa-benchmarks\""), sbomText);
    assertFalse(sbomText.contains("\"name\" : \"jbsa-dist\""), sbomText);
    assertTrue(Files.isRegularFile(notices), () -> "Missing generated notices: " + notices);
    assertEquals(
        normalizeNewlines(Files.readString(reactorRoot().resolve("THIRD-PARTY-NOTICES.md"))),
        normalizeNewlines(Files.readString(notices)));
    assertTrue(Files.isRegularFile(releaseNotes), () -> "Missing release notices: " + releaseNotes);
    assertFileContains(
        "target/compliance/RELEASE-NOTES.md", "fd1e36020b2b5b6217e553dc0038983146a2e2dd");
  }

  /**
   * Verifies the final distribution module reruns the audit after its release inputs are assembled.
   *
   * @throws IOException if the distribution POM cannot be read
   */
  @Test
  void distributionModuleAuditsReleaseInputsAfterAssembly() throws IOException {
    String distributionPom = Files.readString(reactorRoot().resolve("jbsa-dist/pom.xml"));
    assertTrue(distributionPom.contains("<id>verify-assembled-release-inputs</id>"));
    assertTrue(distributionPom.contains("<phase>verify</phase>"));
    assertTrue(distributionPom.contains("build/verify-compliance.ps1"));
    assertTrue(distributionPom.contains("<argument>-RequireGeneratedArtifacts</argument>"));
  }

  /**
   * Verifies proprietary Bethesda Archive bytes cannot enter a release-input directory.
   *
   * @throws Exception if the verifier process or owned temporary directory cannot be managed
   */
  @Test
  void releaseInputAuditRejectsProprietaryArchiveMaterial() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-proprietary-release-input-");
    try {
      Files.writeString(releaseInputs.resolve("local-game.ba2"), "not a real archive");
      AuditResult result = runComplianceAudit(releaseInputs);
      assertFalse(result.exitCode() == 0, result.output());
      assertTrue(
          result.output().contains("Proprietary or local fixture material"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies native bytes absent from the approved inventory cannot enter release inputs.
   *
   * @throws Exception if the verifier process or owned temporary directory cannot be managed
   */
  @Test
  void releaseInputAuditRejectsUnapprovedNativePayloads() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-unapproved-native-input-");
    try {
      Files.writeString(releaseInputs.resolve("unknown.dll"), "not an approved native payload");
      AuditResult result = runComplianceAudit(releaseInputs);
      assertFalse(result.exitCode() == 0, result.output());
      assertTrue(result.output().contains("Unapproved native payload"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies every otherwise permissible release input still requires a checksummed manifest.
   *
   * @throws Exception if the verifier process or owned temporary directory cannot be managed
   */
  @Test
  void releaseInputAuditRejectsUnaccountedArtifacts() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-ambiguous-release-input-");
    try {
      writeZip(releaseInputs.resolve("unaccounted.jar"), "content.txt", "not a declared artifact");
      AuditResult result = runComplianceAudit(releaseInputs);
      assertFalse(result.exitCode() == 0, result.output());
      assertTrue(result.output().contains("Ambiguous release inputs"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies a JAR cannot hide a native payload from the release-input audit.
   *
   * @throws Exception if the verifier process or owned temporary archive cannot be managed
   */
  @Test
  void releaseInputAuditRejectsNativePayloadHiddenInJar() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-hidden-native-input-");
    try {
      writeZip(
          releaseInputs.resolve("candidate.jar"),
          "native/windows-x86_64/unknown.dll",
          "not an approved native payload");
      AuditResult result = runComplianceAudit(releaseInputs);
      assertFalse(result.exitCode() == 0, result.output());
      assertTrue(result.output().contains("Unapproved native payload"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies a ZIP cannot hide proprietary fixture material from the release-input audit.
   *
   * @throws Exception if the verifier process or owned temporary archive cannot be managed
   */
  @Test
  void releaseInputAuditRejectsProprietaryMaterialHiddenInZip() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-hidden-proprietary-input-");
    try {
      writeZip(releaseInputs.resolve("candidate.zip"), "fixtures/local/game.ba2", "protected");
      AuditResult result = runComplianceAudit(releaseInputs);
      assertFalse(result.exitCode() == 0, result.output());
      assertTrue(
          result.output().contains("Proprietary or local fixture material"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies an uninventoried transitive component in the generated SBOM fails the audit.
   *
   * @throws Exception if the synthetic SBOM or verifier process cannot be managed
   */
  @Test
  void sbomAuditRejectsUninventoriedTransitiveDependencies() throws Exception {
    Path sbom = Files.createTempFile("jbsa-uninventoried-transitive-", ".json");
    try {
      Files.writeString(
          sbom,
          """
          {
            "bomFormat": "CycloneDX",
            "specVersion": "1.6",
            "components": [
              {
                "group": "example.uninventoried",
                "name": "transitive-runtime",
                "version": "1.0.0",
                "purl": "pkg:maven/example.uninventoried/transitive-runtime@1.0.0?type=jar"
              }
            ]
          }
          """);
      AuditResult result = runComplianceAuditAgainstSbom(sbom);
      assertFalse(result.exitCode() == 0, result.output());
      assertTrue(result.output().contains("uninventoried external component"), result.output());
    } finally {
      Files.deleteIfExists(sbom);
    }
  }

  /**
   * Verifies an external pull request cannot pass with an unsigned contribution commit.
   *
   * @throws Exception if the contribution verifier or its event fixture cannot be managed
   */
  @Test
  void externalContributionAuditRejectsUnsignedCommits() throws Exception {
    AuditResult result =
        runExternalContributionAudit("External Contributor", "external@example.com");
    assertFalse(result.exitCode() == 0, result.output());
    assertTrue(result.output().contains("lacks a DCO Signed-off-by trailer"), result.output());
  }

  /**
   * Verifies the explicit JBSA-LIC-016 maintainer sign-off exemption remains available.
   *
   * @throws Exception if the contribution verifier or its event fixture cannot be managed
   */
  @Test
  void externalContributionAuditAllowsMaintainerCommits() throws Exception {
    AuditResult result = runExternalContributionAudit("evildarkarchon", "evildarkarchon@gmail.com");
    assertEquals(0, result.exitCode(), result.output());
    assertTrue(result.output().contains("maintainer commits may omit"), result.output());
  }

  /**
   * Runs the repository compliance verifier and captures its process-level result.
   *
   * @param releaseInputRoot optional release-input directory to audit, or {@code null} for the
   *     repository-only audit
   * @return the verifier exit status and merged standard output/error text
   * @throws Exception if the verifier process cannot start or does not finish within its deadline
   */
  private static AuditResult runComplianceAudit(Path releaseInputRoot) throws Exception {
    List<String> command =
        new java.util.ArrayList<>(
            List.of(
                "pwsh",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                reactorRoot().resolve("build/verify-compliance.ps1").toString()));
    if (releaseInputRoot != null) {
      command.add("-ReleaseInputRoot");
      command.add(releaseInputRoot.toString());
    }

    return runAuditProcess(command, reactorRoot(), Map.of(), "Compliance verifier");
  }

  /**
   * Runs the repository compliance verifier against a caller-owned synthetic SBOM.
   *
   * @param sbom exact synthetic CycloneDX JSON file to reconcile with the dependency inventory
   * @return the verifier exit status and merged standard output/error text
   * @throws Exception if the verifier process cannot start or does not finish within its deadline
   */
  private static AuditResult runComplianceAuditAgainstSbom(Path sbom) throws Exception {
    List<String> command =
        List.of(
            "pwsh",
            "-NoLogo",
            "-NoProfile",
            "-NonInteractive",
            "-File",
            reactorRoot().resolve("build/verify-compliance.ps1").toString(),
            "-RequireGeneratedArtifacts",
            "-GeneratedSbomPath",
            sbom.toString());
    return runAuditProcess(command, reactorRoot(), Map.of(), "Compliance verifier");
  }

  /**
   * Runs the PR contribution gate against an unsigned commit in an isolated repository.
   *
   * @param commitAuthorName author name for the unsigned contribution commit
   * @param commitAuthorEmail author email for the unsigned contribution commit
   * @return the contribution verifier exit status and merged output
   * @throws Exception if the isolated repository, event fixture, or verifier process cannot be
   *     managed
   */
  private static AuditResult runExternalContributionAudit(
      String commitAuthorName, String commitAuthorEmail) throws Exception {
    Path repository = Files.createTempDirectory("jbsa-contribution-repository-");
    try {
      runCommand(List.of("git", "init", "--quiet"), repository);
      Path contribution = repository.resolve("contribution.txt");
      Files.writeString(contribution, "base");
      runCommand(List.of("git", "add", "contribution.txt"), repository);
      runCommand(
          List.of(
              "git",
              "-c",
              "user.name=" + commitAuthorName,
              "-c",
              "user.email=" + commitAuthorEmail,
              "commit",
              "--quiet",
              "-m",
              "Base"),
          repository);
      String baseCommit = runCommand(List.of("git", "rev-parse", "HEAD"), repository);

      Files.writeString(contribution, "contribution");
      runCommand(List.of("git", "add", "contribution.txt"), repository);
      runCommand(
          List.of(
              "git",
              "-c",
              "user.name=" + commitAuthorName,
              "-c",
              "user.email=" + commitAuthorEmail,
              "commit",
              "--quiet",
              "-m",
              "Unsigned contribution"),
          repository);
      String headCommit = runCommand(List.of("git", "rev-parse", "HEAD"), repository);
      String eventJson =
          """
          {
            "pull_request": {
              "user": { "login": "external-opener" },
              "base": { "sha": "%s" },
              "head": { "sha": "%s" },
              "body": "- [x] I declare the source provenance used for this change.\\n- [x] I declare the fixture provenance or confirm that no fixtures are added."
            }
          }
          """
              .formatted(baseCommit, headCommit);
      Path eventPath = repository.resolve("event.json");
      Files.writeString(eventPath, eventJson);
      List<String> command =
          List.of(
              "pwsh",
              "-NoLogo",
              "-NoProfile",
              "-NonInteractive",
              "-File",
              reactorRoot().resolve("build/verify-external-contribution.ps1").toString());
      return runAuditProcess(
          command,
          repository,
          Map.of("GITHUB_EVENT_PATH", eventPath.toString()),
          "Contribution verifier");
    } finally {
      deleteTree(repository);
    }
  }

  /**
   * Runs a short local command in an explicit working directory.
   *
   * @param command executable and arguments to run
   * @param workingDirectory exact directory in which to start the process
   * @return trimmed merged standard output/error text from a successful command
   * @throws Exception if the command fails, cannot start, or exceeds its deadline
   */
  private static String runCommand(List<String> command, Path workingDirectory) throws Exception {
    AuditResult result = runAuditProcess(command, workingDirectory, Map.of(), command.toString());
    assertEquals(0, result.exitCode(), result.output());
    return result.output().trim();
  }

  /**
   * Runs a process with bounded execution and captures merged output without risking pipe blockage.
   *
   * @param command executable and arguments to run
   * @param workingDirectory exact process working directory
   * @param environment environment variables to add or replace
   * @param displayName stable process name used in timeout diagnostics
   * @return process exit status and merged standard output/error text
   * @throws Exception if the process or its owned output file cannot be started, read, or cleaned
   *     up
   */
  private static AuditResult runAuditProcess(
      List<String> command,
      Path workingDirectory,
      Map<String, String> environment,
      String displayName)
      throws Exception {
    Path outputPath = Files.createTempFile("jbsa-process-output-", ".log");
    try {
      ProcessBuilder builder =
          new ProcessBuilder(command)
              .directory(workingDirectory.toFile())
              .redirectErrorStream(true)
              .redirectOutput(outputPath.toFile());
      builder.environment().putAll(environment);
      Process process = builder.start();
      boolean finished = process.waitFor(30, TimeUnit.SECONDS);
      if (!finished) {
        process.destroyForcibly();
        process.waitFor(5, TimeUnit.SECONDS);
      }
      String output = Files.readString(outputPath, StandardCharsets.UTF_8);
      assertTrue(finished, () -> displayName + " timed out:\n" + output);
      return new AuditResult(process.exitValue(), output);
    } finally {
      Files.deleteIfExists(outputPath);
    }
  }

  /**
   * Asserts that one policy file includes a required, independently selected literal.
   *
   * @param relativePath policy-file path relative to the reactor root
   * @param expected literal text selected independently from the implementation
   * @throws IOException if the policy file cannot be read
   */
  private static void assertFileContains(String relativePath, String expected) throws IOException {
    String actual = normalizeNewlines(Files.readString(reactorRoot().resolve(relativePath)));
    assertTrue(actual.contains(expected), () -> relativePath + " is missing: " + expected);
  }

  /**
   * Normalizes platform line endings before comparing generated text artifacts.
   *
   * @param value text whose line endings may reflect the host platform
   * @return the same text with LF line endings
   */
  private static String normalizeNewlines(String value) {
    return value.replace("\r\n", "\n");
  }

  /**
   * Writes one small ZIP-compatible archive used to probe nested release-input inspection.
   *
   * @param archive exact JAR or ZIP path to create
   * @param entryName relative archive-entry name
   * @param content literal UTF-8 entry content
   * @throws IOException if the owned archive cannot be written
   */
  private static void writeZip(Path archive, String entryName, String content) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry(entryName));
      output.write(content.getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  /**
   * Deletes an owned temporary tree after a release-input rejection test.
   *
   * @param root exact temporary directory created and owned by the calling test
   * @throws IOException if an owned temporary file cannot be deleted
   */
  private static void deleteTree(Path root) throws IOException {
    try (var paths = Files.walk(root)) {
      for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
        if (Files.isRegularFile(path)) {
          // Git object files may be read-only on Windows; the test owns this entire temporary tree.
          Files.setAttribute(path, "dos:readonly", false);
        }
        Files.deleteIfExists(path);
      }
    }
  }

  /**
   * Returns the checked-out reactor root supplied by the Maven policy-test configuration.
   *
   * @return absolute or working-directory-relative reactor root supplied by Maven
   */
  private static Path reactorRoot() {
    return Path.of(System.getProperty("jbsa.reactor.root"));
  }

  /** Captures the observable exit status and merged output from the compliance command. */
  private record AuditResult(int exitCode, String output) {}
}
