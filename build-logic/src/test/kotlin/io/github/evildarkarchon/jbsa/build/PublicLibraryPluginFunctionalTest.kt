package io.github.evildarkarchon.jbsa.build

import java.lang.module.ModuleFinder
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.jar.JarEntry
import java.util.jar.JarFile
import javax.xml.parsers.DocumentBuilderFactory
import org.gradle.testkit.runner.BuildResult
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.w3c.dom.Element

class PublicLibraryPluginFunctionalTest {
    @TempDir lateinit var projectDir: Path

    /** Creates the six-project fixture and a minimal modular library before each functional test. */
    @BeforeEach
    fun createFixture() {
        write(
            "settings.gradle.kts",
            """
            pluginManagement { repositories { gradlePluginPortal() } }
            plugins { id("jbsa.settings") }
            rootProject.name = "jbsa-parent"
            include("jbsa", "jbsa-cli", "jbsa-test-support", "jbsa-conformance-tests", "jbsa-benchmarks", "jbsa-dist")
            """.trimIndent(),
        )
        write("gradle.properties", "version=0.1.0-SNAPSHOT\n")
        writeCatalog()
        write("build.gradle.kts", "plugins { id(\"jbsa.foundation\") }\n")
        listOf(
                "jbsa",
                "jbsa-cli",
                "jbsa-test-support",
                "jbsa-conformance-tests",
                "jbsa-benchmarks",
                "jbsa-dist",
            )
            .forEach { Files.createDirectories(projectDir.resolve(it)) }
        write(
            "jbsa/src/main/java/module-info.java",
            """
            module io.github.evildarkarchon.jbsa {
                requires jdk.unsupported;
                requires static org.lwjgl;
                requires static org.lwjgl.lz4;
                exports io.github.evildarkarchon.jbsa;
            }
            """.trimIndent(),
        )
        write(
            "jbsa/src/main/java/io/github/evildarkarchon/jbsa/Sample.java",
            """
            package io.github.evildarkarchon.jbsa;
            /** Public fixture type. */
            public final class Sample {
                private Sample() {}
                /** Returns the supplied value so parameter metadata is observable. */
                public static String identity(String value) { return value; }
            }
            """.trimIndent(),
        )
        write(
            "jbsa/src/main/java/io/github/evildarkarchon/jbsa/package-info.java",
            "/** Public fixture package. */\npackage io.github.evildarkarchon.jbsa;\n",
        )
        write(
            "jbsa/src/main/java/io/github/evildarkarchon/jbsa/PackageAnchor.java",
            "package io.github.evildarkarchon.jbsa; final class PackageAnchor {}\n",
        )
        write(
            "jbsa-test-support/src/main/java/io/github/evildarkarchon/jbsa/fixtures/FixtureSupport.java",
            """
            package io.github.evildarkarchon.jbsa.fixtures;
            /** Build-only fixture support. */
            public final class FixtureSupport { private FixtureSupport() {} }
            """.trimIndent(),
        )
    }

