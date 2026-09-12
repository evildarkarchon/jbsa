package io.github.evildarkarchon.jbsa.build

import java.lang.module.ModuleFinder
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Instant
import java.util.jar.JarFile
import org.gradle.testkit.runner.GradleRunner
import org.gradle.testkit.runner.TaskOutcome
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ThinApplicationPluginFunctionalTest {
    @TempDir lateinit var projectDir: Path

    /** Creates the retained six-project topology with a minimal public library and thin CLI. */
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
            /** Minimal public-library fixture. */
            public final class Sample { private Sample() {} }
            """.trimIndent(),
        )
        write(
            "jbsa-cli/src/main/java/module-info.java",
            """
            module io.github.evildarkarchon.jbsa.cli {
                requires io.github.evildarkarchon.jbsa;
            }
            """.trimIndent(),
        )
        write(
            "jbsa-cli/src/main/java/io/github/evildarkarchon/jbsa/cli/Main.java",
            """
            package io.github.evildarkarchon.jbsa.cli;
            /** Minimal packaged CLI fixture. */
            public final class Main {
                private Main() {}
                /** Prints the packaged module version for black-box launch verification. */
                public static void main(String[] arguments) {
                    for (String module : java.util.List.of("org.lwjgl.natives", "org.lwjgl.lz4.natives")) {
                        if (ModuleLayer.boot().findModule(module).isEmpty()) {
                            throw new IllegalStateException("Native root module is missing: " + module);
                        }
                    }
                    System.out.println("FIXTURE " + Main.class.getModule().getDescriptor().rawVersion().orElseThrow());
                }
            }
            """.trimIndent(),
        )
        write("build/windows-runtime/launch-policy.json", launchPolicy())
    }

    /** Builds, launches, and verifies the thin CLI and exact external runtime inputs. */
    @Test
    fun `builds and exercises the compatible thin application`() {
        val result =
            run(
                "--info",
                "--write-locks",
                ":jbsa-cli:verifyThinCliArtifact",
                ":jbsa-cli:smokeTestThinCli",
                ":jbsa-dist:verifyRuntimeDependencies",
            )

        val cliJar = projectDir.resolve("jbsa-cli/target/libs/jbsa-cli-0.1.0-SNAPSHOT.jar")
        assertTrue(Files.isRegularFile(cliJar), cliJar.toString())
        JarFile(cliJar.toFile()).use { archive ->
            archive.entries().asSequence().forEach { entry ->
                assertEquals(Instant.parse("2026-09-03T00:00:00Z"), entry.lastModifiedTime.toInstant())
            }
        }
        val descriptor =
            ModuleFinder.of(cliJar).find("io.github.evildarkarchon.jbsa.cli").orElseThrow().descriptor()
        assertFalse(descriptor.isAutomatic)
        assertEquals("0.1.0-SNAPSHOT", descriptor.rawVersion().orElseThrow().toString())
        assertTrue(descriptor.mainClass().isEmpty)
        assertEquals(emptySet<String>(), descriptor.exports().map { it.source() }.toSet())
        assertEquals(
            setOf("java.base", "io.github.evildarkarchon.jbsa"),
            descriptor.requires().map { it.name() }.toSet(),
        )
        assertTrue(result.output.contains("FIXTURE 0.1.0-SNAPSHOT"), result.output)
        assertTrue(result.output.contains("--illegal-native-access=deny"), result.output)
        assertTrue(
            result.output.contains(
                "--enable-native-access=io.github.evildarkarchon.jbsa,org.lwjgl,org.lwjgl.lz4"
            ),
            result.output,
        )
        assertTrue(
            result.output.contains("--add-modules=org.lwjgl.natives,org.lwjgl.lz4.natives"),
            result.output,
        )

        val runtimeDirectory = projectDir.resolve("jbsa-dist/target/runtime-dependencies")
        val expected =
            mapOf(
                "lwjgl-3.4.3.jar" to "46eeca5471833c3cf5da3c1da015b41e3bb3eb16dd3da03f366886da10096751",
                "lwjgl-lz4-3.4.3.jar" to "fd81606cbfdd7084cdbf576f6260087a2ae68bcfb53ca1ccbec0707ae603f876",
                "lwjgl-3.4.3-natives-windows.jar" to
                    "19949bca7b780f55e5d2db12ac061a7657a4c1b7c860c30607f5406e7017aa2b",
                "lwjgl-lz4-3.4.3-natives-windows.jar" to
                    "4980edf40520be80a7753bc791283edf303b936466dd47b633138b05887f0edc",
            )
        val actual =
            Files.list(runtimeDirectory).use { paths ->
                paths.iterator().asSequence().associate { path -> path.fileName.toString() to sha256(path) }
            }
        assertEquals(expected, actual)
    }

    /** Proves incidental application archives and launcher distributions stay unreachable. */
    @Test
    fun `disables and verifies incidental application distributions`() {
        val result =
            run(
                "--write-locks",
                ":jbsa-cli:startScripts",
                ":jbsa-cli:installDist",
                ":jbsa-cli:distZip",
                ":jbsa-cli:distTar",
                ":jbsa-cli:verifyThinApplicationOutputs",
            )

        listOf("startScripts", "installDist", "distZip", "distTar").forEach { task ->
            assertEquals(TaskOutcome.SKIPPED, result.task(":jbsa-cli:$task")?.outcome)
        }
        assertFalse(Files.exists(projectDir.resolve("jbsa-cli/target/distributions")))
        assertFalse(Files.exists(projectDir.resolve("jbsa-cli/target/install")))
        assertFalse(Files.exists(projectDir.resolve("jbsa-cli/target/scripts")))
    }

    /** Rejects a runtime artifact when the checked-in policy does not approve its exact bytes. */
    @Test
    fun `rejects changed runtime dependency hashes`() {
        write(
            "build/windows-runtime/launch-policy.json",
            launchPolicy().replace(
                "46eeca5471833c3cf5da3c1da015b41e3bb3eb16dd3da03f366886da10096751",
                "0000000000000000000000000000000000000000000000000000000000000000",
            ),
        )

        val result = runAndFail("--write-locks", ":jbsa-dist:verifyRuntimeDependencies")

        assertTrue(result.output.contains("External runtime dependencies changed"), result.output)
    }

    /** Rejects stale launcher or archive output even when its producing task remains disabled. */
    @Test
    fun `rejects stale incidental application outputs`() {
        write("jbsa-cli/target/distributions/rogue.zip", "not a canonical release input")

        val result = runAndFail(":jbsa-cli:verifyThinApplicationOutputs")

        assertTrue(result.output.contains("Incidental application outputs are prohibited"), result.output)
        assertTrue(result.output.contains("rogue.zip"), result.output)
    }

    /** Runs the fixture with the convention-plugin class path and repository verification disabled. */
    private fun run(vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("--dependency-verification=off", "--stacktrace", *arguments)
            .build()

    /** Runs a fixture build that is expected to reject an invalid thin-application contract. */
    private fun runAndFail(vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments("--dependency-verification=off", "--stacktrace", *arguments)
            .buildAndFail()

    /** Writes the centralized dependency pins required by the foundation policy. */
    private fun writeCatalog() {
        write(
            "gradle/libs.versions.toml",
            """
            [versions]
            junit = "6.1.3"
            lwjgl = "3.4.3"
            spotless = "8.10.2"

            [libraries]
            junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
            junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
            junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junit" }
            lwjgl = { module = "org.lwjgl:lwjgl", version.ref = "lwjgl" }
            lwjgl-lz4 = { module = "org.lwjgl:lwjgl-lz4", version.ref = "lwjgl" }

            [plugins]
            spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
            """.trimIndent(),
        )
    }

    /** Returns the approved launcher contract used by the runtime-input hash gate. */
    private fun launchPolicy(): String =
        """
        {
          "schemaVersion": 1,
          "platform": "windows-x64",
          "javaFeature": 25,
          "mainModule": "io.github.evildarkarchon.jbsa.cli",
          "mainClass": "io.github.evildarkarchon.jbsa.cli.Main",
          "runtimeArtifacts": [
            { "file": "lwjgl-3.4.3.jar", "sha256": "46eeca5471833c3cf5da3c1da015b41e3bb3eb16dd3da03f366886da10096751" },
            { "file": "lwjgl-lz4-3.4.3.jar", "sha256": "fd81606cbfdd7084cdbf576f6260087a2ae68bcfb53ca1ccbec0707ae603f876" },
            { "file": "lwjgl-3.4.3-natives-windows.jar", "sha256": "19949bca7b780f55e5d2db12ac061a7657a4c1b7c860c30607f5406e7017aa2b" },
            { "file": "lwjgl-lz4-3.4.3-natives-windows.jar", "sha256": "4980edf40520be80a7753bc791283edf303b936466dd47b633138b05887f0edc" }
          ]
        }
        """.trimIndent()

    /** Computes the lowercase SHA-256 digest of one copied runtime artifact. */
    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    /** Writes one UTF-8 fixture file and creates its parent directory. */
    private fun write(relativePath: String, content: String) {
        val path = projectDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
