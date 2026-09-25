package io.github.evildarkarchon.jbsa.build

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.publish.PublishingExtension
import org.gradle.api.publish.maven.MavenPublication
import org.gradle.api.publish.maven.plugins.MavenPublishPlugin
import org.gradle.api.publish.maven.tasks.PublishToMavenRepository

/** Enforces the local-only, library-only publication boundary for the Gradle migration. */
internal object PublicationPolicy {
    /** Verifies publication ownership and reports the absence of remote publication tasks. */
    fun verify(root: Project) {
        root.allprojects.forEach { project -> verifyProject(project) }
        val remoteTasks = root.allprojects.sumOf { it.tasks.withType(PublishToMavenRepository::class.java).size }
        root.logger.lifecycle(
            "JBSA_PUBLICATION ${JbsaPublicLibraryIdentity.PROJECT_PATH}=" +
                JbsaPublicLibraryIdentity.PUBLICATION_NAME
        )
        root.logger.lifecycle("JBSA_PUBLICATION_REMOTE_TASKS $remoteTasks")
        if (remoteTasks != 0) {
            throw GradleException("Remote Maven publication tasks are prohibited; configured $remoteTasks.")
        }
    }

    /** Rejects Maven publication capabilities, repositories, or public sets outside `:jbsa`. */
    private fun verifyProject(project: Project) {
        val hasPublishing = project.plugins.hasPlugin(MavenPublishPlugin::class.java)
        if (project.path != JbsaPublicLibraryIdentity.PROJECT_PATH && hasPublishing) {
            throw GradleException("${project.path} is build-only and cannot apply maven-publish.")
        }
        if (!hasPublishing) return

        val publishing = project.extensions.getByType(PublishingExtension::class.java)
        if (!publishing.repositories.isEmpty()) {
            throw GradleException("Remote publication repositories are prohibited for ${project.path}.")
        }
        val publications = publishing.publications.withType(MavenPublication::class.java)
        if (
            project.path != JbsaPublicLibraryIdentity.PROJECT_PATH ||
                publications.map { it.name } != listOf(JbsaPublicLibraryIdentity.PUBLICATION_NAME)
        ) {
            throw GradleException("Only :jbsa may expose the single 'library' Maven publication.")
        }
    }
}
