package io.github.evildarkarchon.jbsa.build

/** Defines and validates the identity shared by every project in the JBSA build. */
internal object BuildIdentity {
    const val GROUP = "io.github.evildarkarchon"
    const val ROOT_NAME = "jbsa-parent"

    private val candidatePattern = Regex("[0-9]+(?:\\.[0-9]+){2}(?:-[0-9A-Za-z][0-9A-Za-z.-]*)?")

    /**
     * Validates a candidate artifact version without normalizing or otherwise changing its identity.
     *
     * @param candidate version supplied by `gradle.properties` or `-Pversion`
     * @return the unchanged validated candidate
     * @throws IllegalArgumentException when the candidate is not a pinned semantic version
     */
    fun validateVersion(candidate: String): String {
        require(candidatePattern.matches(candidate)) {
            "Invalid JBSA version '$candidate'. Expected MAJOR.MINOR.PATCH with an optional pinned qualifier."
        }
        return candidate
    }

    /** Returns whether the validated candidate represents a non-snapshot release. */
    fun isRelease(candidate: String): Boolean = !candidate.endsWith("-SNAPSHOT")
}
