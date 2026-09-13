package io.github.evildarkarchon.jbsa.build

/** Defines the bounded task set that must explicitly reject Gradle configuration-cache mode. */
object ConfigurationCachePolicy {
    private val incompatibleTaskNames =
        setOf(
            "automatedConformance",
            "captureAutomatedConformance",
            "generateResolvedProductionDependencies",
            "smokeTestBenchmarkLauncher",
            "smokeTestThinCli",
            "stageReleaseInputs",
            "verify",
            "verifyCompliance",
            "verifyStagedReleaseInputs",
        )

    /**
     * Rejects evidence, staging, and external-process task requests before Gradle builds their graph.
     *
     * @param requestedTasks command-line task paths or names exactly as supplied to Gradle
     * @throws IllegalArgumentException when any requested task is outside the proven compatible set
     */
    fun validate(requestedTasks: List<String>) {
        val incompatible =
            requestedTasks
                .filter { requested -> requested.substringAfterLast(':') in incompatibleTaskNames }
                .sorted()
        require(incompatible.isEmpty()) {
            "Configuration cache is not supported for evidence, staging, or external-process tasks: " +
                "${incompatible.joinToString()}. Run them without --configuration-cache."
        }
    }
}
