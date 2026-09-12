package io.github.evildarkarchon.jbsa.build

import java.nio.file.Files
import java.nio.file.Path

/** Immutable dependency and plugin pins read from the repository's single version catalog. */
internal class PinnedVersionCatalog private constructor(
    private val dependencies: Map<String, Set<String>>,
    private val plugins: Map<String, String>,
) {
    /** Requires a direct external dependency to use a version declared for its module in the catalog. */
    fun requireDependency(group: String, name: String, version: String) {
        val module = "$group:$name"
        val approved = dependencies[module]
        require(approved != null) {
            "External dependency '$module:$version' is not declared in gradle/libs.versions.toml."
        }
        require(version in approved) {
            "External dependency '$module:$version' must use a catalog-pinned version; approved $approved."
        }
    }

    /** Returns the catalog-pinned version for an external plugin, or `null` when it is undeclared. */
    fun pluginVersion(pluginId: String): String? = plugins[pluginId]

    /** Returns the sole catalog-pinned version for a dependency required by convention build logic. */
    fun dependencyVersion(group: String, name: String): String {
        val module = "$group:$name"
        val approved = requireNotNull(dependencies[module]) { "Missing catalog dependency '$module'." }
        require(approved.size == 1) { "Convention dependency '$module' must have one pinned version; found $approved." }
        return approved.single()
    }

    companion object {
        private val assignment = Regex("([A-Za-z0-9_.-]+)\\s*=\\s*\"([^\"]+)\"")
        private val module = Regex(".*module\\s*=\\s*\"([^\"]+)\".*version\\.ref\\s*=\\s*\"([^\"]+)\".*")
        private val plugin = Regex(".*id\\s*=\\s*\"([^\"]+)\".*version\\.ref\\s*=\\s*\"([^\"]+)\".*")

        /**
         * Loads the controlled TOML subset used by the JBSA catalog.
         *
         * @param path repository catalog path
         * @throws IllegalArgumentException when a catalog entry is malformed or references an unknown version
         */
        fun load(path: Path): PinnedVersionCatalog {
            require(Files.isRegularFile(path)) { "Missing centralized version catalog at $path." }
            val versions = linkedMapOf<String, String>()
            val dependencyReferences = mutableListOf<Pair<String, String>>()
            val pluginReferences = mutableListOf<Pair<String, String>>()
            var section = ""

            Files.readAllLines(path).forEachIndexed { index, sourceLine ->
                val line = sourceLine.substringBefore('#').trim()
                if (line.isEmpty()) return@forEachIndexed
                if (line.startsWith('[') && line.endsWith(']')) {
                    section = line.removeSurrounding("[", "]")
                    return@forEachIndexed
                }
                when (section) {
                    "versions" -> {
                        val match = assignment.matchEntire(line)
                            ?: throw IllegalArgumentException("Malformed catalog version at $path:${index + 1}.")
                        versions[match.groupValues[1]] = match.groupValues[2]
                    }
                    "libraries" -> {
                        val match = module.matchEntire(line.substringAfter('=').trim())
                            ?: throw IllegalArgumentException("Malformed catalog library at $path:${index + 1}.")
                        dependencyReferences += match.groupValues[1] to match.groupValues[2]
                    }
                    "plugins" -> {
                        val match = plugin.matchEntire(line.substringAfter('=').trim())
                            ?: throw IllegalArgumentException("Malformed catalog plugin at $path:${index + 1}.")
                        pluginReferences += match.groupValues[1] to match.groupValues[2]
                    }
                    else -> throw IllegalArgumentException("Unsupported catalog entry at $path:${index + 1}.")
                }
            }

            val dependencyPins =
                dependencyReferences
                    .groupBy({ it.first }, { resolveVersion(path, versions, it.second) })
                    .mapValues { (_, values) -> values.toSet() }
            val pluginPins =
                pluginReferences.associate { (pluginId, reference) ->
                    pluginId to resolveVersion(path, versions, reference)
                }
            return PinnedVersionCatalog(dependencyPins, pluginPins)
        }

        /** Resolves one catalog version reference or fails without accepting an implicit version. */
        private fun resolveVersion(path: Path, versions: Map<String, String>, reference: String): String =
            requireNotNull(versions[reference]) {
                "Catalog entry in $path references missing version '$reference'."
            }
    }
}
