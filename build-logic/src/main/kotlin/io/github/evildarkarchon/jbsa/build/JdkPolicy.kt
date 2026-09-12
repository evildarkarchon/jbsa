package io.github.evildarkarchon.jbsa.build

/** Validates that Gradle runs on a complete installed Java 25 development kit. */
internal object JdkPolicy {
    /**
     * Validates the runtime major version and compiler availability.
     *
     * @throws IllegalArgumentException when Gradle is not running on a complete Java 25 JDK
     */
    fun validate(majorVersion: String, compilerAvailable: Boolean) {
        require(majorVersion == "25" && compilerAvailable) {
            "JBSA requires an installed Java 25 JDK with javac and does not provision JDKs automatically; " +
                "running Java $majorVersion with compilerAvailable=$compilerAvailable."
        }
    }
}
