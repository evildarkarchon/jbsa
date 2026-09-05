package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Comparator;
import java.util.HexFormat;
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
          "README.md",
          "REUSE.toml",
          "docs/reference-use.md",
          "tests/fixtures/synthetic/README.md",
          "compliance/dependency-inventory.json",
          "compliance/native-payload-inventory.json");

  /**
   * Runs the repository compliance verifier and captures its process-level result.
   *
   * @param releaseInputRoot optional release-input directory to audit, or {@code null} for the
   *     repository-only audit
   * @return the verifier exit status and merged standard output/error text
   * @throws Exception if the verifier process cannot start or does not finish within its deadline
   */
  private static AuditResult runComplianceAudit(Path releaseInputRoot) throws Exception {
    return runComplianceAudit(releaseInputRoot, null);
  }

  /**
   * Runs the repository compliance verifier with an optional release-input manifest.
   *
   * @param releaseInputRoot optional release-input directory to audit, or {@code null} for the
   *     repository-only audit
   * @param releaseInputManifest optional manifest accounting for every release input
   * @return the verifier exit status and merged standard output/error text
   * @throws Exception if the verifier process cannot start or does not finish within its deadline
   */
  private static AuditResult runComplianceAudit(Path releaseInputRoot, Path releaseInputManifest)
      throws Exception {
    List<String> command =
        new java.util.ArrayList<>(
            List.of(
                "pwsh",
                "-NoLogo",
                "-NoProfile",
                "-NonInteractive",
                "-File",
                reactorRoot().resolve("build/verify-compliance.ps1").toString(),
                "-ReactorVersion",
                System.getProperty("jbsa.version")));
    if (releaseInputRoot != null) {
      command.add("-ReleaseInputRoot");
      command.add(releaseInputRoot.toString());
    }
    if (releaseInputManifest != null) {
      command.add("-ReleaseInputManifest");
      command.add(releaseInputManifest.toString());
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
            "-ReactorVersion",
            System.getProperty("jbsa.version"),
            "-RequireGeneratedArtifacts",
            "-GeneratedSbomPath",
            sbom.toString());
    return runAuditProcess(command, reactorRoot(), Map.of(), "Compliance verifier");
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
    writeZip(archive, entryName, content.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Writes one small ZIP-compatible archive entry with exact caller-supplied bytes.
   *
   * @param archive exact JAR or ZIP path to create
   * @param entryName relative archive-entry name
   * @param content exact entry content
   * @throws IOException if the owned archive cannot be written
   */
  private static void writeZip(Path archive, String entryName, byte[] content) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry(entryName));
      output.write(content);
      output.closeEntry();
    }
  }

  /**
   * Writes a ZIP whose single entry carries the Unix symbolic-link file type in external metadata.
   *
   * @param archive exact ZIP path to create
   * @param entryName relative symbolic-link entry name
   * @param target literal link-target text stored as the entry payload
   * @throws IOException if the owned archive cannot be written or lacks a central-directory header
   */
  private static void writeUnixSymbolicLinkZip(Path archive, String entryName, String target)
      throws IOException {
    writeZip(archive, entryName, target);
    byte[] bytes = Files.readAllBytes(archive);
    int centralHeader = findCentralDirectoryHeader(bytes);
    if (centralHeader < 0) {
      throw new IOException("Synthetic ZIP has no central-directory header: " + archive);
    }

    // ZIP stores the Unix st_mode bits in the upper half of this central-directory field.
    bytes[centralHeader + 5] = 3;
    int externalAttributes = Integer.parseInt("120777", 8) << 16;
    for (int index = 0; index < Integer.BYTES; index++) {
      bytes[centralHeader + 38 + index] = (byte) (externalAttributes >>> (Byte.SIZE * index));
    }
    Files.write(archive, bytes);
  }

  /**
   * Writes a valid ZIP with a non-native preamble before its local file data.
   *
   * @param archive exact ZIP-compatible path to create
   * @param entryName relative archive-entry name
   * @param content exact entry content
   * @throws IOException if the owned archive cannot be written or patched
   */
  private static void writePrefixedZip(Path archive, String entryName, byte[] content)
      throws IOException {
    writeZip(archive, entryName, content);
    byte[] zipBytes = Files.readAllBytes(archive);
    int centralHeader = findZipHeader(zipBytes, 0x02014b50);
    int endHeader = findZipHeader(zipBytes, 0x06054b50);
    if (centralHeader < 0 || endHeader < 0) {
      throw new IOException("Synthetic ZIP is missing required headers: " + archive);
    }

    int prefixLength = 16;
    // Prefixed ZIPs must shift the central local-header pointer and the EOCD directory pointer.
    addLittleEndianInt(zipBytes, centralHeader + 42, prefixLength);
    addLittleEndianInt(zipBytes, endHeader + 16, prefixLength);
    byte[] prefixedBytes = new byte[prefixLength + zipBytes.length];
    System.arraycopy(zipBytes, 0, prefixedBytes, prefixLength, zipBytes.length);
    Files.write(archive, prefixedBytes);
  }

  /**
   * Writes a ZIP with a directory and file name that alias under Windows comparison.
   *
   * @param archive exact ZIP path to create
   * @throws IOException if the owned archive cannot be written
   */
  private static void writeDirectoryFileAliasZip(Path archive) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry("payload/"));
      output.closeEntry();
      output.putNextEntry(new ZipEntry("PAYLOAD"));
      output.write("file".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  /**
   * Writes a ZIP containing extraction-equivalent ordinary and dot-segment entry names.
   *
   * @param archive exact ZIP path to create
   * @throws IOException if the owned archive cannot be written
   */
  private static void writeDotSegmentAliasZip(Path archive) throws IOException {
    try (ZipOutputStream output = new ZipOutputStream(Files.newOutputStream(archive))) {
      output.putNextEntry(new ZipEntry("a/b"));
      output.write("first".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
      output.putNextEntry(new ZipEntry("a/./b"));
      output.write("second".getBytes(StandardCharsets.UTF_8));
      output.closeEntry();
    }
  }

  /**
   * Writes a tiny ZIP whose metadata declares an entry larger than the inspection limit.
   *
   * @param archive exact ZIP path to create
   * @param declaredSize synthetic uncompressed entry size stored in ZIP metadata
   * @throws IOException if the owned archive cannot be written or patched
   */
  private static void writeZipWithDeclaredEntrySize(Path archive, int declaredSize)
      throws IOException {
    writeZip(archive, "oversized.bin", "tiny");
    byte[] bytes = Files.readAllBytes(archive);
    int localHeader = findZipHeader(bytes, 0x04034b50);
    int centralHeader = findCentralDirectoryHeader(bytes);
    if (localHeader < 0 || centralHeader < 0) {
      throw new IOException("Synthetic ZIP is missing required headers: " + archive);
    }

    // Both headers carry the uncompressed size; ZipArchive trusts the central-directory value.
    writeLittleEndianInt(bytes, localHeader + 22, declaredSize);
    writeLittleEndianInt(bytes, centralHeader + 24, declaredSize);
    Files.write(archive, bytes);
  }

  /**
   * Finds the first ZIP central-directory header in a single-entry synthetic archive.
   *
   * @param bytes complete ZIP bytes
   * @return header offset, or {@code -1} when the signature is absent
   */
  private static int findCentralDirectoryHeader(byte[] bytes) {
    return findZipHeader(bytes, 0x02014b50);
  }

  /**
   * Finds the first little-endian ZIP header signature in a synthetic archive.
   *
   * @param bytes complete ZIP bytes
   * @param signature ZIP header signature in host integer order
   * @return header offset, or {@code -1} when the signature is absent
   */
  private static int findZipHeader(byte[] bytes, int signature) {
    for (int index = 0; index <= bytes.length - 4; index++) {
      if ((bytes[index] & 0xff) == (signature & 0xff)
          && (bytes[index + 1] & 0xff) == ((signature >>> 8) & 0xff)
          && (bytes[index + 2] & 0xff) == ((signature >>> 16) & 0xff)
          && (bytes[index + 3] & 0xff) == ((signature >>> 24) & 0xff)) {
        return index;
      }
    }
    return -1;
  }

  /**
   * Adds a delta to one little-endian 32-bit ZIP field.
   *
   * @param bytes complete mutable ZIP bytes
   * @param offset first byte of the field
   * @param delta value to add
   */
  private static void addLittleEndianInt(byte[] bytes, int offset, int delta) {
    int value =
        (bytes[offset] & 0xff)
            | ((bytes[offset + 1] & 0xff) << 8)
            | ((bytes[offset + 2] & 0xff) << 16)
            | ((bytes[offset + 3] & 0xff) << 24);
    writeLittleEndianInt(bytes, offset, value + delta);
  }

  /**
   * Writes one little-endian 32-bit ZIP field.
   *
   * @param bytes complete mutable ZIP bytes
   * @param offset first byte of the field
   * @param value exact field value
   */
  private static void writeLittleEndianInt(byte[] bytes, int offset, int value) {
    for (int index = 0; index < Integer.BYTES; index++) {
      bytes[offset + index] = (byte) (value >>> (Byte.SIZE * index));
    }
  }

  /**
   * Writes one schema-version-1 release-input manifest entry.
   *
   * @param manifest exact manifest path to create
   * @param path release-input-relative path
   * @param sha256 independently calculated lowercase artifact digest
   * @param kind declared release-input kind
   * @param source declared source identity or repository-relative evidence path
   * @throws IOException if the manifest cannot be written
   */
  private static void writeReleaseInputManifest(
      Path manifest, String path, String sha256, String kind, String source) throws IOException {
    Files.writeString(
        manifest,
        """
                        {
                          "schemaVersion": 1,
                          "entries": [
                            {
                              "path": "%s",
                              "sha256": "%s",
                              "kind": "%s",
                              "source": "%s"
                            }
                          ]
                        }
                        """
            .formatted(path, sha256, kind, source));
  }

  /**
   * Returns a small PE-shaped byte sequence with a DOS and PE signature.
   *
   * @return independently constructed native test payload
   */
  private static byte[] syntheticPePayload() {
    byte[] payload = new byte[128];
    payload[0] = 0x4d;
    payload[1] = 0x5a;
    payload[0x3c] = 0x40;
    payload[0x40] = 0x50;
    payload[0x41] = 0x45;
    return payload;
  }

  /**
   * Returns the lowercase SHA-256 digest of one test-owned file.
   *
   * @param path exact file to hash
   * @return lowercase 64-character hexadecimal digest
   * @throws IOException if the file cannot be read
   */
  private static String sha256(Path path) throws IOException {
    try {
      return HexFormat.of()
          .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("The Java runtime does not provide SHA-256", impossible);
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
    assertFileContains("CONTRIBUTING.md", "No contributor license agreement");
    assertFileContains("docs/reference-use.md", "copy, mechanically translate, or preserve");
    assertFileContains("README.md", "fd1e36020b2b5b6217e553dc0038983146a2e2dd");
    assertFileContains("RELEASE-NOTES.md", "fd1e36020b2b5b6217e553dc0038983146a2e2dd");
    assertFileContains("NOTICE", "Copyright 2026 evildarkarchon");
    assertFileContains("REUSE.toml", "SPDX-License-Identifier");
    assertFileContains("tests/fixtures/synthetic/README.md", "CC0-1.0");
    assertFileContains(
        ".github/workflows/build.yml",
        "fsfe/reuse-action@676e2d560c9a403aa252096d99fcab3e1132b0f5");
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
      assertNotEquals(0, result.exitCode(), result.output());
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
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("Unapproved native payload"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies PE content remains subject to native approval after its filename extension is changed.
   *
   * @throws Exception if the verifier process or owned temporary files cannot be managed
   */
  @Test
  void releaseInputAuditRejectsRenamedNativePayloads() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-renamed-native-input-");
    Path manifest = Files.createTempFile("jbsa-renamed-native-manifest-", ".json");
    try {
      Path payload = releaseInputs.resolve("unknown.bin");
      Files.write(payload, syntheticPePayload());
      writeReleaseInputManifest(
          manifest, "unknown.bin", sha256(payload), "documentation", "README.md");

      AuditResult result = runComplianceAudit(releaseInputs, manifest);
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("Unapproved native payload"), result.output());
    } finally {
      Files.deleteIfExists(manifest);
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
      assertNotEquals(0, result.exitCode(), result.output());
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
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("Unapproved native payload"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies an archive cannot hide PE content by assigning its entry a non-native extension.
   *
   * @throws Exception if the verifier process or owned temporary files cannot be managed
   */
  @Test
  void releaseInputAuditRejectsRenamedNativePayloadHiddenInJar() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-renamed-archived-native-input-");
    Path manifest = Files.createTempFile("jbsa-renamed-archived-native-manifest-", ".json");
    try {
      Path archive = releaseInputs.resolve("candidate.jar");
      writeZip(archive, "native/windows-x86_64/unknown.bin", syntheticPePayload());
      writeReleaseInputManifest(
          manifest, "candidate.jar", sha256(archive), "documentation", "README.md");

      AuditResult result = runComplianceAudit(releaseInputs, manifest);
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("Unapproved native payload"), result.output());
    } finally {
      Files.deleteIfExists(manifest);
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies a prefixed ZIP cannot hide renamed native content from recursive inspection.
   *
   * @throws Exception if the verifier process or owned temporary files cannot be managed
   */
  @Test
  void releaseInputAuditRejectsNativePayloadHiddenInPrefixedRenamedZip() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-prefixed-zip-native-input-");
    Path manifest = Files.createTempFile("jbsa-prefixed-zip-native-manifest-", ".json");
    try {
      Path archive = releaseInputs.resolve("candidate.bin");
      writePrefixedZip(archive, "native/windows-x86_64/unknown.bin", syntheticPePayload());
      writeReleaseInputManifest(
          manifest, "candidate.bin", sha256(archive), "documentation", "README.md");

      AuditResult result = runComplianceAudit(releaseInputs, manifest);
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("Unapproved native payload"), result.output());
    } finally {
      Files.deleteIfExists(manifest);
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
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(
          result.output().contains("Proprietary or local fixture material"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies unsafe archive paths are rejected even when the ZIP entry represents a directory.
   *
   * @throws Exception if the verifier process or owned temporary archive cannot be managed
   */
  @Test
  void releaseInputAuditRejectsUnsafeDirectoryEntriesInZip() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-unsafe-directory-input-");
    try {
      writeZip(releaseInputs.resolve("candidate.zip"), "../", new byte[0]);

      AuditResult result = runComplianceAudit(releaseInputs);
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("unsafe entry path"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies a directory and file cannot alias under case-insensitive extraction semantics.
   *
   * @throws Exception if the verifier process or owned temporary archive cannot be managed
   */
  @Test
  void releaseInputAuditRejectsDirectoryFileAliasesInZip() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-directory-alias-input-");
    try {
      writeDirectoryFileAliasZip(releaseInputs.resolve("candidate.zip"));

      AuditResult result = runComplianceAudit(releaseInputs);
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("duplicate case-insensitive entry"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies dot-segment archive names cannot alias a separately named extraction path.
   *
   * @throws Exception if the verifier process or owned temporary archive cannot be managed
   */
  @Test
  void releaseInputAuditRejectsDotSegmentAliasesInZip() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-dot-segment-alias-input-");
    try {
      writeDotSegmentAliasZip(releaseInputs.resolve("candidate.zip"));

      AuditResult result = runComplianceAudit(releaseInputs);
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("unsafe entry path"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies Unix symbolic-link metadata is rejected before an archive entry is treated as a file.
   *
   * @throws Exception if the verifier process or owned temporary archive cannot be managed
   */
  @Test
  void releaseInputAuditRejectsSymbolicLinkEntriesInZip() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-symbolic-link-input-");
    try {
      writeUnixSymbolicLinkZip(releaseInputs.resolve("candidate.zip"), "link", "target");

      AuditResult result = runComplianceAudit(releaseInputs);
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("symbolic-link entry"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies declared oversized entries are rejected before the verifier inflates their payload.
   *
   * @throws Exception if the verifier process or owned temporary archive cannot be managed
   */
  @Test
  void releaseInputAuditRejectsOversizedEntriesBeforeInspection() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-oversized-entry-input-");
    try {
      writeZipWithDeclaredEntrySize(releaseInputs.resolve("candidate.zip"), 268_435_457);

      AuditResult result = runComplianceAudit(releaseInputs);
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("inspection size limit"), result.output());
    } finally {
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies a self-declared project coordinate cannot bless bytes unlike the reactor output.
   *
   * @throws Exception if the verifier process or owned temporary files cannot be managed
   */
  @Test
  void releaseInputAuditRejectsProjectArtifactThatDiffersFromReactorOutput() throws Exception {
    Path releaseInputs = Files.createTempDirectory("jbsa-forged-project-artifact-input-");
    Path manifest = Files.createTempFile("jbsa-forged-project-artifact-manifest-", ".json");
    try {
      String stagedName = "jbsa-" + System.getProperty("jbsa.version") + ".jar";
      Path artifact = releaseInputs.resolve(stagedName);
      writeZip(artifact, "forged.txt", "not the reactor artifact");
      writeReleaseInputManifest(
          manifest,
          stagedName,
          sha256(artifact),
          "project-artifact",
          "io.github.evildarkarchon:jbsa:" + System.getProperty("jbsa.version"));

      AuditResult result = runComplianceAudit(releaseInputs, manifest);
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("does not match the reactor output"), result.output());
    } finally {
      Files.deleteIfExists(manifest);
      deleteTree(releaseInputs);
    }
  }

  /**
   * Verifies the exact current reactor JAR remains a valid project-artifact release input.
   *
   * @throws Exception if the verifier process or owned temporary files cannot be managed
   */
  @Test
  void releaseInputAuditAcceptsExactReactorProjectArtifact() throws Exception {
    String version = System.getProperty("jbsa.version");
    List<ProjectArtifactFixture> fixtures =
        List.of(
            new ProjectArtifactFixture("jbsa-" + version + ".jar", "jbsa.library.jar", "jbsa"),
            new ProjectArtifactFixture(
                "jbsa-" + version + ".pom", "jbsa.library.consumerPom", "jbsa"),
            new ProjectArtifactFixture(
                "jbsa-" + version + "-sources.jar", "jbsa.library.sourcesJar", "jbsa"),
            new ProjectArtifactFixture(
                "jbsa-" + version + "-javadoc.jar", "jbsa.library.javadocJar", "jbsa"),
            new ProjectArtifactFixture("jbsa-cli-" + version + ".jar", "jbsa.cli.jar", "jbsa-cli"));

    for (ProjectArtifactFixture fixture : fixtures) {
      Path releaseInputs = Files.createTempDirectory("jbsa-reactor-project-artifact-input-");
      Path manifest = Files.createTempFile("jbsa-reactor-project-artifact-manifest-", ".json");
      try {
        Path artifact = releaseInputs.resolve(fixture.stagedName());
        Files.copy(Path.of(System.getProperty(fixture.systemProperty())), artifact);
        writeReleaseInputManifest(
            manifest,
            fixture.stagedName(),
            sha256(artifact),
            "project-artifact",
            "io.github.evildarkarchon:" + fixture.artifactId() + ":" + version);

        AuditResult result = runComplianceAudit(releaseInputs, manifest);
        assertEquals(0, result.exitCode(), () -> fixture.stagedName() + ":\n" + result.output());
      } finally {
        Files.deleteIfExists(manifest);
        deleteTree(releaseInputs);
      }
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
      assertNotEquals(0, result.exitCode(), result.output());
      assertTrue(result.output().contains("uninventoried external component"), result.output());
    } finally {
      Files.deleteIfExists(sbom);
    }
  }

  /** Captures the observable exit status and merged output from the compliance command. */
  private record AuditResult(int exitCode, String output) {}

  /** Identifies one reactor output and its canonical staged project-artifact name. */
  private record ProjectArtifactFixture(
      String stagedName, String systemProperty, String artifactId) {}
}
