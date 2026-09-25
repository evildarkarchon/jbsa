package io.github.evildarkarchon.jbsa.build

/** Validates dependency declarations that would make resolution nondeterministic. */
internal object DependencyPolicy {
    /**
     * Validates one external dependency against the current build candidate.
     *
     * @param buildVersion validated identity shared by the build
     * @param dependencyVersion requested external module version, or `null` when omitted
     * @param changing whether Gradle considers the dependency mutable at fixed coordinates
     * @throws IllegalArgumentException when resolution would be dynamic, changing, or snapshot-based for a release
     */
    fun validate(buildVersion: String, dependencyVersion: String?, changing: Boolean) {
        require(!dependencyVersion.isNullOrBlank()) {
            "External dependencies must declare an exact version in the centralized catalog."
        }
        require(!isDynamic(dependencyVersion)) {
            "Dynamic dependency version '$dependencyVersion' is prohibited; use an exact catalog version."
        }
        require(!changing) {
            "Changing dependency '$dependencyVersion' is prohibited because identical coordinates must be immutable."
        }
        require(!BuildIdentity.isRelease(buildVersion) || !dependencyVersion.endsWith("-SNAPSHOT")) {
            "Release build '$buildVersion' cannot resolve snapshot dependency '$dependencyVersion'."
        }
    }

    /** Returns whether a Gradle version selector can resolve to more than one version. */
    private fun isDynamic(version: String): Boolean =
        version.endsWith("+") ||
            version.startsWith("latest.") ||
            version.any { it == '[' || it == ']' || it == '(' || it == ')' || it == ',' }
}
