package io.github.evildarkarchon.jbsa.build

import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.LockMode
import org.gradle.api.tasks.Exec
import org.gradle.api.tasks.bundling.Jar
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** Establishes the single-version, six-project JBSA build and its deterministic resolution policy. */
class JbsaFoundationPlugin : Plugin<Project> {
    private val expectedRoles =
        linkedMapOf(
            JbsaPublicLibraryIdentity.PROJECT_PATH to JbsaProjectRole.PUBLIC_LIBRARY,
            ":jbsa-cli" to JbsaProjectRole.THIN_APPLICATION,
            ":jbsa-test-support" to JbsaProjectRole.BUILD_ONLY_TEST_SUPPORT,
            ":jbsa-conformance-tests" to JbsaProjectRole.BUILD_ONLY_CONFORMANCE,
            ":jbsa-benchmarks" to JbsaProjectRole.BUILD_ONLY_BENCHMARKS,
            ":jbsa-dist" to JbsaProjectRole.NON_JAVA_STAGING_AUDIT,
        )

    /**
     * Configures the root build and every retained project from one validated identity.
     *
     * @throws GradleException when applied outside the root, on an unsupported Gradle runtime, or to the wrong topology
     */
    override fun apply(project: Project) {
        if (project != project.rootProject) {
            throw GradleException("The jbsa.foundation plugin must be applied to the root project.")
        }
        if (project.name != BuildIdentity.ROOT_NAME) {
            throw GradleException(
                "The Gradle root must retain the '${BuildIdentity.ROOT_NAME}' identity, not '${project.name}'."
            )
        }
        try {
            JdkPolicy.validateRuntime(JavaVersion.current().majorVersion)
        } catch (exception: IllegalArgumentException) {
            throw GradleException(exception.message ?: "Invalid Java development kit.", exception)
        }

        val candidate =
            try {
                BuildIdentity.validateVersion(
                    project.providers.gradleProperty("version").orNull
                        ?: throw IllegalArgumentException(
                            "Missing version. Declare the default once in gradle.properties or pass -Pversion=<candidate>."
                        )
                )
            } catch (exception: IllegalArgumentException) {
                throw GradleException(exception.message ?: "Invalid JBSA version.", exception)
            }

        verifyTopology(project)
        val catalog = PinnedVersionCatalog.load(project.rootDir.toPath().resolve("gradle/libs.versions.toml"))
        project.allprojects { configureProject(this, candidate, catalog) }
        configureLifecycle(project, candidate)
    }

    /** Applies identity, output ownership, locking, and resolution failure policy to one project. */
    private fun configureProject(project: Project, candidate: String, catalog: PinnedVersionCatalog) {
        val role =
            if (project == project.rootProject) {
                JbsaProjectRole.ROOT_AGGREGATOR
            } else {
                expectedRoles.getValue(project.path)
            }

        project.pluginManager.apply("base")
        project.group = BuildIdentity.GROUP
        project.version = candidate
        project.layout.buildDirectory.set(project.layout.projectDirectory.dir("target"))
        project.extensions.extraProperties.set("jbsaRole", role.id)

        when (role) {
            JbsaProjectRole.PUBLIC_LIBRARY -> project.pluginManager.apply("jbsa.public-library")
            JbsaProjectRole.THIN_APPLICATION -> project.pluginManager.apply("jbsa.thin-application")
            JbsaProjectRole.BUILD_ONLY_TEST_SUPPORT -> project.pluginManager.apply("jbsa.test-support")
            JbsaProjectRole.BUILD_ONLY_CONFORMANCE -> project.pluginManager.apply("jbsa.build-only-conformance")
            JbsaProjectRole.BUILD_ONLY_BENCHMARKS -> project.pluginManager.apply("jbsa.build-only-benchmarks")
            JbsaProjectRole.NON_JAVA_STAGING_AUDIT -> project.pluginManager.apply("jbsa.runtime-inputs")
            else -> Unit
        }

        project.dependencyLocking.lockAllConfigurations()
        project.dependencyLocking.lockMode.set(LockMode.STRICT)
        project.configurations.configureEach {
            resolutionStrategy.failOnVersionConflict()
            // Gradle forbids both fail-on-dynamic/changing modes with locking, so validate them explicitly.
            resolutionStrategy.eachDependency {
                validateDependency(candidate, requested.version, false)
            }
            incoming.beforeResolve {
                allDependencies
                    .withType(ExternalModuleDependency::class.java)
                    .forEach { dependency ->
                        validateDependency(candidate, dependency.version, dependency.isChanging)
                        catalog.requireDependency(
                            requireNotNull(dependency.group),
                            dependency.name,
                            requireNotNull(dependency.version),
                        )
                    }
            }
        }
    }

