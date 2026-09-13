package io.github.evildarkarchon.jbsa.build

import javax.inject.Inject
import org.gradle.api.GradleException
import org.gradle.api.Plugin
import org.gradle.api.artifacts.repositories.MavenArtifactRepository
import org.gradle.api.configuration.BuildFeatures
import org.gradle.api.initialization.Settings
import org.gradle.api.initialization.resolve.RepositoriesMode

/** Owns plugin and dependency repository policy before any JBSA project is configured. */
class JbsaSettingsPlugin @Inject constructor(private val buildFeatures: BuildFeatures) : Plugin<Settings> {
    /** Configures central repositories and rejects unpinned external plugin requests. */
    override fun apply(settings: Settings) {
        if (buildFeatures.configurationCache.requested.getOrElse(false)) {
            try {
                ConfigurationCachePolicy.validate(settings.gradle.startParameter.taskNames)
            } catch (exception: IllegalArgumentException) {
                throw GradleException(exception.message ?: "Invalid configuration-cache request.", exception)
            }
        }
        val catalog = PinnedVersionCatalog.load(settings.settingsDir.toPath().resolve("gradle/libs.versions.toml"))
        settings.pluginManagement.resolutionStrategy.eachPlugin {
            try {
                PluginVersionPolicy.validate(requested.id.id, requested.version, catalog.pluginVersion(requested.id.id))
            } catch (exception: IllegalArgumentException) {
                throw GradleException(exception.message ?: "Invalid plugin request.", exception)
            }
        }
        settings.dependencyResolutionManagement.repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
        settings.dependencyResolutionManagement.repositories.mavenCentral()

        // Repository blocks can appear after the settings plugin, so verify the final model as well.
        settings.gradle.settingsEvaluated { verifyRepositories(settings) }
    }

    /** Rejects any repository outside the two centrally approved public services. */
    private fun verifyRepositories(settings: Settings) {
        val dependencyUrls =
            settings.dependencyResolutionManagement.repositories
                .map { repository ->
                    if (repository is MavenArtifactRepository) normalize(repository.url.toString())
                    else "${repository.javaClass.simpleName}:${repository.name}"
                }
        if (dependencyUrls != listOf(MAVEN_CENTRAL)) {
            throw GradleException(
                "Dependency repositories are central policy: expected only Maven Central, found $dependencyUrls."
            )
        }

        val pluginUrls =
            settings.pluginManagement.repositories
                .map { repository ->
                    if (repository is MavenArtifactRepository) normalize(repository.url.toString())
                    else "${repository.javaClass.simpleName}:${repository.name}"
                }
        if (pluginUrls != listOf(GRADLE_PLUGIN_PORTAL)) {
            throw GradleException(
                "Plugin repositories are central policy: expected only the Gradle Plugin Portal, found $pluginUrls."
            )
        }
    }

    /** Removes inconsequential trailing slashes before comparing repository identities. */
    private fun normalize(url: String): String = url.trimEnd('/')

    private companion object {
        const val MAVEN_CENTRAL = "https://repo.maven.apache.org/maven2"
        const val GRADLE_PLUGIN_PORTAL = "https://plugins.gradle.org/m2"
    }
}
