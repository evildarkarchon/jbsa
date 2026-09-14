package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.nio.file.Path
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir

class ConformancePluginFunctionalTest {
    @TempDir lateinit var projectDir: Path

    /** Creates the six-project build and tagged public tests used by each conformance behavior check. */
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
            "jbsa/src/main/java/io/github/evildarkarchon/jbsa/Sample.java",
            """
            package io.github.evildarkarchon.jbsa;
            /** Minimal public-library fixture. */
            public final class Sample { private Sample() {} }
            """.trimIndent(),
        )
        write(
            "jbsa-cli/src/main/java/io/github/evildarkarchon/jbsa/cli/Main.java",
            """
            package io.github.evildarkarchon.jbsa.cli;
            /** Minimal thin-application fixture. */
            public final class Main { private Main() {} }
            """.trimIndent(),
        )
        write(
            "jbsa-test-support/src/main/java/io/github/evildarkarchon/jbsa/fixtures/FixtureSupport.java",
            """
            package io.github.evildarkarchon.jbsa.fixtures;
            /** Minimal build-only fixture support. */
            public final class FixtureSupport { private FixtureSupport() {} }
            """.trimIndent(),
        )
        writeTaggedTest("ContractFixtureIT", "contract", verifyEnvironment = true)
        writeTaggedTest("Tes3FixtureIT", "tes3")
        writeTaggedTest("BsaFixtureIT", "bsa")
        writeTaggedTest("HarnessFixtureIT", "conformance-harness")
        writeTaggedTest("AssuranceFixtureIT", "assurance-plan")
        writeTaggedTest("PerformanceFixtureIT", "performance-harness")
        write("tests/conformance/catalog.json", "{}\n")
        write("tests/conformance/contradictions.json", "{}\n")
        write("tests/fixtures/synthetic/manifest.json", "{}\n")
        write("jbsa/src/main/resources/META-INF/jbsa-codec-profile.json", "{}\n")
        listOf(
                "conformance-catalog.ps1",
                "conformance-evidence.ps1",
                "conformance-adapters.ps1",
                "conformance-execution.ps1",
                "verify-fixture-corpus.ps1",
            )
            .forEach { write("build/$it", "# fixture input\n") }
    }

    /** Runs each public tag seam once and verifies the inherited deterministic/native process contract. */
    @Test
    fun `runs portable and tagged conformance tests through disjoint tasks`() {
        val result =
            run(
                "--write-locks",
                ":jbsa-conformance-tests:integrationTest",
                ":jbsa-conformance-tests:tes3ConformanceTest",
                ":jbsa-conformance-tests:bsaConformanceTest",
                ":jbsa-conformance-tests:conformanceHarnessTest",
                ":jbsa-conformance-tests:performanceHarnessTest",
            )

        val executions = Files.readAllLines(projectDir.resolve("conformance-executions.txt"))
        assertEquals(
            setOf("ContractFixtureIT", "Tes3FixtureIT", "BsaFixtureIT", "HarnessFixtureIT", "PerformanceFixtureIT"),
            executions.toSet(),
        )
        assertEquals(5, executions.size)
        listOf(
                "integrationTest",
                "tes3ConformanceTest",
                "bsaConformanceTest",
                "conformanceHarnessTest",
                "performanceHarnessTest",
            )
            .forEach { task -> assertTrue(result.task(":jbsa-conformance-tests:$task") != null, task) }
    }

    /** Runs the compact assurance shadow gate from the plan and available Archive Family checks. */
    @Test
    fun `automated assurance aggregates the compact plan and available family tests`() {
        val result = run("--write-locks", ":jbsa-conformance-tests:automatedAssurance")

        val executions = Files.readAllLines(projectDir.resolve("conformance-executions.txt"))
        assertEquals(
            setOf("ContractFixtureIT", "AssuranceFixtureIT", "Tes3FixtureIT", "BsaFixtureIT"),
            executions.toSet(),
        )
        assertEquals(4, executions.size)
        listOf("assurancePlanTest", "tes3ConformanceTest", "bsaConformanceTest")
            .forEach { task -> assertTrue(result.task(":jbsa-conformance-tests:$task") != null, task) }
        assertTrue(result.task(":jbsa:test") != null)
        assertTrue(result.task(":jbsa-cli:test") != null)
        listOf(
                "assurancePlanValidation",
                "assuranceCoverageComparison",
                "assuranceHistoryVerification",
                "assuranceEvidenceCapsule",
            )
            .forEach { task -> assertTrue(result.task(":jbsa-conformance-tests:$task") != null, task) }
        assertTrue(result.task(":jbsa-conformance-tests:automatedAssurance") != null)
        assertTrue(result.task(":jbsa-conformance-tests:captureAutomatedConformance") == null)
    }

    /** Runs only the Archive Family task selected by a validated affected-tier manifest. */
    @Test
    fun `automated assurance consumes an affected family selection`() {
        write(
            "target/assurance-selection.json",
            """
            {"assurance_scenarios":[{"family":"tes3","assurance_scenario_id":"tes3:decode-entries"}]}
            """.trimIndent(),
        )

        val result =
            run(
                "--write-locks",
                "-PjbsaAssuranceSelection=target/assurance-selection.json",
                ":jbsa-conformance-tests:automatedAssurance",
            )

        val executions = Files.readAllLines(projectDir.resolve("conformance-executions.txt"))
        assertEquals(setOf("ContractFixtureIT", "AssuranceFixtureIT", "Tes3FixtureIT"), executions.toSet())
        assertTrue(result.task(":jbsa-conformance-tests:tes3ConformanceTest") != null)
        assertTrue(result.task(":jbsa-conformance-tests:bsaConformanceTest") == null)
        assertTrue(result.task(":jbsa-conformance-tests:ba2ConformanceTest") == null)
    }

    /** Verifies explicit local-evidence opt-ins reach the forked JVM selected by a Gradle task. */
    @Test
    fun `forwards supported local evidence system properties`() {
        writeTaggedTest("BsaFixtureIT", "bsa", expectedSystemProperty = "jbsa.bsa.local")

        run("--write-locks", "-Djbsa.bsa.local=true", ":jbsa-conformance-tests:bsaConformanceTest")

        assertEquals("BsaFixtureIT", Files.readString(projectDir.resolve("conformance-executions.txt")).trim())
    }

    /** Keeps the conformance project out of install, publication, and product runtime graphs. */
    @Test
    fun `keeps conformance build only and outside production dependencies`() {
        val tasks = run(":jbsa-conformance-tests:tasks", "--all")
        val runtime = run("--write-locks", ":jbsa:dependencies", "--configuration", "runtimeClasspath")

        assertFalse(tasks.output.contains("publishToMavenLocal"), tasks.output)
        assertFalse(tasks.output.contains("installDist"), tasks.output)
        assertFalse(runtime.output.contains("jbsa-conformance-tests"), runtime.output)
    }

    /** Accepts exit 1 as trustworthy non-passing evidence and retains its report and result marker. */
    @Test
    fun `preserves expected non passing evidence`() {
        writeConformanceRunner(1)

        val result = run("--write-locks", ":jbsa-conformance-tests:automatedConformance")

        assertTrue(Files.isRegularFile(projectDir.resolve("target/conformance/report.json")))
        assertEquals("1", Files.readString(projectDir.resolve("target/conformance-exit-code.txt")).trim())
        assertTrue(result.output.contains("trustworthy non-passing Conformance Case evidence"), result.output)
    }

    /** Raises exit 2 as infrastructure failure only after the subprocess evidence has been retained. */
    @Test
    fun `preserves evidence before propagating infrastructure failure`() {
        writeConformanceRunner(2)

        val result = runAndFail("--write-locks", ":jbsa-conformance-tests:automatedConformance")

        assertTrue(Files.isRegularFile(projectDir.resolve("target/conformance/report.json")))
        assertEquals("2", Files.readString(projectDir.resolve("target/conformance-exit-code.txt")).trim())
        assertTrue(result.output.contains("infrastructure failure with exit code 2"), result.output)
    }

    /** Writes one JUnit integration class whose tag and optional process assertions are externally observable. */
    private fun writeTaggedTest(
        className: String,
        tag: String,
        verifyEnvironment: Boolean = false,
        expectedSystemProperty: String? = null,
    ) {
        val environmentAssertions =
            if (verifyEnvironment) {
                """
                org.junit.jupiter.api.Assertions.assertEquals("UTC", java.util.TimeZone.getDefault().getID());
                org.junit.jupiter.api.Assertions.assertEquals("en", java.util.Locale.getDefault().getLanguage());
                org.junit.jupiter.api.Assertions.assertEquals("US", java.util.Locale.getDefault().getCountry());
                org.junit.jupiter.api.Assertions.assertEquals("UTF-8", System.getProperty("file.encoding"));
                org.junit.jupiter.api.Assertions.assertEquals(
                    "org.junit.jupiter.api.ClassOrderer${'$'}ClassName",
                    System.getProperty("junit.jupiter.testclass.order.default"));
                org.junit.jupiter.api.Assertions.assertTrue(
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments()
                        .contains("--enable-native-access=ALL-UNNAMED"));
                for (String property : java.util.List.of(
                        "jbsa.library.jar", "jbsa.library.sourcesJar", "jbsa.library.javadocJar",
                        "jbsa.library.consumerPom", "jbsa.cli.jar")) {
                    org.junit.jupiter.api.Assertions.assertTrue(java.nio.file.Files.isRegularFile(java.nio.file.Path.of(System.getProperty(property))), property);
                }
                """.trimIndent()
            } else {
                ""
            }
        val systemPropertyAssertion =
            expectedSystemProperty?.let { property ->
                "org.junit.jupiter.api.Assertions.assertEquals(\"true\", System.getProperty(\"$property\"));"
            } ?: ""
        write(
            "jbsa-conformance-tests/src/test/java/io/github/evildarkarchon/jbsa/verification/$className.java",
            """
            package io.github.evildarkarchon.jbsa.verification;
            import java.nio.file.Files;
            import java.nio.file.Path;
            import java.nio.file.StandardOpenOption;
            import org.junit.jupiter.api.Tag;
            import org.junit.jupiter.api.Test;
            @Tag("$tag")
            final class $className {
                /** Records this tag-selected execution and validates the inherited process contract. */
                @Test void recordsExecution() throws Exception {
                    $environmentAssertions
                    $systemPropertyAssertion
                    Files.writeString(
                        Path.of(System.getProperty("jbsa.reactor.root"), "conformance-executions.txt"),
                        "$className\n", StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                }
            }
            """.trimIndent(),
        )
    }

    /** Replaces the public runner with a deterministic process fixture that writes evidence then exits. */
    private fun writeConformanceRunner(exitCode: Int) {
        write(
            "build/run-conformance.ps1",
            """
            param(
                [string] ${'$'}RepositoryRoot,
                [string] ${'$'}OutputDirectory,
                [string] ${'$'}Mode,
                [string] ${'$'}LibraryArtifactPath,
                [string] ${'$'}CliArtifactPath,
                [string] ${'$'}CodecProfilePath
            )
            [void][IO.Directory]::CreateDirectory(${'$'}OutputDirectory)
            [IO.File]::WriteAllText((Join-Path ${'$'}OutputDirectory 'report.json'), '{"fixture":true}')
            exit $exitCode
            """.trimIndent(),
        )
    }

    /** Runs the fixture with the convention-plugin class path and repository verification disabled. */
    private fun run(vararg arguments: String) = runner(arguments).build()

    /** Runs a fixture task whose public contract requires a Gradle failure. */
    private fun runAndFail(vararg arguments: String) = runner(arguments).buildAndFail()

    /** Creates one TestKit runner with stable diagnostics. */
    private fun runner(arguments: Array<out String>): GradleRunner =
        GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(TestKitBuildArguments.create(arguments))

    /** Writes the fixture's complete centrally pinned dependency catalog. */
    private fun writeCatalog() {
        write(
            "gradle/libs.versions.toml",
            """
            [versions]
            jackson = "2.22.1"
            jmh = "1.37"
            junit = "6.1.3"
            lwjgl = "3.4.3"
            snakeyaml = "2.5"

            [libraries]
            jackson-yaml = { module = "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", version.ref = "jackson" }
            jmh-core = { module = "org.openjdk.jmh:jmh-core", version.ref = "jmh" }
            jmh-generator = { module = "org.openjdk.jmh:jmh-generator-annprocess", version.ref = "jmh" }
            junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
            junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
            junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junit" }
            lwjgl = { module = "org.lwjgl:lwjgl", version.ref = "lwjgl" }
            lwjgl-lz4 = { module = "org.lwjgl:lwjgl-lz4", version.ref = "lwjgl" }
            snakeyaml = { module = "org.yaml:snakeyaml", version.ref = "snakeyaml" }

            [plugins]
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
