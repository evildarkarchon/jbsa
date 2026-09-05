package io.github.evildarkarchon.jbsa.verification;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.SAXException;

@Tag("build-policy")
final class BuildPolicyIT {
  private static final int JAVA_25_CLASS_MAJOR_VERSION = 69;
  private static final List<String> REACTOR_MODULES =
      List.of(
          "jbsa",
          "jbsa-cli",
          "jbsa-test-support",
          "jbsa-conformance-tests",
          "jbsa-benchmarks",
          "jbsa-dist");

  /**
   * Verifies every class in a product JAR uses the Java 25 class-file major version.
   *
   * @throws IOException if the JAR or one of its class entries cannot be read
   */
  private static void assertClassFileVersion(Path jarPath, int expectedMajorVersion)
      throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      for (JarEntry entry : Collections.list(jar.entries())) {
        if (entry.isDirectory() || !entry.getName().endsWith(".class")) {
          continue;
        }
        try (InputStream input = jar.getInputStream(entry)) {
          byte[] header = input.readNBytes(8);
          assertEquals(8, header.length, () -> "Truncated class file " + entry.getName());
          int majorVersion = (Byte.toUnsignedInt(header[6]) << 8) | Byte.toUnsignedInt(header[7]);
          assertEquals(
              expectedMajorVersion,
              majorVersion,
              () -> entry.getName() + " does not target Java 25");
        }
      }
    }
  }

  /**
   * Verifies every entry in a product JAR carries the configured deterministic timestamp.
   *
   * @throws IOException if the JAR cannot be read
   */
  private static void assertSingleTimestamp(Path jarPath) throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      Set<Long> timestamps = new HashSet<>();
      for (JarEntry entry : Collections.list(jar.entries())) {
        timestamps.add(entry.getTime());
      }
      assertEquals(
          1, timestamps.size(), () -> jarPath + " contains non-deterministic entry timestamps");
    }
  }

  /**
   * Verifies a companion artifact contains real generated content rather than a placeholder.
   *
   * @throws IOException if the companion artifact cannot be read
   */
  private static void assertJarContains(Path jarPath, String expectedEntry) throws IOException {
    try (JarFile jar = new JarFile(jarPath.toFile())) {
      assertNotNull(jar.getJarEntry(expectedEntry), () -> jarPath + " is missing " + expectedEntry);
    }
  }

  /**
   * Parses a trusted local POM with external entity and DTD processing disabled.
   *
   * @throws ParserConfigurationException if the hardened parser cannot be configured
   * @throws IOException if the POM cannot be read
   * @throws SAXException if the POM is not well-formed XML
   */
  private static Document parse(Path path)
      throws ParserConfigurationException, IOException, SAXException {
    DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
    factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
    factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
    factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
    factory.setXIncludeAware(false);
    factory.setExpandEntityReferences(false);
    return factory.newDocumentBuilder().parse(path.toFile());
  }

  /** Returns direct dependency elements without conflating dependency-management entries. */
  private static List<Element> dependencies(Element project) {
    return directChildren(directChild(project, "dependencies"), "dependency");
  }

  /** Returns the first named element that is an immediate child of the supplied parent. */
  private static Element directChild(Element parent, String name) {
    return directChildren(parent, name).stream().findFirst().orElse(null);
  }

  /** Returns trimmed text from a direct child or an empty string when it is absent. */
  private static String directText(Element parent, String name) {
    Element child = directChild(parent, name);
    return child == null ? "" : child.getTextContent().trim();
  }

  /** Returns trimmed text from all direct children with the supplied name. */
  private static List<String> directTexts(Element parent, String name) {
    return directChildren(parent, name).stream()
        .map(element -> element.getTextContent().trim())
        .toList();
  }

  /** Returns all named elements that are immediate children of the supplied parent. */
  private static List<Element> directChildren(Element parent, String name) {
    if (parent == null) {
      return List.of();
    }
    List<Element> values = new ArrayList<>();
    for (Node child = parent.getFirstChild(); child != null; child = child.getNextSibling()) {
      if (child instanceof Element element && element.getTagName().equals(name)) {
        values.add(element);
      }
    }
    return List.copyOf(values);
  }

  private static Path reactorRoot() {
    return Path.of(System.getProperty("jbsa.reactor.root"));
  }

  /**
   * Verifies JBSA-BUILD-001 and JBSA-BUILD-002 at the source reactor seam.
   *
   * @throws Exception if a reactor POM cannot be read or parsed
   */
  @Test
  void reactorContainsExactlyTheSpecifiedProjectsAtOneVersion() throws Exception {
    Document rootPom = parse(reactorRoot().resolve("pom.xml"));
    Element project = rootPom.getDocumentElement();
    assertEquals("io.github.evildarkarchon", directText(project, "groupId"));
    assertEquals("jbsa-parent", directText(project, "artifactId"));
    assertEquals("${revision}", directText(project, "version"));
    assertEquals(REACTOR_MODULES, directTexts(directChild(project, "modules"), "module"));

    for (String module : REACTOR_MODULES) {
      Element child = parse(reactorRoot().resolve(module).resolve("pom.xml")).getDocumentElement();
      Element parent = directChild(child, "parent");
      assertNotNull(parent, () -> module + " must inherit from jbsa-parent");
      assertEquals("io.github.evildarkarchon", directText(parent, "groupId"));
      assertEquals("jbsa-parent", directText(parent, "artifactId"));
      assertEquals("${revision}", directText(parent, "version"));
      assertEquals("../pom.xml", directText(parent, "relativePath"));
      assertTrue(
          directText(child, "version").isEmpty(),
          () -> module + " must inherit the reactor version");
    }
  }

  /**
   * Verifies JBSA-BUILD-003, JBSA-BUILD-005, and JBSA-BUILD-006 dependency policy.
   *
   * @throws Exception if a production POM cannot be read or parsed
   */
  @Test
  void productionPomsKeepBuildSupportOutsideTheProductGraph() throws Exception {
    Element library = parse(reactorRoot().resolve("jbsa/pom.xml")).getDocumentElement();
    assertTrue(
        dependencies(library).isEmpty(), "The library skeleton must not have runtime dependencies");

    Element cli = parse(reactorRoot().resolve("jbsa-cli/pom.xml")).getDocumentElement();
    List<Element> cliDependencies = dependencies(cli);
    assertEquals(1, cliDependencies.size(), "The CLI skeleton should depend only on jbsa");
    Element dependency = cliDependencies.getFirst();
    assertEquals("io.github.evildarkarchon", directText(dependency, "groupId"));
    assertEquals("jbsa", directText(dependency, "artifactId"));
    assertEquals("${project.version}", directText(dependency, "version"));
    assertTrue(
        directText(dependency, "scope").isEmpty(), "jbsa must be a normal compile dependency");
    assertNotEquals("true", directText(dependency, "optional"), "jbsa is not optional for the CLI");
  }

  /**
   * Verifies the self-contained consumer metadata required by JBSA-BUILD-007.
   *
   * @throws Exception if the generated consumer POM cannot be read or parsed
   */
  @Test
  void flattenedLibraryPomIsSelfContainedAndComplete() throws Exception {
    Path consumerPom = Path.of(System.getProperty("jbsa.library.consumerPom"));
    assertTrue(
        Files.isRegularFile(consumerPom), () -> "Missing flattened consumer POM: " + consumerPom);
    Element project = parse(consumerPom).getDocumentElement();

    assertNull(
        directChild(project, "parent"), "The consumer POM must not retain an unpublished parent");
    assertEquals("io.github.evildarkarchon", directText(project, "groupId"));
    assertEquals("jbsa", directText(project, "artifactId"));
    assertEquals(System.getProperty("jbsa.version"), directText(project, "version"));
    assertEquals("JBSA archive library", directText(project, "name"));
    assertTrue(directText(project, "description").contains("TES5Edit Reference Snapshot"));
    assertTrue(
        directText(project, "description").contains("fd1e36020b2b5b6217e553dc0038983146a2e2dd"));
    assertEquals("https://github.com/evildarkarchon/jbsa", directText(project, "url"));
    assertNotNull(directChild(project, "licenses"));
    assertNotNull(directChild(project, "developers"));
    Element scm = directChild(project, "scm");
    assertNotNull(scm);
    assertEquals(
        "scm:git:https://github.com/evildarkarchon/jbsa.git", directText(scm, "connection"));
    assertEquals(
        "scm:git:ssh://git@github.com/evildarkarchon/jbsa.git",
        directText(scm, "developerConnection"));
    assertEquals("https://github.com/evildarkarchon/jbsa", directText(scm, "url"));
    assertNull(directChild(project, "build"));
    assertNull(directChild(project, "modules"));
    assertNull(directChild(project, "profiles"));
    assertNull(directChild(project, "repositories"));
    assertTrue(
        dependencies(project).isEmpty(),
        "Consumer dependency metadata must match the dependency-free skeleton");
  }

  /**
   * Verifies the ticket's pinned and checksummed Maven-wrapper acceptance criterion.
   *
   * @throws Exception if the wrapper properties cannot be read
   */
  @Test
  void wrapperPinsMavenAndItsDistributionChecksum() throws Exception {
    assertTrue(Files.isRegularFile(reactorRoot().resolve("mvnw")));
    assertTrue(Files.isRegularFile(reactorRoot().resolve("mvnw.cmd")));
    Path wrapperProperties = reactorRoot().resolve(".mvn/wrapper/maven-wrapper.properties");
    assertTrue(Files.isRegularFile(wrapperProperties));

    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(wrapperProperties)) {
      properties.load(input);
    }
    assertEquals("3.3.4", properties.getProperty("wrapperVersion"));
    assertEquals("only-script", properties.getProperty("distributionType"));
    assertEquals(
        "https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/3.9.16/apache-maven-3.9.16-bin.zip",
        properties.getProperty("distributionUrl"));
    assertEquals(
        "5af3b743dd8b876b5c45da33b676251e5f1687712644abb4ee519ca56e1d89ce",
        properties.getProperty("distributionSha256Sum"));
  }

  /**
   * Verifies pull-request branches do not also receive a duplicate push-triggered CI run.
   *
   * @throws IOException if the GitHub Actions workflow cannot be read
   */
  @Test
  void githubActionsRunsPushBuildsOnlyForMain() throws IOException {
    List<String> workflow =
        Files.readAllLines(reactorRoot().resolve(".github/workflows/build.yml"));
    int pushTrigger = workflow.indexOf("  push:");
    assertTrue(pushTrigger >= 0, "The build workflow must retain a push trigger");
    assertEquals("    branches:", workflow.get(pushTrigger + 1));
    assertEquals("      - main", workflow.get(pushTrigger + 2));
    assertTrue(workflow.contains("  pull_request:"));
  }

  /**
   * Verifies JBSA-BUILD-007 and JBSA-SCOPE-001 library artifact policy.
   *
   * @throws Exception if a POM or generated artifact cannot be inspected
   */
  @Test
  void libraryArtifactsAreJava25AndReproduciblyTimestamped() throws Exception {
    Path libraryJar = Path.of(System.getProperty("jbsa.library.jar"));
    Path cliJar = Path.of(System.getProperty("jbsa.cli.jar"));
    assertTrue(Files.isRegularFile(libraryJar));
    assertTrue(Files.isRegularFile(cliJar));
    assertTrue(Files.isRegularFile(Path.of(System.getProperty("jbsa.library.sourcesJar"))));
    assertTrue(Files.isRegularFile(Path.of(System.getProperty("jbsa.library.javadocJar"))));

    assertJarContains(
        Path.of(System.getProperty("jbsa.library.sourcesJar")),
        "io/github/evildarkarchon/jbsa/package-info.java");
    assertJarContains(Path.of(System.getProperty("jbsa.library.javadocJar")), "index.html");

    assertClassFileVersion(libraryJar, JAVA_25_CLASS_MAJOR_VERSION);
    assertClassFileVersion(cliJar, JAVA_25_CLASS_MAJOR_VERSION);
    assertSingleTimestamp(libraryJar);
    assertSingleTimestamp(cliJar);

    Element root = parse(reactorRoot().resolve("pom.xml")).getDocumentElement();
    Element properties = directChild(root, "properties");
    assertEquals("25", directText(properties, "maven.compiler.release"));
    assertEquals(
        Instant.parse("2026-09-03T00:00:00Z"),
        Instant.parse(directText(properties, "project.build.outputTimestamp")));
  }
}
