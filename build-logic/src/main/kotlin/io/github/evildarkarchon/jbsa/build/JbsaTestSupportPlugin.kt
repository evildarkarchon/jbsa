package io.github.evildarkarchon.jbsa.build

import org.gradle.api.Plugin
import org.gradle.api.Project
import org.gradle.api.tasks.JavaExec
import org.gradle.api.tasks.SourceSetContainer
import org.gradle.language.base.plugins.LifecycleBasePlugin

/** Builds the retained Java test-support sources without adding any publication capability. */
class JbsaTestSupportPlugin : Plugin<Project> {
    /** Applies only the shared Java convention to the build-only test-support project. */
    override fun apply(project: Project) {
        project.pluginManager.apply("jbsa.java")
        configureArchiveFixtures(project)
    }

    /** Exposes deliberate fixture generation to an empty caller-owned directory. */
    private fun configureArchiveFixtures(project: Project) {
        val mainSourceSet = project.extensions.getByType(SourceSetContainer::class.java).named("main")
        val output = project.providers.gradleProperty("archiveFixtureOutput").orElse("target/archive-fixtures-generated")
        project.tasks.register("generateArchiveFixtures", JavaExec::class.java) {
            group = LifecycleBasePlugin.VERIFICATION_GROUP
            description = "Generates independent archive fixtures into an empty directory."
            dependsOn(project.tasks.named("classes"))
            classpath = mainSourceSet.get().runtimeClasspath
            mainClass.set("io.github.evildarkarchon.jbsa.fixtures.ArchiveFixtureGenerator")
            args("--output", project.rootProject.file(output.get()).absolutePath)
        }
    }
}
