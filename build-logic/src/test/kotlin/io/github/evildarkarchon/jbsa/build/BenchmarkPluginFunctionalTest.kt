package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.nio.file.Path
import java.util.jar.JarFile
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class BenchmarkPluginFunctionalTest {
    @TempDir lateinit var projectDir: Path

    /** Creates a six-project fixture with one JMH benchmark and two service providers. */
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
                exports fixture.library;
            }
            """.trimIndent(),
        )
        write(
            "jbsa/src/main/java/fixture/library/LibraryAnchor.java",
            """
            package fixture.library;
            /** Minimal library fixture included in the standalone benchmark. */
            public final class LibraryAnchor { private LibraryAnchor() {} }
            """.trimIndent(),
        )
        write(
            "jbsa-test-support/src/main/java/fixture/support/SupportProvider.java",
            """
            package fixture.support;
            /** Minimal build-only provider included in the standalone benchmark. */
            public final class SupportProvider { private SupportProvider() {} }
            """.trimIndent(),
        )
        write(
            "jbsa-test-support/src/main/resources/META-INF/services/fixture.Service",
            "fixture.support.SupportProvider\n",
        )
        write(
            "jbsa-benchmarks/src/main/java/fixture/BenchmarkProvider.java",
            """
            package fixture;
            /** Minimal benchmark-local service provider. */
            public final class BenchmarkProvider { private BenchmarkProvider() {} }
            """.trimIndent(),
        )
        write(
            "jbsa-benchmarks/src/main/java/fixture/SampleBenchmark.java",
            """
            package fixture;
            import org.openjdk.jmh.annotations.Benchmark;
            /** Minimal benchmark proving annotation processing is active. */
            public class SampleBenchmark {
                /** Supplies one observable JMH operation. */
                @Benchmark public int sample() { return 42; }
            }
            """.trimIndent(),
        )
        write(
            "jbsa-benchmarks/src/main/resources/META-INF/services/fixture.Service",
            "fixture.BenchmarkProvider\n",
        )
    }

    /** Verifies the compatible standalone artifact name, launcher, generated classes, services, and JPMS exclusion. */
    @Test
    fun `builds the compatible standalone benchmark artifact`() {
        run("--write-locks", ":jbsa-benchmarks:verifyBenchmarkArtifact")

        val artifact = projectDir.resolve("jbsa-benchmarks/target/jbsa-benchmarks-0.1.0-SNAPSHOT-standalone.jar")
        assertTrue(Files.isRegularFile(artifact), artifact.toString())
        JarFile(artifact.toFile()).use { jar ->
            assertEquals("org.openjdk.jmh.Main", jar.manifest.mainAttributes.getValue("Main-Class"))
            assertNotNull(jar.getJarEntry("fixture/SampleBenchmark.class"))
            assertNotNull(jar.getJarEntry("fixture/BenchmarkProvider.class"))
            assertNotNull(jar.getJarEntry("fixture/library/LibraryAnchor.class"))
            assertNotNull(jar.getJarEntry("fixture/support/SupportProvider.class"))
            assertNotNull(jar.getJarEntry("fixture/jmh_generated/SampleBenchmark_sample_jmhTest.class"))
            assertNotNull(jar.getJarEntry("org/openjdk/jmh/Main.class"))
            assertNotNull(jar.getJarEntry("META-INF/BenchmarkList"))
            assertNotNull(jar.getJarEntry("META-INF/CompilerHints"))
            assertFalse(jar.entries().asSequence().any { entry -> entry.name == "module-info.class" })
            assertFalse(jar.entries().asSequence().any { entry -> entry.name.endsWith("/module-info.class") })
            val providers =
                jar.getInputStream(jar.getJarEntry("META-INF/services/fixture.Service"))
                    .bufferedReader()
                    .readLines()
                    .filter { line -> line.isNotBlank() }
                    .toSet()
            assertEquals(setOf("fixture.BenchmarkProvider", "fixture.support.SupportProvider"), providers)
        }
    }

    /** Verifies the operational launcher reads the standalone manifest and discovers generated benchmarks. */
    @Test
    fun `smoke tests the standalone benchmark launcher without running measurements`() {
        val result = run("--write-locks", ":jbsa-benchmarks:smokeTestBenchmarkLauncher")

        assertTrue(result.output.contains("fixture.SampleBenchmark.sample"), result.output)
    }

    /** Runs the fixture with the convention-plugin class path and repository verification disabled. */
    private fun run(vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(TestKitBuildArguments.create(projectDir, arguments))
            .build()

    /** Runs a fixture build that is expected to reject benchmark release-input leakage. */
    private fun runAndFail(vararg arguments: String) =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(TestKitBuildArguments.create(projectDir, arguments))
            .buildAndFail()

    /** Writes the dependency and plugin pins used by the benchmark convention. */
    private fun writeCatalog() {
        write(
            "gradle/libs.versions.toml",
            """
            [versions]
            jackson = "2.22.1"
            jlibdeflate = "0.1.0"
            jmh = "1.37"
            junit = "6.1.3"
            lwjgl = "3.4.3"
            lz4-java = "1.12.0"
            snakeyaml = "2.5"
            shadow = "9.6.1"
            spotless = "8.10.2"

            [libraries]
            jackson-yaml = { module = "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", version.ref = "jackson" }
            snakeyaml = { module = "org.yaml:snakeyaml", version.ref = "snakeyaml" }
            jlibdeflate = { module = "com.fulcrumgenomics:jlibdeflate", version.ref = "jlibdeflate" }
            jmh-core = { module = "org.openjdk.jmh:jmh-core", version.ref = "jmh" }
            jmh-generator = { module = "org.openjdk.jmh:jmh-generator-annprocess", version.ref = "jmh" }
            junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
            junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
            junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junit" }
            lwjgl = { module = "org.lwjgl:lwjgl", version.ref = "lwjgl" }
            lwjgl-lz4 = { module = "org.lwjgl:lwjgl-lz4", version.ref = "lwjgl" }
            lz4-java = { module = "at.yawk.lz4:lz4-java", version.ref = "lz4-java" }
            shadow-gradle-plugin = { module = "com.gradleup.shadow:shadow-gradle-plugin", version.ref = "shadow" }

            [plugins]
            shadow = { id = "com.gradleup.shadow", version.ref = "shadow" }
            spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
            """.trimIndent(),
        )
    }

    /** Writes one UTF-8 fixture file and creates its parent directory. */
    private fun write(relativePath: String, content: String) {
        val path = projectDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
