package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class FoundationPluginFunctionalTest {
    @TempDir lateinit var projectDir: Path

    /** Creates the six-project public build seam used by each functional policy test. */
    @BeforeEach
    fun createFixture() {
        write(
            "settings.gradle.kts",
            """
            pluginManagement {
                repositories { gradlePluginPortal() }
            }
            plugins { id("jbsa.settings") }
            rootProject.name = "jbsa-parent"
            include("jbsa", "jbsa-cli", "jbsa-test-support", "jbsa-conformance-tests", "jbsa-benchmarks", "jbsa-dist")
            """.trimIndent(),
        )
        write("gradle.properties", "version=0.1.0-SNAPSHOT\n")
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
    }

    /** Verifies default identity and every preserved project role through the build's report task. */
    @Test
    fun `reports the default identity and all six roles`() {
        val result = run("foundationIdentity")

        assertTrue(
            result.output.contains(
                "JBSA_FOUNDATION root=jbsa-parent group=io.github.evildarkarchon version=0.1.0-SNAPSHOT"
            )
        )
        assertTrue(result.output.contains(":jbsa=public-library"))
        assertTrue(result.output.contains(":jbsa-cli=thin-application"))
        assertTrue(result.output.contains(":jbsa-test-support=build-only-test-support"))
        assertTrue(result.output.contains(":jbsa-conformance-tests=build-only-conformance"))
        assertTrue(result.output.contains(":jbsa-benchmarks=build-only-benchmarks"))
        assertTrue(result.output.contains(":jbsa-dist=non-java-staging-audit"))
    }

    /** Verifies that settings reject an external plugin request without a catalog-pinned version. */
    @Test
    fun `rejects an unpinned external plugin`() {
        write(
            "build.gradle.kts",
            """
            plugins {
                id("jbsa.foundation")
                id("com.diffplug.spotless")
            }
            """.trimIndent(),
        )

        val result = runAndFail("help")

        assertTrue(result.output.contains("External plugin 'com.diffplug.spotless' must use a pinned version"))
    }

    /** Verifies that an explicit candidate replaces the default for the entire build. */
    @Test
    fun `applies an explicit version override to every project`() {
        val result = run("-Pversion=1.2.3", "foundationIdentity", "verifyBuildFoundation")

        assertTrue(result.output.contains("group=io.github.evildarkarchon version=1.2.3"))
    }

    /** Verifies that invalid candidate syntax fails before any project can acquire a divergent identity. */
    @Test
    fun `rejects an invalid build version`() {
        val result = runAndFail("-Pversion=1.+", "help")

        assertTrue(result.output.contains("Invalid JBSA version '1.+'"))
    }

    /** Verifies that later project scripts cannot replace the centrally assigned group or version. */
    @Test
    fun `rejects inconsistent project identity`() {
        write(
            "jbsa/build.gradle.kts",
            """
            group = "example.invalid"
            version = "9.9.9"
            """.trimIndent(),
        )

        val result = runAndFail("verifyBuildFoundation")

        assertTrue(result.output.contains("Project :jbsa must use io.github.evildarkarchon:0.1.0-SNAPSHOT"))
    }

    /** Verifies that projects cannot append repositories outside the settings-owned Maven Central policy. */
    @Test
    fun `rejects project local repositories`() {
        write("jbsa/build.gradle.kts", "repositories { maven { url = uri(\"https://example.invalid/maven\") } }\n")

        val result = runAndFail("help")

        assertTrue(result.output.contains("prefer settings repositories over project repositories"))
    }

    /** Verifies strict locking fails resolution when an external graph has no committed lock state. */
    @Test
    fun `rejects a resolvable graph without a lock`() {
        declareProbe("org.junit.jupiter:junit-jupiter-api:6.1.3")

        val result = runAndFail("resolveProbe")

        assertTrue(result.output.contains("locked but does not have lock state"), result.output)
    }

    /** Verifies strict checksum metadata rejects an artifact before it can enter a resolved graph. */
    @Test
    fun `rejects an unverified dependency artifact`() {
        write(
            "gradle/verification-metadata.xml",
            """
            <?xml version="1.0" encoding="UTF-8"?>
            <verification-metadata>
               <configuration>
                  <verify-metadata>true</verify-metadata>
                  <verify-signatures>false</verify-signatures>
               </configuration>
               <components/>
            </verification-metadata>
            """.trimIndent(),
        )
        declareProbe("org.junit.jupiter:junit-jupiter-api:6.1.3")

        val result = runStrictAndFail("--write-locks", "resolveProbe")

        assertTrue(result.output.contains("Dependency verification failed"), result.output)
    }

    /** Verifies dynamic selectors fail with the build's actionable deterministic-resolution diagnostic. */
    @Test
    fun `rejects dynamic dependencies`() {
        declareProbe("org.junit.jupiter:junit-jupiter-api:6.+")

        val result = runAndFail("--write-locks", "resolveProbe")

        assertTrue(result.output.contains("Dynamic dependency version '6.+' is prohibited"))
    }

    /** Verifies dependencies marked mutable fail before any artifact is accepted. */
    @Test
    fun `rejects changing dependencies`() {
        declareProbe("org.junit.jupiter:junit-jupiter-api:6.1.3", changing = true)

        val result = runAndFail("--write-locks", "resolveProbe")

        assertTrue(result.output.contains("Changing dependency '6.1.3' is prohibited"))
    }

    /** Verifies a release candidate cannot resolve snapshot dependencies. */
    @Test
    fun `rejects snapshot dependencies for release builds`() {
        declareProbe("example.invalid:unpublished:1.0.0-SNAPSHOT")

        val result = runAndFail("-Pversion=1.0.0", "--write-locks", "resolveProbe")

        assertTrue(result.output.contains("Release build '1.0.0' cannot resolve snapshot dependency"))
    }

    /** Verifies a graph with two versions of the same module fails rather than selecting silently. */
    @Test
    fun `rejects dependency version conflicts`() {
        write(
            "build.gradle.kts",
            """
            plugins { id("jbsa.foundation") }
            val policyProbe by configurations.creating {
                isCanBeResolved = true
                isCanBeConsumed = false
            }
            dependencies {
                policyProbe("org.junit.jupiter:junit-jupiter-api:6.1.3")
                policyProbe("org.junit.jupiter:junit-jupiter-api:6.0.0")
            }
            tasks.register("resolveProbe") {
                doLast { policyProbe.files.forEach { println(it.name) } }
            }
            """.trimIndent(),
        )

        val result = runAndFail("--write-locks", "resolveProbe")

        assertTrue(result.output.contains("Conflict found for module"), result.output)
    }

    /** Verifies clean removes owned target output without changing tracked automation bytes. */
    @Test
    fun `clean preserves tracked automation while removing owned output`() {
        val sentinel = "tracked automation sentinel\r\nwith byte-defined content\n"
        write("build/automation-sentinel.txt", sentinel)
        write("target/generated.txt", "generated")
        write("jbsa/target/generated.txt", "generated")

        run("clean")

        assertTrue(Files.readString(projectDir.resolve("build/automation-sentinel.txt")) == sentinel)
        assertFalse(Files.exists(projectDir.resolve("target")))
        assertFalse(Files.exists(projectDir.resolve("jbsa/target")))
    }

    /** Runs the fixture with the plugin-under-test classpath and strict command-line diagnostics. */
    private fun run(vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("--dependency-verification=off", "--stacktrace", *arguments)
            .build()

    /** Runs a fixture expected to fail while retaining its actionable Gradle diagnostic. */
    private fun runAndFail(vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("--dependency-verification=off", "--stacktrace", *arguments)
            .buildAndFail()

    /** Runs a failing fixture with Gradle's default strict dependency verification enabled. */
    private fun runStrictAndFail(vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("--stacktrace", *arguments)
            .buildAndFail()

    /** Declares a single resolvable dependency graph for negative resolution-policy tests. */
    private fun declareProbe(coordinate: String, changing: Boolean = false) {
        val changingClause = if (changing) " { isChanging = true }" else ""
        write(
            "build.gradle.kts",
            """
            plugins { id("jbsa.foundation") }
            val policyProbe by configurations.creating {
                isCanBeResolved = true
                isCanBeConsumed = false
            }
            dependencies { policyProbe("$coordinate")$changingClause }
            tasks.register("resolveProbe") {
                doLast { policyProbe.files.forEach { println(it.name) } }
            }
            """.trimIndent(),
        )
    }

    /** Writes one UTF-8 fixture file, creating its parent directory when necessary. */
    private fun write(relativePath: String, content: String) {
        val path = projectDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