    /** Verifies compatible library artifacts, exact entry timestamps, Java level, and parameter metadata. */
    @Test
    fun `builds the compatible public artifact set`() {
        run("--write-locks", ":jbsa:assembleLibraryPublication", ":jbsa:verifyPublicLibraryArtifact")

        val target = projectDir.resolve("jbsa/target/libs")
        val binary = target.resolve("jbsa-0.1.0-SNAPSHOT.jar")
        val sources = target.resolve("jbsa-0.1.0-SNAPSHOT-sources.jar")
        val javadocs = target.resolve("jbsa-0.1.0-SNAPSHOT-javadoc.jar")
        listOf(binary, sources, javadocs).forEach { archive ->
            assertTrue(Files.isRegularFile(archive), archive.toString())
            assertArchiveTimestamp(archive)
        }
        assertJarContains(binary, "module-info.class")
        assertJarContains(sources, "io/github/evildarkarchon/jbsa/package-info.java")
        assertJarContains(javadocs, "index.html")

        JarFile(binary.toFile()).use { jar ->
            val bytes = jar.getInputStream(jar.getJarEntry("io/github/evildarkarchon/jbsa/Sample.class")).readAllBytes()
            assertEquals(69, bytes[7].toInt() and 0xff)
            assertTrue(bytes.toString(Charsets.ISO_8859_1).contains("MethodParameters"))
        }
        val descriptor = ModuleFinder.of(binary).find("io.github.evildarkarchon.jbsa").orElseThrow().descriptor()
        assertFalse(descriptor.isAutomatic)
        assertFalse(descriptor.isOpen)
        assertEquals(setOf("io.github.evildarkarchon.jbsa"), descriptor.exports().map { it.source() }.toSet())
        assertEquals(
            setOf("java.base", "jdk.unsupported", "org.lwjgl", "org.lwjgl.lz4"),
            descriptor.requires().map { it.name() }.toSet(),
        )
        descriptor.requires().filter { it.name().startsWith("org.lwjgl") }.forEach { requirement ->
            assertTrue(requirement.compiledVersion().isPresent)
            assertTrue(requirement.modifiers().contains(java.lang.module.ModuleDescriptor.Requires.Modifier.STATIC))
        }
        URLClassLoader(arrayOf(binary.toUri().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val publicType = Class.forName("io.github.evildarkarchon.jbsa.Sample", true, loader)
            val method = publicType.getMethod("identity", String::class.java)
            assertEquals(String::class.java, method.returnType)
            assertEquals(listOf(String::class.java), method.parameterTypes.toList())
        }
        assertEquals(
            setOf(
                "jbsa-0.1.0-SNAPSHOT.jar",
                "jbsa-0.1.0-SNAPSHOT-sources.jar",
                "jbsa-0.1.0-SNAPSHOT-javadoc.jar",
                "jbsa-0.1.0-SNAPSHOT.pom",
            ),
            Files.list(projectDir.resolve("jbsa/target/publications/library/artifacts")).use { paths ->
                paths.map { it.fileName.toString() }.toList().toSet()
            },
        )
    }

    /** Verifies the parent-free consumer POM carries exact metadata and dependency semantics. */
    @Test
    fun `generates the exact parent-free consumer pom`() {
        run(":jbsa:generatePomFileForLibraryPublication")

        val pom = projectDir.resolve("jbsa/target/publications/library/pom-default.xml")
        assertFalse(Files.readString(pom).contains("published-with-gradle-metadata"))
        val document = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(pom.toFile())
        val root = document.documentElement
        assertEquals("io.github.evildarkarchon", directText(root, "groupId"))
        assertEquals("jbsa", directText(root, "artifactId"))
        assertEquals("0.1.0-SNAPSHOT", directText(root, "version"))
        assertEquals("JBSA archive library", directText(root, "name"))
        assertEquals(
            "Independently authored Java support for Bethesda Archives, informed by the pinned TES5Edit " +
                "Reference Snapshot at fd1e36020b2b5b6217e553dc0038983146a2e2dd.",
            directText(root, "description"),
        )
        assertEquals("https://github.com/evildarkarchon/jbsa", directText(root, "url"))
        assertFalse(hasDirectChild(root, "parent"))
        assertFalse(hasDirectChild(root, "repositories"))
        assertFalse(hasDirectChild(root, "build"))
        assertFalse(hasDirectChild(root, "profiles"))
        assertFalse(hasDirectChild(root, "properties"))
        val license = directChildren(directChildren(root, "licenses").single(), "license").single()
        assertEquals("Apache License, Version 2.0", directText(license, "name"))
        assertEquals("https://www.apache.org/licenses/LICENSE-2.0.txt", directText(license, "url"))
        assertEquals("repo", directText(license, "distribution"))
        val developer = directChildren(directChildren(root, "developers").single(), "developer").single()
        assertEquals("evildarkarchon", directText(developer, "id"))
        assertEquals("evildarkarchon", directText(developer, "name"))
        assertEquals("https://github.com/evildarkarchon", directText(developer, "url"))
        val scm = directChildren(root, "scm").single()
        assertEquals("scm:git:https://github.com/evildarkarchon/jbsa.git", directText(scm, "connection"))
        assertEquals("scm:git:ssh://git@github.com/evildarkarchon/jbsa.git", directText(scm, "developerConnection"))
        assertEquals("https://github.com/evildarkarchon/jbsa", directText(scm, "url"))
        assertEquals("HEAD", directText(scm, "tag"))

        val dependencies = root.getElementsByTagName("dependency")
        assertEquals(4, dependencies.length)
        val actual =
            (0 until dependencies.length).map { index ->
                val dependency = dependencies.item(index) as Element
                listOf(
                    directText(dependency, "groupId"),
                    directText(dependency, "artifactId"),
                    directText(dependency, "version"),
                    directText(dependency, "classifier"),
                    directText(dependency, "scope"),
                )
            }.toSet()
        assertEquals(
            setOf(
                listOf("org.lwjgl", "lwjgl", "3.4.3", "", "compile"),
                listOf("org.lwjgl", "lwjgl-lz4", "3.4.3", "", "compile"),
                listOf("org.lwjgl", "lwjgl", "3.4.3", "natives-windows", "runtime"),
                listOf("org.lwjgl", "lwjgl-lz4", "3.4.3", "natives-windows", "runtime"),
            ),
            actual,
        )
        (0 until dependencies.length).forEach { index ->
            val dependency = dependencies.item(index) as Element
            assertFalse(hasDirectChild(dependency, "optional"))
            assertFalse(hasDirectChild(dependency, "exclusions"))
        }
    }

    /** Verifies one compilation feeds disjoint unit/integration tasks with deterministic runtime policy. */
    @Test
    fun `runs Test and IT classes exactly once in separate tasks`() {
        writeTest("AlphaTest")
        writeTest("BetaTest")
        writeTest("AlphaIT")
        writeTest("BetaIT")

        val result = run("--write-locks", ":jbsa:test", ":jbsa:integrationTest")

        val executions = Files.readAllLines(projectDir.resolve("test-executions.txt"))
        assertEquals(listOf("AlphaTest", "BetaTest", "AlphaIT", "BetaIT"), executions.map { it.substringBefore('|') })
        assertEquals(4, executions.map { it.substringBefore('|') }.toSet().size)
        assertEquals(1, executions.take(2).map { it.split('|')[1] }.toSet().size)
        assertEquals(1, executions.takeLast(2).map { it.split('|')[1] }.toSet().size)
        executions.forEach { line ->
            assertTrue(line.endsWith("|UTC|en|US|UTF-8|true|true"), line)
        }
        assertEquals(1, Regex(Regex.escape(":jbsa:compileTestJava")).findAll(result.output).count())
        assertTrue(Files.isRegularFile(projectDir.resolve("jbsa/target/test-results/test/TEST-fixture.AlphaTest.xml")))
        assertFalse(Files.exists(projectDir.resolve("jbsa/target/test-results/test/TEST-fixture.AlphaIT.xml")))
        assertTrue(
            Files.isRegularFile(projectDir.resolve("jbsa/target/test-results/integrationTest/TEST-fixture.AlphaIT.xml"))
        )
        assertFalse(
            Files.exists(projectDir.resolve("jbsa/target/test-results/integrationTest/TEST-fixture.AlphaTest.xml"))
        )
    }

    /** Verifies publication remains local-only and build-only projects cannot acquire publications. */
    @Test
    fun `permits publication only for the library and configures no remote action`() {
        val result = run("verifyPublicationPolicy", ":jbsa-test-support:tasks", "--all")

        assertTrue(result.output.contains("JBSA_PUBLICATION :jbsa=library"), result.output)
        assertTrue(result.output.contains("JBSA_PUBLICATION_REMOTE_TASKS 0"), result.output)
        assertFalse(result.output.contains(":jbsa-test-support:publish"), result.output)
    }

    /** Verifies local publication cannot expand the compatible four-file Maven artifact set. */
    @Test
    fun `disables incidental Gradle module metadata`() {
        val result = run("--write-locks", ":jbsa:generateMetadataFileForLibraryPublication")

        assertEquals(TaskOutcome.SKIPPED, result.task(":jbsa:generateMetadataFileForLibraryPublication")?.outcome)
        assertFalse(Files.exists(projectDir.resolve("jbsa/target/publications/library/module.json")))
    }

    /** Verifies the artifact gate rejects a third-party type leaked through an exported signature. */
    @Test
    fun `rejects leaked public signature types`() {
        write(
            "jbsa/src/main/java/io/github/evildarkarchon/jbsa/Sample.java",
            """
            package io.github.evildarkarchon.jbsa;
            /** Deliberately invalid public fixture type. */
            public final class Sample {
                private Sample() {}
                /** Leaks a third-party implementation type. */
                public static org.lwjgl.system.MemoryStack leaked() { return null; }
            }
            """.trimIndent(),
        )

        val result = runAndFail("--write-locks", ":jbsa:verifyPublicLibraryArtifact")

        assertTrue(result.output.contains("Public signature leaks prohibited type org.lwjgl.system.MemoryStack"))
    }

    /** Verifies CLASS-retained annotation values cannot conceal third-party public API types. */
    @Test
    fun `rejects class-retained annotation value types`() {
        write(
            "jbsa/src/main/java/io/github/evildarkarchon/jbsa/ThirdPartyCarrier.java",
            """
            package io.github.evildarkarchon.jbsa;
            @java.lang.annotation.Retention(java.lang.annotation.RetentionPolicy.CLASS)
            @java.lang.annotation.Target(java.lang.annotation.ElementType.TYPE)
            public @interface ThirdPartyCarrier { Class<?> value(); }
            """.trimIndent(),
        )
        write(
            "jbsa/src/main/java/io/github/evildarkarchon/jbsa/Sample.java",
            """
            package io.github.evildarkarchon.jbsa;
            /** Deliberately invalid public fixture type. */
            @ThirdPartyCarrier(org.lwjgl.system.MemoryStack.class)
            public final class Sample { private Sample() {} }
            """.trimIndent(),
        )

        val result = runAndFail("--write-locks", ":jbsa:verifyPublicLibraryArtifact")

        assertTrue(
            result.output.contains("annotation value leaks prohibited type org.lwjgl.system.MemoryStack"),
            result.output,
        )
    }

    /** Verifies a build-only project cannot acquire Maven publication capability. */
    @Test
    fun `rejects build-only publication configuration`() {
        write(
            "jbsa-test-support/build.gradle.kts",
            """
            plugins { `maven-publish` }
            publishing {
                repositories { maven { url = uri("https://example.invalid/releases") } }
            }
            """.trimIndent(),
        )

        val result = runAndFail("verifyPublicationPolicy")

        assertTrue(result.output.contains(":jbsa-test-support is build-only and cannot apply maven-publish"), result.output)
    }

    /** Verifies the public library cannot configure a remote publication repository. */
    @Test
    fun `rejects a remote publication repository`() {
        write(
            "jbsa/build.gradle.kts",
            """
            publishing {
                repositories { maven { url = uri("https://example.invalid/releases") } }
            }
            """.trimIndent(),
        )

        val result = runAndFail("verifyPublicationPolicy")

        assertTrue(result.output.contains("Remote publication repositories are prohibited for :jbsa"), result.output)
    }

    /** Writes one JUnit class whose observation log exposes ordering and test-process policy. */
    private fun writeTest(className: String) {
        write(
            "jbsa/src/test/java/fixture/$className.java",
            """
            package fixture;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;
            import java.time.ZoneId;
            import java.util.Locale;
            import org.junit.jupiter.api.Test;
            class $className {
                /** Records the deterministic process environment and built-JAR property. */
                @Test void recordsEnvironment() throws Exception {
                    boolean jarExists = Files.isRegularFile(Path.of(System.getProperty("jbsa.library.jar")));
                    String line = "$className|" + ProcessHandle.current().pid() + "|" + ZoneId.systemDefault() + "|"
                        + Locale.getDefault().getLanguage() + "|" + Locale.getDefault().getCountry() + "|"
                        + System.getProperty("file.encoding") + "|" + jarExists + "|" + getClass().getModule().isNamed()
                        + System.lineSeparator();
                    Files.writeString(Path.of(System.getProperty("jbsa.reactor.root"), "test-executions.txt"), line,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            }
            """.trimIndent(),
        )
    }

    /** Asserts an archive contains the exact configured epoch on every entry. */
    private fun assertArchiveTimestamp(path: Path) {
        JarFile(path.toFile()).use { jar ->
            assertEquals(
                setOf(Instant.parse("2026-09-03T00:00:00Z").toEpochMilli()),
                jar.entries().asSequence().map(JarEntry::getTime).toSet(),
            )
        }
    }

    /** Asserts an archive contains a required compatibility payload. */
    private fun assertJarContains(path: Path, entry: String) {
        JarFile(path.toFile()).use { jar -> assertNotNull(jar.getJarEntry(entry)) }
    }

    /** Returns direct child text while treating an absent optional element as empty. */
    private fun directText(parent: Element, name: String): String =
        directChildren(parent, name).singleOrNull()?.textContent?.trim().orEmpty()

    /** Reports whether a direct child element exists. */
    private fun hasDirectChild(parent: Element, name: String): Boolean = directChildren(parent, name).isNotEmpty()

    /** Returns direct child elements with a local XML name. */
    private fun directChildren(parent: Element, name: String): List<Element> =
        (0 until parent.childNodes.length)
            .map { parent.childNodes.item(it) }
            .filterIsInstance<Element>()
            .filter { it.tagName.substringAfter(':') == name }

    /** Runs the fixture with the plugin-under-test classpath and dependency verification disabled. */
    private fun run(vararg arguments: String): BuildResult = runner(arguments).build()

    /** Runs a fixture expected to fail with an actionable policy diagnostic. */
    private fun runAndFail(vararg arguments: String): BuildResult = runner(arguments).buildAndFail()

    /** Creates one TestKit runner with stable diagnostics. */
    private fun runner(arguments: Array<out String>): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(listOf("--dependency-verification=off", "--stacktrace") + arguments)

    /** Writes the fixture's complete centrally pinned dependency catalog. */
    private fun writeCatalog() {
        write(
            "gradle/libs.versions.toml",
            """
            [versions]
            junit = "6.1.3"
            lwjgl = "3.4.3"

            [libraries]
            junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
            junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
            junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junit" }
            lwjgl = { module = "org.lwjgl:lwjgl", version.ref = "lwjgl" }
            lwjgl-lz4 = { module = "org.lwjgl:lwjgl-lz4", version.ref = "lwjgl" }

            [plugins]
            """.trimIndent(),
        )
    }

    /** Writes one UTF-8 fixture file, creating parent directories as necessary. */
    private fun write(relativePath: String, content: String) {
        val path = projectDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
