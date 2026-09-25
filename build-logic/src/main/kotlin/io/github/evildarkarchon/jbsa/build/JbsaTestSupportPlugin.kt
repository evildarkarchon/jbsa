package io.github.evildarkarchon.jbsa.build

import org.gradle.api.Plugin
import org.gradle.api.Project

/** Builds the retained Java test-support sources without adding any publication capability. */
class JbsaTestSupportPlugin : Plugin<Project> {
    /** Applies only the shared Java convention to the build-only test-support project. */
    override fun apply(project: Project) {
        project.pluginManager.apply("jbsa.java")
    }
}