    /** Registers compliance generation/audit, foundation reporting, verification, and clean aggregation. */
    private fun configureLifecycle(project: Project, candidate: String) {
        val generateBuildLayout =
            project.tasks.register("generateBuildLayoutManifest", GenerateBuildLayoutManifest::class.java) {
                group = "build"
                description = "Generates the internal build-output contract consumed by repository automation."
                rootProjectName.set(project.name)
                repositoryRoot.set(project.layout.projectDirectory)
                manifestFile.set(project.layout.buildDirectory.file("compliance/build-layout.json"))
                outputEntries.set(
                    listOf(
                        BuildLayoutEntry(
                            "aggregate-sbom",
                            "compliance-evidence",
                            "target/compliance/jbsa.cdx.json",
                            ":generateProductionSbom",
                        ),
                        BuildLayoutEntry(
                            "build-layout-manifest",
                            "internal-manifest",
                            "target/compliance/build-layout.json",
                            ":generateBuildLayoutManifest",
                        ),
                        BuildLayoutEntry(
                            "cli-binary",
                            "project-artifact",
                            "jbsa-cli/target/libs/jbsa-cli-$candidate.jar",
                            ":jbsa-cli:jar",
                        ),
                        BuildLayoutEntry(
                            "generated-release-notes",
                            "compliance-evidence",
                            "target/compliance/RELEASE-NOTES.md",
                            ":verifyCompliance",
                        ),
                        BuildLayoutEntry(
                            "generated-third-party-notices",
                            "compliance-evidence",
                            "target/compliance/THIRD-PARTY-NOTICES.md",
                            ":verifyCompliance",
                        ),
                        BuildLayoutEntry(
                            "library-binary",
                            "project-artifact",
                            "jbsa/target/libs/jbsa-$candidate.jar",
                            ":jbsa:jar",
                        ),
                        BuildLayoutEntry(
                            "library-consumer-pom",
                            "consumer-metadata",
                            "jbsa/target/${JbsaPublicLibraryIdentity.publicationPath("pom-default.xml")}",
                            ":jbsa:${JbsaPublicLibraryIdentity.generatePomTaskName()}",
                        ),
                        BuildLayoutEntry(
                            "library-javadoc",
                            "project-artifact",
                            "jbsa/target/libs/jbsa-$candidate-javadoc.jar",
                            ":jbsa:javadocJar",
                        ),
                        BuildLayoutEntry(
                            "library-sources",
                            "project-artifact",
                            "jbsa/target/libs/jbsa-$candidate-sources.jar",
                            ":jbsa:sourcesJar",
                        ),
                        BuildLayoutEntry(
                            "resolved-production-dependencies",
                            "internal-manifest",
                            "target/compliance/resolved-production-dependencies.json",
                            ":generateResolvedProductionDependencies",
                        ),
                        BuildLayoutEntry(
                            "runtime-dependencies",
                            "runtime-input-directory",
                            "jbsa-dist/target/runtime-dependencies",
                            ":jbsa-dist:${JbsaThinApplicationIdentity.STAGE_RUNTIME_DEPENDENCIES_TASK}",
                        ),
                    )
                )
            }
        val productionScopes =
            listOf(
                JbsaPublicLibraryIdentity.PROJECT_PATH to "runtimeClasspath",
                JbsaThinApplicationIdentity.PROJECT_PATH to "runtimeClasspath",
                JbsaThinApplicationIdentity.DISTRIBUTION_PROJECT_PATH to
                    JbsaThinApplicationIdentity.RUNTIME_CONFIGURATION,
            )
        val generateResolvedDependencies =
            project.tasks.register(
                "generateResolvedProductionDependencies",
                GenerateResolvedProductionDependencies::class.java,
            ) {
                group = "build"
                description = "Generates exact checksummed dependency selections for production compliance."
                scopeNames.set(productionScopes.map { (projectPath, configuration) -> "$projectPath:$configuration" })
                manifestFile.set(
                    project.layout.buildDirectory.file("compliance/resolved-production-dependencies.json")
                )
                // Resolution edge metadata is not fully represented by Gradle's normalized file inputs.
                outputs.upToDateWhen { false }
                productionScopes.forEach { (projectPath, configurationName) ->
                    productionScope(
                        projectPath,
                        project.project(projectPath).configurations.getByName(configurationName),
                    )
                }
            }
        val generateProductionSbom =
            project.tasks.register("generateProductionSbom", GenerateProductionSbom::class.java) {
                group = "build"
                description = "Generates the deterministic production-only CycloneDX 1.6 SBOM."
                dependsOn(generateResolvedDependencies, ":jbsa:cyclonedxDirectBom")
                componentGroup.set(BuildIdentity.GROUP)
                componentVersion.set(candidate)
                rawCycloneDx.set(
                    project.layout.projectDirectory.file("jbsa/target/reports/cyclonedx-direct/bom.json")
                )
                resolvedDependencies.set(generateResolvedDependencies.flatMap { it.manifestFile })
                sbomFile.set(project.layout.buildDirectory.file("compliance/jbsa.cdx.json"))
            }
        project.pluginManager.withPlugin("org.cyclonedx.bom") {
            project.allprojects.forEach { current ->
                val role = current.extensions.extraProperties["jbsaRole"] as String
                current.tasks.named("cyclonedxDirectBom") {
                    enabled =
                        role in
                            setOf(
                                JbsaProjectRole.ROOT_AGGREGATOR.id,
                                JbsaProjectRole.PUBLIC_LIBRARY.id,
                                JbsaProjectRole.THIN_APPLICATION.id,
                            )
                }
            }
        }
        val library = project.project(JbsaPublicLibraryIdentity.PROJECT_PATH)
        val generateConsumerPom = library.tasks.named(JbsaPublicLibraryIdentity.generatePomTaskName())
        val consumerPom = library.layout.buildDirectory.file(JbsaPublicLibraryIdentity.publicationPath("pom-default.xml"))
        val lockFiles =
            listOf(
                project.layout.projectDirectory.file("gradle.lockfile"),
                project.layout.projectDirectory.file("jbsa/gradle.lockfile"),
                project.layout.projectDirectory.file("jbsa-cli/gradle.lockfile"),
                project.layout.projectDirectory.file("jbsa-dist/gradle.lockfile"),
            )
        val verificationFiles =
            listOf(
                project.layout.projectDirectory.file("gradle/verification-metadata.xml"),
                project.layout.projectDirectory.file("build-logic/gradle/verification-metadata.xml"),
            )
        val verifyCompliance =
            project.tasks.register("verifyCompliance", Exec::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Audits Gradle production resolution, licensing, notices, and the generated SBOM."
                dependsOn(generateBuildLayout, generateResolvedDependencies, generateProductionSbom, generateConsumerPom)
                workingDir(project.rootDir)
                val script = project.layout.projectDirectory.file("build/verify-compliance.ps1")
                inputs.files(
                    script,
                    project.layout.projectDirectory.file("compliance/dependency-inventory.json"),
                    project.layout.projectDirectory.file("compliance/native-payload-inventory.json"),
                    project.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"),
                    project.layout.projectDirectory.file("RELEASE-NOTES.md"),
                    generateBuildLayout.flatMap { it.manifestFile },
                    generateResolvedDependencies.flatMap { it.manifestFile },
                    generateProductionSbom.flatMap { it.sbomFile },
                    consumerPom,
                )
                inputs.files(lockFiles, verificationFiles)
                outputs.files(
                    project.layout.buildDirectory.file("compliance/THIRD-PARTY-NOTICES.md"),
                    project.layout.buildDirectory.file("compliance/RELEASE-NOTES.md"),
                )
                // The script audits every tracked byte, including paths outside its explicit model inputs.
                outputs.upToDateWhen { false }
                doFirst {
                    commandLine(
                        buildList {
                            addAll(
                                listOf(
                                    "pwsh",
                                    "-NoLogo",
                                    "-NoProfile",
                                    "-NonInteractive",
                                    "-File",
                                    script.asFile.absolutePath,
                                    "-ReactorVersion",
                                    candidate,
                                    "-BuildLayoutManifest",
                                    generateBuildLayout.get().manifestFile.get().asFile.absolutePath,
                                    "-ResolvedProductionDependencies",
                                    generateResolvedDependencies.get().manifestFile.get().asFile.absolutePath,
                                    "-ConsumerPomPath",
                                    consumerPom.get().asFile.absolutePath,
                                )
                            )
                            addAll(
                                listOf(
                                    "-RequireGeneratedArtifacts",
                                    "-GeneratedSbomPath",
                                    generateProductionSbom.get().sbomFile.get().asFile.absolutePath,
                                )
                            )
                        }
                    )
                }
            }
        project.project(JbsaConformanceIdentity.PROJECT_PATH).tasks.named("buildPolicyTest") {
            dependsOn(verifyCompliance)
        }
        val distribution = project.project(JbsaThinApplicationIdentity.DISTRIBUTION_PROJECT_PATH)
        val libraryBinary = library.tasks.named("jar", Jar::class.java)
        val librarySources = library.tasks.named("sourcesJar", Jar::class.java)
        val libraryJavadoc = library.tasks.named("javadocJar", Jar::class.java)
        val cli = project.project(JbsaThinApplicationIdentity.PROJECT_PATH)
        val cliBinary = cli.tasks.named("jar", Jar::class.java)
        val runtimeDependencies = distribution.layout.buildDirectory.dir("runtime-dependencies")
        val releaseInputDirectory = distribution.layout.buildDirectory.dir("release-inputs")
        val releaseInputManifest = distribution.layout.buildDirectory.file("release-inputs.json")
        val stageReleaseInputs =
            distribution.tasks.register(
                JbsaThinApplicationIdentity.STAGE_RELEASE_INPUTS_TASK,
                StageReleaseInputs::class.java,
            ) {
                group = "distribution"
                description = "Stages the unchanged canonical release-input set with a deterministic manifest."
                dependsOn(
                    verifyCompliance,
                    library.tasks.named(JbsaPublicLibraryIdentity.ASSEMBLE_PUBLICATION_TASK),
                    cliBinary,
                    distribution.tasks.named(JbsaThinApplicationIdentity.STAGE_RUNTIME_DEPENDENCIES_TASK),
                )
                stagingScript.set(project.layout.projectDirectory.file("build/stage-release-inputs.ps1"))
                buildLayoutManifest.set(generateBuildLayout.flatMap { it.manifestFile })
                dependencyInventory.set(
                    project.layout.projectDirectory.file("compliance/dependency-inventory.json")
                )
                canonicalFiles.from(
                    libraryBinary.flatMap(Jar::getArchiveFile),
                    librarySources.flatMap(Jar::getArchiveFile),
                    libraryJavadoc.flatMap(Jar::getArchiveFile),
                    cliBinary.flatMap(Jar::getArchiveFile),
                    consumerPom,
                    generateProductionSbom.flatMap { it.sbomFile },
                    project.layout.buildDirectory.file("compliance/THIRD-PARTY-NOTICES.md"),
                    project.layout.buildDirectory.file("compliance/RELEASE-NOTES.md"),
                    project.layout.projectDirectory.file("LICENSE"),
                    project.layout.projectDirectory.file("NOTICE"),
                    project.layout.projectDirectory.file("compliance/licenses/LWJGL-3.4.3.txt"),
                    project.layout.projectDirectory.file("compliance/licenses/LZ4-1.10.0.txt"),
                    project.layout.projectDirectory.file("build/windows-runtime/jbsa.ps1"),
                    project.layout.projectDirectory.file("build/windows-runtime/launch-policy.json"),
                )
                this.runtimeDependencies.set(runtimeDependencies)
                reactorVersion.set(candidate)
                powershellExecutable.set("pwsh")
                this.releaseInputDirectory.set(releaseInputDirectory)
                this.releaseInputManifest.set(releaseInputManifest)
            }
        val verifyStagedReleaseInputs =
            distribution.tasks.register(
                JbsaThinApplicationIdentity.VERIFY_STAGED_RELEASE_INPUTS_TASK,
                VerifyStagedReleaseInputs::class.java,
            ) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Audits the completed canonical staging set against compliance policy."
                dependsOn(stageReleaseInputs)
                verificationScript.set(project.layout.projectDirectory.file("build/verify-compliance.ps1"))
                buildLayoutManifest.set(generateBuildLayout.flatMap { it.manifestFile })
                resolvedProductionDependencies.set(generateResolvedDependencies.flatMap { it.manifestFile })
                this.consumerPom.set(consumerPom)
                generatedSbom.set(generateProductionSbom.flatMap { it.sbomFile })
                algorithmInputs.from(
                    project.layout.projectDirectory.file("compliance/dependency-inventory.json"),
                    project.layout.projectDirectory.file("compliance/native-payload-inventory.json"),
                    project.layout.projectDirectory.file("THIRD-PARTY-NOTICES.md"),
                    project.layout.projectDirectory.file("RELEASE-NOTES.md"),
                    project.layout.buildDirectory.file("compliance/THIRD-PARTY-NOTICES.md"),
                    project.layout.buildDirectory.file("compliance/RELEASE-NOTES.md"),
                    lockFiles,
                    verificationFiles,
                )
                this.releaseInputDirectory.set(stageReleaseInputs.flatMap { it.releaseInputDirectory })
                this.releaseInputManifest.set(stageReleaseInputs.flatMap { it.releaseInputManifest })
                reactorVersion.set(candidate)
                powershellExecutable.set("pwsh")
            }
        val verifyFoundation =
            project.tasks.register("verifyBuildFoundation") {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Verifies the JBSA Gradle identity, roles, and owned output locations."
                doLast { verifyConfiguredProjects(project, candidate) }
            }
        project.tasks.register("foundationIdentity") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Prints the deterministic JBSA project identity and role model."
            doLast {
                project.logger.lifecycle(
                    "JBSA_FOUNDATION root=${project.name} group=${project.group} version=${project.version}"
                )
                expectedRoles.forEach { (path, role) ->
                    project.logger.lifecycle("JBSA_PROJECT $path=${role.id}")
                }
            }
        }
        val verifyPublicationPolicy =
            project.tasks.register("verifyPublicationPolicy") {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Verifies that publication remains local-only and library-only."
                doLast {
                    PublicationPolicy.verify(project)
                    BenchmarkIsolationPolicy.verify(project)
                }
            }
        val verifyActiveReferences =
            project.tasks.register("verifyActiveReferences", VerifyActiveReferences::class.java) {
                group = LifecycleBasePlugin.VERIFICATION_GROUP
                description = "Rejects stale legacy build instructions from active repository surfaces."
                repositoryRoot.set(project.layout.projectDirectory)
                allowlistFile.set(
                    project.layout.projectDirectory.file("build/active-maven-reference-allowlist.properties")
                )
                legacyBuildFiles.from(
                    project.fileTree(project.rootDir) {
                        // Split scanner targets so this production configuration does not approve its own patterns.
                        include(
                            "pom" + ".xml",
                            "**/pom" + ".xml",
                            "mvn" + "w",
                            "mvn" + "w.cmd",
                            ".m" + "vn/**",
                            "**/.m" + "vn/**",
                        )
                        exclude(".scratch/**", "docs/**", "**/target/**", "TES5Edit/**", "graphify-out/**")
                    }
                )
                activeFiles.from(
                    project.fileTree(project.rootDir) {
                        include("**/*.md")
                        include("CONTRIBUTING.md")
                        include("README.md", "compliance/**/*.md")
                        include("docs/**/*.md", "docs/**/*.yaml", "docs/**/*.yml")
                        include(".github/workflows/**/*.yaml", ".github/workflows/**/*.yml")
                        include(
                            ".github/actions/**/*.yaml",
                            ".github/actions/**/*.yml",
                            ".github/actions/**/*.ps1",
                            ".github/actions/**/*.sh",
                            ".github/actions/**/*.js",
                            ".github/actions/**/*.ts",
                        )
                        include(
                            "build/**/*.ps1",
                            "build/**/*.py",
                            "build/**/*.cs",
                            "build/**/*.json",
                            "build/**/*.sh",
                            "build/**/*.cmd",
                            "build/**/*.bat",
                            "build/**/*.kts",
                            "build/**/*.gradle",
                            "build/**/*.md",
                            "build/**/*.yaml",
                            "build/**/*.yml",
                        )
                        include("tests/**/*.md", "tests/**/*.yaml", "tests/**/*.yml", "tests/**/*.json")
                        include("tests/**/*.java", "tests/**/*.kt", "tests/**/*.kts", "tests/**/*.ps1", "tests/**/*.py")
                        include(".scratch/*/spec.md", ".scratch/*/issues/*.md")
                        include("**/*.gradle.kts", "**/*.gradle")
                        include("build-logic/src/main/**/*.kt", "build-logic/src/main/**/*.java")
                        include("build-logic/src/test/**/*.kt", "build-logic/src/test/**/*.java")
                        include("jbsa*/src/test/**/*.java", "jbsa*/src/test/**/*.kt")
                        exclude("**/target/**", "TES5Edit/**", "graphify-out/**")
                    }
                )
            }
        project.tasks.register("verify") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Builds, audits, stages, and post-audits the complete canonical release inputs."
            dependsOn(
                generateBuildLayout,
                generateResolvedDependencies,
                generateProductionSbom,
                verifyCompliance,
                verifyFoundation,
                verifyPublicationPolicy,
                verifyActiveReferences,
                project.project(JbsaPublicLibraryIdentity.PROJECT_PATH).tasks.named("check"),
                project.project(JbsaPublicLibraryIdentity.PROJECT_PATH).tasks.named(
                    JbsaPublicLibraryIdentity.ASSEMBLE_PUBLICATION_TASK
                ),
                project.project(JbsaPublicLibraryIdentity.PROJECT_PATH).tasks.named(
                    JbsaPublicLibraryIdentity.VERIFY_ARTIFACT_TASK
                ),
                project.project(":jbsa-test-support").tasks.named("build"),
                project.project(JbsaThinApplicationIdentity.PROJECT_PATH).tasks.named("check"),
                project
                    .project(JbsaThinApplicationIdentity.PROJECT_PATH)
                    .tasks
                    .named(JbsaThinApplicationIdentity.VERIFY_ARTIFACT_TASK),
                project
                    .project(JbsaThinApplicationIdentity.PROJECT_PATH)
                    .tasks
                    .named(JbsaThinApplicationIdentity.SMOKE_TEST_TASK),
                project
                    .project(JbsaThinApplicationIdentity.DISTRIBUTION_PROJECT_PATH)
                    .tasks
                    .named(JbsaThinApplicationIdentity.VERIFY_RUNTIME_DEPENDENCIES_TASK),
                project
                    .project(JbsaConformanceIdentity.PROJECT_PATH)
                    .tasks
                    .named(JbsaConformanceIdentity.AUTOMATED_TASK),
                project.project(JbsaConformanceIdentity.PROJECT_PATH).tasks.named("check"),
                JbsaConformanceIdentity.ARCHIVE_FAMILY_TASKS.map { taskName ->
                    project.project(JbsaConformanceIdentity.PROJECT_PATH).tasks.named(taskName)
                },
                project.project(JbsaBenchmarkIdentity.PROJECT_PATH).tasks.named("check"),
                verifyStagedReleaseInputs,
            )
        }
        project.tasks.named(LifecycleBasePlugin.CLEAN_TASK_NAME) {
            dependsOn(project.subprojects.map { child -> child.tasks.named(LifecycleBasePlugin.CLEAN_TASK_NAME) })
        }
    }

    /** Rejects a topology that differs from the six roles approved for the migration. */
    private fun verifyTopology(project: Project) {
        val actualPaths = project.subprojects.map { it.path }.toSet()
        if (actualPaths != expectedRoles.keys) {
            throw GradleException(
                "JBSA requires exactly the six projects ${expectedRoles.keys}; configured $actualPaths."
            )
        }
    }

    /** Verifies configuration-time policy again at the execution seam where later scripts cannot hide drift. */
    private fun verifyConfiguredProjects(project: Project, candidate: String) {
        project.allprojects.forEach { current ->
            val expectedRole =
                if (current == project) JbsaProjectRole.ROOT_AGGREGATOR.id
                else expectedRoles.getValue(current.path).id
            if (current.group.toString() != BuildIdentity.GROUP || current.version.toString() != candidate) {
                throw GradleException(
                    "Project ${current.path} must use ${BuildIdentity.GROUP}:$candidate; found ${current.group}:${current.version}."
                )
            }
            if (current.extensions.extraProperties["jbsaRole"] != expectedRole) {
                throw GradleException(
                    "Project ${current.path} must retain role '$expectedRole'."
                )
            }
            val expectedOutput = current.layout.projectDirectory.dir("target").asFile.canonicalFile
            if (current.layout.buildDirectory.get().asFile.canonicalFile != expectedOutput) {
                throw GradleException(
                    "Project ${current.path} generated output must remain under ${expectedOutput.path}."
                )
            }
        }
    }

    /** Converts pure dependency-policy failures into actionable Gradle configuration failures. */
    private fun validateDependency(buildVersion: String, dependencyVersion: String?, changing: Boolean) {
        try {
            DependencyPolicy.validate(buildVersion, dependencyVersion, changing)
        } catch (exception: IllegalArgumentException) {
            throw GradleException(exception.message ?: "Invalid dependency declaration.", exception)
        }
    }
}
