package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import groovy.json.JsonSlurper
import org.gradle.testkit.runner.GradleRunner
import org.junit.jupiter.api.Assertions.assertEquals
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
        write(
            "build/active-maven-reference-allowlist.properties",
            "allowlistVersion=1\nentryCount=0\n",
        )
        writeCatalog()
        write(
            "build.gradle.kts",
            """
            import org.cyclonedx.gradle.CyclonedxDirectTask

            plugins { id("jbsa.foundation"); alias(libs.plugins.cyclonedx) }

            project(":jbsa").tasks.named<CyclonedxDirectTask>("cyclonedxDirectBom") {
                includeConfigs = listOf("runtimeClasspath")
                includeMetadataResolution = false
                includeBomSerialNumber = false
                includeBuildSystem = false
                xmlOutput.unsetConvention()
            }
            """.trimIndent(),
        )
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

        assertTrue(result.output.contains("External plugin 'com.diffplug.spotless' must use its pinned version"))
    }

    /** Verifies an external plugin cannot override the version owned by the central catalog. */
    @Test
    fun `rejects an external plugin version outside the catalog`() {
        write(
            "build.gradle.kts",
            """
            plugins {
                id("jbsa.foundation")
                id("com.diffplug.spotless") version "1.+"
            }
            """.trimIndent(),
        )

        val result = runAndFail("help")

        assertTrue(result.output.contains("the catalog pins '8.10.2'"), result.output)
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

    /** Verifies direct external dependency versions remain owned by the central catalog. */
    @Test
    fun `rejects a pinned dependency outside the catalog`() {
        declareProbe("org.opentest4j:opentest4j:1.3.0")

        val result = runAndFail("--write-locks", "resolveProbe")

        assertTrue(result.output.contains("is not declared in gradle/libs.versions.toml"), result.output)
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

    /** Verifies strict checksum metadata rejects an external plugin implementation before use. */
    @Test
    fun `rejects an unverified plugin artifact`() {
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
        write(
            "build.gradle.kts",
            """
            plugins {
                id("jbsa.foundation")
                id("com.diffplug.spotless") version "8.10.2"
            }
            """.trimIndent(),
        )

        val result = runStrictAndFail("help")

        assertTrue(result.output.contains("Dependency verification failed"), result.output)
        assertTrue(result.output.contains("spotless"), result.output)
    }

    /** Verifies process and evidence tasks reject configuration-cache mode at the settings seam. */
    @Test
    fun `rejects configuration cache for incompatible tasks`() {
        val result = runAndFail("--configuration-cache", "verify")

        assertTrue(
            result.output.contains(
                "Configuration cache is not supported for evidence, staging, or external-process tasks: verify"
            ),
            result.output,
        )
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
        writeCatalog(includeConflictPin = true)
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
        val expectedDigest = sha256(sentinel.toByteArray())
        write("build/automation-sentinel.txt", sentinel)
        write("build/automation-sentinel.sha256", "$expectedDigest\n")
        write("target/generated.txt", "generated")
        write("jbsa/target/generated.txt", "generated")

        run("clean")

        assertTrue(Files.readString(projectDir.resolve("build/automation-sentinel.txt")) == sentinel)
        assertTrue(Files.readString(projectDir.resolve("build/automation-sentinel.sha256")) == "$expectedDigest\n")
        assertTrue(sha256(Files.readAllBytes(projectDir.resolve("build/automation-sentinel.txt"))) == expectedDigest)
        assertFalse(Files.exists(projectDir.resolve("target")))
        assertFalse(Files.exists(projectDir.resolve("jbsa/target")))
    }

    /** Verifies active contributor instructions cannot reintroduce a Maven wrapper command. */
    @Test
    fun `rejects stale active Maven instructions with their source location`() {
        write("docs/development.md", "Run `.\\mvnw.cmd -B clean verify` before committing.\n")

        val result = runAndFail("verifyActiveReferences")

        assertTrue(result.output.contains("docs/development.md:1"), result.output)
        assertTrue(result.output.contains(".\\mvnw.cmd -B clean verify"), result.output)
    }

    /** Verifies a reviewed historical exception is bound to the exact preserved instruction. */
    @Test
    fun `accepts an exact reviewed historical reference`() {
        val historical = "The archived procedure ran `mvn -B verify`."
        write("docs/reviews/historical-build.md", "$historical\n")
        writeSingleReferenceAllowlist(
            "docs/reviews/historical-build.md",
            historical,
            "historical-provenance",
            "Preserves the reviewed command as historical evidence.",
        )

        run("verifyActiveReferences")
    }

    /** Verifies an edited historical instruction cannot reuse approval for different bytes. */
    @Test
    fun `rejects a changed allowlisted reference`() {
        val reviewed = "The archived procedure ran `mvn -B verify`."
        write("docs/reviews/historical-build.md", "The archived procedure ran `mvn -B clean verify`.\n")
        writeSingleReferenceAllowlist(
            "docs/reviews/historical-build.md",
            reviewed,
            "historical-provenance",
            "Preserves the reviewed command as historical evidence.",
        )

        val result = runAndFail("verifyActiveReferences")

        assertTrue(result.output.contains("does not match any prohibited line"), result.output)
    }

    /** Verifies the completed cutover cannot retain temporary comparison exceptions. */
    @Test
    fun `rejects temporary comparison exceptions after cutover`() {
        val comparison = "& .\\mvnw.cmd -B verify"
        write("build/compare-builds.ps1", "$comparison\n")
        writeSingleReferenceAllowlist(
            "build/compare-builds.ps1",
            comparison,
            "migration-comparison",
            "Retains the parity oracle only until the coherent cutover.",
        )

        val result = runAndFail("verifyActiveReferences")

        assertTrue(result.output.contains("Unsupported active-reference allowlist category"), result.output)
    }

    /** Verifies the cutover gate rejects a reintroduced legacy build descriptor. */
    @Test
    fun `rejects active legacy build descriptors after cutover`() {
        val descriptor = "pom" + ".xml"
        write(descriptor, "<project/>\n")

        val result = runAndFail("verifyActiveReferences")

        assertTrue(result.output.contains(descriptor), result.output)
        assertTrue(result.output.contains("Active legacy build files are prohibited"), result.output)
    }

    /** Verifies every ticket-defined active surface participates in one repository scan. */
    @Test
    fun `scans specifications documentation automation CI tests and local issues`() {
        val paths =
            listOf(
                ".scratch/migration/spec.md",
                ".scratch/migration/issues/11-active-references.md",
                ".github/workflows/reference.yml",
                "build/reference-probe.ps1",
                "docs/reference-probe.md",
                "tests/reference-probe.md",
            )
        paths.forEach { path -> write(path, "Run `.\\mvnw.cmd -B verify` here.\n") }

        val result = runAndFail("verifyActiveReferences")

        paths.forEach { path -> assertTrue(result.output.contains("$path:1"), result.output) }
    }

    /** Verifies alternate automation entry points and production build logic cannot bypass the scan. */
    @Test
    fun `scans shell batch actions project scripts and production build logic`() {
        val paths =
            listOf(
                ".github/actions/reference/action.yml",
                "build/reference-probe.sh",
                "build/reference-probe.cmd",
                "build/reference-probe.bat",
                "build-logic/src/main/kotlin/example/ReferenceProbe.kt",
                "jbsa/build.gradle.kts",
                "jbsa-benchmarks/README.md",
                ".github/pull_request_template.md",
            )
        paths.forEach { path ->
            val prefix = if (path.endsWith(".kt") || path.endsWith(".kts")) "// " else ""
            write(path, "${prefix}Run mvn.cmd verify here.\n")
        }

        val result = runAndFail("verifyActiveReferences")

        paths.forEach { path -> assertTrue(result.output.contains("$path:1"), result.output) }
    }

    /** Verifies accurate ecosystem terminology is not mistaken for an active build instruction. */
    @Test
    fun `preserves consumer POM requirements and the Maven Central proper name`() {
        write(
            "docs/dependency-metadata.md",
            "The consumer POM retains accurate dependencies resolved from Maven Central.\n",
        )

        run("verifyActiveReferences")
    }

    /** Verifies complete verification orders canonical inputs, pre-audit, staging, and post-audit. */
    @Test
    fun `complete verification orders the release staging lifecycle`() {
        val result = run("--write-locks", "verify", "--dry-run")
        val taskPaths = taskPathsFromDryRun(result.output)

        assertTaskPrecedes(taskPaths, ":jbsa:assembleLibraryPublication", ":jbsa-dist:stageReleaseInputs")
        assertTaskPrecedes(taskPaths, ":jbsa-cli:jar", ":jbsa-dist:stageReleaseInputs")
        assertTaskPrecedes(taskPaths, ":jbsa-dist:stageRuntimeDependencies", ":jbsa-dist:stageReleaseInputs")
        assertTaskPrecedes(taskPaths, ":verifyCompliance", ":jbsa-dist:stageReleaseInputs")
        assertTaskPrecedes(taskPaths, ":jbsa-dist:stageReleaseInputs", ":jbsa-dist:verifyStagedReleaseInputs")
        assertTaskPrecedes(taskPaths, ":jbsa-dist:verifyStagedReleaseInputs", ":verify")
        assertTaskPrecedes(taskPaths, ":verifyActiveReferences", ":verify")
    }

    /** Verifies ordinary Gradle lifecycles do not silently expand into full release qualification. */
    @Test
    fun `assemble check and build exclude complete release staging`() {
        val result = run("assemble", "check", "build", "--dry-run")
        val taskPaths = taskPathsFromDryRun(result.output)

        assertFalse(taskPaths.any { it.endsWith(":stageReleaseInputs") }, taskPaths.toString())
        assertFalse(taskPaths.any { it.endsWith(":verifyStagedReleaseInputs") }, taskPaths.toString())
        assertFalse(taskPaths.contains(":verifyCompliance"), taskPaths.toString())
        assertFalse(taskPaths.any { it.endsWith(":automatedConformance") }, taskPaths.toString())
    }

    /** Verifies the post-staging audit cannot bypass the stage that owns its required input tree. */
    @Test
    fun `post-staging audit rejects an absent staging output`() {
        val result =
            runAndFail(
                ":jbsa-dist:verifyStagedReleaseInputs",
                "-x",
                ":jbsa-dist:stageReleaseInputs",
                "-x",
                ":verifyCompliance",
                "-x",
                ":generateBuildLayoutManifest",
                "-x",
                ":generateResolvedProductionDependencies",
                "-x",
                ":generateProductionSbom",
                "-x",
                ":jbsa:generatePomFileForLibraryPublication",
            )

        assertTrue(result.output.contains("release-inputs"), result.output)
        assertFalse(result.output.contains("Task 'verifyStagedReleaseInputs' not found"), result.output)
    }

    /** Verifies staging passes its declared PowerShell arguments and propagates a nonzero process exit. */
    @Test
    fun `release staging propagates its PowerShell failure`() {
        configureStageProbe(
            """
            param([string] ${'$'}ReactorVersion, [string] ${'$'}BuildLayoutManifest)
            if (${'$'}ReactorVersion -cne '2.3.4' -or
                (Split-Path -Leaf ${'$'}BuildLayoutManifest) -cne 'stage-layout.json') {
                Write-Error 'staging process contract mismatch'
                exit 18
            }
            Write-Error 'intentional staging process failure'
            exit 19
            """.trimIndent(),
        )

        val result = runAndFail(":jbsa-dist:stageReleaseInputs")

        assertTrue(result.output.contains("intentional staging process failure"), result.output)
        assertFalse(result.output.contains("staging process contract mismatch"), result.output)
    }

    /** Verifies staging rejects a successful process that omits its declared transaction outputs. */
    @Test
    fun `release staging rejects missing outputs after a successful process`() {
        configureStageProbe("param()`nexit 0")

        val result = runAndFail(":jbsa-dist:stageReleaseInputs")

        assertTrue(
            result.output.contains("Release staging completed without its declared directory and manifest outputs"),
            result.output,
        )
    }

    /** Verifies post-staging audit passes every switch/path contract and propagates a rejected audit. */
    @Test
    fun `post-staging audit propagates its PowerShell failure`() {
        configureAuditProbe()

        val result = runAndFail(":jbsa-dist:verifyStagedReleaseInputs")

        assertTrue(result.output.contains("intentional post-staging audit failure"), result.output)
        assertFalse(result.output.contains("audit process contract mismatch"), result.output)
    }

    /** Verifies the public task emits the versioned, contained output contract consumed by automation. */
    @Test
    fun `generates the deterministic build layout manifest`() {
        run("generateBuildLayoutManifest")

        val manifestPath = projectDir.resolve("target/compliance/build-layout.json")
        val firstBytes = Files.readAllBytes(manifestPath)
        val manifest = JsonSlurper().parse(manifestPath.toFile()) as Map<*, *>
        assertEquals(1, manifest["schemaVersion"])
        assertEquals("jbsa-parent", manifest["rootProject"])
        assertEquals(
            listOf(
                "jbsa-benchmarks/target",
                "jbsa-cli/target",
                "jbsa-conformance-tests/target",
                "jbsa-dist/target",
                "jbsa-test-support/target",
                "jbsa/target",
                "target",
            ),
            manifest["ownedOutputRoots"],
        )
        @Suppress("UNCHECKED_CAST")
        val outputs = manifest["outputs"] as List<Map<String, String>>
        assertEquals(outputs.sortedBy { it.getValue("id") }, outputs)
        assertTrue(
            outputs.any {
                it["id"] == "library-consumer-pom" &&
                    it["path"] == "jbsa/target/publications/library/pom-default.xml"
            }
        )
        assertTrue(outputs.all { output ->
            val path = Path.of(output.getValue("path"))
            !path.isAbsolute && path.none { segment -> segment.toString() == ".." }
        })

        Files.delete(manifestPath)
        run("generateBuildLayoutManifest")
        assertTrue(firstBytes.contentEquals(Files.readAllBytes(manifestPath)))
    }

    /** Verifies generated compliance contracts cannot escape into tracked automation or outside the repository. */
    @Test
    fun `rejects an uncontained build layout manifest output`() {
        write(
            "build.gradle.kts",
            """
            plugins { id("jbsa.foundation") }
            layout.buildDirectory = layout.projectDirectory.dir("build")
            """.trimIndent(),
        )

        val result = runAndFail("generateBuildLayoutManifest")

        assertTrue(result.output.contains("Build-layout output must be a contained generated path"), result.output)
    }

    /** Verifies a contained tracked repository path cannot be declared as generated output. */
    @Test
    fun `rejects a build layout entry outside owned target roots`() {
        write(
            "build.gradle.kts",
            """
            plugins { id("jbsa.foundation") }
            tasks.named<io.github.evildarkarchon.jbsa.build.GenerateBuildLayoutManifest>(
                "generateBuildLayoutManifest"
            ) {
                outputEntries.add(
                    io.github.evildarkarchon.jbsa.build.BuildLayoutEntry(
                        "forged-doc-output", "documentation", "docs/target/forged.json", ":unsafeProducer"
                    )
                )
            }
            """.trimIndent(),
        )

        val result = runAndFail("generateBuildLayoutManifest")

        assertTrue(result.output.contains("Build-layout output must be a contained generated path"), result.output)
    }

    /** Verifies the public task records only resolved production artifacts and serializes them reproducibly. */
    @Test
    fun `generates the deterministic resolved production dependency manifest`() {
        run("--write-locks", "generateResolvedProductionDependencies")

        val manifestPath = projectDir.resolve("target/compliance/resolved-production-dependencies.json")
        val firstBytes = Files.readAllBytes(manifestPath)
        val manifest = JsonSlurper().parse(manifestPath.toFile()) as Map<*, *>
        assertEquals(1, manifest["schemaVersion"])
        @Suppress("UNCHECKED_CAST")
        val dependencies = manifest["dependencies"] as List<Map<String, Any?>>
        assertTrue(dependencies.isNotEmpty())
        assertTrue(dependencies.all { it["sourceProject"] in setOf(":jbsa", ":jbsa-cli", ":jbsa-dist") })
        assertTrue(dependencies.all { it["configuration"] in setOf("runtimeClasspath", "runtimeInputs") })
        assertTrue(dependencies.all { dependency ->
            @Suppress("UNCHECKED_CAST")
            val resolved = dependency.getValue("resolved") as Map<String, String>
            resolved["groupId"] == "org.lwjgl" && resolved["artifactId"] in setOf("lwjgl", "lwjgl-lz4")
        })
        assertEquals(
            mapOf(
                "org.lwjgl:lwjgl:3.4.3:" to
                    "46eeca5471833c3cf5da3c1da015b41e3bb3eb16dd3da03f366886da10096751",
                "org.lwjgl:lwjgl:3.4.3:natives-windows" to
                    "19949bca7b780f55e5d2db12ac061a7657a4c1b7c860c30607f5406e7017aa2b",
                "org.lwjgl:lwjgl-lz4:3.4.3:" to
                    "fd81606cbfdd7084cdbf576f6260087a2ae68bcfb53ca1ccbec0707ae603f876",
                "org.lwjgl:lwjgl-lz4:3.4.3:natives-windows" to
                    "4980edf40520be80a7753bc791283edf303b936466dd47b633138b05887f0edc",
            ),
            dependencies.associate { dependency ->
                @Suppress("UNCHECKED_CAST")
                val resolved = dependency.getValue("resolved") as Map<String, String>
                @Suppress("UNCHECKED_CAST")
                val artifact = dependency.getValue("artifact") as Map<String, String>
                "${resolved["groupId"]}:${resolved["artifactId"]}:${resolved["version"]}:${dependency["classifier"] ?: ""}" to
                    artifact.getValue("sha256")
            },
        )
        assertTrue(dependencies.all { dependency ->
            @Suppress("UNCHECKED_CAST")
            val requested = dependency.getValue("requested") as Map<String, String>
            @Suppress("UNCHECKED_CAST")
            val variant = dependency.getValue("selectedVariant") as Map<String, Map<String, String>>
            @Suppress("UNCHECKED_CAST")
            val artifact = dependency.getValue("artifact") as Map<String, String>
            requested.keys == setOf("groupId", "artifactId", "version") &&
                variant.getValue("attributes").isNotEmpty() &&
                artifact.getValue("sha256").matches(Regex("[0-9a-f]{64}"))
        })
        val dependencyComparator =
            compareBy<Map<String, Any?>>(
                { it.getValue("sourceProject") as String },
                { it.getValue("configuration") as String },
                { (it.getValue("resolved") as Map<*, *>)["groupId"] as String },
                { (it.getValue("resolved") as Map<*, *>)["artifactId"] as String },
                { (it.getValue("resolved") as Map<*, *>)["version"] as String },
                { it["classifier"] as? String ?: "" },
                { (it.getValue("artifact") as Map<*, *>)["fileName"] as String },
                { (it.getValue("requested") as Map<*, *>)["groupId"] as String },
                { (it.getValue("requested") as Map<*, *>)["artifactId"] as String },
                { (it.getValue("requested") as Map<*, *>)["version"] as String },
            )
        assertEquals(dependencies.sortedWith(dependencyComparator), dependencies)

        Files.delete(manifestPath)
        run("generateResolvedProductionDependencies")
        assertTrue(firstBytes.contentEquals(Files.readAllBytes(manifestPath)))
    }

    /** Verifies the production SBOM is deterministic CycloneDX 1.6 with only the shipped graph. */
    @Test
    fun `generates the deterministic production CycloneDX SBOM`() {
        run("--write-locks", "generateProductionSbom")

        val sbomPath = projectDir.resolve("target/compliance/jbsa.cdx.json")
        val firstBytes = Files.readAllBytes(sbomPath)
        @Suppress("UNCHECKED_CAST")
        val sbom = JsonSlurper().parse(sbomPath.toFile()) as Map<String, Any?>
        assertEquals("CycloneDX", sbom["bomFormat"])
        assertEquals("1.6", sbom["specVersion"])
        assertFalse(sbom.containsKey("serialNumber"))
        @Suppress("UNCHECKED_CAST")
        val metadata = sbom.getValue("metadata") as Map<String, Any?>
        assertFalse(metadata.containsKey("timestamp"))
        @Suppress("UNCHECKED_CAST")
        val root = metadata.getValue("component") as Map<String, String>
        assertEquals("jbsa-parent", root["name"])
        @Suppress("UNCHECKED_CAST")
        val components = sbom.getValue("components") as List<Map<String, Any?>>
        assertEquals(
            setOf("jbsa", "jbsa-cli", "lwjgl", "lwjgl-lz4"),
            components.map { it.getValue("name") }.toSet(),
        )
        assertFalse(
            components.any {
                it["name"] in setOf("jbsa-test-support", "jbsa-conformance-tests", "jbsa-benchmarks", "jbsa-dist")
            }
        )
        @Suppress("UNCHECKED_CAST")
        val relationships = sbom.getValue("dependencies") as List<Map<String, Any?>>
        assertTrue(relationships.any { it["ref"] == root["bom-ref"] })

        Files.delete(sbomPath)
        Files.delete(projectDir.resolve("jbsa/target/reports/cyclonedx-direct/bom.json"))
        run("generateProductionSbom")
        assertTrue(firstBytes.contentEquals(Files.readAllBytes(sbomPath)))
    }

    /** Verifies a structurally valid but empty plugin graph cannot qualify resolved production bytes. */
    @Test
    fun `rejects a CycloneDX graph that omits production components`() {
        write(
            "empty-cyclonedx.json",
            """
            {
              "bomFormat": "CycloneDX",
              "specVersion": "1.6",
              "metadata": {
                "tools": {"components": [{"name": "cyclonedx-gradle-plugin", "version": "3.4.1"}]},
                "component": {
                  "group": "io.github.evildarkarchon",
                  "name": "jbsa",
                  "version": "0.1.0-SNAPSHOT",
                  "bom-ref": "fixture-root"
                }
              },
              "components": [],
              "dependencies": []
            }
            """.trimIndent(),
        )
        write(
            "build.gradle.kts",
            Files.readString(projectDir.resolve("build.gradle.kts")) +
                """

                tasks.named<io.github.evildarkarchon.jbsa.build.GenerateProductionSbom>(
                    "generateProductionSbom"
                ) {
                    rawCycloneDx.set(layout.projectDirectory.file("empty-cyclonedx.json"))
                }
                """.trimIndent(),
        )

        val result = runAndFail("--write-locks", "generateProductionSbom")

        assertTrue(
            result.output.contains("CycloneDX components differ from unclassified production resolution"),
            result.output,
        )
    }

    /** Runs the fixture with the plugin-under-test classpath and strict command-line diagnostics. */
    private fun run(vararg arguments: String) = runner(arguments, verificationOff = true).build()

    /** Runs a fixture expected to fail while retaining its actionable Gradle diagnostic. */
    private fun runAndFail(vararg arguments: String) = runner(arguments, verificationOff = true).buildAndFail()

    /** Runs a failing fixture with Gradle's default strict dependency verification enabled. */
    private fun runStrictAndFail(vararg arguments: String) = runner(arguments, verificationOff = false).buildAndFail()

    /** Creates one consistently configured runner while allowing strict-verification negative coverage. */
    private fun runner(arguments: Array<out String>, verificationOff: Boolean): GradleRunner {
        return GradleRunner.create()
            .withProjectDir(projectDir.toFile())
            .withPluginClasspath()
            .withArguments(TestKitBuildArguments.create(arguments, verificationOff))
    }

    /** Asserts an exact task-graph edge through observable execution order. */
    private fun assertTaskPrecedes(taskPaths: List<String>, predecessor: String, successor: String) {
        val predecessorIndex = taskPaths.indexOf(predecessor)
        val successorIndex = taskPaths.indexOf(successor)
        assertTrue(predecessorIndex >= 0, "Missing $predecessor from $taskPaths")
        assertTrue(successorIndex >= 0, "Missing $successor from $taskPaths")
        assertTrue(predecessorIndex < successorIndex, "$predecessor must run before $successor: $taskPaths")
    }

    /** Extracts the observable task order printed by Gradle's dry-run execution plan. */
    private fun taskPathsFromDryRun(output: String): List<String> =
        output.lineSequence()
            .map(String::trim)
            .filter { it.startsWith(":") && it.endsWith(" SKIPPED") }
            .map { it.substringBefore(' ') }
            .toList()

    /** Writes one exact reviewed scanner exception for an isolated functional-test fixture. */
    private fun writeSingleReferenceAllowlist(
        path: String,
        line: String,
        category: String,
        rationale: String,
    ) {
        write(
            "build/active-maven-reference-allowlist.properties",
            """
            allowlistVersion=1
            entryCount=1
            entry.0.path=$path
            entry.0.lineSha256=${sha256(line.toByteArray())}
            entry.0.category=$category
            entry.0.rationale=$rationale
            """.trimIndent() + "\n",
        )
    }

    /** Rebinds the real staging task to an owned process fixture while preserving its declared property model. */
    private fun configureStageProbe(scriptBody: String) {
        write("stage-probe.ps1", scriptBody)
        write("stage-layout.json", "{}")
        write("stage-inventory.json", "{}")
        write("stage-input.txt", "canonical input")
        Files.createDirectories(projectDir.resolve("stage-runtime"))
        appendBuildScript(
            """
            project(":jbsa-dist") {
                tasks.named<io.github.evildarkarchon.jbsa.build.StageReleaseInputs>("stageReleaseInputs") {
                    setDependsOn(emptyList<Any>())
                    stagingScript.set(rootProject.layout.projectDirectory.file("stage-probe.ps1"))
                    buildLayoutManifest.set(rootProject.layout.projectDirectory.file("stage-layout.json"))
                    dependencyInventory.set(rootProject.layout.projectDirectory.file("stage-inventory.json"))
                    canonicalFiles.setFrom(rootProject.layout.projectDirectory.file("stage-input.txt"))
                    runtimeDependencies.set(rootProject.layout.projectDirectory.dir("stage-runtime"))
                    reactorVersion.set("2.3.4")
                    powershellExecutable.set("pwsh")
                    releaseInputDirectory.set(layout.buildDirectory.dir("probe-release-inputs"))
                    releaseInputManifest.set(layout.buildDirectory.file("probe-release-inputs.json"))
                }
            }
            """.trimIndent(),
        )
    }

    /** Rebinds the real post-staging task to complete owned inputs and a rejecting process-contract probe. */
    private fun configureAuditProbe() {
        write(
            "audit-probe.ps1",
            """
            param(
                [string] ${'$'}ReactorVersion,
                [string] ${'$'}BuildLayoutManifest,
                [string] ${'$'}ResolvedProductionDependencies,
                [string] ${'$'}ConsumerPomPath,
                [string] ${'$'}GeneratedSbomPath,
                [string] ${'$'}ReleaseInputRoot,
                [string] ${'$'}ReleaseInputManifest,
                [switch] ${'$'}RequireGeneratedArtifacts,
                [switch] ${'$'}VerifyGeneratedComplianceOutputs
            )
            ${'$'}leafNames = @(
                (Split-Path -Leaf ${'$'}BuildLayoutManifest),
                (Split-Path -Leaf ${'$'}ResolvedProductionDependencies),
                (Split-Path -Leaf ${'$'}ConsumerPomPath),
                (Split-Path -Leaf ${'$'}GeneratedSbomPath),
                (Split-Path -Leaf ${'$'}ReleaseInputRoot),
                (Split-Path -Leaf ${'$'}ReleaseInputManifest)
            )
            if (${'$'}ReactorVersion -cne '2.3.4' -or
                (${'$'}leafNames -join ',') -cne 'audit-layout.json,audit-resolved.json,audit-pom.xml,audit-sbom.json,audit-release-inputs,audit-release-inputs.json' -or
                -not ${'$'}RequireGeneratedArtifacts -or -not ${'$'}VerifyGeneratedComplianceOutputs) {
                Write-Error 'audit process contract mismatch'
                exit 18
            }
            Write-Error 'intentional post-staging audit failure'
            exit 19
            """.trimIndent(),
        )
        listOf(
                "audit-layout.json",
                "audit-resolved.json",
                "audit-pom.xml",
                "audit-sbom.json",
                "audit-input.txt",
                "audit-release-inputs.json",
            )
            .forEach { write(it, "fixture") }
        Files.createDirectories(projectDir.resolve("audit-release-inputs"))
        appendBuildScript(
            """
            project(":jbsa-dist") {
                tasks.named<io.github.evildarkarchon.jbsa.build.VerifyStagedReleaseInputs>(
                    "verifyStagedReleaseInputs"
                ) {
                    setDependsOn(emptyList<Any>())
                    verificationScript.set(rootProject.layout.projectDirectory.file("audit-probe.ps1"))
                    buildLayoutManifest.set(rootProject.layout.projectDirectory.file("audit-layout.json"))
                    resolvedProductionDependencies.set(rootProject.layout.projectDirectory.file("audit-resolved.json"))
                    consumerPom.set(rootProject.layout.projectDirectory.file("audit-pom.xml"))
                    generatedSbom.set(rootProject.layout.projectDirectory.file("audit-sbom.json"))
                    algorithmInputs.setFrom(rootProject.layout.projectDirectory.file("audit-input.txt"))
                    releaseInputDirectory.set(rootProject.layout.projectDirectory.dir("audit-release-inputs"))
                    releaseInputManifest.set(rootProject.layout.projectDirectory.file("audit-release-inputs.json"))
                    reactorVersion.set("2.3.4")
                    powershellExecutable.set("pwsh")
                }
            }
            """.trimIndent(),
        )
    }

    /** Appends one Kotlin DSL fragment without replacing the fixture's required root plugin configuration. */
    private fun appendBuildScript(content: String) {
        val buildFile = projectDir.resolve("build.gradle.kts")
        Files.writeString(buildFile, Files.readString(buildFile) + System.lineSeparator() + content)
    }

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

    /** Writes the fixture's centralized catalog, optionally approving both versions used by conflict coverage. */
    private fun writeCatalog(includeConflictPin: Boolean = false) {
        val conflictVersion = if (includeConflictPin) "junit-old = \"6.0.0\"" else ""
        val conflictLibrary =
            if (includeConflictPin) {
                "junit-api-old = { module = \"org.junit.jupiter:junit-jupiter-api\", version.ref = \"junit-old\" }"
            } else {
                ""
            }
        write(
            "gradle/libs.versions.toml",
            """
            [versions]
            cyclonedx = "3.4.1"
            jackson = "2.22.1"
            jmh = "1.37"
            junit = "6.1.3"
            lwjgl = "3.4.3"
            snakeyaml = "2.5"
            spotless = "8.10.2"
            $conflictVersion

            [libraries]
            jackson-yaml = { module = "com.fasterxml.jackson.dataformat:jackson-dataformat-yaml", version.ref = "jackson" }
            snakeyaml = { module = "org.yaml:snakeyaml", version.ref = "snakeyaml" }
            jmh-core = { module = "org.openjdk.jmh:jmh-core", version.ref = "jmh" }
            jmh-generator = { module = "org.openjdk.jmh:jmh-generator-annprocess", version.ref = "jmh" }
            junit-api = { module = "org.junit.jupiter:junit-jupiter-api", version.ref = "junit" }
            junit-bom = { module = "org.junit:junit-bom", version.ref = "junit" }
            junit-jupiter = { module = "org.junit.jupiter:junit-jupiter", version.ref = "junit" }
            junit-platform-launcher = { module = "org.junit.platform:junit-platform-launcher", version.ref = "junit" }
            lwjgl = { module = "org.lwjgl:lwjgl", version.ref = "lwjgl" }
            lwjgl-lz4 = { module = "org.lwjgl:lwjgl-lz4", version.ref = "lwjgl" }
            $conflictLibrary

            [plugins]
            cyclonedx = { id = "org.cyclonedx.bom", version.ref = "cyclonedx" }
            spotless = { id = "com.diffplug.spotless", version.ref = "spotless" }
            """.trimIndent(),
        )
    }

    /** Returns a lowercase SHA-256 digest for the clean sentinel's exact bytes. */
    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    /** Writes one UTF-8 fixture file, creating its parent directory when necessary. */
    private fun write(relativePath: String, content: String) {
        val path = projectDir.resolve(relativePath)
        Files.createDirectories(path.parent)
        Files.writeString(path, content)
    }
}
