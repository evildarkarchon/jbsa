package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import javax.xml.parsers.DocumentBuilderFactory
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class GradleFoundationFilesTest {
    private val repositoryRoot = Path.of(requireNotNull(System.getProperty("jbsa.repositoryRoot")))

    /** Verifies wrapper identities, line endings, and Git attributes against the approved specification. */
    @Test
    fun `pins and classifies both Gradle wrapper launchers`() {
        assertEquals(
            "7a9ce74cff467ca1bf60a4fcd9f05185acceda4d0f382434d393e17864262c5d",
            sha256(repositoryRoot.resolve("gradle/wrapper/gradle-wrapper.jar")),
        )
        val properties = Files.readString(repositoryRoot.resolve("gradle/wrapper/gradle-wrapper.properties"))
        assertTrue(properties.contains("gradle-9.7.1-bin.zip"))
        assertTrue(
            properties.contains(
                "distributionSha256Sum=acd53f1edaf02f1a8ff99879f8a34b302661a057d9b063ae9e35b552f804d20a"
            )
        )

        val shellBytes = Files.readAllBytes(repositoryRoot.resolve("gradlew"))
        assertFalse(shellBytes.any { it == '\r'.code.toByte() })
        assertTrue(shellBytes.any { it == '\n'.code.toByte() })

        val batchBytes = Files.readAllBytes(repositoryRoot.resolve("gradlew.bat"))
        batchBytes.forEachIndexed { index, byte ->
            if (byte == '\n'.code.toByte()) {
                assertTrue(index > 0 && batchBytes[index - 1] == '\r'.code.toByte())
            }
            if (byte == '\r'.code.toByte()) {
                assertTrue(index + 1 < batchBytes.size && batchBytes[index + 1] == '\n'.code.toByte())
            }
        }

        val attributes =
            runCommand(
                listOf(
                    "git",
                    "check-attr",
                    "text",
                    "eol",
                    "--",
                    "gradlew",
                    "gradlew.bat",
                    "gradle/wrapper/gradle-wrapper.jar",
                )
            )
        assertTrue(attributes.contains("gradlew: eol: lf"))
        assertTrue(attributes.contains("gradlew.bat: eol: crlf"))
        assertTrue(attributes.contains("gradle/wrapper/gradle-wrapper.jar: text: unset"))
        assertTrue(runCommand(listOf("git", "ls-files", "--stage", "--", "gradlew")).startsWith("100755 "))
    }

    /** Verifies each launcher supported by the current host starts the exact pinned Gradle release. */
    @Test
    fun `launches the supported platform wrapper`() {
        val commands =
            if (System.getProperty("os.name").startsWith("Windows")) {
                listOf(
                    listOf("cmd.exe", "/d", "/c", ".\\gradlew.bat", "--version", "--no-daemon"),
                    listOf(gitBash().toString(), "./gradlew", "--version", "--no-daemon"),
                )
            } else {
                listOf(listOf("./gradlew", "--version", "--no-daemon"))
            }

        commands.forEach { command -> assertTrue(runCommand(command).contains("Gradle 9.7.1")) }
    }

    /** Verifies catalog pinning, execution settings, locks, and one reviewed checksum per artifact. */
    @Test
    fun `commits deterministic catalog lock and verification policy`() {
        val catalog = Files.readString(repositoryRoot.resolve("gradle/libs.versions.toml"))
        listOf("cyclonedx", "google-java-format", "jmh", "junit", "kotlin", "lwjgl", "shadow", "spotless")
            .forEach { alias -> assertTrue(catalog.contains("$alias = \""), "Missing pinned version $alias") }
        assertFalse(catalog.contains("latest."))
        assertFalse(catalog.contains("+\""))
        listOf("org.cyclonedx.bom", "com.gradleup.shadow", "com.diffplug.spotless")
            .forEach { pluginId -> assertTrue(catalog.contains("id = \"$pluginId\"")) }
        val includedBuild = Files.readString(repositoryRoot.resolve("build-logic/build.gradle.kts"))
        val rootBuild = Files.readString(repositoryRoot.resolve("build.gradle.kts"))
        val includedSettings = Files.readString(repositoryRoot.resolve("build-logic/settings.gradle.kts"))
        assertTrue(catalog.contains("shadow-gradle-plugin = { module = \"com.gradleup.shadow:shadow-gradle-plugin\""))
        assertTrue(includedBuild.contains("implementation(libs.shadow.gradle.plugin)"))
        assertTrue(rootBuild.contains("alias(libs.plugins.cyclonedx)"))
        assertTrue(includedSettings.contains("forRepository { gradlePluginPortal() }"))
        assertTrue(includedSettings.contains("includeModule(\"com.gradleup.shadow\", \"shadow-gradle-plugin\")"))
        assertTrue(includedBuild.contains("testRuntimeOnly(libs.junit.platform.launcher)"))
        assertTrue(includedBuild.contains("requested in catalogDependencyPins"))

        listOf(repositoryRoot.resolve("gradle.properties"), repositoryRoot.resolve("build-logic/gradle.properties"))
            .forEach { path ->
                val properties = Files.readString(path)
                assertTrue(properties.contains("org.gradle.parallel=false"))
                assertTrue(properties.contains("org.gradle.configuration-cache=false"))
                assertTrue(properties.contains("org.gradle.caching=false"))
                assertTrue(properties.contains("org.gradle.java.installations.auto-download=false"))
                assertFalse(properties.contains("develocity", ignoreCase = true))
                assertFalse(properties.contains("scan", ignoreCase = true))
                assertFalse(properties.contains("telemetry", ignoreCase = true))
            }

        val includedLock = Files.readString(repositoryRoot.resolve("build-logic/gradle.lockfile"))
        val rootLock = Files.readString(repositoryRoot.resolve("gradle.lockfile"))
        val verificationMetadata = Files.readString(repositoryRoot.resolve("gradle/verification-metadata.xml"))
        assertTrue(rootLock.contains("empty=cyclonedxBom"))
        assertTrue(verificationMetadata.contains("cyclonedx-gradle-plugin-3.4.1.jar"))
        assertTrue(includedLock.contains("junit-jupiter:6.1.3"))
        assertTrue(includedLock.contains("shadow-gradle-plugin:9.6.1"))
        val benchmarkLock = Files.readString(repositoryRoot.resolve("jbsa-benchmarks/gradle.lockfile"))
        assertTrue(benchmarkLock.contains("jmh-core:1.37"))
        assertTrue(benchmarkLock.contains("jmh-generator-annprocess:1.37"))
        assertTrue(Files.exists(repositoryRoot.resolve("settings-gradle.lockfile")))
        assertTrue(Files.exists(repositoryRoot.resolve("build-logic/settings-gradle.lockfile")))

        listOf(
                repositoryRoot.resolve("gradle/verification-metadata.xml"),
                repositoryRoot.resolve("build-logic/gradle/verification-metadata.xml"),
            )
            .forEach { assertSingleChecksumPerArtifact(it) }

        val review =
            Files.readString(repositoryRoot.resolve(".scratch/migrate-maven-to-gradle/dependency-verification-review.md"))
        assertTrue(review.contains(sha256(repositoryRoot.resolve("gradle/verification-metadata.xml"))))
        assertTrue(review.contains(sha256(repositoryRoot.resolve("build-logic/gradle/verification-metadata.xml"))))
        assertTrue(review.contains(sha256(repositoryRoot.resolve("build-logic/gradle.lockfile"))))
        assertTrue(review.contains(sha256(repositoryRoot.resolve("gradle/libs.versions.toml"))))
        assertTrue(review.contains("All 124 values matched; failures: 0."))
    }

    /** Returns the lowercase SHA-256 digest of one committed wrapper artifact. */
    private fun sha256(path: Path): String =
        MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(path)).joinToString("") { "%02x".format(it) }

    /** Executes a repository command and fails the test with its combined output on nonzero exit. */
    private fun runCommand(command: List<String>): String {
        val process = ProcessBuilder(command).directory(repositoryRoot.toFile()).redirectErrorStream(true).start()
        val output = process.inputStream.bufferedReader().use { it.readText() }
        assertEquals(0, process.waitFor(), output)
        return output
    }

    /** Locates Git for Windows' POSIX shell without accepting the unrelated WSL launcher on PATH. */
    private fun gitBash(): Path {
        val gitExecPath = Path.of(runCommand(listOf("git", "--exec-path")).trim())
        val bash = gitExecPath.parent.parent.parent.resolve("bin/bash.exe")
        assertTrue(Files.isRegularFile(bash), "Git Bash not found at $bash")
        return bash
    }

    /** Verifies strict metadata contains exactly one SHA-256 trust decision for every artifact. */
    private fun assertSingleChecksumPerArtifact(path: Path) {
        val factory = DocumentBuilderFactory.newInstance()
        factory.isNamespaceAware = true
        val document = Files.newInputStream(path).use { factory.newDocumentBuilder().parse(it) }
        val artifacts = document.getElementsByTagNameNS("*", "artifact")
        assertTrue(artifacts.length > 0, "$path must verify at least one artifact")
        repeat(artifacts.length) { index ->
            val artifact = artifacts.item(index)
            val checksums = artifact.childNodes
            val sha256Count =
                (0 until checksums.length).count { childIndex ->
                    val child = checksums.item(childIndex)
                    child.nodeType == org.w3c.dom.Node.ELEMENT_NODE && child.localName == "sha256"
                }
            assertEquals(1, sha256Count, "${artifact.attributes.getNamedItem("name").nodeValue} must have one SHA-256")
        }
    }
}
