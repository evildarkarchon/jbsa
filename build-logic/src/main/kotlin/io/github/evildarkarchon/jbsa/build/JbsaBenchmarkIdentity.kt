package io.github.evildarkarchon.jbsa.build

/** Stable Gradle identities owned by the build-only JMH project. */
internal object JbsaBenchmarkIdentity {
    const val PROJECT_PATH = ":jbsa-benchmarks"
    const val MAIN_CLASS = "org.openjdk.jmh.Main"
    const val STANDALONE_TASK = "shadowJar"
    const val VERIFY_ARTIFACT_TASK = "verifyBenchmarkArtifact"
    const val SMOKE_TEST_TASK = "smokeTestBenchmarkLauncher"
    const val STANDALONE_CLASSIFIER = "standalone"
}
