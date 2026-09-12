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
     * @throws IllegalArgumentException when an external plugin omits its version
     */
    fun validate(pluginId: String, version: String?) {
        val isVersionlessByDesign =
            pluginId in corePluginIds || pluginId.startsWith("org.gradle.") || pluginId.startsWith("jbsa.")
        require(isVersionlessByDesign || !version.isNullOrBlank()) {
            "External plugin '$pluginId' must use a pinned version from gradle/libs.versions.toml."
        }
    }
}
