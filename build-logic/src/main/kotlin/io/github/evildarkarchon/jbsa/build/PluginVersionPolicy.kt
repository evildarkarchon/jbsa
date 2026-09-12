package io.github.evildarkarchon.jbsa.build

/** Requires every non-core, non-included plugin request to carry an exact version. */
internal object PluginVersionPolicy {
    private val corePluginIds =
        setOf(
            "application",
            "base",
            "java",
            "java-library",
            "java-platform",
            "maven-publish",
            "signing",
            "version-catalog",
        )

    /**
     * Validates one plugin request before Gradle resolves it.
     *
     * @param pluginId requested Gradle plugin identifier
     * @param version requested version, or `null` for core and included-build plugins
     * @param catalogVersion centrally approved version, or `null` when the plugin is undeclared
     * @throws IllegalArgumentException when an external plugin is unpinned, undeclared, or mismatched
     */
    fun validate(pluginId: String, version: String?, catalogVersion: String?) {
        val isVersionlessByDesign =
            pluginId in corePluginIds || pluginId.startsWith("org.gradle.") || pluginId.startsWith("jbsa.")
        if (isVersionlessByDesign) return

        require(!version.isNullOrBlank()) {
            "External plugin '$pluginId' must use its pinned version from gradle/libs.versions.toml."
        }
        require(catalogVersion != null) {
            "External plugin '$pluginId' is not declared in gradle/libs.versions.toml."
        }
        require(version == catalogVersion) {
            "External plugin '$pluginId' requested '$version'; the catalog pins '$catalogVersion'."
        }
    }
}
