package io.github.evildarkarchon.jbsa.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.attributes.Category
import org.gradle.api.attributes.LibraryElements
import org.gradle.api.attributes.Usage
import org.gradle.api.tasks.Sync

/** Materializes and verifies the external runtime inputs consumed by the thin CLI launcher. */
class JbsaRuntimeInputsPlugin : Plugin<Project> {
    /** Registers the exact external-runtime copy and hash-verification lifecycle. */
    override fun apply(project: Project) {
        val runtimeInputs =
            project.configurations.create(JbsaThinApplicationIdentity.RUNTIME_CONFIGURATION) {
                isCanBeConsumed = false
                isCanBeResolved = true
                attributes {
                    attribute(
                        Category.CATEGORY_ATTRIBUTE,
                        project.objects.named(Category::class.java, Category.LIBRARY),
                    )
                    attribute(
                        Usage.USAGE_ATTRIBUTE,
                        project.objects.named(Usage::class.java, Usage.JAVA_RUNTIME),
                    )
                    attribute(
                        LibraryElements.LIBRARY_ELEMENTS_ATTRIBUTE,
                        project.objects.named(LibraryElements::class.java, LibraryElements.JAR),
                    )
                }
            }
        project.dependencies.add(
            runtimeInputs.name,
            project.dependencies.project(JbsaThinApplicationIdentity.PROJECT_PATH),
        )
        val externalArtifacts = ExternalRuntimeArtifacts.from(runtimeInputs)
        val runtimeDirectory = project.layout.buildDirectory.dir("runtime-dependencies")
        val stage =
            project.tasks.register(
                JbsaThinApplicationIdentity.STAGE_RUNTIME_DEPENDENCIES_TASK,
                Sync::class.java,
            ) {
                group = "build"
                description = "Copies the thin CLI external runtime artifacts with established filenames."
                from(externalArtifacts)
                into(runtimeDirectory)
            }
        project.tasks.register(
            JbsaThinApplicationIdentity.VERIFY_RUNTIME_DEPENDENCIES_TASK,
            VerifyRuntimeDependencies::class.java,
        ) {
            group = "verification"
            description = "Verifies exact filenames and SHA-256 hashes for external runtime inputs."
            dependsOn(stage)
            runtimeDependencies.set(runtimeDirectory)
            launchPolicy.set(project.rootProject.layout.projectDirectory.file("build/windows-runtime/launch-policy.json"))
        }
        project.tasks.named("assemble") { dependsOn(stage) }
        project.tasks.named("check") {
            dependsOn(project.tasks.named(JbsaThinApplicationIdentity.VERIFY_RUNTIME_DEPENDENCIES_TASK))
        }
    }
}
