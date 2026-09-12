package io.github.evildarkarchon.jbsa.build

import javax.tools.ToolProvider
import org.gradle.api.GradleException
import org.gradle.api.JavaVersion
import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.artifacts.ExternalModuleDependency
import org.gradle.api.artifacts.dsl.LockMode
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
     * @throws GradleException when applied outside the root, on a non-Java-25 runtime, or to the wrong topology
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
            JdkPolicy.validate(JavaVersion.current().majorVersion, ToolProvider.getSystemJavaCompiler() != null)
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
            JbsaProjectRole.BUILD_ONLY_TEST_SUPPORT -> project.pluginManager.apply("jbsa.test-support")
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

    /** Registers the foundation report, verification gate, and clean aggregation tasks. */
    private fun configureLifecycle(project: Project, candidate: String) {
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
                doLast { PublicationPolicy.verify(project) }
            }
        project.tasks.register("verify") {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Runs the complete verification available at the current migration stage."
            dependsOn(
                verifyFoundation,
                verifyPublicationPolicy,
                project.project(JbsaPublicLibraryIdentity.PROJECT_PATH).tasks.named("check"),
                project.project(JbsaPublicLibraryIdentity.PROJECT_PATH).tasks.named(
                    JbsaPublicLibraryIdentity.ASSEMBLE_PUBLICATION_TASK
                ),
                project.project(JbsaPublicLibraryIdentity.PROJECT_PATH).tasks.named(
                    JbsaPublicLibraryIdentity.VERIFY_ARTIFACT_TASK
                ),
                project.project(":jbsa-test-support").tasks.named("build"),
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
